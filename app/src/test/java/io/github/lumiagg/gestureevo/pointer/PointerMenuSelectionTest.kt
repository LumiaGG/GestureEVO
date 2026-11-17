package io.github.lumiagg.gestureevo.pointer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PointerMenuSelectionTest {
    @Test fun smallMotionAccumulatesUntilOneStepAndSelectsFirstItem() {
        val selection = PointerMenuSelection()
        selection.reset(100f)
        assertNull(selection.move(112f, 4, -1, 28f))
        assertNull(selection.move(127f, 4, -1, 28f))
        assertEquals(0, selection.move(128f, 4, -1, 28f))
        assertEquals(1, selection.move(156f, 4, 0, 28f))
    }

    @Test fun upwardTravelStopsAtFirstItemWithoutWrapping() {
        val selection = PointerMenuSelection()
        selection.reset(100f)
        assertEquals(0, selection.move(72f, 4, 1, 28f))
        assertNull(selection.move(44f, 4, 0, 28f))
    }

    @Test fun downwardTravelStopsAtLastItemWithoutWrapping() {
        val selection = PointerMenuSelection()
        selection.reset(100f)
        assertEquals(3, selection.move(128f, 4, 2, 28f))
        assertNull(selection.move(156f, 4, 3, 28f))
    }

    @Test fun overscrollingAtEitherEndDoesNotDelayReverseTravel() {
        val selection = PointerMenuSelection()
        selection.reset(100f)
        assertNull(selection.move(300f, 4, 3, 28f))
        assertEquals(2, selection.move(272f, 4, 3, 28f))
        selection.reset(100f)
        assertNull(selection.move(0f, 4, 0, 28f))
        assertEquals(1, selection.move(28f, 4, 0, 28f))
    }

    @Test fun reversingBeforeAFullStepDoesNotChangeSelection() {
        val selection = PointerMenuSelection()
        selection.reset(100f)
        assertNull(selection.move(120f, 4, 1, 28f))
        assertNull(selection.move(100f, 4, 1, 28f))
        assertEquals(0, selection.move(72f, 4, 1, 28f))
    }

    @Test fun aLargeEventClampsAtEitherEnd() {
        val selection = PointerMenuSelection()
        selection.reset(100f)
        assertEquals(3, selection.move(400f, 4, 1, 28f))
        assertEquals(2, selection.move(372f, 4, 3, 28f))
        assertEquals(0, selection.move(-100f, 4, 2, 28f))
    }

    @Test fun dynamicLinkInsertionUsesCurrentActionIndex() {
        val selection = PointerMenuSelection()
        selection.reset(0f)
        assertNull(selection.move(28f, 5, 4, 28f))
        assertEquals(3, selection.move(0f, 5, 4, 28f))
    }

    @Test fun unchangedVerticalPositionAndNewSessionHaveNoImplicitSelection() {
        val selection = PointerMenuSelection()
        selection.reset(500f)
        assertNull(selection.move(500f, 4, -1, 28f))
        selection.move(520f, 4, -1, 28f)
        selection.reset(200f)
        assertNull(selection.move(210f, 4, -1, 28f))
    }
}
