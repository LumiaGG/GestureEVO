package io.github.lumiagg.gestureevo.pointer

import io.github.lumiagg.gestureevo.utils.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PointerTopEdgePanelTest {
    private val portrait = PointerCoordinateMapping(1080, 2400, 600, 1500, 400, 800)
    private val edgeHeight = 72f

    @Test fun usesTheMappedPointerAndIncludesTheTopBandBoundary() {
        val pointer = portrait.map(Point(700f, 1524f), Point(0f, 0f))
        assertEquals(PointerTopEdgePanel.NOTIFICATIONS, portrait.topEdgePanel(pointer, edgeHeight))
        assertNull(portrait.topEdgePanel(Point(pointer.x, edgeHeight + 1f), edgeHeight))
    }

    @Test fun splitsAtTheCurrentWidthMidpoint() {
        val landscape = portrait.copy(screenWidth = 2400, screenHeight = 1080)
        assertEquals(PointerTopEdgePanel.QUICK_SETTINGS, portrait.topEdgePanel(Point(540f, 0f), edgeHeight))
        assertEquals(PointerTopEdgePanel.NOTIFICATIONS, landscape.topEdgePanel(Point(540f, 0f), edgeHeight))
        assertEquals(PointerTopEdgePanel.QUICK_SETTINGS, landscape.topEdgePanel(Point(1200f, 0f), edgeHeight))
    }
}
