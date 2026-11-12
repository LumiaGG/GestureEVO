package io.github.lumiagg.gestureevo.drop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class RootTouchCancellerTest {
    @Test fun stoppedClosedAndSupersededRequestsDoNotLaunch() {
        val executor = QueuedExecutor()
        var launches = 0
        val canceller = canceller(executor, launch = { launches++; FakeProcess() })
        canceller.start { true }
        canceller.stop()
        executor.drain()
        assertEquals(0, launches)
        canceller.start { true }
        canceller.start { true }
        executor.drain()
        assertEquals(1, launches)
        canceller.start { true }
        canceller.close()
        executor.drain()
        canceller.start { true }
        executor.drain()
        assertEquals(1, launches)
        assertTrue(executor.isShutdown)
    }

    @Test fun requestEndingWhileSuStartsNeverApprovesCancellation() {
        val executor = QueuedExecutor()
        val process = FakeProcess()
        var current = true
        val canceller = canceller(executor, launch = { current = false; process })
        canceller.start { current }
        executor.drain()
        assertEquals(0, process.approval.size())
        assertTrue(process.destroyed)
        canceller.close()
    }

    @Test fun successfulHelperReceivesApprovalAndIsCleanedUp() {
        val executor = QueuedExecutor()
        val process = FakeProcess()
        val messages = mutableListOf<String>()
        val canceller = canceller(executor, launch = { process }, report = messages::add)
        canceller.start { true }
        executor.drain()
        assertArrayEquals(byteArrayOf(1), process.approval.toByteArray())
        assertTrue(process.destroyed)
        assertEquals(listOf("root_cancel_success elapsedMs=0"), messages)
        canceller.close()
    }

    @Test fun timeoutAndHelperFailureKeepShieldAndCleanUpProcess() {
        for (exits in listOf(false, true)) {
            val executor = QueuedExecutor()
            val process = FakeProcess("EVO_TOUCH_ERROR: denied\n", exits, 1)
            val messages = mutableListOf<String>()
            var time = 0L
            val canceller = canceller(executor, { process }, { time.also { time += 3_000 } }, messages::add)
            canceller.start { true }
            executor.drain()
            assertEquals(0, process.approval.size())
            assertTrue(process.destroyed)
            assertTrue(messages.single().contains("root_cancel_failed"))
            assertTrue(messages.single().contains("shield retained"))
            assertTrue(messages.single().contains(if (exits) "exit=1" else "timed out"))
            canceller.close()
        }
    }

    private fun canceller(
        executor: QueuedExecutor,
        launch: (List<String>) -> Process,
        clock: () -> Long = { 0L },
        report: (String) -> Unit = {},
    ) = RootTouchCanceller("/data/app/base.apk", executor, launch, clock, report)

    private class FakeProcess(
        text: String = "EVO_TOUCH_READY\nEVO_TOUCH_DONE\n",
        private val exits: Boolean = true,
        private val code: Int = 0,
    ) : Process() {
        private val output = ByteArrayInputStream(text.toByteArray())
        val approval = ByteArrayOutputStream()
        var destroyed = false
        override fun getOutputStream() = approval
        override fun getInputStream() = output
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = code
        override fun waitFor(timeout: Long, unit: TimeUnit) = exits
        override fun exitValue(): Int {
            if (!exits) throw IllegalThreadStateException("still running")
            return code
        }
        override fun destroy() { destroyed = true }
    }

    private class QueuedExecutor : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        private var stopped = false
        override fun execute(command: Runnable) { tasks.addLast(command) }
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return mutableListOf() }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
}
