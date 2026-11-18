package io.github.lumiagg.gestureevo.pointer

import io.github.lumiagg.gestureevo.utils.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerSelectionContinuityTest {
    private val mapping = PointerCoordinateMapping(1000, 2000, 100, 400, 500, 1000)
    private val zeroOffset = Point(0f, 0f)
    private fun selection() = PointerDragSelection(20f, 10f)

    @Test fun menuHandoffMatchesDirectDragAtTheSameFingerPositions() {
        val start = mapping.map(Point(200f, 600f), zeroOffset)
        val end = mapping.map(Point(350f, 900f), zeroOffset)
        val direct = selection().apply { begin(start); update(end) }
        val throughMenu = selection().apply { begin(start, end) }
        assertEquals(direct.start, throughMenu.start)
        assertEquals(direct.end, throughMenu.end)
        assertEquals(direct.isOverSize, throughMenu.isOverSize)
        assertTrue(throughMenu.shouldUseDragSelection(end))
    }

    @Test fun handoffContainsBothAxesAndIsReadyForImmediateRelease() {
        val drag = selection()
        drag.begin(Point(100f, 200f), Point(300f, 500f))
        assertEquals(Point(100f, 200f), drag.start)
        assertEquals(Point(300f, 500f), drag.end)
        assertTrue(drag.isOverSize)
        assertFalse(drag.isBackToOrigin)
    }

    @Test fun horizontalMovementDuringDwellUpdatesTheFinalEndpoint() {
        val start = mapping.map(Point(200f, 600f), zeroOffset)
        val preview = mapping.map(Point(200f, 900f), zeroOffset)
        val confirmed = mapping.map(Point(300f, 900f), zeroOffset)
        assertEquals(preview.y, confirmed.y)
        assertNotEquals(preview.x, confirmed.x)
        val drag = selection().apply { begin(start, confirmed) }
        assertEquals(Point(400f, 1000f), drag.end)
        assertTrue(drag.isOverSize)
    }

    @Test fun continuingAfterMenuUsesTheOriginalScaleWithoutRebasing() {
        val start = mapping.map(Point(200f, 600f), zeroOffset)
        val drag = selection().apply { begin(start, mapping.map(Point(300f, 900f), zeroOffset)) }
        val previous = drag.end
        drag.update(mapping.map(Point(310f, 920f), zeroOffset))
        assertEquals(20f, drag.end.x - previous.x, 0f)
        assertEquals(40f, drag.end.y - previous.y, 0f)
        assertEquals(start, drag.start)
    }

    @Test fun fingerAtBottomCanStillReachBottomOfScreen() {
        val start = mapping.map(Point(200f, 1000f), zeroOffset)
        val end = mapping.map(Point(350f, 1400f), zeroOffset)
        val drag = selection().apply { begin(start, end) }
        assertEquals(2000f, drag.end.y, 0f)
        assertEquals(800f, drag.end.y - drag.start.y, 0f)
    }

    @Test fun boundaryOvershootAndReverseMovementDoNotAccumulateExtraDistance() {
        assertEquals(Point(1000f, 2000f), mapping.map(Point(800f, 1800f), zeroOffset))
        assertEquals(Point(900f, 1800f), mapping.map(Point(550f, 1300f), zeroOffset))
    }

    @Test fun existingReturnToPointerOffsetIsPreserved() {
        val offset = Point(-50f, 100f)
        assertEquals(Point(350f, 1100f), mapping.map(Point(300f, 900f), offset))
        assertEquals(Point(370f, 1140f), mapping.map(Point(310f, 920f), offset))
    }

    @Test fun reversedCornersKeepTheOriginalAnchor() {
        val drag = selection().apply { begin(Point(400f, 700f), Point(100f, 300f)) }
        assertEquals(Point(400f, 700f), drag.start)
        assertEquals(Point(100f, 300f), drag.end)
        assertTrue(drag.isOverSize)
    }

    @Test fun verticalOnlyHandoffRetainsOriginalThinSelectionRules() {
        val drag = selection().apply { begin(Point(100f, 200f), Point(100f, 500f)) }
        assertFalse(drag.isOverSize)
        assertFalse(drag.shouldUseDragSelection(drag.end))
        drag.update(Point(150f, 500f))
        assertTrue(drag.isOverSize)
    }

    @Test fun returnToOriginWorksAfterAnAlreadyPopulatedHandoff() {
        val drag = selection().apply { begin(Point(100f, 200f), Point(300f, 500f)) }
        drag.update(Point(104f, 203f))
        assertTrue(drag.isBackToOrigin)
        drag.begin(Point(600f, 700f))
        assertFalse(drag.isOverSize)
        assertFalse(drag.isBackToOrigin)
    }

    @Test fun resizedOrRelocatedTouchAreaInvalidatesMenuCoordinateSnapshot() {
        assertEquals(mapping, mapping.copy())
        assertNotEquals(mapping, mapping.copy(touchTop = 450))
        assertNotEquals(mapping, mapping.copy(touchHeight = 900))
        assertNotEquals(mapping, mapping.copy(screenWidth = 2000, screenHeight = 1000))
    }
}
