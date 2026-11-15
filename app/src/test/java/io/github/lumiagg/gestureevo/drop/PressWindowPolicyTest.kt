package io.github.lumiagg.gestureevo.drop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PressWindowPolicyTest {
    private val sourceBounds = PressWindowPolicy.Bounds(0, 0, 1000, 2000)
    private val source = PressWindowPolicy.Window(1, 1, "com.app", sourceBounds, active = true)
    private val ownOverlay = PressWindowPolicy.Window(9, 4, "io.github.lumiagg.gestureevo.debug",
        PressWindowPolicy.Bounds(0, 0, 100, 100))

    private fun menuPopup(id: Int = 2, active: Boolean = false, focused: Boolean = false,
        bounds: PressWindowPolicy.Bounds = PressWindowPolicy.Bounds(300, 800, 700, 1200)) =
        PressWindowPolicy.Window(id, 1, "com.app", bounds, active, focused)

    private fun reason(windows: List<PressWindowPolicy.Window>, eventId: Int = 2, stateChanged: Boolean = false) =
        PressWindowPolicy.cancellationReason(1, "com.app", sourceBounds, windows, eventId, stateChanged, false,
            "io.github.lumiagg.gestureevo.debug", setOf(9))

    @Test fun missingSourceAndExplicitRemovalCancel() {
        val windows = listOf(ownOverlay, menuPopup(active = true))
        assertEquals("source_missing", reason(windows))
        assertEquals("source_removed", PressWindowPolicy.cancellationReason(
            1, "com.app", sourceBounds, windows, 1, true, true, "io.github.lumiagg.gestureevo.debug"))
    }

    @Test fun ownOverlayWindowChangesRemainAllowed() {
        assertNull(reason(listOf(source, ownOverlay), eventId = 9, stateChanged = true))
    }

    @Test fun focusedSmallMenuCancels() {
        for (popup in listOf(menuPopup(focused = true), menuPopup(active = true))) {
            val windows = listOf(source, ownOverlay, popup)
            assertEquals("source_switched", reason(windows))
        }
    }

    @Test fun smallMenuWindowStateCancels() {
        val windows = listOf(source, ownOverlay, menuPopup())
        assertEquals("other_window_state", reason(windows, stateChanged = true))
    }

    @Test fun stillCancelsOnFullWidthSamePackagePanel() {
        assertEquals("source_switched",
            reason(listOf(source, ownOverlay, menuPopup(focused = true, bounds = PressWindowPolicy.Bounds(0, 1200, 1000, 2000)))))
        assertEquals("other_window_state",
            reason(listOf(source, ownOverlay, menuPopup(bounds = PressWindowPolicy.Bounds(0, 1200, 1000, 2000))), stateChanged = true))
    }

    @Test fun stillCancelsOnNearFullscreenSamePackageDialog() {
        assertEquals("source_switched",
            reason(listOf(source, ownOverlay, menuPopup(focused = true, bounds = PressWindowPolicy.Bounds(50, 100, 950, 1900)))))
    }

    @Test fun stillCancelsOnForeignPackageWindow() {
        val foreign = PressWindowPolicy.Window(3, 1, "com.other", PressWindowPolicy.Bounds(0, 0, 200, 200), focused = true)
        assertEquals("source_switched", reason(listOf(source, ownOverlay, foreign)))
    }
}
