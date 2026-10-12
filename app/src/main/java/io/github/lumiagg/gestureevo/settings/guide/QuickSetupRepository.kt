package io.github.lumiagg.gestureevo.settings.guide

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.gesture.EventBusManager.produceFreeTriggerEvent
import io.github.lumiagg.gestureevo.gesture.FreeTriggerEvent
import io.github.lumiagg.gestureevo.gesture.config.ActionConfig
import io.github.lumiagg.gestureevo.gesture.config.ActionItemsTag
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerConfig
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerDataManager
import io.github.lumiagg.gestureevo.gesture.config.GestureActionDataManager
import io.github.lumiagg.gestureevo.gesture.config.GestureActionItem
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.gesture.ui.GesturePhase
import io.github.lumiagg.gestureevo.gesture.ui.GestureType
import io.github.lumiagg.gestureevo.gesture.ui.GestureTypeEvent
import io.github.lumiagg.gestureevo.utils.actionStore
import io.github.lumiagg.gestureevo.utils.gestureStore
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.uriBuilder
import io.github.lumiagg.gestureevo.utils.dp2px
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

object QuickSetupRepository {
    internal const val LEFT_TRIGGER_TAG = "settings_guide_trigger_left"
    internal const val RIGHT_TRIGGER_TAG = "settings_guide_trigger_right"
    internal const val WHEEL_TAG = "settings_guide_wheel"
    private const val KEY_CONFIG = "settings_guide_quick_config"
    private const val KEY_FREE_TRIGGERS = "key_free_triggers"
    private val applyMutex = Mutex()

    suspend fun load(context: Context): QuickSetupConfig? = withContext(Dispatchers.IO) {
        QuickSetupConfig.fromJson(context.applicationContext.gestureStore.readKey(KEY_CONFIG, ""))
    }

    suspend fun apply(context: Context, config: QuickSetupConfig) = withContext(Dispatchers.IO) {
        applyMutex.withLock {
            config.validate(Build.VERSION.SDK_INT)
            val appContext = context.applicationContext
            val name = appContext.getString(R.string.guide_title)
            val triggers = buildQuickSetupTriggers(config, name)
            val triggerActions = triggers.associate { trigger ->
                val side = if (trigger.tag == LEFT_TRIGGER_TAG) QuickSetupSide.LEFT else QuickSetupSide.RIGHT
                trigger.tag to GestureActionItem.toJsonList(buildTriggerActions(config, side))
            }
            val wheelItems = buildQuickSetupWheelItems(config)
            val wheelJson = GestureActionItem.toJsonList(wheelItems)
            val configJson = config.toJson()
            val wheelConfig = buildQuickSetupWheelConfig(config).toJson()
            val triggerKey = stringPreferencesKey(KEY_FREE_TRIGGERS)
            val wheelTagsKey = stringPreferencesKey(GestureActionDataManager.KEY_ACTION_ITEMS_TAG_LIST)

            // Resolve existing data before writing, so malformed user configuration is never replaced.
            readTriggers(appContext.gestureStore.readKey(KEY_FREE_TRIGGERS, ""))
            readWheelTags(appContext.actionStore.readKey(GestureActionDataManager.KEY_ACTION_ITEMS_TAG_LIST, ""))

            // Finish the writes and runtime refresh if the practice screen is recreated mid-save.
            commitQuickSetup(writeConfiguration = {
                appContext.actionStore.edit { preferences ->
                    val tags = readWheelTags(preferences[wheelTagsKey].orEmpty())
                    preferences[wheelTagsKey] = ActionItemsTag.toJsonList(mergeQuickSetupWheelTags(tags, name))
                    preferences[stringPreferencesKey(WHEEL_TAG)] = wheelJson
                    preferences[stringPreferencesKey("${GestureActionDataManager.KEY_ACTION_CONFIG_PREFIX}$WHEEL_TAG")] = wheelConfig
                    triggerActions.forEach { (tag, json) ->
                        preferences[stringPreferencesKey(tag)] = json
                    }
                }

                // Publish enabled triggers only after their actions and wheel are available.
                appContext.gestureStore.edit { preferences ->
                    val existing = readTriggers(preferences[triggerKey].orEmpty())
                    preferences[triggerKey] = FreeTriggerConfig.toJsonList(mergeQuickSetupTriggers(existing, triggers))
                    preferences[stringPreferencesKey(KEY_CONFIG)] = configJson
                }
                FreeTriggerDataManager.invalidateCache()
            }, publishConfiguration = {
                produceFreeTriggerEvent(FreeTriggerEvent(isConfig = false, reloadWheelTag = WHEEL_TAG))
            })
        }
    }

    private fun buildTriggerActions(config: QuickSetupConfig, side: QuickSetupSide): List<GestureActionItem> {
        val selected = config.gestures.mapKeys { (gesture, _) -> quickGestureType(gesture, side) }
        // Missing actions otherwise fall back to the shared default wheel at runtime.
        return GestureType.entries.filter { it != GestureType.TOUCH }.flatMap { type ->
            GesturePhase.entries.map { phase ->
                val action = if (phase == GesturePhase.TD) selected[type] else null
                buildActionItem(
                    action ?: QuickAction(QuickActionType.NONE),
                    GestureTypeEvent(type, phase).toActionKey()
                )
            }
        }
    }

    internal fun buildActionItem(action: QuickAction, itemTag: String): GestureActionItem {
        val spec = quickActionSpec(action)
        return GestureActionItem().apply {
            tag = itemTag
            name = action.appName
            firstStageLink = uriBuilder(spec.type).apply {
                spec.parameters.forEach { (key, value) -> appendQueryParameter(key, value) }
            }.build().toString()
        }
    }

    private fun readTriggers(json: String): List<FreeTriggerConfig> =
        if (json.isBlank()) emptyList() else requireNotNull(FreeTriggerConfig.toModelList(json))

    private fun readWheelTags(json: String): List<ActionItemsTag> =
        if (json.isBlank()) emptyList() else requireNotNull(ActionItemsTag.toModelList(json))
}

internal suspend fun commitQuickSetup(
    writeConfiguration: suspend () -> Unit,
    publishConfiguration: suspend () -> Unit
) {
    coroutineContext.ensureActive()
    withContext(NonCancellable) {
        writeConfiguration()
        publishConfiguration()
    }
    coroutineContext.ensureActive()
}

internal fun buildQuickSetupWheelConfig(config: QuickSetupConfig): ActionConfig = ActionConfig().apply {
    tag = QuickSetupRepository.WHEEL_TAG
    actionOffsetX = config.wheelOffsetXDp.dp2px
    initRadius = config.wheelRadiusDp.dp2px
}

internal fun buildQuickSetupWheelItems(config: QuickSetupConfig): List<GestureActionItem> =
    config.wheelItems.mapIndexed { index, action ->
        QuickSetupRepository.buildActionItem(action, "${QuickSetupRepository.WHEEL_TAG}_$index")
    }

internal data class QuickActionSpec(val type: GestureActionType, val parameters: Map<String, String> = emptyMap())

internal fun quickActionSpec(action: QuickAction): QuickActionSpec {
    fun accessibility(id: Int) = QuickActionSpec(GestureActionType.ACC_ACTION, mapOf("accAction" to id.toString()))
    return when (action.type) {
        QuickActionType.NONE -> QuickActionSpec(GestureActionType.NONE)
        QuickActionType.WHEEL -> QuickActionSpec(
            GestureActionType.SHOW_ACTION_VIEW,
            mapOf("actionItemsTag" to QuickSetupRepository.WHEEL_TAG)
        )
        QuickActionType.BACK -> accessibility(AccessibilityService.GLOBAL_ACTION_BACK)
        QuickActionType.HOME -> accessibility(AccessibilityService.GLOBAL_ACTION_HOME)
        QuickActionType.RECENTS -> accessibility(AccessibilityService.GLOBAL_ACTION_RECENTS)
        QuickActionType.NOTIFICATIONS -> accessibility(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
        QuickActionType.QUICK_SETTINGS -> accessibility(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        QuickActionType.SCREENSHOT -> accessibility(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
        QuickActionType.SEARCH -> QuickActionSpec(GestureActionType.SEARCH)
        QuickActionType.SCREEN_CONTENT -> QuickActionSpec(GestureActionType.SCREEN_CONTENT)
        QuickActionType.SCREEN_SEARCH -> QuickActionSpec(GestureActionType.SCREEN_SEARCH)
        QuickActionType.PIN_POOL -> QuickActionSpec(GestureActionType.PIN_POOL)
        QuickActionType.OPEN_APP -> QuickActionSpec(
            GestureActionType.OPEN_APP,
            buildMap {
                put("pkg", requireNotNull(action.packageName))
                if (action.userId != 0) put("userId", action.userId.toString())
            }
        )
    }
}

internal fun quickGestureType(gesture: QuickGesture, side: QuickSetupSide): GestureType = when (gesture) {
    QuickGesture.TAP -> GestureType.SINGLE_CLICK
    QuickGesture.SWIPE_IN -> if (side == QuickSetupSide.LEFT) GestureType.SWIPE_RIGHT else GestureType.SWIPE_LEFT
    QuickGesture.SWIPE_UP -> GestureType.SWIPE_UP
    QuickGesture.SWIPE_DOWN -> GestureType.SWIPE_DOWN
    QuickGesture.LONG_PRESS -> GestureType.LONG_PRESS
}

internal fun buildQuickSetupTriggers(config: QuickSetupConfig, name: String): List<FreeTriggerConfig> {
    val sides = when (config.side) {
        QuickSetupSide.LEFT -> listOf(QuickSetupSide.LEFT)
        QuickSetupSide.RIGHT -> listOf(QuickSetupSide.RIGHT)
        QuickSetupSide.BOTH -> listOf(QuickSetupSide.LEFT, QuickSetupSide.RIGHT)
    }
    return sides.map { side ->
        FreeTriggerConfig().apply {
            tag = if (side == QuickSetupSide.LEFT) QuickSetupRepository.LEFT_TRIGGER_TAG else QuickSetupRepository.RIGHT_TRIGGER_TAG
            this.name = name
            enable = true
            width = 18
            height = config.triggerHeightDp
            xRelativeValue = if (side == QuickSetupSide.LEFT) 0f else 1f
            yRelativeValue = config.verticalPercent / 100f
            setState(FreeTriggerConfig.FLAG_PORTRAIT, true)
            setState(FreeTriggerConfig.FLAG_LANDSCAPE, true)
            setState(FreeTriggerConfig.FLAG_WHEEL_FOLLOW_TRIGGER_POINT, false)
            val quadrant = when {
                side == QuickSetupSide.LEFT && config.verticalPercent < 50 -> 3
                side == QuickSetupSide.LEFT -> 0
                config.verticalPercent < 50 -> 2
                else -> 1
            }
            setQuadrant(List(4) { it == quadrant })
        }
    }
}

internal fun mergeQuickSetupTriggers(
    existing: List<FreeTriggerConfig>,
    replacements: List<FreeTriggerConfig>
): List<FreeTriggerConfig> = existing.filterNot {
    it.tag == QuickSetupRepository.LEFT_TRIGGER_TAG || it.tag == QuickSetupRepository.RIGHT_TRIGGER_TAG
} + replacements

internal fun mergeQuickSetupWheelTags(existing: List<ActionItemsTag>, name: String): List<ActionItemsTag> =
    existing.filterNot { it.tag == QuickSetupRepository.WHEEL_TAG } + ActionItemsTag().apply {
        tag = QuickSetupRepository.WHEEL_TAG
        this.name = name
    }
