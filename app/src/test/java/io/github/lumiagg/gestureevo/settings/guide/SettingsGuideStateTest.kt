package io.github.lumiagg.gestureevo.settings.guide

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsGuideStateTest {
    @Test
    fun newInstallStartsTheGuide() {
        assertTrue(shouldShowSettingsGuide(null, false, false))
    }

    @Test
    fun existingConfigurationPreservesThePreviousLaunchDestination() {
        assertFalse(shouldShowSettingsGuide(null, true, false))
    }

    @Test
    fun updatedInstallWithoutConfigurationDoesNotForceTheGuide() {
        assertFalse(shouldShowSettingsGuide(null, false, true))
    }

    @Test
    fun unfinishedGuideResumesAfterDefaultConfigurationIsCreated() {
        assertTrue(shouldShowSettingsGuide(GuideLaunchState.IN_PROGRESS, true, false))
    }

    @Test
    fun unfinishedGuideResumesAfterAnUpdate() {
        assertTrue(shouldShowSettingsGuide(GuideLaunchState.IN_PROGRESS, true, true))
    }

    @Test
    fun completedGuideDoesNotOpenAutomaticallyEvenIfConfigurationIsEmpty() {
        assertFalse(shouldShowSettingsGuide(GuideLaunchState.COMPLETED, false, false))
    }

    @Test
    fun skippedGuideDoesNotOpenAutomaticallyEvenIfConfigurationIsEmpty() {
        assertFalse(shouldShowSettingsGuide(GuideLaunchState.SKIPPED, false, false))
    }
}
