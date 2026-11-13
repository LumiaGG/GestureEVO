package io.github.lumiagg.gestureevo.utils.shizuku

import io.github.lumiagg.gestureevo.diagnostics.DiagnosticProcess
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import io.github.lumiagg.gestureevo.ocr.coloros.privileged.ColorOcrResultPacket
import io.github.lumiagg.gestureevo.ocr.coloros.privileged.PrivilegedOcrEntry
import kotlin.concurrent.thread
import androidx.annotation.Keep
import io.github.lumiagg.gestureevo.IUserService
import io.github.lumiagg.gestureevo.utils.ExecuteResult
import io.github.lumiagg.gestureevo.utils.runProcess
import java.io.File
import kotlin.system.exitProcess

class UserService : IUserService.Stub {
    private val diagnosticStreams = HashMap<String, io.github.lumiagg.gestureevo.diagnostics.DiagnosticRelay>()
    private val cancelledStreams = LinkedHashSet<String>()
    private var colorOsOcrProcess: Pair<String, Process>? = null
    constructor() : super()

    @Keep
    constructor(context: Context) : this() {
        Log.i(TAG, "constructor with Context: context=$context")
    }

    override fun destroy() {
        synchronized(diagnosticStreams) { diagnosticStreams.values.forEach { it.close() }; diagnosticStreams.clear() }
        synchronized(this) {
            if (android.os.Build.VERSION.SDK_INT >= 26) colorOsOcrProcess?.second?.destroyForcibly()
            colorOsOcrProcess = null
        }
        Log.i(TAG, "destroy")
        exitProcess(0)
    }

    override fun exit() {
        destroy()
    }

    override fun execute(
        cmdarray: MutableList<String>,
        envp: MutableList<String>?,
        dir: String?
    ): ExecuteResult {
        // AIDL executes on a Binder worker thread; no blocking coroutine bridge is needed.
        val result = runProcess(cmdarray, envp, dir?.let(::File))
        return ExecuteResult(result.exitCode, result.stderr, result.stdout)
    }

    override fun readDiagnosticLogs(): ExecuteResult = try {
        val (code, output) = DiagnosticProcess.run(
            DiagnosticProcess.logcatArguments)
        ExecuteResult(code, null, output)
    } catch (e: Exception) {
        ExecuteResult(-1, e.javaClass.simpleName, null)
    }

    override fun openDiagnosticStream(token: String): android.os.ParcelFileDescriptor = synchronized(diagnosticStreams) {
        require(token.matches(Regex("[a-f0-9-]{36}")))
        check(token !in cancelledStreams)
        diagnosticStreams.values.forEach { it.close() }
        diagnosticStreams.clear()
        val pipe = android.os.ParcelFileDescriptor.createPipe()
        try {
            diagnosticStreams[token] = io.github.lumiagg.gestureevo.diagnostics.DiagnosticRelay(
                io.github.lumiagg.gestureevo.diagnostics.DiagnosticRelay.arguments,
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]))
            pipe[0]
        } catch (e: Exception) { pipe.forEach { it.close() }; throw e }
    }

    override fun closeDiagnosticStream(token: String) {
        synchronized(diagnosticStreams) {
            diagnosticStreams.remove(token)?.close()
            cancelledStreams.add(token)
            while (cancelledStreams.size > 16) cancelledStreams.remove(cancelledStreams.first())
        }
    }

    @Synchronized
    override fun recognizeColorOsOcr(
        token: String,
        request: ParcelFileDescriptor,
        apkPath: String,
        host: String,
    ): ParcelFileDescriptor {
        if (android.os.Build.VERSION.SDK_INT < 26) error("ColorOS OCR requires Android 8.0+")
        require(token.matches(Regex("[a-f0-9-]{36}")))
        require(host == io.github.lumiagg.gestureevo.BuildConfig.APPLICATION_ID)
        colorOsOcrProcess?.second?.destroyForcibly()
        val started = SystemClock.elapsedRealtime()
        val pipe = ParcelFileDescriptor.createPipe()
        val process = try {
            ProcessBuilder(
                "/system/bin/app_process", "/system/bin", PrivilegedOcrEntry::class.java.name,
                "--host", host,
            ).apply { environment()["CLASSPATH"] = apkPath }.start()
        } catch (error: Exception) {
            request.close()
            pipe.forEach { it.close() }
            throw error
        }
        logColorOsStage(token, "process_start", started)
        colorOsOcrProcess = token to process
        val errors = ArrayDeque<String>()
        thread(name = "coloros-shizuku-input", isDaemon = true) {
            val inputStarted = SystemClock.elapsedRealtime()
            try {
                ParcelFileDescriptor.AutoCloseInputStream(request).use { input ->
                    process.outputStream.use { input.copyTo(it) }
                }
            } catch (error: Exception) {
                // The helper can reject a request before reading stdin.
                Log.d(TAG, "ColorOS OCR token=$token input closed: ${error.javaClass.simpleName}")
            } finally {
                logColorOsStage(token, "request_forward", inputStarted)
            }
        }
        val logReader = thread(name = "coloros-shizuku-log", isDaemon = true) {
            var helperReady = false
            runCatching {
                process.errorStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.startsWith("[ColorOS OCR ") && line.contains("] Timing: ")) {
                            if (!helperReady) {
                                logColorOsStage(token, "helper_ready", started)
                                helperReady = true
                            }
                            Log.i(TAG, "ColorOS OCR token=$token ${line.take(500)}")
                        } else {
                            synchronized(errors) {
                                if (errors.size == 6) errors.removeFirst()
                                errors.addLast(line.take(500))
                            }
                        }
                    }
                }
            }
        }
        thread(name = "coloros-shizuku-result", isDaemon = true) {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                    val resultStarted = SystemClock.elapsedRealtime()
                    val packet = try {
                        process.inputStream.use { ColorOcrResultPacket.read(it) }
                    } catch (error: Exception) {
                        runCatching { process.destroyForcibly() }
                        logReader.join(1_000)
                        val message = synchronized(errors) { errors.joinToString(" ← ") }
                        ColorOcrResultPacket(ColorOcrResultPacket.STATUS_ENGINE,
                            "系统 OCR 返回结果不完整或无效（${error.javaClass.simpleName}）: $message", null)
                    }
                    logColorOsStage(token, "result_validate_exit", resultStarted)
                    val returnStarted = SystemClock.elapsedRealtime()
                    packet.write(output)
                    logColorOsStage(token, "result_return", returnStarted)
                }
            } catch (error: Exception) {
                Log.w(TAG, "ColorOS OCR stream closed", error)
            } finally {
                runCatching { process.destroyForcibly() }
                runCatching { request.close() }
                synchronized(this) {
                    if (colorOsOcrProcess?.first == token) colorOsOcrProcess = null
                }
                logColorOsStage(token, "service_total", started)
            }
        }
        return pipe[0]
    }

    private fun logColorOsStage(token: String, stage: String, started: Long) {
        Log.i(TAG, "ColorOS OCR token=$token stage=$stage durationMs=${SystemClock.elapsedRealtime() - started}")
    }

    @Synchronized
    override fun closeColorOsOcr(token: String) {
        if (colorOsOcrProcess?.first == token) {
            if (android.os.Build.VERSION.SDK_INT >= 26) colorOsOcrProcess?.second?.destroyForcibly()
            colorOsOcrProcess = null
        }
    }

    companion object {
        private const val TAG = "UserService"
    }
}
