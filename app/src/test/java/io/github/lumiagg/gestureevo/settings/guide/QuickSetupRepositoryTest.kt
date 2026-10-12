package io.github.lumiagg.gestureevo.settings.guide

import io.github.lumiagg.gestureevo.gesture.config.ActionConfig
import io.github.lumiagg.gestureevo.gesture.config.ActionItemsTag
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerConfig
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.gesture.ui.GestureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickSetupRepositoryTest {
    @Test
    fun switchingSidesReplacesOnlyGuideTriggersWithoutDuplicates() {
        val userTrigger = FreeTriggerConfig().apply {
            tag = "user-trigger"
            name = "My trigger"
        }
        val left = buildQuickSetupTriggers(QuickSetupConfig.default().copy(side = QuickSetupSide.LEFT), "Guide")
        val both = buildQuickSetupTriggers(QuickSetupConfig.default().copy(side = QuickSetupSide.BOTH), "Guide")
        val first = mergeQuickSetupTriggers(listOf(userTrigger), left)
        val second = mergeQuickSetupTriggers(first, both)
        val repeated = mergeQuickSetupTriggers(second, both)
        assertEquals(3, repeated.size)
        assertSame(userTrigger, repeated.first())
        assertEquals(second.map { it.tag }, repeated.map { it.tag })

        val rightOnly = mergeQuickSetupTriggers(repeated, buildQuickSetupTriggers(QuickSetupConfig.default(), "Guide"))
        assertEquals(listOf("user-trigger", QuickSetupRepository.RIGHT_TRIGGER_TAG), rightOnly.map { it.tag })
    }

    @Test
    fun sidePlacementDoesNotDependOnMirroredGestureCoordinates() {
        val triggers = buildQuickSetupTriggers(
            QuickSetupConfig.default().copy(side = QuickSetupSide.BOTH, verticalPercent = 70, triggerHeightDp = 160),
            "Guide"
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
    fun wheelTagMergePreservesUserWheelAndSharedDefault() {
        val userWheel = ActionItemsTag().apply {
            tag = "user-wheel"
            name = "My wheel"
        }
        val initial = mergeQuickSetupWheelTags(listOf(userWheel), "Guide")
        val repeated = mergeQuickSetupWheelTags(initial, "Updated guide")
        assertEquals(2, repeated.size)
        assertSame(userWheel, repeated.first())
        assertEquals("Updated guide", repeated.last().name)
        assertNotEquals(ActionConfig.DEFAULT_TAG, repeated.last().tag)
        assertEquals(
            mapOf("actionItemsTag" to repeated.last().tag),
            quickActionSpec(QuickAction(QuickActionType.WHEEL)).parameters
        )
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
}
