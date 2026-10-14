package io.github.lumiagg.gestureevo.settings.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickSetupConfigTest {
    @Test
    fun defaultsWorkOnTheOldestSupportedAndroidVersion() {
        val config = QuickSetupConfig.default()
        config.validate(24)
        assertEquals(QuickSetupSide.RIGHT, config.side)
        assertEquals(QuickActionType.WHEEL, config.gestures.getValue(QuickGesture.SWIPE_IN).type)
        assertTrue(config.wheelItems.isNotEmpty())
    }

    @Test
    fun jsonPreservesCustomActionsAndWheelOrder() {
        val config = QuickSetupConfig.default().copy(
            side = QuickSetupSide.BOTH,
            verticalPercent = 70,
            triggerHeightDp = 140,
            wheelOffsetXDp = 65,
            wheelRadiusDp = 160,
            wheelItems = listOf(
                QuickAction(QuickActionType.OPEN_APP, "example.application", "Example"),
                QuickAction(QuickActionType.HOME)
            )
        )
        assertEquals(config, QuickSetupConfig.fromJson(config.toJson()))
    }

    @Test
    fun invalidGeometryCannotBeApplied() {
        val config = QuickSetupConfig.default()
        assertInvalid(config.copy(verticalPercent = 0))
        assertInvalid(config.copy(verticalPercent = 91))
        assertInvalid(config.copy(triggerHeightDp = 39))
        assertInvalid(config.copy(triggerHeightDp = 201))
        assertInvalid(config.copy(wheelOffsetXDp = -1))
        assertInvalid(config.copy(wheelOffsetXDp = 121))
        assertInvalid(config.copy(wheelRadiusDp = 79))
        assertInvalid(config.copy(wheelRadiusDp = 201))
    }

    @Test
    fun existingQuickSetupUsesTheSameDefaultWheelGeometry() {
        val config = QuickSetupConfig.fromJson("""{"side":"RIGHT","verticalPercent":55,"triggerHeightDp":100}""")
        requireNotNull(config)
        assertEquals(30, config.wheelOffsetXDp)
        assertEquals(130, config.wheelRadiusDp)
        config.validate(24)
    }

    @Test
    fun wheelRequiresExecutableItemsAndAnOpeningGesture() {
        val config = QuickSetupConfig.default()
        assertInvalid(config.copy(wheelItems = emptyList()))
        assertInvalid(config.copy(wheelItems = listOf(QuickAction(QuickActionType.HOME))))
        config.copy(wheelItems = List(9) { QuickAction(QuickActionType.HOME) }).validate(24)
        assertInvalid(config.copy(wheelItems = listOf(QuickAction(QuickActionType.NONE))))
        assertInvalid(config.copy(wheelItems = listOf(QuickAction(QuickActionType.WHEEL))))
        assertInvalid(config.copy(gestures = config.gestures - QuickGesture.TAP))
        assertInvalid(config.copy(gestures = config.gestures + (QuickGesture.SWIPE_IN to QuickAction(QuickActionType.BACK))))
    }

    @Test
    fun appActionRequiresAnExplicitPackage() {
        val config = QuickSetupConfig.default()
        assertInvalid(config.copy(wheelItems = listOf(QuickAction(QuickActionType.OPEN_APP))))
        assertInvalid(config.copy(wheelItems = listOf(QuickAction(QuickActionType.OPEN_APP, " "))))
        assertInvalid(config.copy(wheelItems = listOf(QuickAction(QuickActionType.HOME, "example.application"))))
        config.copy(wheelItems = listOf(
            QuickAction(QuickActionType.OPEN_APP, "example.application"), QuickAction(QuickActionType.HOME)
        )).validate(24)
    }

    @Test
    fun screenshotRequiresAndroidNine() {
        val config = QuickSetupConfig.default().copy(wheelItems = listOf(
            QuickAction(QuickActionType.SCREENSHOT), QuickAction(QuickActionType.HOME)
        ))
        assertInvalid(config, 27)
        config.validate(28)
    }

    private fun assertInvalid(config: QuickSetupConfig, sdkInt: Int = 35) {
        val failure = runCatching { config.validate(sdkInt) }.exceptionOrNull()
        assertTrue("Expected validation to reject $config", failure is IllegalArgumentException)
    }
}
