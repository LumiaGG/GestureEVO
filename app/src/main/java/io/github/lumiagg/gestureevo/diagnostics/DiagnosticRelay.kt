package io.github.lumiagg.gestureevo.diagnostics

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import io.github.lumiagg.gestureevo.utils.process.ProcessStreamRelay

/** Bounded log transport. Diagnostic framing and logcat policy stay in this module. */
class DiagnosticRelay(
    command: List<String>, output: OutputStream,
    durationMs: Long = 60_000, byteLimit: Int = 2 * 1024 * 1024,
) : Closeable {
    private val transport = ProcessStreamRelay(command, output, durationMs, byteLimit,
        onEnd = { stream, reason, code ->
            stream.write("\nGEVO_END $reason $code\n".toByteArray(Charsets.UTF_8))
        })

    override fun close() = transport.close()

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
    fun read(input: InputStream, maxLength: Int = 8192, onDiscard: () -> Unit = {}, onLine: (String) -> Boolean) =
        io.github.lumiagg.gestureevo.utils.process.ProcessOutputLines.read(input, maxLength, onDiscard, onLine)
}
