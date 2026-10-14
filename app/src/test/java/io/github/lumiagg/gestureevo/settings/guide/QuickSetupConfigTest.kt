package io.github.lumiagg.gestureevo.settings.guide

import io.github.lumiagg.gestureevo.gesture.config.GestureActionItem
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.gesture.config.IconType
import org.junit.Assert.*
import org.junit.Test

class QuickSetupConfigTest {
    @Test fun defaultsWorkOnTheOldestSupportedAndroidVersion() {
        val config = QuickSetupConfig.default()
        config.validate(24)
        assertEquals(GestureActionType.SHOW_ACTION_VIEW, config.gestures.getValue(QuickGesture.SWIPE_IN).guideActionType)
        assertTrue(config.wheelItems.isNotEmpty())
    }

    @Test fun snapshotsKeepAllRuntimeActionFieldsAndAreDetached() {
        val custom = createGuideAction(GestureActionType.OTHER_SHORTCUT, mapOf("foo" to "A & B")).apply {
            tag = "stable-id"
            name = "Custom action"
            iconType = IconType.URI
            icon = "content://custom/icon"
            subActionListTag = "nested-wheel"
            secondStageLink = "gesture-evo://action/SEARCH?timeout=true"
        }
        val config = QuickSetupConfig.default().copy(wheelItems = listOf(custom, createAccessibilityAction(2)))
        val snapshot = config.snapshot()
        assertEquals(config.toJson(), snapshot.toJson())
        assertNotSame(custom, snapshot.wheelItems.first())
        custom.name = "Changed later"
        assertEquals("Custom action", snapshot.wheelItems.first().name)
        assertEquals("stable-id", snapshot.wheelItems.first().tag)
        assertEquals(IconType.URI, snapshot.wheelItems.first().iconType)
        assertEquals("nested-wheel", snapshot.wheelItems.first().subActionListTag)
        assertEquals("gesture-evo://action/SEARCH?timeout=true", snapshot.wheelItems.first().secondStageLink)
    }

    @Test fun positionsAcrossTheWholeScreenCanBeSaved() {
        listOf(0, 5, 90, 95, 100).forEach { position ->
            val config = QuickSetupConfig.default().copy(verticalPercent = position)
            config.validate(24)
            assertEquals(config.toJson(), config.snapshot().toJson())
        }
        assertInvalid(QuickSetupConfig.default().copy(verticalPercent = -1))
        assertInvalid(QuickSetupConfig.default().copy(verticalPercent = 101))
        assertInvalid(QuickSetupConfig.default().copy(triggerHeightDp = 0))
    }

    @Test fun existingShortAndLargeTriggersAndSparseWheelsArePreserved() {
        listOf(20, 300).forEach { height ->
            QuickSetupConfig.default().copy(triggerHeightDp = height, wheelItems = emptyList()).validate(24)
        }
        val subWheel = GestureActionItem().apply { subActionListTag = "nested"; iconType = IconType.TEXT; name = "Tools" }
        QuickSetupConfig.default().copy(wheelItems = listOf(subWheel)).validate(24)
    }

    @Test fun everyGuideGestureMustStillHaveAModel() {
        assertInvalid(QuickSetupConfig.default().copy(gestures = QuickSetupConfig.default().gestures - QuickGesture.TAP))
    }

    @Test fun appActionsValidateTheSelectedProfileAndPackage() {
        assertInvalid(QuickSetupConfig.default().copy(wheelItems = listOf(createGuideAction(GestureActionType.OPEN_APP))))
        assertInvalid(QuickSetupConfig.default().copy(wheelItems = listOf(createGuideAction(GestureActionType.OPEN_APP, mapOf("pkg" to " ")))))
        assertInvalid(QuickSetupConfig.default().copy(wheelItems = listOf(createGuideAction(GestureActionType.OPEN_APP, mapOf("pkg" to "example", "userId" to "-1")))))
        QuickSetupConfig.default().copy(wheelItems = listOf(createGuideAction(GestureActionType.OPEN_APP,
            mapOf("pkg" to "example", "userId" to "10", "freeform" to "true")))).validate(24)
    }

    @Test fun screenshotValidationIncludesSecondStage() {
        val action = createAccessibilityAction(1).apply { secondStageLink = createAccessibilityAction(9).firstStageLink }
        val config = QuickSetupConfig.default().copy(wheelItems = listOf(action))
        assertInvalid(config, 27)
        config.validate(28)
    }

    @Test fun actionCopiesKeepTheirIdentityAndAllSettings() {
        val source = createGuideAction(GestureActionType.OPEN_APP, mapOf("pkg" to "example", "userId" to "10", "freeform" to "true")).apply {
            name = "Work app"; iconType = IconType.TEXT; icon = "custom"; subActionListTag = "sub"; secondStageLink = "gesture-evo://action/SEARCH"
        }
        assertEquals(source.toJson(false), source.copy().toJson(false))
        val retagged = source.copy(tag = "gesture-key")
        assertEquals("gesture-key", retagged.tag)
        assertEquals(source.firstStageLink, retagged.firstStageLink)
        assertEquals(source.secondStageLink, retagged.secondStageLink)
        assertEquals(source.iconType, retagged.iconType)
        assertEquals(source.subActionListTag, retagged.subActionListTag)
    }

    @Test fun dirtyCheckingIgnoresStorageKeysAndRetainsActualEdits() {
        val config = QuickSetupConfig.default()
        val restored = config.snapshot().copy(hasPersistedConfiguration = true,
            editedGestures = setOf(QuickGesture.TAP),
            gestures = config.gestures.mapValues { (_, action) -> action.copy(tag = "runtime-key") })
        assertEquals(config.contentJson(), restored.contentJson())
        assertNotEquals(config.contentJson(), restored.copy(verticalPercent = 95).contentJson())
    }

    @Test fun quickSelectionKeepsCustomIconSecondStageAndSubWheel() {
        val config = QuickSetupConfig.default()
        val original = config.gestures.getValue(QuickGesture.TAP).copy().apply {
            iconType = IconType.URI; icon = "content://custom"; subActionListTag = "nested"
            secondStageLink = "gesture-evo://action/SEARCH"
        }
        val updated = config.copy(gestures = config.gestures + (QuickGesture.TAP to original))
            .withGesture(QuickGesture.TAP, replaceGuideAction(original, createAccessibilityAction(2))).gestures.getValue(QuickGesture.TAP)
        assertEquals(original.tag, updated.tag)
        assertEquals(original.icon, updated.icon)
        assertEquals(original.iconType, updated.iconType)
        assertEquals(original.subActionListTag, updated.subActionListTag)
        assertEquals(original.secondStageLink, updated.secondStageLink)
        assertEquals("2", parseActionLink(updated.firstStageLink!!).second["accAction"])
    }

    @Test fun fullEditorReplacesAdvancedFieldsAndAllowsClearingThem() {
        val config = QuickSetupConfig.default()
        val original = config.gestures.getValue(QuickGesture.TAP).copy().apply {
            iconType = IconType.URI; icon = "content://old"; subActionListTag = "old-sub"
            secondStageLink = "gesture-evo://action/SEARCH"; name = "Old"
        }
        val draft = config.copy(gestures = config.gestures + (QuickGesture.TAP to original))
        val selected = createGuideAction(GestureActionType.OPEN_APP, mapOf("pkg" to "work", "userId" to "10")).apply {
            iconType = IconType.TEXT; name = "Work"; subActionListTag = "new-sub"
            secondStageLink = "gesture-evo://action/PIN_POOL"
        }
        val edited = draft.withGesture(QuickGesture.TAP, selected, preserveOtherPhases = true)
        val result = edited.gestures.getValue(QuickGesture.TAP)
        assertEquals(selected.copy(tag = original.tag).toJson(false), result.toJson(false))
        assertTrue(QuickGesture.TAP in edited.editedGestures)
        assertTrue(QuickGesture.TAP in edited.preservedGesturePhases)
        selected.name = "Changed after return"
        assertEquals("Work", result.name)
        val cleared = edited.withGesture(QuickGesture.TAP, createAccessibilityAction(1), preserveOtherPhases = true)
            .gestures.getValue(QuickGesture.TAP)
        assertNull(cleared.icon)
        assertNull(cleared.subActionListTag)
        assertNull(cleared.secondStageLink)
        assertNull(cleared.name)
    }

    private fun assertInvalid(config: QuickSetupConfig, sdk: Int = 35) {
        assertTrue(runCatching { config.validate(sdk) }.exceptionOrNull() is IllegalArgumentException)
    }
}
