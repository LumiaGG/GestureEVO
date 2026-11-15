package io.github.lumiagg.gestureevo.diagnostics

import io.github.lumiagg.gestureevo.utils.process.ProcessCommandRunner

/** Used only with fixed diagnostic commands. Both pipes are drained, but retained output is bounded. */
object DiagnosticProcess {
    val logcatArguments = listOf("/system/bin/logcat", "-d", "-v", "epoch", "-t", "200",
        "ActivityTaskManager:I", "ActivityManager:I", "VivoPermissionManager:I", "VivoFirewall:I", "*:S")

    fun run(command: List<String>, timeoutMs: Long = 4000): Pair<Int, String> =
        ProcessCommandRunner.run(command, timeoutMs)
}
