package io.github.lumiagg.gestureevo.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalEngineHoverTest {
    @Test fun droppingBeforeTheLongPressUsesNormalSearch() {
        val hover = ExternalEngineHover()
        hover.moveTo(0)
        assertFalse(hover.isLongPressDrop(0))
        assertTrue(hover.triggerLongPress())
        assertTrue(hover.isLongPressDrop(0))
        assertFalse(hover.triggerLongPress())
    }

    @Test fun movingToAnotherEngineRequiresANewLongPress() {
        val hover = ExternalEngineHover()
        hover.moveTo(0)
        hover.triggerLongPress()
        assertFalse(hover.moveTo(0))
        assertTrue(hover.isLongPressDrop(0))
        hover.moveTo(1)
        assertFalse(hover.isLongPressDrop(0))
        assertFalse(hover.isLongPressDrop(1))
        assertTrue(hover.triggerLongPress())
        assertTrue(hover.isLongPressDrop(1))
    }

    @Test fun leavingOrPagingClearsThePreviousEnginesLongPress() {
        val hover = ExternalEngineHover()
        assertFalse(hover.triggerLongPress())
        hover.moveTo(0)
        hover.triggerLongPress()
        hover.moveTo(-1)
        assertFalse(hover.isLongPressDrop(0))
        assertFalse(hover.isLongPressDrop(-1))
        assertFalse(hover.triggerLongPress())
        hover.moveTo(0)
        assertFalse(hover.isLongPressDrop(0))
    }
}
