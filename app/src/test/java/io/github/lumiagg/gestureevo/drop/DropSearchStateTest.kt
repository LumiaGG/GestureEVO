package io.github.lumiagg.gestureevo.drop

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DropSearchStateTest {
    @Test fun restoringBeforeSelectionKeepsSearchAvailable() {
        val handle = SavedStateHandle()
        val state = DropSearchState(handle)
        assertFalse(state.searchStarted)

        val restored = DropSearchState(restore(handle))
        assertFalse(restored.searchStarted)
        assertTrue(restored.tryStartSearch())
    }

    @Test fun retainedStateRejectsAnotherDispatchAfterSelection() {
        val state = DropSearchState(SavedStateHandle())
        assertTrue(state.tryStartSearch())
        assertTrue(state.searchStarted)
        assertFalse(state.tryStartSearch())
    }

    @Test fun restoringAfterDispatchDoesNotRepeatSearch() {
        val handle = SavedStateHandle()
        assertTrue(DropSearchState(handle).tryStartSearch())

        val restored = DropSearchState(restore(handle))
        assertTrue(restored.searchStarted)
        assertFalse(restored.tryStartSearch())
    }

    private fun restore(handle: SavedStateHandle) = SavedStateHandle(
        handle.keys().associateWith { handle.get<Any?>(it) }
    )
}
