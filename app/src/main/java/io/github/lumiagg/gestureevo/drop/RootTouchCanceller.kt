package io.github.lumiagg.gestureevo.drop

import android.os.SystemClock
import android.util.Log
import androidx.annotation.Keep
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** A separate root runtime avoids the application's hidden API reflection restrictions. */
internal class RootTouchCanceller(
    private val apkPath: String,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "EVO-RootTouchCancel").apply { isDaemon = true }
    },
    private val launch: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val report: (String) -> Unit = { Log.i("EVO-TouchBlock", it) },
    private val result: (Boolean) -> Unit = {},
) {
    private val lock = Any()
    private var generation = 0L
    private var closed = false
    private var pending: Future<*>? = null

    fun start(stillCurrent: () -> Boolean) = synchronized(lock) {
        if (closed) return@synchronized
        val request = ++generation
        pending?.cancel(true)
        pending = executor.submit {
            cancelCurrentTouch {
                synchronized(lock) { !closed && generation == request } && stillCurrent()
            }
        }
    }

    fun stop() = synchronized(lock) {
        generation++
        pending?.cancel(true)
        pending = null
    }

    fun close() = synchronized(lock) {
        closed = true
        stop()
        executor.shutdownNow()
        Unit
    }

    private fun cancelCurrentTouch(stillCurrent: () -> Boolean) {
        var process: Process? = null
        val output = StringBuilder()
        try {
            if (!stillCurrent() || Thread.currentThread().isInterrupted) return
            val deadline = clock() + TIMEOUT_MILLIS
            // The helper waits for approval over stdin after su and framework resolution finish.
            process = launch(listOf("su", "-c", command(apkPath, deadline)))
            var committed = false
            while (true) {
                if (!stillCurrent() || Thread.currentThread().isInterrupted) return
                val stream = process.inputStream
                while (stream.available() > 0) {
                    check(output.length < MAX_OUTPUT) { "helper output limit exceeded" }
                    val next = stream.read()
                    if (next < 0) break
                    output.append(next.toChar())
                }
                if (!committed && output.contains("EVO_TOUCH_READY\n")) {
                    if (!stillCurrent() || Thread.currentThread().isInterrupted) return
                    process.outputStream.write(1)
                    process.outputStream.flush()
                    committed = true
                }
                val exitCode = try { process.exitValue() } catch (_: IllegalThreadStateException) { null }
                if (exitCode != null) {
                    // Drain once more: a quick helper can exit between reading and exitValue.
                    while (stream.available() > 0 && output.length < MAX_OUTPUT) {
                        val next = stream.read()
                        if (next < 0) break
                        output.append(next.toChar())
                    }
                    check(committed && exitCode == 0 && output.contains("EVO_TOUCH_DONE\n")) {
                        "helper exit=$exitCode output=${output.toString().trim()}"
                    }
                    report("root_cancel_success elapsedMs=${clock() - (deadline - TIMEOUT_MILLIS)}")
                    if (stillCurrent()) result(true)
                    return
                }
                check(clock() < deadline) { "helper timed out after ${TIMEOUT_MILLIS}ms; output=${output.toString().trim()}" }
                Thread.sleep(10)
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            report("root_cancel_failed: ${error.javaClass.simpleName}: ${error.message}; shield retained")
            if (stillCurrent() && !Thread.currentThread().isInterrupted) result(false)
        } finally {
            process?.let {
                it.destroy()
                runCatching { it.outputStream.close() }
                runCatching { it.inputStream.close() }
                runCatching { it.errorStream.close() }
            }
        }
    }

    companion object {
        private const val TIMEOUT_MILLIS = 2_000L
        private const val MAX_OUTPUT = 8_192

        internal fun command(apkPath: String, deadline: Long): String =
            "CLASSPATH='${apkPath.replace("'", "'\\''")}' exec /system/bin/app_process /system/bin " +
                "${RootTouchCancelHelper::class.java.name} $deadline"
    }
}

/** Only launched by app_process as root; never reflect hidden APIs in the ordinary app process. */
@Keep
internal object RootTouchCancelHelper {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            check(android.os.Process.myUid() == 0) { "helper requires uid 0" }
            val deadline = args.single().toLong()
            val remaining = deadline - SystemClock.elapsedRealtime()
            check(remaining in 1..2_000L) { "request expired before root became available" }
            Thread({
                Thread.sleep(remaining)
                android.os.Process.killProcess(android.os.Process.myPid())
            }, "EVO-RootTouchDeadline").apply { isDaemon = true }.start()

            // InputManager.getInstance() requires an Application, which app_process does not have.
            val remote = checkNotNull(Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String::class.java).invoke(null, "input")) { "Input service unavailable" }
            val manager = checkNotNull(Class.forName("android.hardware.input.IInputManager\$Stub")
                .getDeclaredMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, remote)) {
                "Input service interface unavailable"
            }
            val cancel = Class.forName("android.hardware.input.IInputManager").getMethod("cancelCurrentTouch")
            System.out.print("EVO_TOUCH_READY\n")
            System.out.flush()
            check(System.`in`.read() == 1) { "request withdrawn" }
            check(SystemClock.elapsedRealtime() < deadline) { "request expired" }
            cancel.invoke(manager)
            System.out.print("EVO_TOUCH_DONE\n")
            System.out.flush()
            kotlin.system.exitProcess(0)
        } catch (error: Exception) {
            val cause = error.cause ?: error
            System.err.println("EVO_TOUCH_ERROR: ${cause.javaClass.simpleName}: ${cause.message}")
            cause.printStackTrace(System.err)
            kotlin.system.exitProcess(1)
        }
    }
}
