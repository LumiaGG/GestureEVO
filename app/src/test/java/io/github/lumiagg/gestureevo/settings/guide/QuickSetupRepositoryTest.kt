package io.github.lumiagg.gestureevo.settings.guide

import android.accessibilityservice.AccessibilityService
import io.github.lumiagg.gestureevo.gesture.config.ActionConfig
import io.github.lumiagg.gestureevo.gesture.config.ActionItemsTag
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerConfig
import io.github.lumiagg.gestureevo.gesture.config.GestureActionItem
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.gesture.ui.GesturePhase
import io.github.lumiagg.gestureevo.gesture.ui.GestureType
import io.github.lumiagg.gestureevo.gesture.ui.GestureTypeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class QuickSetupRepositoryTest {
    @Test
    fun defaultTriggerResolutionAndMergingPreservesUserTriggersAndCleansLegacy() {
        val defaultTriggerName = "默认触发器"
        val userTrigger = FreeTriggerConfig().apply {
            tag = "user-trigger"
            name = "My custom trigger"
        }
        val legacyLeft = FreeTriggerConfig().apply {
            tag = QuickSetupRepository.LEFT_TRIGGER_TAG
            name = "Guide Left"
        }
        val legacyRight = FreeTriggerConfig().apply {
            tag = QuickSetupRepository.RIGHT_TRIGGER_TAG
            name = "Guide Right"
        }
        val previewTrigger = FreeTriggerConfig().apply {
            tag = QuickSetupRepository.PREVIEW_LEFT_TRIGGER_TAG
            name = "Preview Left"
        }
        val defaultTrigger = FreeTriggerConfig().apply {
            tag = "default-trigger-id"
            name = defaultTriggerName
        }

        val existing = listOf(userTrigger, legacyLeft, legacyRight, previewTrigger, defaultTrigger)

        val resolved = resolveDefaultTrigger(existing, defaultTriggerName) {
            FreeTriggerConfig().apply {
                tag = "fallback-id"
                name = defaultTriggerName
            }
        }
        assertSame(defaultTrigger, resolved)

        applyDefaultTriggerConfig(
            resolved,
            QuickSetupConfig.default().copy(side = QuickSetupSide.BOTH, verticalPercent = 65, triggerHeightDp = 120),
            defaultTriggerName
        )
        assertEquals(0f, resolved.xRelativeValue, 0.001f)
        assertEquals(0.65f, resolved.yRelativeValue, 0.001f)
        assertEquals(120, resolved.height)
        assertTrue(resolved.getState(FreeTriggerConfig.FLAG_MIRROR))
        assertTrue(resolved.enable)

        val merged = mergeQuickSetupTriggers(existing, resolved)
        assertEquals(2, merged.size)
        assertSame(userTrigger, merged[0])
        assertSame(resolved, merged[1])
    }

    @Test
    fun defaultTriggerSidePlacementHandlesLeftRightBoth() {
        val defaultTriggerName = "默认触发器"

        val leftTrigger = FreeTriggerConfig().apply { tag = "id-left" }
        applyDefaultTriggerConfig(leftTrigger, QuickSetupConfig.default().copy(side = QuickSetupSide.LEFT), defaultTriggerName)
        assertEquals(0f, leftTrigger.xRelativeValue, 0.001f)
        assertFalse(leftTrigger.getState(FreeTriggerConfig.FLAG_MIRROR))

        val rightTrigger = FreeTriggerConfig().apply { tag = "id-right" }
        applyDefaultTriggerConfig(rightTrigger, QuickSetupConfig.default().copy(side = QuickSetupSide.RIGHT), defaultTriggerName)
        assertEquals(1f, rightTrigger.xRelativeValue, 0.001f)
        assertFalse(rightTrigger.getState(FreeTriggerConfig.FLAG_MIRROR))

        val bothTrigger = FreeTriggerConfig().apply { tag = "id-both" }
        applyDefaultTriggerConfig(bothTrigger, QuickSetupConfig.default().copy(side = QuickSetupSide.BOTH), defaultTriggerName)
        assertEquals(0f, bothTrigger.xRelativeValue, 0.001f)
        assertTrue(bothTrigger.getState(FreeTriggerConfig.FLAG_MIRROR))
    }

    @Test
    fun previewTriggersUsePreviewTags() {
        val triggers = buildQuickSetupTriggers(
            QuickSetupConfig.default().copy(side = QuickSetupSide.BOTH, verticalPercent = 70, triggerHeightDp = 160),
            "Guide"
        )
        assertEquals(
            listOf(QuickSetupRepository.PREVIEW_LEFT_TRIGGER_TAG, QuickSetupRepository.PREVIEW_RIGHT_TRIGGER_TAG),
            triggers.map { it.tag }
        )
        assertEquals(listOf(0f, 1f), triggers.map { it.xRelativeValue })
        triggers.forEach {
            assertEquals(0.7f, it.yRelativeValue, 0.001f)
            assertEquals(160, it.height)
            assertTrue(it.enable)
            assertTrue(it.getState(FreeTriggerConfig.FLAG_PORTRAIT))
            assertTrue(it.getState(FreeTriggerConfig.FLAG_LANDSCAPE))
            assertFalse(it.getState(FreeTriggerConfig.FLAG_MIRROR))
            assertFalse(it.getState(FreeTriggerConfig.FLAG_WHEEL_FOLLOW_TRIGGER_POINT))
        }
        assertEquals(GestureType.SWIPE_RIGHT, quickGestureType(QuickGesture.SWIPE_IN, QuickSetupSide.LEFT))
        assertEquals(GestureType.SWIPE_LEFT, quickGestureType(QuickGesture.SWIPE_IN, QuickSetupSide.RIGHT))
    }

    @Test
    fun wheelsUseAdaptiveQuadrantByDefault() {
        listOf(20, 49, 50, 80).forEach { verticalPercent ->
            val triggers = buildQuickSetupTriggers(
                QuickSetupConfig.default().copy(side = QuickSetupSide.BOTH, verticalPercent = verticalPercent),
                "Guide"
            )
            triggers.forEach { trigger ->
                assertEquals(0, trigger.quadrantValue)
            }
        }
    }

    @Test
    fun wheelTagMergeCleansLegacyTagAndDirectlyUsesDefaultTag() {
        val userWheel = ActionItemsTag().apply {
            tag = "user-wheel"
            name = "My wheel"
        }
        val legacyWheel = ActionItemsTag().apply {
            tag = QuickSetupRepository.LEGACY_WHEEL_TAG
            name = "Legacy guide wheel"
        }
        val defaultTagInStore = ActionItemsTag().apply {
            tag = ActionConfig.DEFAULT_TAG
            name = "Default Tag"
        }
        val merged = mergeQuickSetupWheelTags(listOf(userWheel, legacyWheel, defaultTagInStore))
        assertEquals(listOf("user-wheel"), merged.map { it.tag })

        assertEquals(
            mapOf("actionItemsTag" to ActionConfig.DEFAULT_TAG),
            quickActionSpec(QuickAction(QuickActionType.WHEEL)).parameters
        )
    }

    @Test
    fun buildTriggerActionsBindsBothInwardSwipesWhenSideIsBoth() {
        val config = QuickSetupConfig.default().copy(
            side = QuickSetupSide.BOTH,
            gestures = mapOf(
                QuickGesture.TAP to QuickAction(QuickActionType.BACK),
                QuickGesture.SWIPE_IN to QuickAction(QuickActionType.WHEEL),
                QuickGesture.SWIPE_UP to QuickAction(QuickActionType.HOME),
                QuickGesture.SWIPE_DOWN to QuickAction(QuickActionType.NOTIFICATIONS),
                QuickGesture.LONG_PRESS to QuickAction(QuickActionType.RECENTS),
            )
        )
        val actions = QuickSetupRepository.buildTriggerActions(config, QuickSetupSide.BOTH)

        val swipeRightKey = GestureTypeEvent(GestureType.SWIPE_RIGHT, GesturePhase.TD).toActionKey()
        val swipeLeftKey = GestureTypeEvent(GestureType.SWIPE_LEFT, GesturePhase.TD).toActionKey()

        val rightAction = actions.find { it.tag == swipeRightKey }
        val leftAction = actions.find { it.tag == swipeLeftKey }

        assertNotNull(rightAction)
        assertNotNull(leftAction)
        assertEquals(rightAction?.firstStageLink, leftAction?.firstStageLink)
        assertEquals(QuickActionType.WHEEL, parseQuickAction(requireNotNull(rightAction)).type)
        assertEquals(QuickActionType.WHEEL, parseQuickAction(requireNotNull(leftAction)).type)
    }

    @Test
    fun parseQuickActionSupportsKeyTypes() {
        val backItem = QuickSetupRepository.buildActionItem(QuickAction(QuickActionType.BACK), "tag1")
        assertEquals(QuickActionType.BACK, parseQuickAction(backItem).type)

        val wheelItem = QuickSetupRepository.buildActionItem(QuickAction(QuickActionType.WHEEL), "tag2")
        assertEquals(QuickActionType.WHEEL, parseQuickAction(wheelItem).type)

        val searchItem = QuickSetupRepository.buildActionItem(QuickAction(QuickActionType.SEARCH), "tag3")
        assertEquals(QuickActionType.SEARCH, parseQuickAction(searchItem).type)

        val appAction = QuickAction(QuickActionType.OPEN_APP, "org.test.app", "Test App", 10)
        val appItem = QuickSetupRepository.buildActionItem(appAction, "tag4")
        val parsedApp = parseQuickAction(appItem)
        assertEquals(QuickActionType.OPEN_APP, parsedApp.type)
        assertEquals("org.test.app", parsedApp.packageName)
        assertEquals("Test App", parsedApp.appName)
        assertEquals(10, parsedApp.userId)
    }

    @Test
    fun accessibilityActionsUseTheRuntimeParameterAndIds() {
        val ids = mapOf(
            QuickActionType.BACK to 1,
            QuickActionType.HOME to 2,
            QuickActionType.RECENTS to 3,
            QuickActionType.NOTIFICATIONS to 4,
            QuickActionType.QUICK_SETTINGS to 5,
            QuickActionType.SCREENSHOT to 9
        )
        ids.forEach { (action, id) ->
            val spec = quickActionSpec(QuickAction(action))
            assertEquals(GestureActionType.ACC_ACTION, spec.type)
            assertEquals(mapOf("accAction" to id.toString()), spec.parameters)
        }
    }

    @Test
    fun appActionUsesPackageParameterWithoutEmbeddingItsDisplayName() {
        val spec = quickActionSpec(QuickAction(QuickActionType.OPEN_APP, "example.app", "A & B"))
        assertEquals(GestureActionType.OPEN_APP, spec.type)
        assertEquals(mapOf("pkg" to "example.app"), spec.parameters)
    }

    @Test
    fun appActionKeepsTheSelectedProfile() {
        val spec = quickActionSpec(QuickAction(QuickActionType.OPEN_APP, "example.app", "Work", 10))
        assertEquals(mapOf("pkg" to "example.app", "userId" to "10"), spec.parameters)
    }

    @Test
    fun defaultTriggerArbitraryHeightAndPositionArePreserved() {
        val defaultTriggerName = "默认触发器"
        val trigger = FreeTriggerConfig().apply {
            tag = "default-trigger"
            name = defaultTriggerName
            height = 134
            yRelativeValue = 0.573f
        }
        val config = QuickSetupConfig.default().copy(
            verticalPercent = (trigger.yRelativeValue * 100).roundToInt().coerceIn(10, 90),
            triggerHeightDp = trigger.height.coerceIn(40, 200)
        )
        assertEquals(57, config.verticalPercent)
        assertEquals(134, config.triggerHeightDp)
        config.validate(24)

        applyDefaultTriggerConfig(trigger, config, defaultTriggerName)
        assertEquals(134, trigger.height)
        assertEquals(0.57f, trigger.yRelativeValue, 0.001f)
    }
}
