package io.github.lumiagg.gestureevo.utils.icon

import org.junit.Assert.*
import org.junit.Test

class IconResolutionTest {
    @Test fun customIconHasPriorityWithoutLoadingPackOrApplication() {
        assertEquals("custom", resolveIcon("custom.png", { "custom" }, { error("pack should not load") }, { error("app should not load") }))
    }

    @Test fun packHasPriorityOverOriginalApplicationIcon() {
        assertEquals("pack", resolveIcon(null, { error("no custom path") }, { "pack" }, { error("app should not load") }))
    }

    @Test fun unsupportedPackFallsBackToApplicationIcon() {
        assertEquals("application", resolveIcon(null, { null }, { null }, { "application" }))
    }

    @Test fun unreadableCustomIconDoesNotLeaveTheApplicationWithoutAnIcon() {
        assertEquals("pack", resolveIcon("missing.png", { null }, { "pack" }, { "application" }))
        assertEquals("application", resolveIcon("corrupt.png", { null }, { null }, { "application" }))
    }
}
