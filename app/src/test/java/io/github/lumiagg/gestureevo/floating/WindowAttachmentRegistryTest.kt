package io.github.lumiagg.gestureevo.floating

import org.junit.Assert.*
import org.junit.Test

class WindowAttachmentRegistryTest {
    /** WindowManager owns a root after addView, before dispatchAttachedToWindow. */
    private class Windows {
        var accepted = false
        var attached = false
        var adds = 0
        var removes = 0
        fun add() { check(!accepted); accepted = true; adds++ }
        fun remove() { check(accepted); accepted = false; attached = false; removes++ }
    }
    @Test fun acceptedAddIsSuccessBeforeAttachmentAndCallbackWaitsForDispatch() {
        val registry = WindowAttachmentRegistry<Any>()
        val view = Any()
        val wm = Windows()
        var callbacks = 0
        val request = registry.request(view) { callbacks++ }
        assertTrue(registry.submit(view, request, wm::attached, wm::add))
        assertTrue(wm.accepted)
        assertFalse(wm.attached)
        assertEquals(0, callbacks)
        wm.attached = true
        registry.attached(view, request)
        registry.attached(view, request)
        assertEquals(1, callbacks)
    }
    @Test fun removalBeforeAttachRevokesOwnershipAndLateCallbackCannotReviveWindow() {
        val registry = WindowAttachmentRegistry<Any>()
        val view = Any()
        val wm = Windows()
        var callbacks = 0
        val old = registry.request(view) { callbacks++ }
        registry.submit(view, old, wm::attached, wm::add)
        registry.detach(view, wm::attached, wm::remove)
        assertEquals(1, wm.removes)
        assertFalse(wm.accepted)
        assertFalse(registry.attached(view, old))
        assertFalse(registry.submit(view, old, wm::attached, wm::add))
        assertEquals(1, wm.adds)
        assertEquals(0, callbacks)
        val next = registry.request(view) { callbacks++ }
        assertTrue(registry.submit(view, next, wm::attached, wm::add))
        wm.attached = true
        registry.attached(view, next)
        assertEquals(1, callbacks)
    }
    @Test fun repeatedPendingAddDoesNotAddTwiceAndOldRetryCannotReplaceNewRequest() {
        val registry = WindowAttachmentRegistry<Any>()
        val view = Any()
        val wm = Windows()
        val old = registry.request(view) { fail("old callback") }
        registry.submit(view, old, wm::attached, wm::add)
        var callbacks = 0
        val next = registry.request(view) { callbacks++ }
        assertTrue(registry.submit(view, next, wm::attached, wm::add))
        assertFalse(registry.submit(view, old, wm::attached, wm::add))
        assertFalse(registry.attached(view, old))
        assertEquals(1, wm.adds)
        wm.attached = true
        registry.attached(view, next)
        assertEquals(1, callbacks)
    }
    @Test fun failedAddHasNoOwnershipAndCancelledRetryDoesNotAdd() {
        val registry = WindowAttachmentRegistry<Any>()
        val view = Any()
        val wm = Windows()
        val request = registry.request(view) { }
        assertThrows(IllegalStateException::class.java) {
            registry.submit(view, request, wm::attached) { error("WM rejected") }
        }
        registry.detach(view, wm::attached, wm::remove)
        assertEquals(0, wm.removes)
        assertFalse(registry.submit(view, request, wm::attached, wm::add))
        assertEquals(0, wm.adds)
    }
}
