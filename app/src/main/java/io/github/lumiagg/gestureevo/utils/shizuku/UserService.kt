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
    private var touchStream: io.github.lumiagg.gestureevo.diagnostics.DiagnosticRelay? = null
    private var touchToken: String? = null
    private var touchOwner: android.os.IBinder? = null
    private var touchDeath: android.os.IBinder.DeathRecipient? = null
    private val cancelledTouchStreams = LinkedHashSet<String>()
    private var cancellationUnavailable = false
    private var inputMonitor: Any? = null
    private var inputMonitorToken: android.os.IBinder? = null
    constructor() : super()

    @Keep
    constructor(context: Context) : this() {
        Log.i(TAG, "constructor with Context: context=$context")
    }

    override fun destroy() {
        synchronized(diagnosticStreams) { diagnosticStreams.values.forEach { it.close() }; diagnosticStreams.clear() }
        synchronized(this) { closeTouchStream(touchToken.orEmpty()); releaseSourceTouch() }
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
    override fun openTouchStream(token: String, owner: android.os.IBinder): android.os.ParcelFileDescriptor {
        require(token.matches(Regex("[a-f0-9-]{36}")))
        check(token !in cancelledTouchStreams)
        closeTouchStream(touchToken.orEmpty())
        val inventory = runCatching {
            val (code, output) = DiagnosticProcess.run(listOf("/system/bin/cat", "/proc/bus/input/devices"))
            if (code == 0) output else ""
        }.getOrDefault("")
        val (rangeCode, ranges) = DiagnosticProcess.run(listOf("/system/bin/getevent", "-lp"))
        check(rangeCode == 0) { "Touch device discovery failed: getevent exit=$rangeCode" }
        val device = checkNotNull(io.github.lumiagg.gestureevo.drop.TouchInputProtocol.discover(inventory, ranges)) { "No readable multitouch screen" }
        val pipe = android.os.ParcelFileDescriptor.createPipe()
        val output = android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        try {
            val death = android.os.IBinder.DeathRecipient { closeTouchStream(token) }
            owner.linkToDeath(death, 0)
            touchOwner = owner
            touchDeath = death
            touchToken = token
            output.write("EVO_TOUCH ${device.minX} ${device.maxX} ${device.minY} ${device.maxY}\n".toByteArray())
            touchStream = io.github.lumiagg.gestureevo.diagnostics.DiagnosticRelay(
                io.github.lumiagg.gestureevo.drop.TouchStreamProcess.arguments(device.path), output,
                durationMs = io.github.lumiagg.gestureevo.drop.TouchStreamProcess.DURATION_MS,
                byteLimit = 32 * 1024 * 1024, stdinLease = true,
                receiverClosed = {
                    val descriptor = android.system.StructPollfd().apply { fd = pipe[1].fileDescriptor; events = 0 }
                    android.system.Os.poll(arrayOf(descriptor), 0)
                    descriptor.revents.toInt() and (android.system.OsConstants.POLLERR or
                        android.system.OsConstants.POLLHUP or android.system.OsConstants.POLLNVAL) != 0
                })
            return pipe[0]
        } catch (error: Exception) {
            pipe.forEach { runCatching { it.close() } }
            closeTouchStream(token)
            throw error
        }
    }

    @Synchronized
    override fun closeTouchStream(token: String) {
        cancelledTouchStreams.add(token)
        while (cancelledTouchStreams.size > 16) cancelledTouchStreams.remove(cancelledTouchStreams.first())
        if (token != touchToken) return
        touchStream?.close()
        touchStream = null
        touchToken = null
        touchDeath?.let { death -> runCatching { touchOwner?.unlinkToDeath(death, 0) } }
        touchDeath = null
        touchOwner = null
        releaseSourceTouch()
    }

    /** MMGG BackgroundTouchBlocker adaptation: permission failure is cached per shell process. */
    @Synchronized
    override fun cancelSourceTouch(): Boolean {
        if (cancellationUnavailable) return false
        return try {
            // InputManager.getInstance() can kill a standalone shell app_process on some ROMs.
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "input")
            val manager = checkNotNull(Class.forName("android.hardware.input.IInputManager\$Stub")
                .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder))
            val type = Class.forName("android.hardware.input.IInputManager")
            try {
                type.getMethod("cancelCurrentTouch").invoke(manager)
            } catch (_: Exception) {
                val token = android.os.Binder()
                val monitorMethod = try {
                    type.getMethod("monitorGestureInput", android.os.IBinder::class.java,
                        String::class.java, Int::class.javaPrimitiveType)
                } catch (_: NoSuchMethodException) {
                    type.getMethod("monitorGestureInput", String::class.java, Int::class.javaPrimitiveType)
                }
                val monitor = checkNotNull(if (monitorMethod.parameterCount == 3) {
                    monitorMethod.invoke(manager, token, "GestureEVO source drag", 0)
                } else {
                    monitorMethod.invoke(manager, "GestureEVO source drag", 0)
                })
                inputMonitorToken = token
                inputMonitor = monitor
                monitor.javaClass.getMethod("pilferPointers").invoke(monitor)
            }
            true
        } catch (_: Exception) {
            cancellationUnavailable = true
            releaseSourceTouch()
            false
        }
    }

    @Synchronized
    override fun releaseSourceTouch() {
        inputMonitor?.let { monitor -> runCatching { monitor.javaClass.getMethod("dispose").invoke(monitor) } }
        inputMonitor = null
        inputMonitorToken = null
    }

    companion object {
        private const val TAG = "UserService"
    }
}
