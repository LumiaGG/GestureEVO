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

    private fun reason(windows: List<PressWindowPolicy.Window>, eventId: Int = 2, stateChanged: Boolean = false,
        allowSourceMenu: Boolean = false) =
        PressWindowPolicy.cancellationReason(1, "com.app", sourceBounds, windows, eventId, stateChanged, false,
            "io.github.lumiagg.gestureevo.debug", setOf(9), allowSourceMenu)

    private fun covered(windows: List<PressWindowPolicy.Window>) = PressWindowPolicy.isSourceCoveredByMenu(
        1, "com.app", sourceBounds, windows, "io.github.lumiagg.gestureevo.debug")

    @Test fun menuCoverCompatibilityRequiresProtectionAndNeverOverridesRemoval() {
        val windows = listOf(ownOverlay, menuPopup(active = true))
        fun check(allow: Boolean, removed: Boolean = false, eventId: Int = 2) =
            PressWindowPolicy.cancellationReason(1, "com.app", sourceBounds, windows, eventId, true, removed,
                "io.github.lumiagg.gestureevo.debug", allowSourceMenu = allow)
        assertEquals("source_missing", check(false))
        assertNull(check(true))
        assertEquals("source_removed", check(true, removed = true, eventId = 1))
    }

    @Test fun missingSourceRequiresVerifiedFocusedMenuAndContainedBounds() {
        for (windows in listOf(
            emptyList(), listOf(ownOverlay), listOf(menuPopup()),
            listOf(menuPopup(active = true, bounds = PressWindowPolicy.Bounds(300, 800, 300, 800))),
            listOf(menuPopup(active = true, bounds = PressWindowPolicy.Bounds(-50, 100, 200, 400))),
            listOf(menuPopup(active = true, bounds = PressWindowPolicy.Bounds(0, 1000, 1000, 2000))),
            listOf(menuPopup(active = true, bounds = PressWindowPolicy.Bounds(20, 20, 980, 1980))),
            listOf(menuPopup(active = true), PressWindowPolicy.Window(3, 1, "com.other", sourceBounds)),
            listOf(menuPopup(active = true), PressWindowPolicy.Window(4, 2, "keyboard", sourceBounds, focused = true))
        )) org.junit.Assert.assertFalse(covered(windows))
    }

    @Test fun focusedSmallMenuRequiresRootProtection() {
        for (popup in listOf(menuPopup(focused = true), menuPopup(active = true))) {
            val windows = listOf(source, ownOverlay, popup)
            assertEquals("source_switched", reason(windows))
            assertNull(reason(windows, allowSourceMenu = true))
        }
    }

    @Test fun smallMenuWindowStateRequiresRootProtection() {
        val windows = listOf(source, ownOverlay, menuPopup())
        assertEquals("other_window_state", reason(windows, stateChanged = true))
        assertNull(reason(windows, stateChanged = true, allowSourceMenu = true))
    }

    @Test fun stillCancelsOnFullWidthSamePackagePanel() {
        assertEquals("source_switched",
            reason(listOf(source, ownOverlay, menuPopup(focused = true, bounds = PressWindowPolicy.Bounds(0, 1200, 1000, 2000))), allowSourceMenu = true))
        assertEquals("other_window_state",
            reason(listOf(source, ownOverlay, menuPopup(bounds = PressWindowPolicy.Bounds(0, 1200, 1000, 2000))), stateChanged = true, allowSourceMenu = true))
    }

    @Test fun stillCancelsOnNearFullscreenSamePackageDialog() {
        assertEquals("source_switched",
            reason(listOf(source, ownOverlay, menuPopup(focused = true, bounds = PressWindowPolicy.Bounds(50, 100, 950, 1900))), allowSourceMenu = true))
    }

    @Test fun stillCancelsOnForeignPackageWindow() {
        val foreign = PressWindowPolicy.Window(3, 1, "com.other", PressWindowPolicy.Bounds(0, 0, 200, 200), focused = true)
        assertEquals("source_switched", reason(listOf(source, ownOverlay, foreign), allowSourceMenu = true))
    }
}
