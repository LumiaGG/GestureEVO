package io.github.lumiagg.gestureevo.ocr.coloros

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import io.github.lumiagg.gestureevo.EvoApplication
import io.github.lumiagg.gestureevo.inspire.ScreenContentNode
import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import io.github.lumiagg.gestureevo.diagnostics.LogLevel
import io.github.lumiagg.gestureevo.diagnostics.LogModule
import io.github.lumiagg.gestureevo.ocr.OcrSettings
import io.github.lumiagg.gestureevo.ocr.coloros.privileged.ColorOcrAuthorize
import io.github.lumiagg.gestureevo.ocr.coloros.privileged.ColorOcrCommand
import io.github.lumiagg.gestureevo.ocr.coloros.privileged.ColorOcrRequestPacket
import io.github.lumiagg.gestureevo.ocr.coloros.privileged.ColorOcrResultPacket
import io.github.lumiagg.gestureevo.ocr.coloros.privileged.PrivilegedOcrEntry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resumeWithException

/**
 * App side of the ColorOS OCR path. One request = one root helper process: bind AIUnit,
 * write a request packet, launch the privileged entry via su, read the result packet.
 * The vendor SDK is never loaded in this process. No fallback: failures throw
 * [ColorOsOcrException] so the facade surfaces them like any engine error.
 */
object ColorOsOcrClient {
    private const val APP_WAIT_SECONDS = 70L
    private const val BIND_WAIT_MILLIS = 10_000L
    private const val REQUEST_DIR = PrivilegedOcrEntry.REQUEST_DIR

    /** Full-image OCR mapped onto the same nodes the built-in engine returns. */
    suspend fun detectNodes(bitmap: Bitmap): List<ScreenContentNode> {
        val lines = detectLines(bitmap)
        val filter = OcrSettings.filterLowConfidence(EvoApplication.application)
        val nodes = ArrayList<ScreenContentNode>(lines.size)
        for (line in lines) {
            if (filter && line.confidence < OcrSettings.MIN_CONFIDENCE) continue
            line.toScreenContentNode(bitmap.width, bitmap.height)?.let(nodes::add)
        }
        return nodes
    }

    /** Detection-only semantics: run the full OCR and keep the line boxes. */
    suspend fun detectRects(bitmap: Bitmap): List<Rect> {
        val lines = detectLines(bitmap)
        val filter = OcrSettings.filterLowConfidence(EvoApplication.application)
        val rects = ArrayList<Rect>(lines.size)
        for (line in lines) {
            if (filter && line.confidence < OcrSettings.MIN_CONFIDENCE) continue
            line.toRect(bitmap.width, bitmap.height)?.let(rects::add)
        }
        return rects
    }

    /** Recognition of an already-cropped line; single-line crops return their text directly. */
    suspend fun recognize(bitmap: Bitmap): String =
        ColorOcrLine.joinTexts(detectLines(bitmap))

    private suspend fun detectLines(bitmap: Bitmap): List<ColorOcrLine> =
        withContext(Dispatchers.Default) {
            // The privileged machinery (3-arg peekService, external provider APIs) is verified on
            // Android 8+ only; below that the engine is unavailable rather than best-effort.
            if (Build.VERSION.SDK_INT < 26) {
                throw ColorOsOcrException("ColorOS OCR 需要 Android 8.0 及以上系统")
            }
            val context = EvoApplication.application
            val started = SystemClock.elapsedRealtime()
            AppLogger.event(
                LogModule.OCR, "coloros_request_started", LogLevel.DEBUG,
                fields = mapOf("width" to bitmap.width, "height" to bitmap.height))
            val png = encodePng(bitmap)
            val dir = File(context.cacheDir, REQUEST_DIR).apply { mkdirs() }
            val stem = UUID.randomUUID().toString()
            val reqFile = File(dir, "$stem.req")
            val outFile = File(dir, "$stem.out")
            var process: Process? = null
            var outputTail: List<String> = emptyList()
            try {
                reqFile.outputStream().use { output ->
                    ColorOcrRequestPacket(bitmap.width, bitmap.height, png).write(output)
                }
                // Pre-create the result file so it stays owned by the app UID.
                check(outFile.createNewFile() || outFile.isFile) { "结果文件创建失败" }
                withAiUnitBinding(context) {
                    val command = ColorOcrCommand.appProcessCommand(
                        context.applicationInfo.sourceDir,
                        PrivilegedOcrEntry::class.java.name,
                        "--host", context.packageName,
                        "--req", reqFile.absolutePath,
                        "--out", outFile.absolutePath,
                    )
                    process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
                    outputTail = readHelperOutput(process!!)
                    waitForHelper(process!!, APP_WAIT_SECONDS)
                    process!!.exitValue()
                }
                val result = outFile.inputStream().use { ColorOcrResultPacket.read(it) }
                if (result.status != ColorOcrResultPacket.STATUS_OK) {
                    throw ColorOsOcrException(describeStatus(result.status) + ": " + result.message + tailOf(outputTail))
                }
                val lines = ColorOcrLine.parseJson(result.json)
                AppLogger.event(
                    LogModule.OCR, "coloros_request_completed", LogLevel.DEBUG,
                    fields = mapOf(
                        "lines" to lines.size,
                        "elapsedMs" to (SystemClock.elapsedRealtime() - started)))
                lines
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLogger.event(
                    LogModule.OCR, "coloros_request_failed", LogLevel.WARN,
                    fields = mapOf("elapsedMs" to (SystemClock.elapsedRealtime() - started)),
                    error = error)
                throw if (error is ColorOsOcrException) error else ColorOsOcrException(describe(error) + tailOf(outputTail), error)
            } finally {
                process?.destroyForcibly()
                runCatching { process?.inputStream?.close() }
                reqFile.delete()
                outFile.delete()
            }
        }

    /** Binds AIUnit for the duration of the request; the root helper peeks this live binding. */
    private suspend fun <T> withAiUnitBinding(context: Context, block: suspend () -> T): T {
        val ready = CompletableFuture<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                ready.complete(binder)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                ready.completeExceptionally(ColorOsOcrException("AIUnit 连接已断开"))
            }

            override fun onNullBinding(name: ComponentName) {
                ready.completeExceptionally(ColorOsOcrException("AIUnit 未返回 Binder"))
            }

            override fun onBindingDied(name: ComponentName) {
                onServiceDisconnected(name)
            }
        }
        var bound = false
        try {
            withContext(Dispatchers.Main + NonCancellable) {
                bound = runCatching {
                    context.bindService(ColorOcrAuthorize.aiUnitServiceIntent(), connection, Context.BIND_AUTO_CREATE)
                }.getOrElse { throw ColorOsOcrException("AIUnit 绑定失败", it) }
            }
            if (!bound) throw ColorOsOcrException("AIUnit 绑定失败")
            val binder = withTimeoutOrNull(BIND_WAIT_MILLIS) { ready.awaitSuspended() }
                ?: throw ColorOsOcrException("AIUnit 绑定超时")
            if (binder.interfaceDescriptor != ColorOcrAuthorize.DESCRIPTOR) {
                throw ColorOsOcrException("AIUnit 接口不匹配: " + binder.interfaceDescriptor)
            }
            return block()
        } finally {
            if (bound) {
                withContext(NonCancellable + Dispatchers.Main) {
                    runCatching { context.unbindService(connection) }
                }
            }
        }
    }

    private suspend fun <T> CompletableFuture<T>.awaitSuspended(): T =
        suspendCancellableCoroutine { continuation ->
            whenComplete { value, error ->
                if (error == null) {
                    continuation.resume(value, onCancellation = null)
                } else {
                    continuation.resumeWithException(error)
                }
            }
        }

    /** Cancellable wait: delay() throws on cancellation and the caller's finally kills the helper. */
    private suspend fun waitForHelper(process: Process, timeoutSeconds: Long) {
        val deadline = SystemClock.elapsedRealtime() + timeoutSeconds * 1000
        while (true) {
            try {
                process.exitValue()
                return
            } catch (_: IllegalThreadStateException) {
                // Still running; keep polling.
            }
            if (SystemClock.elapsedRealtime() > deadline) {
                throw ColorOsOcrException("Root helper 等待超过 ${timeoutSeconds}s；未确认本次调用完成")
            }
            delay(200)
        }
    }

    private fun readHelperOutput(process: Process): List<String> {
        val tail = ArrayDeque<String>()
        Thread({
            try {
                process.inputStream.bufferedReader().use { stream ->
                    stream.forEachLine { line ->
                        synchronized(tail) {
                            if (tail.size >= 6) tail.removeFirst()
                            tail.addLast(line)
                        }
                    }
                }
            } catch (_: Exception) {
                // Stream closed by cleanup; the collected tail is enough.
            }
        }, "coloros-ocr-output").apply {
            isDaemon = true
            start()
        }
        return tail
    }

    private fun tailOf(tail: List<String>): String = synchronized(tail) {
        if (tail.isEmpty()) "" else "｜" + tail.joinToString(" ⏎ ")
    }

    private fun describeStatus(status: Int): String = when (status) {
        ColorOcrResultPacket.STATUS_AUTHORIZED -> "授权未通过"
        ColorOcrResultPacket.STATUS_SCANNER -> "扫一扫 SDK 不可用"
        ColorOcrResultPacket.STATUS_REQUEST -> "请求无效"
        else -> "引擎错误"
    }

    private fun describe(error: Throwable): String {
        val chain = generateSequence(error as Throwable?) { it.cause }.take(4).toList()
        return chain.joinToString(" ← ") { "${it.javaClass.simpleName}: ${it.message}" }
    }

    private fun encodePng(bitmap: Bitmap): ByteArray {
        if (bitmap.width.toLong() * bitmap.height > ColorOcrRequestPacket.MAX_PIXELS) {
            throw ColorOsOcrException("图片像素超出 ColorOS OCR 上限（${ColorOcrRequestPacket.MAX_PIXELS}）")
        }
        val stream = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
            throw ColorOsOcrException("PNG 编码失败")
        }
        val bytes = stream.toByteArray()
        if (bytes.size > ColorOcrRequestPacket.MAX_PNG_BYTES) {
            throw ColorOsOcrException("PNG 超出传输上限")
        }
        return bytes
    }
}
