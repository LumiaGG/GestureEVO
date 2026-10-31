package io.github.lumiagg.gestureevo.settings.ui

import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import org.junit.Assert.*
import org.junit.Test

class ActionCatalogTest {
    @Test fun unifiedCatalogIncludesEveryExistingActionType() {
        val choices = actionCategories(36).flatMap { it.second }
        assertEquals(GestureActionType.entries.toSet(), choices.map { it.type }.toSet())
        assertEquals(choices.size, choices.distinctBy { it.type to it.parameters }.size)
    }

    @Test fun externalActionsExcludeUnsupportedChoicesBeforeSearching() {
        val choices = actionCategories(36, allowWheel = false, allowNone = false, allowClickThrough = false)
            .flatMap { it.second }
        val excluded = setOf(GestureActionType.SHOW_ACTION_VIEW, GestureActionType.NONE, GestureActionType.CLICK_THROUGH)
        assertEquals(GestureActionType.entries.toSet() - excluded, choices.map { it.type }.toSet())
    }

    @Test fun navigationPreservesAndroidActionIdsAndVersionRequirements() {
        val modern = actionCategories(36).flatMap { it.second }.filter { it.type == GestureActionType.ACC_ACTION }
        assertEquals("8", modern.single { it.title == R.string.acc_action_select_lock_screen }.parameters?.get("accAction"))
        assertEquals("6", modern.single { it.title == R.string.acc_action_select_power_dialog }.parameters?.get("accAction"))
        val legacy = actionCategories(24).flatMap { it.second }
        assertFalse(legacy.any { it.title == R.string.acc_action_select_lock_screen || it.title == R.string.acc_action_select_screenshot })
        assertTrue(legacy.any { it.title == R.string.acc_action_select_home })
    }

    @Test fun parameterizedChoicesRequireConfirmationBeforeCommitting() {
        val choices = actionCategories(36).flatMap { it.second }
        for (type in listOf(GestureActionType.OPEN_APP, GestureActionType.OPEN_ACTIVITY,
            GestureActionType.OTHER_SHORTCUT, GestureActionType.OPEN_SHELL, GestureActionType.TEMP_DND,
            GestureActionType.PAUSE_GESTURE, GestureActionType.OTHER_LINK)) {
            assertNull(choices.single { it.type == type }.parameters)
        }
        assertNotNull(choices.single { it.type == GestureActionType.SEARCH }.parameters)
    }
}
