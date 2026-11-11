package io.github.lumiagg.gestureevo.drop

import org.junit.Assert.*
import org.junit.Test

class DragConversionStateTest {
    @Test fun conversionKeepsBothVersionsAndPreventsDuplicateWork() {
        val state = DragConversionState<String>()
        assertNull(state.begin())
        state.retain("text")
        state.hover(true)
        state.hover(false)
        assertNull(state.begin())
        state.hover(true)
        val request = state.begin()!!
        assertNull(state.begin())
        assertTrue(state.complete(request, "image"))
        assertEquals("image", state.content)
        assertEquals("text", state.alternate)
        assertNull(state.begin())
        state.hover(false)
        state.hover(true)
        val reverse = state.begin()!!
        assertTrue(state.complete(reverse, state.alternate!!))
        assertEquals("text", state.content)
        assertEquals("image", state.alternate)
    }

    @Test fun closingOrReplacingContentRejectsLateResults() {
        val state = DragConversionState<String>()
        state.retain("old")
        state.hover(true)
        val oldRequest = state.begin()!!
        state.clear()
        assertFalse(state.complete(oldRequest, "late image"))
        assertNull(state.content)
        state.retain("new")
        state.hover(true)
        val newRequest = state.begin()!!
        assertFalse(state.fail(oldRequest))
        assertFalse(state.complete(oldRequest, "late text"))
        assertTrue(state.busy)
        assertTrue(state.complete(newRequest, "new image"))
        assertFalse(state.complete(newRequest, "duplicate"))
        assertEquals("new image", state.content)
        state.hover(false)
        state.hover(true)
        val failedRequest = state.begin()!!
        assertTrue(state.fail(failedRequest))
        assertEquals("new image", state.content)
        state.hover(false)
        state.hover(true)
        assertNotNull(state.begin())
    }
}
