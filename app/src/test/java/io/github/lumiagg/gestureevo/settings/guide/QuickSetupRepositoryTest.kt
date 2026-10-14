package io.github.lumiagg.gestureevo.settings.guide

import io.github.lumiagg.gestureevo.gesture.config.ActionItemsTag
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerConfig
import io.github.lumiagg.gestureevo.gesture.config.GestureActionItem
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.gesture.config.IconType
import io.github.lumiagg.gestureevo.gesture.ui.GesturePhase
import io.github.lumiagg.gestureevo.gesture.ui.GestureType
import io.github.lumiagg.gestureevo.gesture.ui.GestureTypeEvent
import org.junit.Assert.*
import org.junit.Test

class QuickSetupRepositoryTest {
    private fun key(type: GestureType, phase: GesturePhase = GesturePhase.TD) = GestureTypeEvent(type, phase).toActionKey()
    private fun trigger() = FreeTriggerConfig().apply { tag = "default-id"; name = "My trigger"; xRelativeValue = 1f; yRelativeValue = 0.573f; height = 300 }

    @Test fun resolutionAndMergePreserveOtherTriggersAndCleanLegacy() {
        val other = FreeTriggerConfig().apply { tag = "other"; name = "Other" }
        val legacy = FreeTriggerConfig().apply { tag = QuickSetupRepository.LEFT_TRIGGER_TAG }
        val selected = trigger()
        val existing = listOf(other, legacy, selected)
        assertSame(selected, resolveDefaultTrigger(existing, "My trigger") { error("unexpected fallback") })
        assertEquals(listOf(other, selected), mergeQuickSetupTriggers(existing, selected))
    }

    @Test fun geometryOnlyChangesKeepPrecisePositionHeightAndAdvancedActions() {
        val item = createGuideAction(GestureActionType.OPEN_APP, mapOf("pkg" to "example", "freeform" to "true", "userId" to "10")).apply {
            tag = key(GestureType.SWIPE_UP); name = "Work"; iconType = IconType.URI; icon = "content://icon"
            secondStageLink = "gesture-evo://action/SEARCH"; subActionListTag = "sub"
        }
        val source = trigger()
        val draft = buildGuideDraft(source, listOf(item), listOf(item))
        val saved = applyDefaultTriggerConfig(source.copy(), draft, "Default")
        assertEquals(0.573f, saved.yRelativeValue, 0f)
        assertEquals(300, saved.height)
        assertEquals("My trigger", saved.name)
        val merged = QuickSetupRepository.mergeTriggerActions(listOf(item), draft.copy(verticalPercent = 95), draft.side)
        assertEquals(item.toJson(false), merged.single().toJson(false))
        assertEquals(item.toJson(false), buildQuickSetupWheelItems(draft).single().toJson(false))
        assertNotSame(item, buildQuickSetupWheelItems(draft).single())
    }

    @Test fun changingPositionReachesTheWholeScreen() {
        val source = trigger()
        val draft = buildGuideDraft(source, emptyList(), emptyList())
        listOf(0, 95, 100).forEach { percent ->
            val result = applyDefaultTriggerConfig(source.copy(), draft.copy(verticalPercent = percent), "Default")
            assertEquals(percent / 100f, result.yRelativeValue, 0f)
        }
    }

    @Test fun committedBaselineAllowsRepeatedSavesAndRestoringOriginalControls() {
        var persisted = trigger()
        var actions = emptyList<GestureActionItem>()
        var draft = buildGuideDraft(persisted, actions, emptyList())
        fun save(value: QuickSetupConfig) {
            actions = QuickSetupRepository.mergeTriggerActions(actions, value, value.side)
            persisted = applyDefaultTriggerConfig(persisted.copy(), value, "Default")
            draft = buildGuideDraft(persisted, actions, value.wheelItems)
                .copy(hasPersistedConfiguration = true)
        }
        save(draft.copy(verticalPercent = 90, triggerHeightDp = 100, side = QuickSetupSide.LEFT))
        assertEquals(0.90f, persisted.yRelativeValue, 0f)
        assertEquals(100, persisted.height)
        assertEquals(QuickSetupSide.LEFT, triggerSide(persisted))
        save(draft.copy(verticalPercent = 57, triggerHeightDp = 300, side = QuickSetupSide.RIGHT))
        assertEquals(0.57f, persisted.yRelativeValue, 0f)
        assertEquals(300, persisted.height)
        assertEquals(QuickSetupSide.RIGHT, triggerSide(persisted))
        save(draft.copy(verticalPercent = 100, side = QuickSetupSide.BOTH))
        assertEquals(1f, persisted.yRelativeValue, 0f)
        assertEquals(QuickSetupSide.BOTH, triggerSide(persisted))
        save(draft.copy(side = QuickSetupSide.RIGHT))
        assertEquals(QuickSetupSide.RIGHT, triggerSide(persisted))
        assertTrue(draft.editedGestures.isEmpty())
        assertEquals(persisted.toJson(), draft.baseTrigger?.copy()?.apply { gestureActionList = null }?.toJson())
    }

    @Test fun savedGestureEditsDoNotOverwriteLaterExternalChanges() {
        val original = buildGuideDraft(trigger(), emptyList(), emptyList())
        val edited = original.withGesture(QuickGesture.TAP, createAccessibilityAction(1))
        val committedActions = QuickSetupRepository.mergeTriggerActions(emptyList(), edited, edited.side)
        val committed = buildGuideDraft(trigger(), committedActions, emptyList())
        val external = createAccessibilityAction(2).copy(tag = key(GestureType.SINGLE_CLICK))
        val latest = committedActions.map { if (it.tag == external.tag) external else it }
        val merged = QuickSetupRepository.mergeTriggerActions(latest, committed.copy(verticalPercent = 90), committed.side)
        assertEquals(external.toJson(false), merged.single { it.tag == external.tag }.toJson(false))
    }

    @Test fun legacyActionsTakeRuntimePrecedenceAndSurviveGuideEdits() {
        val legacyKey = key(GestureType.DOUBLE_CLICK)
        val source = trigger().apply { actionMap = mapOf(legacyKey to "gesture-evo://action/SEARCH") }
        val stale = createAccessibilityAction(2).copy(tag = legacyKey)
        val migrated = readTriggerActions(source, listOf(stale))
        assertEquals("gesture-evo://action/SEARCH", migrated.single().firstStageLink)
        val draft = buildGuideDraft(source, migrated, emptyList())
        val edited = draft.withGesture(QuickGesture.TAP, createAccessibilityAction(1))
        val merged = QuickSetupRepository.mergeTriggerActions(migrated, edited, draft.side)
        assertEquals("gesture-evo://action/SEARCH", merged.single { it.tag == legacyKey }.firstStageLink)
    }

    @Test fun unrelatedGesturePhasesAndAsymmetricInwardActionsStayIntact() {
        val left = createGuideAction(GestureActionType.SEARCH).copy(tag = key(GestureType.SWIPE_LEFT))
        val right = createAccessibilityAction(2).copy(tag = key(GestureType.SWIPE_RIGHT))
        val release = createGuideAction(GestureActionType.PIN_POOL).copy(tag = key(GestureType.SWIPE_UP, GesturePhase.RD))
        val double = createGuideAction(GestureActionType.OTHER_LINK, mapOf("link" to "https://example.com")).copy(tag = key(GestureType.DOUBLE_CLICK))
        val source = trigger().apply { setState(FreeTriggerConfig.FLAG_MIRROR, true) }
        val existing = listOf(left, right, release, double)
        val draft = buildGuideDraft(source, existing, emptyList())
        val merged = QuickSetupRepository.mergeTriggerActions(existing, draft.copy(triggerHeightDp = 100), draft.side)
        assertEquals(existing.map { it.toJson(false) }, merged.map { it.toJson(false) })
    }

    @Test fun fullEditorKeepsOtherNativeGesturePhases() {
        val touch = createGuideAction(GestureActionType.SEARCH).copy(tag = key(GestureType.SWIPE_UP))
        val release = createGuideAction(GestureActionType.PIN_POOL).copy(tag = key(GestureType.SWIPE_UP, GesturePhase.RD))
        val source = buildGuideDraft(trigger(), listOf(touch, release), emptyList())
        val editedAction = touch.copy().apply { iconType = IconType.TEXT; name = "Custom" }
        val edited = source.withGesture(QuickGesture.SWIPE_UP, editedAction, preserveOtherPhases = true)
        val restored = edited.snapshot()
        val result = QuickSetupRepository.mergeTriggerActions(listOf(touch, release), restored, restored.side)
        assertEquals(release.toJson(false), result.single { it.tag == release.tag }.toJson(false))
        assertEquals("Custom", result.single { it.tag == touch.tag }.name)
        val quickSelection = edited.withGesture(QuickGesture.SWIPE_UP, createAccessibilityAction(2))
        assertFalse(QuickGesture.SWIPE_UP in quickSelection.preservedGesturePhases)
        assertEquals(GestureActionType.NONE, QuickSetupRepository.mergeTriggerActions(listOf(touch, release), quickSelection, quickSelection.side)
            .single { it.tag == release.tag }.guideActionType)
        assertEquals(edited.contentJson(), edited.copy(preservedGesturePhases = emptySet()).contentJson())
    }

    @Test fun selectingANewGestureReplacesOnlyItsPhases() {
        val old = createGuideAction(GestureActionType.SEARCH).copy(tag = key(GestureType.SWIPE_UP))
        val release = old.copy(tag = key(GestureType.SWIPE_UP, GesturePhase.RD))
        val other = old.copy(tag = key(GestureType.DOUBLE_CLICK))
        val draft = buildGuideDraft(trigger(), listOf(old, release, other), emptyList()).withGesture(QuickGesture.SWIPE_UP, createAccessibilityAction(2))
        val merged = QuickSetupRepository.mergeTriggerActions(listOf(old, release, other), draft, draft.side)
        assertEquals("2", parseActionLink(merged.single { it.tag == old.tag }.firstStageLink!!).second["accAction"])
        assertEquals(GestureActionType.NONE, merged.single { it.tag == release.tag }.guideActionType)
        assertEquals(other.toJson(false), merged.single { it.tag == other.tag }.toJson(false))
    }

    @Test fun newBothSideConfigurationBindsBothInwardSwipes() {
        val config = QuickSetupConfig.default().copy(side = QuickSetupSide.BOTH)
        val actions = QuickSetupRepository.buildTriggerActions(config, config.side)
        val left = actions.single { it.tag == key(GestureType.SWIPE_LEFT) }
        val right = actions.single { it.tag == key(GestureType.SWIPE_RIGHT) }
        assertEquals(left.firstStageLink, right.firstStageLink)
        assertEquals(GestureActionType.SHOW_ACTION_VIEW, left.guideActionType)
    }

    @Test fun previewCopiesRealWidthTimingsAnglesAndOrientation() {
        val source = trigger().apply {
            width = 36
            gestureTimingConfig.longPressTimeoutMs = 720
            gestureTimingConfig.swipeThresholdDp = 42
            setState(FreeTriggerConfig.FLAG_LANDSCAPE, true)
            setState(FreeTriggerConfig.FLAG_PORTRAIT, false)
            setState(FreeTriggerConfig.FLAG_WHEEL_FOLLOW_TRIGGER_POINT, true)
        }
        val draft = buildGuideDraft(source, emptyList(), emptyList()).copy(side = QuickSetupSide.BOTH)
        val previews = buildQuickSetupTriggers(draft, "Preview")
        assertEquals(listOf(0f, 0f), previews.map { it.xRelativeValue })
        assertEquals(listOf(QuickSetupRepository.PREVIEW_LEFT_TRIGGER_TAG, QuickSetupRepository.PREVIEW_RIGHT_TRIGGER_TAG), previews.map { it.tag })
        previews.forEach {
            assertEquals(36, it.width)
            assertEquals(720, it.gestureTimingConfig.longPressTimeoutMs)
            assertEquals(42, it.gestureTimingConfig.swipeThresholdDp)
            assertEquals(300, it.height)
            assertEquals(0.573f, it.yRelativeValue, 0f)
            assertTrue(it.getState(FreeTriggerConfig.FLAG_LANDSCAPE))
            assertFalse(it.getState(FreeTriggerConfig.FLAG_PORTRAIT))
            assertFalse(it.getState(FreeTriggerConfig.FLAG_MIRROR))
            assertTrue(it.getState(FreeTriggerConfig.FLAG_WHEEL_FOLLOW_TRIGGER_POINT))
        }
        previews.first().gestureTimingConfig.longPressTimeoutMs = 1000
        assertEquals(720, source.gestureTimingConfig.longPressTimeoutMs)
        assertEquals(720, previews.last().gestureTimingConfig.longPressTimeoutMs)
    }

    @Test fun explicitSparseWheelsAreNeverReplacedWithDefaults() {
        assertTrue(buildGuideDraft(trigger(), emptyList(), emptyList()).wheelItems.isEmpty())
        val sub = GestureActionItem().apply { tag = "stable-sub"; subActionListTag = "nested"; iconType = IconType.TEXT; name = "Tools" }
        val draft = buildGuideDraft(trigger(), emptyList(), listOf(sub))
        assertEquals(sub.toJson(false), buildQuickSetupWheelItems(draft).single().toJson(false))
    }

    @Test fun wheelTagMergePreservesOtherGroups() {
        val other = ActionItemsTag().apply { tag = "other" }
        val legacy = ActionItemsTag().apply { tag = QuickSetupRepository.LEGACY_WHEEL_TAG }
        assertEquals(listOf(other), mergeQuickSetupWheelTags(listOf(other, legacy)))
    }

    @Test fun nativeActionLinksKeepParametersAndProfile() {
        val action = createGuideAction(GestureActionType.OPEN_APP, mapOf("pkg" to "example", "userId" to "10", "label" to "A & B"))
        assertEquals(mapOf("pkg" to "example", "userId" to "10", "label" to "A & B"), parseActionLink(action.firstStageLink!!).second)
    }

    @Test fun legacyGuideTriggerIsRecoveredInsteadOfDiscarded() {
        val legacy = trigger().apply { tag = QuickSetupRepository.LEFT_TRIGGER_TAG; xRelativeValue = 0f }
        assertSame(legacy, resolveDefaultTrigger(listOf(legacy), "Default") { error("must recover old guide") })
        val left = createAccessibilityAction(1).copy(tag = key(GestureType.SWIPE_RIGHT))
        val right = createGuideAction(GestureActionType.SEARCH).copy(tag = key(GestureType.SWIPE_LEFT))
        val merged = mergeLegacyGuideActions(listOf(left), listOf(right, left.copy()))
        assertEquals(listOf(left.tag, right.tag), merged.map { it.tag })
    }

    @Test fun selectingTheSameActionDoesNotEraseItsReleasePhase() {
        val tap = createAccessibilityAction(1).copy(tag = key(GestureType.SINGLE_CLICK))
        val release = createGuideAction(GestureActionType.SEARCH).copy(tag = key(GestureType.SINGLE_CLICK, GesturePhase.RD))
        val draft = buildGuideDraft(trigger(), listOf(tap, release), emptyList())
        val unchanged = draft.withGesture(QuickGesture.TAP, createAccessibilityAction(1))
        assertSame(draft, unchanged)
        assertEquals(listOf(tap.toJson(false), release.toJson(false)),
            QuickSetupRepository.mergeTriggerActions(listOf(tap, release), unchanged, unchanged.side).map { it.toJson(false) })
    }
}
