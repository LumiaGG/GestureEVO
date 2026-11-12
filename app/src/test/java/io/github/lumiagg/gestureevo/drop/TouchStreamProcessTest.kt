package io.github.lumiagg.gestureevo.drop

import io.github.lumiagg.gestureevo.diagnostics.DiagnosticRelay
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class TouchStreamProcessTest {
    private class Sink : ByteArrayOutputStream() {
        val ready = CountDownLatch(1)
        val closed = CountDownLatch(1)
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            super.write(buffer, offset, length)
            if (toString("UTF-8").contains("ready")) ready.countDown()
        }
        override fun close() { closed.countDown(); super.close() }
    }

    private fun command(): List<String> {
        val shell = if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "sh" else "bash"
        val script = TouchStreamProcess.leaseScript
            .replace("/system/bin/getevent -t \"${'$'}1\"", "sleep 30")
            .replace("read -r -t 60", "read -r -t 0.5")
            .replace("child=${'$'}!", "child=${'$'}!\nprintf 'ready\\n'")
            .replace("wait \"${'$'}child\" 2>/dev/null", "wait \"${'$'}child\" 2>/dev/null\nprintf 'reaped\\n'")
        return listOf(shell, "-c", script)
    }

    @Test(timeout = 10000) fun renewalsKeepTheSameWorkerAliveUntilExplicitStop() {
        val sink = Sink()
        val relay = DiagnosticRelay(command(), sink, durationMs = null, byteLimit = null,
            stdinLease = true, leaseHeartbeatMs = 20)
        try {
            assertTrue(sink.ready.await(3, TimeUnit.SECONDS))
            assertFalse(sink.closed.await(1200, TimeUnit.MILLISECONDS))
            assertTrue(relay.isProcessAlive())
        } finally { relay.close() }
        assertFalse(relay.isProcessAlive())
    }

    @Test(timeout = 10000) fun missingHeartbeatsReapTheWorkerEvenWithStdinStillOpen() {
        val sink = Sink()
        DiagnosticRelay(command(), sink, durationMs = null, byteLimit = null, stdinLease = true).use {
            assertTrue(sink.ready.await(3, TimeUnit.SECONDS))
            assertTrue(sink.closed.await(3, TimeUnit.SECONDS))
            assertTrue(sink.toString("UTF-8").contains("reaped"))
            assertFalse(it.isProcessAlive())
        }
    }
}
