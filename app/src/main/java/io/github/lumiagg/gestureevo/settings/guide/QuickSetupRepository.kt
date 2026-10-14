package io.github.lumiagg.gestureevo.settings.guide

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import androidx.core.net.toUri
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
import io.github.lumiagg.gestureevo.utils.toGestureActionType
import io.github.lumiagg.gestureevo.utils.uriBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

object QuickSetupRepository {
    internal const val PREVIEW_LEFT_TRIGGER_TAG = "guide_preview_trigger_left"
    internal const val PREVIEW_RIGHT_TRIGGER_TAG = "guide_preview_trigger_right"
    internal const val LEFT_TRIGGER_TAG = "settings_guide_trigger_left"
    internal const val RIGHT_TRIGGER_TAG = "settings_guide_trigger_right"
    internal const val LEGACY_WHEEL_TAG = "settings_guide_wheel"
    internal const val WHEEL_TAG = ActionConfig.DEFAULT_TAG
    private const val KEY_CONFIG = "settings_guide_quick_config"
    private const val KEY_FREE_TRIGGERS = "key_free_triggers"
    private val applyMutex = Mutex()

    suspend fun load(context: Context): QuickSetupConfig? = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val saved = QuickSetupConfig.fromJson(appContext.gestureStore.readKey(KEY_CONFIG, ""))
        if (saved != null) return@withContext saved

        try {
            loadFromDefault(appContext)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun loadFromDefault(context: Context): QuickSetupConfig? {
        val triggers = FreeTriggerConfig.toModelList(context.gestureStore.readKey(KEY_FREE_TRIGGERS, "")).orEmpty()
        val defaultTriggerName = context.getString(R.string.default_trigger_name)
        val defaultTrigger = triggers.find { it.name == defaultTriggerName } ?: triggers.firstOrNull() ?: return null

        val side = when {
            defaultTrigger.getState(FreeTriggerConfig.FLAG_MIRROR) -> QuickSetupSide.BOTH
            defaultTrigger.xRelativeValue == 0f -> QuickSetupSide.LEFT
            else -> QuickSetupSide.RIGHT
        }
        val verticalPercent = (defaultTrigger.yRelativeValue * 100).toInt().coerceIn(10, 90)
        val triggerHeightDp = defaultTrigger.height.coerceIn(40, 200)

        // Read default wheel items
        val wheelItemsJson = context.actionStore.readKey(ActionConfig.DEFAULT_TAG, "")
        val rawWheelItems = GestureActionItem.toModelList(wheelItemsJson).orEmpty()
        val parsedWheelItems = rawWheelItems.map(::parseQuickAction).filter {
            it.type != QuickActionType.NONE && it.type != QuickActionType.WHEEL
        }
        val wheelItems = if (parsedWheelItems.size >= 2) parsedWheelItems else QuickSetupConfig.default().wheelItems

        // Read trigger gestures
        val triggerActionsJson = context.actionStore.readKey(defaultTrigger.tag, "")
        val triggerActions = GestureActionItem.toModelList(triggerActionsJson).orEmpty()
        val gestureMap = mutableMapOf<QuickGesture, QuickAction>()
        QuickGesture.entries.forEach { gesture ->
            val gestureType = quickGestureType(gesture, side)
            val actionKey = GestureTypeEvent(gestureType, GesturePhase.TD).toActionKey()
            val item = triggerActions.find { it.tag == actionKey }
            val quickAction = item?.let(::parseQuickAction)
            if (quickAction != null && quickAction.type != QuickActionType.NONE) {
                gestureMap[gesture] = quickAction
            }
        }
        val gestures = if (gestureMap.keys == QuickGesture.entries.toSet() && gestureMap.values.any { it.type == QuickActionType.WHEEL }) {
            gestureMap
        } else {
            QuickSetupConfig.default().gestures
        }

        return QuickSetupConfig(
            side = side,
            verticalPercent = verticalPercent,
            triggerHeightDp = triggerHeightDp,
            gestures = gestures,
            wheelItems = wheelItems
        )
    }

    suspend fun apply(context: Context, config: QuickSetupConfig) = withContext(Dispatchers.IO) {
        applyMutex.withLock {
            config.validate(Build.VERSION.SDK_INT)
            val appContext = context.applicationContext
            val defaultTriggerName = appContext.getString(R.string.default_trigger_name)

            val existingTriggers = readTriggers(appContext.gestureStore.readKey(KEY_FREE_TRIGGERS, ""))
            val defaultTrigger = resolveDefaultTrigger(existingTriggers, defaultTriggerName) {
                FreeTriggerDataManager.createDefaultTrigger(appContext)
            }
            applyDefaultTriggerConfig(defaultTrigger, config, defaultTriggerName)
            val finalTriggers = mergeQuickSetupTriggers(existingTriggers, defaultTrigger)

            val triggerActions = buildTriggerActions(config, config.side)
            val triggerActionsJson = GestureActionItem.toJsonList(triggerActions)

            val wheelItems = buildQuickSetupWheelItems(config)
            val wheelJson = GestureActionItem.toJsonList(wheelItems)
            val configJson = config.toJson()
            val baseConfig = GestureActionDataManager.readActionConfig(appContext, ActionConfig.DEFAULT_TAG, false)
            val wheelConfig = buildQuickSetupWheelConfig(config, baseConfig).toJson()

            val triggerKey = stringPreferencesKey(KEY_FREE_TRIGGERS)
            val wheelTagsKey = stringPreferencesKey(GestureActionDataManager.KEY_ACTION_ITEMS_TAG_LIST)

            // Resolve existing data before writing, so malformed user configuration is never replaced.
            readWheelTags(appContext.actionStore.readKey(GestureActionDataManager.KEY_ACTION_ITEMS_TAG_LIST, ""))

            commitQuickSetup(writeConfiguration = {
                appContext.actionStore.edit { preferences ->
                    val tags = readWheelTags(preferences[wheelTagsKey].orEmpty())
                    preferences[wheelTagsKey] = ActionItemsTag.toJsonList(mergeQuickSetupWheelTags(tags))

                    // Apply directly to default wheel
                    preferences[stringPreferencesKey(ActionConfig.DEFAULT_TAG)] = wheelJson
                    preferences[stringPreferencesKey("${GestureActionDataManager.KEY_ACTION_CONFIG_PREFIX}${ActionConfig.DEFAULT_TAG}")] = wheelConfig

                    // Apply directly to default trigger
                    preferences[stringPreferencesKey(defaultTrigger.tag)] = triggerActionsJson

                    // Clean up legacy guide triggers & wheel if present
                    preferences.remove(stringPreferencesKey(LEGACY_WHEEL_TAG))
                    preferences.remove(stringPreferencesKey("${GestureActionDataManager.KEY_ACTION_CONFIG_PREFIX}$LEGACY_WHEEL_TAG"))
                    preferences.remove(stringPreferencesKey(LEFT_TRIGGER_TAG))
                    preferences.remove(stringPreferencesKey(RIGHT_TRIGGER_TAG))
                }

                appContext.gestureStore.edit { preferences ->
                    preferences[triggerKey] = FreeTriggerConfig.toJsonList(finalTriggers)
                    preferences[stringPreferencesKey(KEY_CONFIG)] = configJson
                }
                FreeTriggerDataManager.invalidateCache()
            }, publishConfiguration = {
                produceFreeTriggerEvent(FreeTriggerEvent(isConfig = false, reloadWheelTag = ActionConfig.DEFAULT_TAG))
            })
        }
    }

    internal fun buildTriggerActions(config: QuickSetupConfig, side: QuickSetupSide): List<GestureActionItem> {
        val actionMap = mutableMapOf<GestureType, QuickAction>()
        config.gestures.forEach { (gesture, action) ->
            when (gesture) {
                QuickGesture.TAP -> actionMap[GestureType.SINGLE_CLICK] = action
                QuickGesture.LONG_PRESS -> actionMap[GestureType.LONG_PRESS] = action
                QuickGesture.SWIPE_UP -> actionMap[GestureType.SWIPE_UP] = action
                QuickGesture.SWIPE_DOWN -> actionMap[GestureType.SWIPE_DOWN] = action
                QuickGesture.SWIPE_IN -> {
                    when (side) {
                        QuickSetupSide.LEFT -> actionMap[GestureType.SWIPE_RIGHT] = action
                        QuickSetupSide.RIGHT -> actionMap[GestureType.SWIPE_LEFT] = action
                        QuickSetupSide.BOTH -> {
                            actionMap[GestureType.SWIPE_RIGHT] = action
                            actionMap[GestureType.SWIPE_LEFT] = action
                        }
                    }
                }
            }
        }
        return GestureType.entries.filter { it != GestureType.TOUCH }.flatMap { type ->
            GesturePhase.entries.map { phase ->
                val action = if (phase == GesturePhase.TD) actionMap[type] else null
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
            firstStageLink = buildActionUriString(spec.type, spec.parameters)
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

internal fun buildQuickSetupWheelConfig(
    config: QuickSetupConfig,
    baseConfig: ActionConfig = ActionConfig()
): ActionConfig = ActionConfig().apply {
    tag = QuickSetupRepository.WHEEL_TAG
    itemDiameter = baseConfig.itemDiameter
    initRadius = baseConfig.initRadius
    itemWarpOffset = baseConfig.itemWarpOffset
    iconPadding = baseConfig.iconPadding
    iconCornerRadius = baseConfig.iconCornerRadius
    iconImageRoundPercent = baseConfig.iconImageRoundPercent
    transparentIconBackground = baseConfig.transparentIconBackground
    actionOffsetX = baseConfig.actionOffsetX
    actionOffsetY = baseConfig.actionOffsetY
    rectangleLayoutEnabled = baseConfig.rectangleLayoutEnabled
    rectangleColumns = baseConfig.rectangleColumns
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
            tag = if (side == QuickSetupSide.LEFT) QuickSetupRepository.PREVIEW_LEFT_TRIGGER_TAG else QuickSetupRepository.PREVIEW_RIGHT_TRIGGER_TAG
            this.name = name
            enable = true
            width = 18
            height = config.triggerHeightDp
            xRelativeValue = if (side == QuickSetupSide.LEFT) 0f else 1f
            yRelativeValue = config.verticalPercent / 100f
            setState(FreeTriggerConfig.FLAG_PORTRAIT, true)
            setState(FreeTriggerConfig.FLAG_LANDSCAPE, true)
            setState(FreeTriggerConfig.FLAG_WHEEL_FOLLOW_TRIGGER_POINT, false)
        }
    }
}

internal fun buildActionUriString(type: GestureActionType, parameters: Map<String, String>): String {
    if (parameters.isEmpty()) {
        return "gesture-evo://action/${type.name}"
    }
    val query = parameters.entries.joinToString("&") { (k, v) ->
        "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
    }
    return "gesture-evo://action/${type.name}?$query"
}

internal fun parseActionLink(link: String): Pair<GestureActionType, Map<String, String>> {
    if (link.isBlank()) return GestureActionType.NONE to emptyMap()
    val pathAndQuery = link.substringAfter("gesture-evo://action/", "")
    val typeName = pathAndQuery.substringBefore("?")
    val type = GestureActionType.valueOfSafe(typeName)
    val query = if (pathAndQuery.contains("?")) pathAndQuery.substringAfter("?") else ""
    val params = if (query.isNotBlank()) {
        query.split("&").associate { param ->
            val parts = param.split("=", limit = 2)
            val key = java.net.URLDecoder.decode(parts[0], "UTF-8")
            val value = if (parts.size > 1) java.net.URLDecoder.decode(parts[1], "UTF-8") else ""
            key to value
        }
    } else {
        emptyMap()
    }
    return type to params
}

internal fun parseQuickAction(item: GestureActionItem): QuickAction {
    val (actionType, params) = parseActionLink(item.firstStageLink.orEmpty())
    return when (actionType) {
        GestureActionType.NONE -> QuickAction(QuickActionType.NONE)
        GestureActionType.SHOW_ACTION_VIEW -> QuickAction(QuickActionType.WHEEL)
        GestureActionType.ACC_ACTION -> {
            when (params["accAction"]?.toIntOrNull()) {
                AccessibilityService.GLOBAL_ACTION_BACK -> QuickAction(QuickActionType.BACK)
                AccessibilityService.GLOBAL_ACTION_HOME -> QuickAction(QuickActionType.HOME)
                AccessibilityService.GLOBAL_ACTION_RECENTS -> QuickAction(QuickActionType.RECENTS)
                AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS -> QuickAction(QuickActionType.NOTIFICATIONS)
                AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS -> QuickAction(QuickActionType.QUICK_SETTINGS)
                AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT -> QuickAction(QuickActionType.SCREENSHOT)
                else -> QuickAction(QuickActionType.NONE)
            }
        }
        GestureActionType.SEARCH -> QuickAction(QuickActionType.SEARCH)
        GestureActionType.SCREEN_CONTENT -> QuickAction(QuickActionType.SCREEN_CONTENT)
        GestureActionType.SCREEN_SEARCH -> QuickAction(QuickActionType.SCREEN_SEARCH)
        GestureActionType.PIN_POOL -> QuickAction(QuickActionType.PIN_POOL)
        GestureActionType.OPEN_APP -> {
            val pkg = params["pkg"] ?: item.firstStagePkg
            if (pkg.isNullOrBlank()) {
                QuickAction(QuickActionType.NONE)
            } else {
                val userId = params["userId"]?.toIntOrNull() ?: 0
                QuickAction(
                    type = QuickActionType.OPEN_APP,
                    packageName = pkg,
                    appName = item.name,
                    userId = userId
                )
            }
        }
        else -> QuickAction(QuickActionType.NONE)
    }
}

internal fun resolveDefaultTrigger(
    existing: List<FreeTriggerConfig>,
    defaultTriggerName: String,
    createFallback: () -> FreeTriggerConfig
): FreeTriggerConfig {
    val clean = existing.filterNot {
        it.tag == QuickSetupRepository.LEFT_TRIGGER_TAG ||
            it.tag == QuickSetupRepository.RIGHT_TRIGGER_TAG ||
            it.tag == QuickSetupRepository.PREVIEW_LEFT_TRIGGER_TAG ||
            it.tag == QuickSetupRepository.PREVIEW_RIGHT_TRIGGER_TAG
    }
    return clean.find { it.name == defaultTriggerName }
        ?: clean.firstOrNull()
        ?: createFallback()
}

internal fun applyDefaultTriggerConfig(
    trigger: FreeTriggerConfig,
    config: QuickSetupConfig,
    defaultTriggerName: String
): FreeTriggerConfig = trigger.apply {
    name = defaultTriggerName
    enable = true
    height = config.triggerHeightDp
    yRelativeValue = config.verticalPercent / 100f
    when (config.side) {
        QuickSetupSide.LEFT -> {
            xRelativeValue = 0f
            setState(FreeTriggerConfig.FLAG_MIRROR, false)
        }
        QuickSetupSide.RIGHT -> {
            xRelativeValue = 1f
            setState(FreeTriggerConfig.FLAG_MIRROR, false)
        }
        QuickSetupSide.BOTH -> {
            xRelativeValue = 0f
            setState(FreeTriggerConfig.FLAG_MIRROR, true)
        }
    }
    setState(FreeTriggerConfig.FLAG_PORTRAIT, true)
    setState(FreeTriggerConfig.FLAG_LANDSCAPE, true)
    setState(FreeTriggerConfig.FLAG_WHEEL_FOLLOW_TRIGGER_POINT, false)
}

internal fun mergeQuickSetupTriggers(
    existing: List<FreeTriggerConfig>,
    defaultTrigger: FreeTriggerConfig
): List<FreeTriggerConfig> {
    val clean = existing.filterNot {
        it.tag == QuickSetupRepository.LEFT_TRIGGER_TAG ||
            it.tag == QuickSetupRepository.RIGHT_TRIGGER_TAG ||
            it.tag == QuickSetupRepository.PREVIEW_LEFT_TRIGGER_TAG ||
            it.tag == QuickSetupRepository.PREVIEW_RIGHT_TRIGGER_TAG
    }
    val index = clean.indexOfFirst { it.tag == defaultTrigger.tag }
    return if (index >= 0) {
        clean.toMutableList().apply { set(index, defaultTrigger) }
    } else {
        listOf(defaultTrigger) + clean
    }
}

internal fun mergeQuickSetupWheelTags(existing: List<ActionItemsTag>): List<ActionItemsTag> =
    existing.filterNot {
        it.tag == QuickSetupRepository.LEGACY_WHEEL_TAG || it.tag == ActionConfig.DEFAULT_TAG
    }
