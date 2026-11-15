package io.github.lumiagg.gestureevo.utils.process

import java.io.Closeable
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Streams a child process with optional output limits and an owner-renewed stdin lease. */
internal class ProcessStreamRelay(
    command: List<String>, private val output: OutputStream,
    durationMs: Long? = 60_000, private val byteLimit: Int? = 2 * 1024 * 1024,
    private val stdinLease: Boolean = false, private val receiverClosed: (() -> Boolean)? = null,
    leaseHeartbeatMs: Long? = null,
    private val onEnd: (OutputStream, String, Int) -> Unit = { _, _, _ -> },
) : Closeable {
    private val closed = AtomicBoolean()
    private val process = ProcessBuilder(command).redirectErrorStream(true).start()
    private val watchdog = Executors.newSingleThreadScheduledExecutor { Thread(it, "process-stream-watchdog").apply { isDaemon = true } }
    private val reader = Thread({ pump() }, "process-stream-reader").apply { isDaemon = true }

    init {
        if (!stdinLease) process.outputStream.close()
        if (durationMs != null) watchdog.schedule({ close() }, durationMs, TimeUnit.MILLISECONDS)
        if (receiverClosed != null) watchdog.scheduleWithFixedDelay({
            if (runCatching { receiverClosed?.invoke() == true }.getOrDefault(true)) close()
        }, 250, 250, TimeUnit.MILLISECONDS)
        if (leaseHeartbeatMs != null) watchdog.scheduleWithFixedDelay({
            if (!closed.get()) {
                try {
                    process.outputStream.write('\n'.code)
                    process.outputStream.flush()
                } catch (_: Exception) { close() }
            }
        }, 0, leaseHeartbeatMs, TimeUnit.MILLISECONDS)
        reader.start()
    }

    private fun pump() {
        try {
            val buffer = ByteArray(4096)
            var copied = 0
            process.inputStream.use { input ->
                while (!closed.get() && (byteLimit == null || copied < byteLimit)) {
                    val size = input.read(buffer, 0, if (byteLimit == null) buffer.size else minOf(buffer.size, byteLimit - copied))
                    if (size < 0) break
                    output.write(buffer, 0, size)
                    if (byteLimit != null) copied += size
                }
            }
            if (!closed.get()) {
                val limited = byteLimit != null && copied >= byteLimit
                if (limited && !stdinLease) process.destroy()
                val code = if (limited) 0 else process.waitFor()
                val reason = if (limited) "output_limit" else if (code == 0) "stream_closed" else "command_failed"
                onEnd(output, reason, code)
                output.flush()
            }
        } catch (_: Exception) {
            // A closed receiver or terminated process is expected during cancellation.
        } finally { close() }
    }

    internal fun isProcessAlive(): Boolean = try {
        process.exitValue()
        false
    } catch (_: IllegalThreadStateException) { true }

    private fun awaitExit(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (isProcessAlive()) {
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(10)
        }
        return true
    }

    private fun forceStop() {
        if (android.os.Build.VERSION.SDK_INT >= 26) process.destroyForcibly() else process.destroy()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Close the outgoing pipe before process cleanup, so a blocked downstream read gets EOF.
        runCatching { output.close() }
        runCatching { process.outputStream.close() }
        try {
            if (stdinLease) {
                // Let the shell reap its worker; missing heartbeats also bound a stalled su client.
                awaitExit(1000)
            } else {
                process.destroy()
                if (!awaitExit(500)) {
                    forceStop()
                    awaitExit(500)
                }
            }
        } catch (_: InterruptedException) {
            if (!stdinLease) forceStop()
            Thread.currentThread().interrupt()
        } finally { watchdog.shutdownNow() }
    }

}
