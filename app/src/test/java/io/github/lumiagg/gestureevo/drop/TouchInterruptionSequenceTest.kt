package io.github.lumiagg.gestureevo.drop

import org.junit.Assert.*
import org.junit.Test

class TouchInterruptionSequenceTest {
    @Test fun expiredOrRevokedRequestsNeverInject() {
        assertFalse(TouchInterruptionSequence.run({ false },
            down = { error("Stale DOWN") }, cancel = { error("Stale CANCEL") }, pause = { error("Stale pause") }))
    }

    @Test fun revocationAfterDownStillCancelsAndNeverSendsUp() {
        val calls = mutableListOf<String>()
        var current = true
        assertTrue(TouchInterruptionSequence.run({ current },
            down = { calls += "DOWN"; true },
            pause = { current = false; calls += "pause" },
            cancel = { calls += "CANCEL"; true }))
        assertEquals(listOf("DOWN", "pause", "CANCEL"), calls)
        assertFalse(current)
    }

    @Test fun failedOrThrowingDownStillAttemptsCleanup() {
        var cleaned = 0
        assertFalse(TouchInterruptionSequence.run({ true }, down = { false },
            cancel = { cleaned++; true }, pause = { error("Failed DOWN must not wait") }))
        assertEquals(1, cleaned)
        val failure = IllegalStateException("injection failed")
        try {
            TouchInterruptionSequence.run({ true }, down = { throw failure },
                cancel = { cleaned++; true }, pause = { error("Unreachable") })
            fail("Expected failure")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertEquals(2, cleaned)
    }

    @Test fun interruptedPauseStillCancelsAndCancelFailureIsNotSuccess() {
        var cleaned = false
        try {
            TouchInterruptionSequence.run({ true }, down = { true },
                pause = { throw InterruptedException() }, cancel = { cleaned = true; true })
            fail("Expected interruption")
        } catch (_: InterruptedException) { assertTrue(cleaned) }
        assertFalse(TouchInterruptionSequence.run({ true }, down = { true }, pause = {}, cancel = { false }))
    }
}
