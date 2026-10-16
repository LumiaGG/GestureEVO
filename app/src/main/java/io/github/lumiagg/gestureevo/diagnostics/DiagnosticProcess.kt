package io.github.lumiagg.gestureevo.diagnostics

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Used only with fixed diagnostic commands. Both pipes are drained, but retained output is bounded. */
object DiagnosticProcess {
    val logcatArguments = listOf("/system/bin/logcat", "-d", "-v", "epoch", "-t", "200",
        "ActivityTaskManager:I", "ActivityManager:I", "VivoPermissionManager:I", "VivoFirewall:I", "*:S")

    fun run(command: List<String>, timeoutMs: Long = 4000): Pair<Int, String> {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val watchdog = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "diagnostic-timeout").apply { isDaemon = true } }
        val timedOut = java.util.concurrent.atomic.AtomicBoolean()
        val timer = watchdog.schedule({ timedOut.set(true); process.destroy() }, timeoutMs, TimeUnit.MILLISECONDS)
        return try {
            process.outputStream.close()
            val output = StringBuilder()
            process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(2048)
                while (true) {
                    val size = reader.read(buffer)
                    if (size < 0) break
                    if (output.length < 48 * 1024) output.append(buffer, 0, size.coerceAtMost(48 * 1024 - output.length))
                }
            }
            val code = process.waitFor()
            (if (timedOut.get()) -1 else code) to output.toString()
        } finally {
            timer.cancel(false)
            watchdog.shutdownNow()
            process.destroy()
            process.inputStream.close()
        }
    }
}
