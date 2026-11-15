package io.github.lumiagg.gestureevo.drop

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalDropSidebarPolicyTest {
    private fun layout(columns: Int, rows: Int, height: Int) =
        ExternalDropSidebarPolicy.Layout(columns, rows, height)

    @Test fun heightPriorityUsesTheNarrowestLayoutThatFitsAllEngines() {
        val candidates = listOf(layout(1, 2, 180), layout(2, 1, 120))
        assertEquals(1, ExternalDropSidebarPolicy.choose(candidates, 2, 200, false).columns)
        assertEquals(2, ExternalDropSidebarPolicy.choose(candidates, 2, 200, true).columns)
        val sixEngines = listOf(layout(1, 4, 300), layout(2, 3, 240), layout(3, 2, 180))
        assertEquals(2, ExternalDropSidebarPolicy.choose(sixEngines, 6, 300, false).columns)
        assertEquals(3, ExternalDropSidebarPolicy.choose(sixEngines, 6, 300, true).columns)
    }

    @Test fun tooSmallHeightFallsBackToTheShortestUsableLayout() {
        val candidates = listOf(layout(1, 1, 260), layout(2, 1, 220), layout(3, 1, 180))
        assertEquals(3, ExternalDropSidebarPolicy.choose(candidates, 8, 100, false).columns)
        assertEquals(3, ExternalDropSidebarPolicy.choose(candidates, 8, 100, true).columns)
    }

    @Test fun detachedCardKeepsThePanelCenteredUnlessTheScreenEdgeRequiresAShift() {
        assertEquals(300, ExternalDropSidebarPolicy.topOffset(1000, 400, 400))
        assertEquals(300, ExternalDropSidebarPolicy.topOffset(1000, 400, 460))
        assertEquals(40, ExternalDropSidebarPolicy.topOffset(500, 400, 460))
        assertEquals(0, ExternalDropSidebarPolicy.topOffset(400, 400, 460))
        assertEquals(0, ExternalDropSidebarPolicy.topOffset(300, 400, 460))
    }

    @Test fun pagingRequiresActualEntryAndRetainsOnlySmallPointerWobble() {
        val next = ExternalDropSidebarPolicy.HoverBounds(8, 180, 112, 212)
        assertEquals(0, ExternalDropSidebarPolicy.hoverDirection(60f, 176f, null, next, 0, 6))
        assertEquals(1, ExternalDropSidebarPolicy.hoverDirection(60f, 180f, null, next, 0, 6))
        assertEquals(0, ExternalDropSidebarPolicy.hoverDirection(60f, 190f, null, null, 1, 6))
        assertEquals(1, ExternalDropSidebarPolicy.hoverDirection(60f, 215f, null, next, 1, 6))
        assertEquals(1, ExternalDropSidebarPolicy.hoverDirection(5f, 196f, null, next, 1, 6))
        assertEquals(0, ExternalDropSidebarPolicy.hoverDirection(60f, 219f, null, next, 1, 6))
        assertEquals(0, ExternalDropSidebarPolicy.hoverDirection(60f, 215f, null, next, 0, 6))
    }
}
