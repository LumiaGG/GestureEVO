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

    @Test fun focusedSourceMenuDoesNotCancel() {
        for (popup in listOf(menuPopup(focused = true), menuPopup(active = true))) {
            val windows = listOf(source, ownOverlay, popup)
            assertNull(reason(windows))
        }
    }

    @Test fun sourceMenuWindowStateDoesNotCancel() {
        val windows = listOf(source, ownOverlay, menuPopup())
        assertNull(reason(windows, stateChanged = true))
    }

    @Test fun sourceBottomSheetDoesNotCancel() {
        assertNull(reason(listOf(source, ownOverlay,
            menuPopup(focused = true, bounds = PressWindowPolicy.Bounds(0, 1200, 1000, 2000)))))
        assertNull(reason(listOf(source, ownOverlay,
            menuPopup(bounds = PressWindowPolicy.Bounds(0, 1200, 1000, 2000))), stateChanged = true))
    }

    @Test fun sourceDialogDoesNotCancel() {
        assertNull(reason(listOf(source, ownOverlay,
            menuPopup(focused = true, bounds = PressWindowPolicy.Bounds(50, 100, 950, 1900)))))
    }

    @Test fun inactiveButVisibleSourceRemainsUsableUnderItsMenu() {
        assertNull(reason(listOf(source.copy(active = false), menuPopup(active = true)), stateChanged = true))
        assertEquals("source_hidden", reason(listOf(source.copy(visible = false), menuPopup(active = true))))
    }

    @Test fun sameAppReplacementAndOutsideWindowStillCancel() {
        assertEquals("source_switched", reason(listOf(source, menuPopup(active = true, bounds = sourceBounds))))
        assertEquals("source_switched", reason(listOf(source,
            menuPopup(active = true, bounds = PressWindowPolicy.Bounds(-1, 100, 300, 400)))))
    }

    @Test fun closedSourceMenuDoesNotLookLikeAnAppSwitch() {
        assertNull(PressWindowPolicy.cancellationReason(1, "com.app", sourceBounds, listOf(source),
            2, true, false, "io.github.lumiagg.gestureevo.debug", eventPackage = "com.app"))
        assertEquals("other_window_state", reason(listOf(source), stateChanged = true))
    }

    @Test fun stillCancelsOnForeignPackageWindow() {
        val foreign = PressWindowPolicy.Window(3, 1, "com.other", PressWindowPolicy.Bounds(0, 0, 200, 200), focused = true)
        assertEquals("source_switched", reason(listOf(source, ownOverlay, foreign)))
    }
}
