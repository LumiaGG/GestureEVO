package io.github.lumiagg.gestureevo.pointer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerHoverStateTest {
    @Test fun selectionPreviewOnlyExistsWhileSelectIsHighlightedInMenu() {
        val state = PointerHoverState()
        state.show()
        assertFalse(state.isSelectionPreviewActive)
        state.highlight(PointerHoverAction.SELECT)
        assertTrue(state.isSelectionPreviewActive)
        state.highlight(PointerHoverAction.DOUBLE_TAP)
        assertFalse(state.isSelectionPreviewActive)
        state.highlight(PointerHoverAction.SELECT)
        state.enterSelection()
        assertFalse(state.isSelectionPreviewActive)
    }

    @Test fun cancelOrReleaseBeforeConfirmationClearsSelectionPreview() {
        val state = PointerHoverState()
        state.show()
        state.highlight(PointerHoverAction.SELECT)
        state.cancel()
        assertFalse(state.isSelectionPreviewActive)
        assertFalse(state.enterSelection())
        state.reset()
        state.show()
        state.highlight(PointerHoverAction.SELECT)
        assertNull(state.release())
        assertFalse(state.isSelectionPreviewActive)
        assertFalse(state.enterSelection())
    }

    @Test fun dismissedMenuNeverDispatchesAScreenActionOnRelease() {
        val state = PointerHoverState()
        state.show()
        state.highlight(PointerHoverAction.LONG_PRESS)
        state.dismiss()
        assertNull(state.release())
    }

    @Test fun dismissedMenuCanReopenInTheSameSession() {
        val state = PointerHoverState()
        state.show()
        state.highlight(PointerHoverAction.DOUBLE_TAP)
        state.dismiss()
        assertEquals(PointerHoverState.Phase.IDLE, state.phase)
        assertTrue(state.hasOpened)
        assertTrue(state.show())
        assertNull(state.selected)
        state.highlight(PointerHoverAction.LONG_PRESS)
        assertEquals(PointerHoverAction.LONG_PRESS, state.release())
        assertFalse(state.show())
        assertNull(state.release())
    }

    @Test fun leavingSelectionRearmsMenuWithoutResettingSessionReleaseOwnership() {
        val state = PointerHoverState()
        state.show()
        state.highlight(PointerHoverAction.SELECT)
        state.enterSelection()
        state.dismiss()
        assertTrue(state.hasOpened)
        assertTrue(state.show())
    }

    @Test fun releasingAfterDismissalDoesNotExecuteOldAction() {
        val state = PointerHoverState()
        state.show()
        state.highlight(PointerHoverAction.OPEN_LINK)
        state.dismiss()
        assertTrue(state.hasOpened)
        assertNull(state.release())
        assertEquals(PointerHoverState.Phase.FINISHED, state.phase)
        state.dismiss()
        assertFalse(state.show())
    }

    @Test fun openingHasNoImplicitActionAndCannotRepeat() {
        val state = PointerHoverState()
        assertTrue(state.show())
        assertNull(state.selected)
        assertFalse(state.show())
        assertNull(state.release())
        assertEquals(PointerHoverState.Phase.FINISHED, state.phase)
    }

    @Test fun movingOutsideClearsSelectionAndReleaseDoesNothing() {
        val state = PointerHoverState()
        state.show()
        state.highlight(PointerHoverAction.LONG_PRESS)
        state.highlight(null)
        assertNull(state.release())
    }

    @Test fun actionCanOnlyBeAcceptedOnce() {
        val state = PointerHoverState()
        state.show()
        state.highlight(PointerHoverAction.DOUBLE_TAP)
        assertEquals(PointerHoverAction.DOUBLE_TAP, state.release())
        assertNull(state.release())
        assertFalse(state.show())
    }

    @Test fun cancellationRejectsLateHighlightAndSelection() {
        val state = PointerHoverState()
        state.show()
        state.highlight(PointerHoverAction.SELECT)
        state.cancel()
        state.highlight(PointerHoverAction.OPEN_LINK)
        assertFalse(state.enterSelection())
        assertNull(state.release())
    }

    @Test fun selectionRequiresDwellAndCannotBeExecutedOnRelease() {
        val state = PointerHoverState()
        state.show()
        state.highlight(PointerHoverAction.SELECT)
        assertNull(state.release())
        state.reset()
        state.show()
        state.highlight(PointerHoverAction.SELECT)
        assertTrue(state.enterSelection())
        assertEquals(PointerHoverState.Phase.SELECTING, state.phase)
        assertFalse(state.show())
        assertFalse(state.enterSelection())
    }

    @Test fun leavingSelectBeforeDwellPreventsModeChange() {
        val state = PointerHoverState()
        state.show()
        state.highlight(PointerHoverAction.SELECT)
        state.highlight(PointerHoverAction.LONG_PRESS)
        assertFalse(state.enterSelection())
        assertEquals(PointerHoverAction.LONG_PRESS, state.release())
    }

    @Test fun resetAllowsNextSessionWithoutOldSelection() {
        val state = PointerHoverState()
        state.show()
        state.highlight(PointerHoverAction.OPEN_LINK)
        state.cancel()
        state.reset()
        assertEquals(PointerHoverState.Phase.IDLE, state.phase)
        assertTrue(state.show())
        assertNull(state.selected)
    }

    @Test fun menuFlipsAtRightEdgeAndRespectsInsets() {
        assertEquals(HoverMenuPosition(560f, 700f), placeHoverMenu(780f, 950f, 200, 240, 10f, 30f, 790f, 940f, 20f))
        assertEquals(HoverMenuPosition(30f, 30f), placeHoverMenu(10f, 0f, 200, 240, 10f, 30f, 790f, 940f, 20f))
    }

    @Test fun oversizedMenuDoesNotCreateAnInvalidClampRange() {
        assertEquals(HoverMenuPosition(8f, 20f), placeHoverMenu(50f, 50f, 200, 300, 8f, 20f, 100f, 120f, 20f))
    }

    @Test fun selectionRebaseKeepsAnchorAndPreservesSubsequentMovement() {
        val originalMapped = 600f
        val anchor = 240f
        val offset = anchor - originalMapped
        assertEquals(anchor, rebasePointerPosition(originalMapped, offset, 1000), 0f)
        assertEquals(260f, rebasePointerPosition(620f, offset, 1000), 0f)
        assertEquals(0f, rebasePointerPosition(0f, offset, 1000), 0f)
        assertEquals(1000f, rebasePointerPosition(1500f, offset, 1000), 0f)
    }
}
