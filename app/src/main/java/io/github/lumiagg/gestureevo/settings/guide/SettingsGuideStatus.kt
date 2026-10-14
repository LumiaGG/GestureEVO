package io.github.lumiagg.gestureevo.settings.guide

import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import io.github.lumiagg.gestureevo.gesture.GestureService
import io.github.lumiagg.gestureevo.gesture.config.ActionConfig
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerConfig
import io.github.lumiagg.gestureevo.gesture.config.GestureActionItem
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.gesture.ui.GesturePhase
import io.github.lumiagg.gestureevo.gesture.ui.GestureType
import io.github.lumiagg.gestureevo.gesture.ui.GestureTypeEvent
import io.github.lumiagg.gestureevo.utils.CurrentPackageProvider
import io.github.lumiagg.gestureevo.utils.actionStore
import io.github.lumiagg.gestureevo.utils.gestureStore
import io.github.lumiagg.gestureevo.utils.readKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class SettingsGuideStatus(
    val accessibilityEnabled: Boolean,
    val serviceConnected: Boolean,
    val enabledTriggerTags: Set<String>,
    val configuredTriggerTags: Set<String>,
    val batteryExempt: Boolean
)

object SettingsGuideStatusReader {
    private const val TAG = "SettingsGuideStatus"
    private const val KEY_FREE_TRIGGERS = "key_free_triggers"

    private val gestureEvents = GestureType.entries.filter { it != GestureType.TOUCH }.flatMap { type ->
        val phases = when (type) {
            GestureType.SINGLE_CLICK, GestureType.DOUBLE_CLICK -> listOf(GesturePhase.TD)
            GestureType.LONG_PRESS -> listOf(GesturePhase.TD, GesturePhase.RD)
            else -> GesturePhase.entries
        }
        phases.map { GestureTypeEvent(type, it) }
    }

    suspend fun read(context: Context): SettingsGuideStatus = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val gestureSnapshot = appContext.gestureStore.data.first()
        val actionSnapshot = appContext.actionStore.data.first()
        val triggers = readConfiguration {
            FreeTriggerConfig.toModelList(gestureSnapshot.readKey(KEY_FREE_TRIGGERS, ""))
        }.orEmpty().filter { trigger ->
            trigger.enable && trigger.width > 0 && trigger.height > 0 &&
                (trigger.getState(FreeTriggerConfig.FLAG_PORTRAIT) ||
                    trigger.getState(FreeTriggerConfig.FLAG_LANDSCAPE))
        }
        val actionLists = mutableMapOf<String, List<GestureActionItem>>()
        fun actions(tag: String): List<GestureActionItem> = actionLists.getOrPut(tag) {
            readConfiguration {
                GestureActionItem.toModelList(actionSnapshot.readKey(tag, ""))
            }.orEmpty()
        }
        fun hasAction(item: GestureActionItem): Boolean =
            item.firstStageAction != GestureActionType.NONE || item.secondStageAction != GestureActionType.NONE
        fun wheelHasAction(tag: String): Boolean = actions(tag).any { item ->
            hasAction(item) || item.subActionListTag?.let { actions(it).any(::hasAction) } == true
        }
        val configuredTriggers = triggers.filter { trigger ->
            // Legacy migration replaces the saved action list, so use the same precedence here.
            val triggerActions = if (trigger.actionMap.isNotEmpty()) {
                trigger.actionMap.mapNotNull { (key, link) ->
                    readConfiguration {
                        GestureActionItem().apply {
                            tag = key
                            firstStageLink = link
                        }
                    }
                }
            } else {
                actions(trigger.tag)
            }
            val availableEvents = gestureEvents.filter { event ->
                event.phase == GesturePhase.TD || triggerActions.firstOrNull {
                    it.tag == event.toActionKey(GesturePhase.TD)
                }?.firstStageAction == GestureActionType.NONE
            }
            val hasExplicitAction = availableEvents.any { event ->
                val action = triggerActions.firstOrNull { it.tag == event.toActionKey() }
                when (action?.firstStageAction) {
                    null, GestureActionType.NONE -> false
                    GestureActionType.SHOW_ACTION_VIEW -> wheelHasAction(
                        action.firstStageUri?.getQueryParameter("actionItemsTag") ?: ActionConfig.DEFAULT_TAG
                    )
                    else -> true
                }
            }
            val hasDefaultWheelGesture = availableEvents.any { event ->
                event.type != GestureType.DOUBLE_CLICK &&
                    triggerActions.none { it.tag == event.toActionKey() }
            }
            hasExplicitAction || (hasDefaultWheelGesture && wheelHasAction(ActionConfig.DEFAULT_TAG))
        }
        val expectedService = ComponentName(appContext, GestureService::class.java)
        val enabledServices = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val accessibilityEnabled = Settings.Secure.getInt(
            appContext.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        ) == 1 && enabledServices.split(':').any {
            ComponentName.unflattenFromString(it) == expectedService
        }
        val powerManager = appContext.getSystemService(PowerManager::class.java)
        SettingsGuideStatus(
            accessibilityEnabled = accessibilityEnabled,
            serviceConnected = CurrentPackageProvider.accessibilityService() != null,
            batteryExempt = powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true,
            enabledTriggerTags = triggers.map { it.tag }.toSet(),
            configuredTriggerTags = configuredTriggers.map { it.tag }.toSet()
        )
    }

    private inline fun <T> readConfiguration(read: () -> T): T? = try {
        read()
    } catch (exception: Exception) {
        if (exception is CancellationException) throw exception
        Log.w(TAG, "Unable to read gesture configuration for the setup guide", exception)
        null
    }
}
