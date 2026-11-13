package io.github.lumiagg.gestureevo.ocr.coloros

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import io.github.lumiagg.gestureevo.EvoApplication
import io.github.lumiagg.gestureevo.inspire.ScreenContentNode
import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import io.github.lumiagg.gestureevo.diagnostics.LogLevel
import io.github.lumiagg.gestureevo.diagnostics.LogModule
import io.github.lumiagg.gestureevo.ocr.OcrSettings
import io.github.lumiagg.gestureevo.ocr.coloros.privileged.ColorOcrAuthorize
import io.github.lumiagg.gestureevo.ocr.coloros.privileged.ColorOcrRequestPacket
import io.github.lumiagg.gestureevo.ocr.coloros.privileged.ColorOcrResultPacket
import io.github.lumiagg.gestureevo.utils.app.AShizuku
import io.github.lumiagg.gestureevo.utils.shizuku.ShizukuServiceHelper
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resumeWithException

/**
 * Each request runs in a Shizuku-launched helper using packet streams and an app-held
 * AIUnit binding. The vendor SDK stays out of the app process; failures are reported
 * without falling back to the built-in engine.
 */
object ColorOsOcrClient {
    private const val APP_WAIT_SECONDS = 70L
    private const val BIND_WAIT_MILLIS = 10_000L

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
            if (!Shizuku.pingBinder()) throw ColorOsOcrException("请启动 Shizuku 并授权")
            if (!AShizuku.checkPermission(context)) throw ColorOsOcrException("Shizuku 未获授权")
            val png = encodePng(bitmap)
            val reqFile = File.createTempFile("coloros-ocr-", ".req", context.cacheDir)
            try {
                reqFile.outputStream().use { output ->
                    ColorOcrRequestPacket(bitmap.width, bitmap.height, png).write(output)
                }
                val result = withAiUnitBinding(context) { runWithShizuku(context, reqFile) }
                if (result.status != ColorOcrResultPacket.STATUS_OK) {
                    throw ColorOsOcrException(describeStatus(result.status) + ": " + result.message)
                }
                val lines = ColorOcrLine.parseJson(result.json)
                AppLogger.event(
                    LogModule.OCR, "coloros_request_completed", LogLevel.DEBUG,
                    fields = mapOf(
                        "lines" to lines.size,
                        "backend" to "Shizuku",
                        "elapsedMs" to (SystemClock.elapsedRealtime() - started)))
                lines
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLogger.event(
                    LogModule.OCR, "coloros_request_failed", LogLevel.WARN,
                    fields = mapOf("elapsedMs" to (SystemClock.elapsedRealtime() - started)),
                    error = error)
                throw if (error is ColorOsOcrException) error else ColorOsOcrException(describe(error), error)
            } finally {
                reqFile.delete()
            }
        }

    private suspend fun runWithShizuku(context: Context, request: File): ColorOcrResultPacket {
        val service = ShizukuServiceHelper.awaitService()
        val token = UUID.randomUUID().toString()
        var resultFd: ParcelFileDescriptor? = null
        try {
            withContext(Dispatchers.IO + NonCancellable) {
                ParcelFileDescriptor.open(request, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
                    resultFd = service.recognizeColorOsOcr(token, input, context.applicationInfo.sourceDir, context.packageName)
                }
            }
            val reader = CompletableFuture.supplyAsync {
                ParcelFileDescriptor.AutoCloseInputStream(resultFd!!).use { ColorOcrResultPacket.read(it) }
            }
            return withTimeoutOrNull(APP_WAIT_SECONDS * 1000) { reader.awaitSuspended() }
                ?: throw ColorOsOcrException("OCR 等待超时")
        } finally {
            withContext(Dispatchers.IO + NonCancellable) {
                runCatching { service.closeColorOsOcr(token) }
                runCatching { resultFd?.close() }
            }
        }
    }

    /** Binds AIUnit for the duration of the request; the privileged helper peeks this live binding. */
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
