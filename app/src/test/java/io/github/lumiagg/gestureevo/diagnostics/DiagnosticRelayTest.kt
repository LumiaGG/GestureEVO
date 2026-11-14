package io.github.lumiagg.gestureevo.diagnostics

import io.github.lumiagg.gestureevo.utils.ProcessFixture
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DiagnosticRelayTest {
    private class Sink : ByteArrayOutputStream() {
        val closed = CountDownLatch(1)
        override fun close() { closed.countDown(); super.close() }
    }
    private fun command(mode: String): List<String> {
        val executable = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "java.exe" else "java"
        val java = File(System.getProperty("java.home"), "bin/$executable").absolutePath
        val classes = File(requireNotNull(requireNotNull(ProcessFixture::class.java.protectionDomain).codeSource).location.toURI()).absolutePath
        return listOf(java, "-cp", classes, ProcessFixture::class.java.name, mode)
    }

    @Test(timeout = 10000) fun continuousTransportKeepsAnEntireBurstBeyondTheOldSnapshotLimit() {
        val lines = (1..1500).joinToString("\n", postfix = "\n") { "$it 中文 background event" }
        val actual = ArrayList<String>()
        DiagnosticLines.read(lines.byteInputStream()) { actual.add(it); true }
        assertEquals(1500, actual.size)
        assertTrue(actual.last().contains("1500 中文"))
        val sink = Sink()
        DiagnosticRelay(command("large"), sink).use {
            assertTrue(sink.closed.await(5, TimeUnit.SECONDS))
            assertTrue(sink.size() > 500 * 1024)
            assertTrue(sink.toString("UTF-8").contains("GEVO_END stream_closed 0"))
        }
    }

    @Test(timeout = 10000) fun deadlineAndExplicitStopCloseTheReceiverWithoutWaitingForMoreMessages() {
        val sink = Sink()
        DiagnosticRelay(command("wait"), sink, durationMs = 400).use {
            assertTrue(sink.closed.await(3, TimeUnit.SECONDS))
        }
        val stopped = Sink()
        val relay = DiagnosticRelay(command("wait"), stopped)
        relay.close()
        assertTrue(stopped.closed.await(1, TimeUnit.SECONDS))
        relay.close() // Cancellation is idempotent.
    }

    @Test(timeout = 10000) fun outputBudgetIsEnforcedAndNonzeroExitIsNotCalledSuccess() {
        val limited = Sink()
        DiagnosticRelay(command("large"), limited, byteLimit = 4096).use {
            assertTrue(limited.closed.await(5, TimeUnit.SECONDS))
            assertTrue(limited.size() < 4200)
            assertTrue(limited.toString("UTF-8").contains("GEVO_END output_limit 0"))
        }
        val failed = Sink()
        DiagnosticRelay(command("fail"), failed).use {
            assertTrue(failed.closed.await(5, TimeUnit.SECONDS))
            assertTrue(failed.toString("UTF-8").contains("GEVO_END command_failed 7"))
        }
    }

    @Test fun oversizedMessagesAreDrainedWithoutLosingFollowingEvents() {
        var discarded = 0
        val actual = ArrayList<String>()
        DiagnosticLines.read(("x".repeat(10000) + "\nvalid\n").byteInputStream(), maxLength = 128,
            onDiscard = { discarded++ }) { actual.add(it); true }
        assertEquals(1, discarded)
        assertEquals(listOf("valid"), actual)
    }
}
