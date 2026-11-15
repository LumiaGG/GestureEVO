package io.github.lumiagg.gestureevo.utils.process

import io.github.lumiagg.gestureevo.utils.ProcessFixture
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ProcessStreamRelayTest {
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

    @Test(timeout = 10000) fun rawTransportPreservesOutputWithoutAddingDiagnosticFraming() {
        val sink = Sink()
        ProcessStreamRelay(command("unicode"), sink).use {
            assertTrue(sink.closed.await(5, TimeUnit.SECONDS))
            assertEquals("中文图标\n", sink.toString("UTF-8"))
        }
    }

    @Test(timeout = 10000) fun leasedIdleProcessExitsAndIsReapedWhenClosed() {
        val sink = Sink()
        val relay = ProcessStreamRelay(command("lease"), sink, stdinLease = true)
        assertFalse(sink.closed.await(100, TimeUnit.MILLISECONDS))
        relay.close()
        assertTrue(sink.closed.await(1, TimeUnit.SECONDS))
        assertFalse(relay.isProcessAlive())
        relay.close()
    }

    @Test(timeout = 10000) fun receiverDisconnectClosesIdleLeaseWithoutWaitingForOutput() {
        val sink = Sink()
        val disconnected = java.util.concurrent.atomic.AtomicBoolean()
        val relay = ProcessStreamRelay(command("lease"), sink, durationMs = null, byteLimit = null,
            stdinLease = true, leaseHeartbeatMs = 20, receiverClosed = disconnected::get)
        disconnected.set(true)
        assertTrue(sink.closed.await(3, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (relay.isProcessAlive() && System.nanoTime() < deadline) Thread.sleep(20)
        assertFalse(relay.isProcessAlive())
        relay.close()
    }

}
