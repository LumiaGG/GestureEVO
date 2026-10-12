package io.github.lumiagg.gestureevo.utils

import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

internal data class ProcessOutput(val exitCode: Int, val stdout: String, val stderr: String) {
    val isSuccess: Boolean get() = exitCode == 0
    fun asCommandResult(): Pair<Boolean, String> = isSuccess to if (isSuccess) {
        stdout
    } else {
        stderr.ifBlank { stdout }
    }
}

/** Drains both pipes before waiting for completion, including commands with large output. */
internal fun runProcess(
    command: List<String>,
    environment: List<String>? = null,
    directory: File? = null,
): ProcessOutput {
    require(command.isNotEmpty())
    val builder = ProcessBuilder(command).directory(directory)
    if (environment != null) {
        builder.environment().apply {
            clear()
            for (entry in environment) {
                val separator = entry.indexOf('=')
                if (separator > 0) put(entry.substring(0, separator), entry.substring(separator + 1))
            }
        }
    }
    val process = builder.start()
    val readers = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "GestureEVO-process-reader").apply { isDaemon = true }
    }
    try {
        process.outputStream.close()
        val stdout = readers.submit(Callable { process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } })
        val stderr = readers.submit(Callable { process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() } })
        val exitCode = process.waitFor()
        return ProcessOutput(exitCode, stdout.get(), stderr.get())
    } catch (e: ExecutionException) {
        throw IOException("Unable to read command output", e.cause)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw e
    } finally {
        process.destroy()
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
        readers.shutdownNow()
    }
}
