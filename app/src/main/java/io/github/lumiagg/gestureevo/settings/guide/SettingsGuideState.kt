package io.github.lumiagg.gestureevo.settings.guide

import android.content.Context
import io.github.lumiagg.gestureevo.utils.actionStore
import io.github.lumiagg.gestureevo.utils.gestureStore
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.searchStore
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal enum class GuideLaunchState {
    IN_PROGRESS,
    COMPLETED,
    SKIPPED
}

internal fun shouldShowSettingsGuide(
    state: GuideLaunchState?,
    hasExistingConfiguration: Boolean,
    isUpdatedInstall: Boolean
): Boolean = when (state) {
    GuideLaunchState.IN_PROGRESS -> true
    GuideLaunchState.COMPLETED, GuideLaunchState.SKIPPED -> false
    null -> !hasExistingConfiguration && !isUpdatedInstall
}

object SettingsGuideState {
    private const val KEY_GUIDE_STATE = "key_settings_guide_state"

    suspend fun shouldShowOnLaunch(context: Context): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val settings = appContext.settingsStore.data.first()
        val savedState = settings.readKey(KEY_GUIDE_STATE, "")
        val state = GuideLaunchState.entries.firstOrNull { it.name == savedState }
        if (state != null) {
            return@withContext shouldShowSettingsGuide(state, false, false)
        }

        // Inspect persisted data before any default trigger or search engine is created.
        val hasExistingConfiguration = settings.asMap().keys.any { it.name != KEY_GUIDE_STATE } ||
            appContext.gestureStore.data.first().asMap().isNotEmpty() ||
            appContext.actionStore.data.first().asMap().isNotEmpty() ||
            appContext.searchStore.data.first().asMap().isNotEmpty()
        @Suppress("DEPRECATION")
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val showGuide = shouldShowSettingsGuide(
            state,
            hasExistingConfiguration,
            packageInfo.lastUpdateTime > packageInfo.firstInstallTime
        )
        if (showGuide) {
            appContext.settingsStore.saveKey(KEY_GUIDE_STATE, GuideLaunchState.IN_PROGRESS.name)
        }
        showGuide
    }

    suspend fun markCompleted(context: Context) {
        context.settingsStore.saveKey(KEY_GUIDE_STATE, GuideLaunchState.COMPLETED.name)
    }

    suspend fun markSkipped(context: Context) {
        context.settingsStore.saveKey(KEY_GUIDE_STATE, GuideLaunchState.SKIPPED.name)
    }
}
