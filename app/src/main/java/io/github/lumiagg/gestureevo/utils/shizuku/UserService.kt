package io.github.lumiagg.gestureevo.utils.shizuku

import io.github.lumiagg.gestureevo.diagnostics.DiagnosticProcess
import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import io.github.lumiagg.gestureevo.IUserService
import io.github.lumiagg.gestureevo.utils.ExecuteResult
import io.github.lumiagg.gestureevo.utils.runProcess
import java.io.File
import kotlin.system.exitProcess

class UserService : IUserService.Stub {
    private val diagnosticStreams = HashMap<String, io.github.lumiagg.gestureevo.diagnostics.DiagnosticRelay>()
    private val cancelledStreams = LinkedHashSet<String>()
    constructor() : super()

    @Keep
    constructor(context: Context) : this() {
        Log.i(TAG, "constructor with Context: context=$context")
    }

    override fun destroy() {
        synchronized(diagnosticStreams) { diagnosticStreams.values.forEach { it.close() }; diagnosticStreams.clear() }
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

    companion object {
        private const val TAG = "UserService"
    }
}
