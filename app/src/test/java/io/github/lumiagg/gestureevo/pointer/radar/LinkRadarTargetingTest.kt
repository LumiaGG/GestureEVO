package io.github.lumiagg.gestureevo.pointer.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkRadarTargetingTest {
    private fun area(index: Int, left: Float, right: Float, precise: Boolean = true, group: Int = index) =
        RadarHitArea(index, group, precise, listOf(RadarBox(left, 10f, right, 30f)))

    @Test fun preciseLinkWinsOverContainingControlFallback() {
        val areas = listOf(area(0, 0f, 100f, precise = false), area(1, 40f, 60f))
        assertEquals(listOf(1), findRadarTargets(areas, 50f, 20f, 10f))
    }

    @Test fun separatePreciseLinksInSameNodeRemainIndividuallySelectable() {
        val areas = listOf(area(0, 0f, 20f, group = 7), area(1, 80f, 100f, group = 7))
        assertEquals(listOf(0), findRadarTargets(areas, 10f, 20f, 5f))
        assertEquals(listOf(1), findRadarTargets(areas, 90f, 20f, 5f))
    }

    @Test fun ambiguousControlFallbackOffersAllLinksInThatNode() {
        val areas = listOf(area(0, 0f, 100f, false, 7), area(1, 0f, 100f, false, 7), area(2, 200f, 300f, false, 8))
        assertEquals(listOf(0, 1), findRadarTargets(areas, 50f, 20f, 5f))
    }

    @Test fun overlappingPreciseLinksOfferSelection() {
        assertEquals(listOf(0, 1), findRadarTargets(listOf(area(0, 0f, 30f), area(1, 20f, 50f)), 25f, 20f, 2f))
    }

    @Test fun hysteresisKeepsNearbyPreviousTargetWithoutSelectingDistantLinks() {
        val areas = listOf(area(0, 0f, 10f), area(1, 20f, 30f))
        assertEquals(listOf(0), findRadarTargets(areas, 16f, 20f, 10f, previous = 0, hysteresis = 5f))
        assertEquals(listOf(1), findRadarTargets(areas, 29f, 20f, 10f, previous = 0, hysteresis = 5f))
    }

    @Test fun releaseOutsideLinkDoesNotUsePreviousTarget() {
        assertTrue(findRadarTargets(listOf(area(0, 0f, 20f)), 100f, 100f, 5f, previous = 0, hysteresis = 5f).isEmpty())
    }

    @Test fun emptyCharacterGeometryCannotBeClicked() {
        assertTrue(findRadarTargets(listOf(RadarHitArea(0, 0, true, emptyList())), 0f, 0f, 10f).isEmpty())
    }
}
