package io.github.lumiagg.gestureevo.diagnostics

import io.github.lumiagg.gestureevo.utils.ProcessFixture
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DiagnosticProcessTest {
    private fun command(mode: String): List<String> {
        val executable = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "java.exe" else "java"
        val java = File(System.getProperty("java.home"), "bin/$executable").absolutePath
        val classes = File(requireNotNull(requireNotNull(ProcessFixture::class.java.protectionDomain).codeSource).location.toURI()).absolutePath
        return listOf(java, "-cp", classes, ProcessFixture::class.java.name, mode)
    }

    @Test(timeout = 10000) fun diagnosticOutputIsBoundedEvenWithLargeStdoutAndStderr() {
        val (code, output) = DiagnosticProcess.run(command("large"))
        assertEquals(0, code)
        assertEquals(48 * 1024, output.length)
    }

    @Test(timeout = 10000) fun hungCollectorIsTerminatedByDeadline() {
        val started = System.nanoTime()
        val (code, _) = DiagnosticProcess.run(command("wait"), timeoutMs = 500)
        assertEquals(-1, code)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5000)
    }
}
