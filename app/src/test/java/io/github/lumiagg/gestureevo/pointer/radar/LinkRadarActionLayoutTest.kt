package io.github.lumiagg.gestureevo.pointer.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkRadarActionLayoutTest {
    private val viewport = RadarBox(0f, 0f, 400f, 600f)

    @Test fun scannedLinksGetActionsWithoutAnyPointerOrPress() {
        val anchors = linkedMapOf(0 to RadarBox(100f, 100f, 150f, 120f), 1 to RadarBox(220f, 100f, 270f, 120f))
        val actions = placeRadarActions(anchors, anchors.mapValues { listOf(it.value) }, viewport, 80f, 40f, 8f)
        assertEquals(setOf(0, 1), actions.keys)
        assertFalse(actions.getValue(0).overlaps(actions.getValue(1)))
    }

    @Test fun adjacentLinksKeepDistinctNonOverlappingActions() {
        val anchors = linkedMapOf(0 to RadarBox(100f, 100f, 160f, 120f), 1 to RadarBox(165f, 100f, 220f, 120f))
        val obstacles = anchors.mapValues { listOf(it.value) }
        val actions = placeRadarActions(anchors, obstacles, viewport, 120f, 40f, 8f)
        assertEquals(2, actions.size)
        assertFalse(actions.getValue(0).overlaps(actions.getValue(1)))
        actions.forEach { (key, action) ->
            obstacles.filterKeys { it != key }.values.flatten().forEach { assertFalse(action.overlaps(it)) }
        }
    }

    @Test fun actionForTopOrBottomLinkStaysOnScreen() {
        val anchors = linkedMapOf(0 to RadarBox(350f, 0f, 399f, 20f), 1 to RadarBox(0f, 580f, 50f, 600f))
        val actions = placeRadarActions(anchors, anchors.mapValues { listOf(it.value) }, viewport, 120f, 40f, 8f)
        assertEquals(2, actions.size)
        for (action in actions.values) {
            assertTrue(action.left >= 0 && action.top >= 0 && action.right <= 400 && action.bottom <= 600)
        }
    }

    @Test fun largeFallbackFrameStillGetsAnOpenPrompt() {
        val actions = placeRadarActions(mapOf(7 to viewport), mapOf(7 to listOf(viewport)), viewport, 120f, 40f, 8f)
        assertNotNull(actions[7])
    }

    @Test fun offscreenLinksDoNotGetClampedIntoVisiblePrompts() {
        val actions = placeRadarActions(mapOf(0 to RadarBox(0f, 700f, 100f, 720f)), emptyMap(), viewport, 120f, 40f, 8f)
        assertTrue(actions.isEmpty())
    }

    @Test fun tinyViewportDoesNotProduceOffscreenActions() {
        assertTrue(placeRadarActions(mapOf(0 to viewport), emptyMap(), RadarBox(0f, 0f, 10f, 10f), 120f, 40f, 8f).isEmpty())
    }

    @Test fun wrappedLinkCanBeClickedAnywhereInsideItsDrawnFrame() {
        val frame = enclosingRadarBox(listOf(RadarBox(10f, 10f, 100f, 30f), RadarBox(10f, 40f, 50f, 60f)))!!
        val areas = listOf(RadarHitArea(0, 7, true, listOf(frame)))
        assertEquals(listOf(0), findRadarTargets(areas, 80f, 50f, 0f))
        assertTrue(findRadarTargets(areas, 110f, 50f, 0f).isEmpty())
    }
}
