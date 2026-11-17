package io.github.lumiagg.gestureevo.utils.shizuku

import io.github.lumiagg.gestureevo.diagnostics.DiagnosticProcess
import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import io.github.lumiagg.gestureevo.IUserService
import io.github.lumiagg.gestureevo.drop.TouchStreamHost
import io.github.lumiagg.gestureevo.utils.ExecuteResult
import io.github.lumiagg.gestureevo.utils.runProcess
import java.io.File
import kotlin.system.exitProcess

class UserService : IUserService.Stub {
    private val diagnosticStreams = HashMap<String, io.github.lumiagg.gestureevo.diagnostics.DiagnosticRelay>()
    private val cancelledStreams = LinkedHashSet<String>()
    private val touchStreams = TouchStreamHost()
    private var clipboardDaemon: Process? = null
    private var clipboardPump: Thread? = null
    constructor() : super()

    @Keep
    constructor(context: Context) : this() {
        Log.i(TAG, "constructor with Context: context=$context")
    }

    override fun destroy() {
        synchronized(diagnosticStreams) { diagnosticStreams.values.forEach { it.close() }; diagnosticStreams.clear() }
        touchStreams.close()
        stopClipboardDaemon()
        Log.i(TAG, "destroy")
        exitProcess(0)
    }

    /**
     * Runs the clipboard daemon as this process's uid (shell under Shizuku) and hands its stdout
     * back through a pipe. The daemon ships inside the app's own APK, so no second artifact has
     * to be built or kept in sync; shell can read APK files in /data/app.
     */
    override fun startClipboardDaemon(apkPath: String): android.os.ParcelFileDescriptor {
        stopClipboardDaemon()
        val pipe = android.os.ParcelFileDescriptor.createPipe()
        val out = android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        try {
            val process = ProcessBuilder(
                "app_process",
                "-Djava.class.path=" + apkPath,
                "/system/bin",
                clipboardDaemonClass,
            ).redirectErrorStream(true).start()
            clipboardDaemon = process
            clipboardPump = Thread({
                try {
                    process.inputStream.use { it.copyTo(out) }
                } catch (_: Exception) {
                    // Reader gone; the daemon exits on its own watchdog.
                } finally {
                    runCatching { out.close() }
                    runCatching { process.destroy() }
                }
            }, "clipboard-daemon-pump").apply {
                isDaemon = true
                start()
            }
            return pipe[0]
        } catch (e: Exception) {
            runCatching { out.close() }
            runCatching { pipe[0].close() }
            throw IllegalStateException("clipboard daemon launch failed: " + e.javaClass.simpleName)
        }
    }

    override fun stopClipboardDaemon() {
        clipboardPump?.interrupt()
        clipboardPump = null
        clipboardDaemon?.let { runCatching { it.destroy() } }
        clipboardDaemon = null
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

    override fun openTouchStream(token: String, owner: android.os.IBinder): android.os.ParcelFileDescriptor =
        touchStreams.open(token, owner)

    override fun closeTouchStream(token: String) = touchStreams.close(token)

    override fun interruptTouchForDrag(token: String, sourcePackage: String, x: Float, y: Float,
        displayId: Int, deadlineUptimeMs: Long, guard: io.github.lumiagg.gestureevo.ITouchInterruptionGuard): Boolean =
        touchStreams.interrupt(token, sourcePackage, x, y, displayId, deadlineUptimeMs, guard)

    companion object {
        private const val TAG = "UserService"
        private const val clipboardDaemonClass =
            "io.github.lumiagg.gestureevo.clipboard.ClipboardDaemon"
    }
}
