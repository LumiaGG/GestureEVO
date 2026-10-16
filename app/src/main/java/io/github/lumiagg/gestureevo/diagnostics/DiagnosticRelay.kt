package io.github.lumiagg.gestureevo.diagnostics

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Continuous bounded transport. Raw messages are streamed through a pipe and never saved to disk. */
class DiagnosticRelay(
    command: List<String>, private val output: OutputStream,
    durationMs: Long = 60_000, private val byteLimit: Int = 2 * 1024 * 1024,
) : Closeable {
    private val closed = AtomicBoolean()
    private val process = ProcessBuilder(command).redirectErrorStream(true).start()
    private val watchdog = Executors.newSingleThreadScheduledExecutor { Thread(it, "log-stream-deadline").apply { isDaemon = true } }
    private val reader = Thread({ pump() }, "log-stream-relay").apply { isDaemon = true }

    init {
        process.outputStream.close()
        watchdog.schedule({ close() }, durationMs, TimeUnit.MILLISECONDS)
        reader.start()
    }

    private fun pump() {
        try {
            val buffer = ByteArray(4096)
            var copied = 0
            process.inputStream.use { input ->
                while (!closed.get() && copied < byteLimit) {
                    val size = input.read(buffer, 0, minOf(buffer.size, byteLimit - copied))
                    if (size < 0) break
                    output.write(buffer, 0, size)
                    copied += size
                }
            }
            if (!closed.get()) {
                val limited = copied >= byteLimit
                if (limited) process.destroy()
                val code = if (limited) 0 else process.waitFor()
                val reason = if (limited) "output_limit" else if (code == 0) "stream_closed" else "command_failed"
                output.write("\nGEVO_END $reason $code\n".toByteArray(Charsets.UTF_8))
                output.flush()
            }
        } catch (_: Exception) {
            // A closed receiver or terminated process is expected during cancellation.
        } finally { close() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Close the outgoing pipe before process cleanup, so a blocked downstream read gets EOF.
        runCatching { output.close() }
        runCatching { process.destroy() }
        watchdog.shutdownNow()
    }

    companion object {
        val arguments = listOf("/system/bin/logcat", "-v", "epoch", "-T", "1",
            "ActivityTaskManager:I", "ActivityManager:I", "VivoPermissionManager:I", "VivoFirewall:I", "*:S")
        // The privileged shell also enforces a deadline if the app or su client disappears.
        val rootCommand = """
            /system/bin/logcat -v epoch -T 1 ActivityTaskManager:I ActivityManager:I VivoPermissionManager:I VivoFirewall:I '*:S' &
            child=${'$'}!
            ( /system/bin/sleep 60; kill "${'$'}child" 2>/dev/null ) &
            timer=${'$'}!
            trap 'kill "${'$'}child" "${'$'}timer" 2>/dev/null' EXIT HUP INT TERM
            wait "${'$'}child"
            code=${'$'}?
            exit "${'$'}code"
        """.trimIndent()
    }
}

object DiagnosticLines {
    /** Discard oversized lines while continuing to drain the stream. No unbounded readLine allocation. */
    fun read(input: InputStream, maxLength: Int = 8192, onDiscard: () -> Unit = {}, onLine: (String) -> Boolean) {
        val reader = input.reader(Charsets.UTF_8)
        val buffer = CharArray(2048)
        val line = StringBuilder()
        var oversized = false
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            for (index in 0 until count) {
                val char = buffer[index]
                if (char == '\n') {
                    if (oversized) onDiscard() else if (!onLine(line.toString().trimEnd('\r'))) return
                    line.setLength(0)
                    oversized = false
                } else if (!oversized) {
                    if (line.length >= maxLength) { oversized = true; line.setLength(0) } else line.append(char)
                }
            }
        }
        if (oversized) onDiscard() else if (line.isNotEmpty()) onLine(line.toString())
    }
}
