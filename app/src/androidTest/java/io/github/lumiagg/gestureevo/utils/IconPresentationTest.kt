package io.github.lumiagg.gestureevo.utils

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.appcompat.view.ContextThemeWrapper
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.gesture.config.ActionConfig
import io.github.lumiagg.gestureevo.gesture.ui.RoundImageTextView
import io.github.lumiagg.gestureevo.gesture.ui.applyWheelStyle
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import io.github.lumiagg.gestureevo.utils.icon.setAppIcon
import io.github.lumiagg.gestureevo.utils.shortcuts.ShortcutLaunchRequests
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class IconPresentationTest {
    @Test fun shortcutContinuationIsSingleUseAndCancellationRevokesIt() {
        val options = android.os.Bundle()
        val id = "test-${java.util.UUID.randomUUID()}"
        try {
            ShortcutLaunchRequests.register(id, "authorized-command", options)
            assertEquals("authorized-command", ShortcutLaunchRequests.consume(id)?.link)
            assertNull(ShortcutLaunchRequests.consume(id))
            ShortcutLaunchRequests.register(id, "cancelled-command", options)
            ShortcutLaunchRequests.forget(id)
            assertNull(ShortcutLaunchRequests.consume(id))
            assertNull(ShortcutLaunchRequests.consume("unregistered-command"))
        } finally {
            ShortcutLaunchRequests.forget(id)
        }
    }

    @Test fun startupSnapshotAppliesToListsWhileWheelsRetainTheirOwnShape() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_GestureEVO)
        val original = context.settingsStore.data.first()
        try {
            IconHelper.initializeFromPreferences(context, preferencesOf(
                floatPreferencesKey(AppearanceSettingsActivity.KEY_ICON_IMAGE_ROUND_PERCENT) to 0.8f))
            instrumentation.runOnMainSync {
                val listIcon = RoundImageTextView(context)
                listIcon.setAppIcon(null)
                listIcon.setAppIcon(ColorDrawable(Color.RED))
                assertTrue(listIcon.applyGlobalImageRoundPercent)
                assertEquals(0.8f, listIcon.imageFilterView.roundPercent, 0.001f)

                val wheelIcon = RoundImageTextView(context)
                wheelIcon.applyWheelStyle(ActionConfig().apply { iconImageRoundPercent = 0.3f })
                wheelIcon.setAppIcon(null)
                wheelIcon.setAppIcon(ColorDrawable(Color.RED))
                assertFalse(wheelIcon.applyGlobalImageRoundPercent)
                assertEquals(0.3f, wheelIcon.imageFilterView.roundPercent, 0.001f)
            }
        } finally {
            IconHelper.initializeFromPreferences(context, original)
        }
    }
}
