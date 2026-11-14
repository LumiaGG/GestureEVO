package io.github.lumiagg.gestureevo.drop

import org.junit.Assert.*
import org.junit.Test

class PressTouchInterruptionStateTest {
    @Test fun successfulProtectionIsReusedByDragWithoutAnotherInjection() {
        val state = PressTouchInterruptionState()
        val request = state.beginProtection()!!
        assertTrue(state.protectionRequested)
        assertNull(state.beginProtection())
        assertNull(state.beginDrag())
        assertTrue(state.complete(request, true))
        assertTrue(state.succeeded)
        assertNull(state.beginDrag())
    }

    @Test fun failedProtectionCanRetryAtDragStartButDoesNotRepeatWhileHolding() {
        val state = PressTouchInterruptionState()
        val protection = state.beginProtection()!!
        assertTrue(state.complete(protection, false))
        assertNull(state.beginProtection())
        val drag = state.beginDrag()!!
        assertNull(state.beginDrag())
        assertFalse(state.complete(protection, true))
        assertTrue(state.complete(drag, true))
    }

    @Test fun dragWithoutEarlyProtectionStillInterruptsOnce() {
        val state = PressTouchInterruptionState()
        val request = state.beginDrag()!!
        assertFalse(state.protectionRequested)
        assertNull(state.beginProtection())
        assertTrue(state.complete(request, true))
        assertNull(state.beginDrag())
    }

    @Test fun releaseAndNewPressRejectLateCompletion() {
        val state = PressTouchInterruptionState()
        val old = state.beginProtection()!!
        state.clear()
        assertFalse(state.protectionRequested)
        assertFalse(state.succeeded)
        val current = state.beginDrag()!!
        assertFalse(state.complete(old, true))
        assertFalse(state.succeeded)
        assertTrue(state.complete(current, true))
        assertFalse(state.complete(current, false))
    }
}
