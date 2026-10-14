package io.github.lumiagg.gestureevo.settings.guide

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
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
import io.github.lumiagg.gestureevo.gesture.config.gestureConfigurationWriteMutex
import io.github.lumiagg.gestureevo.gesture.ui.GesturePhase
import io.github.lumiagg.gestureevo.gesture.ui.GestureType
import io.github.lumiagg.gestureevo.gesture.ui.GestureTypeEvent
import io.github.lumiagg.gestureevo.utils.actionStore
import io.github.lumiagg.gestureevo.utils.gestureStore
import io.github.lumiagg.gestureevo.utils.readKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

object QuickSetupRepository {
    internal const val PREVIEW_LEFT_TRIGGER_TAG = "guide_preview_trigger_left"
    internal const val PREVIEW_RIGHT_TRIGGER_TAG = "guide_preview_trigger_right"
    internal const val LEFT_TRIGGER_TAG = "settings_guide_trigger_left"
    internal const val RIGHT_TRIGGER_TAG = "settings_guide_trigger_right"
    internal const val LEGACY_WHEEL_TAG = "settings_guide_wheel"
    internal const val WHEEL_TAG = ActionConfig.DEFAULT_TAG
    private const val KEY_CONFIG = "settings_guide_quick_config"
    private const val KEY_FREE_TRIGGERS = "key_free_triggers"

    suspend fun load(context: Context): QuickSetupConfig = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val gestures = app.gestureStore.data.first()
        val actions = app.actionStore.data.first()
        val triggers = readTriggers(gestures.readKey(KEY_FREE_TRIGGERS, ""))
        var trigger = resolveDefaultTrigger(triggers, app.getString(R.string.default_trigger_name)) {
            FreeTriggerDataManager.createDefaultTrigger(app)
        }
        val existingTrigger = triggers.any { it.tag == trigger.tag }
        var triggerActions = readTriggerActions(trigger, readActions(actions.readKey(trigger.tag, "")))
        if (trigger.tag == LEFT_TRIGGER_TAG && triggers.any { it.tag == RIGHT_TRIGGER_TAG }) {
            val right = triggers.first { it.tag == RIGHT_TRIGGER_TAG }
            triggerActions = mergeLegacyGuideActions(triggerActions, readTriggerActions(right, readActions(actions.readKey(right.tag, ""))))
            trigger = trigger.copy().apply { setState(FreeTriggerConfig.FLAG_MIRROR, true) }
        }
        val defaultWheelJson = actions.readKey(WHEEL_TAG, "")
        val wheelJson = defaultWheelJson.ifBlank { actions.readKey(LEGACY_WHEEL_TAG, "") }
        buildGuideDraft(trigger, triggerActions,
            if (wheelJson.isBlank()) QuickSetupConfig.default().wheelItems else readActions(wheelJson),
            existingTrigger).copy(hasPersistedConfiguration = existingTrigger && !isGuideTrigger(trigger.tag) && defaultWheelJson.isNotBlank() && trigger.actionMap.isEmpty())
    }

    suspend fun readWheelConfig(context: Context): ActionConfig {
        val snapshot = context.applicationContext.actionStore.data.first()
        val prefix = GestureActionDataManager.KEY_ACTION_CONFIG_PREFIX
        val json = snapshot.readKey("$prefix$WHEEL_TAG", "").ifBlank { snapshot.readKey("$prefix$LEGACY_WHEEL_TAG", "") }
        return (if (json.isBlank()) ActionConfig() else requireNotNull(ActionConfig.toModel(json))).apply { tag = WHEEL_TAG }
    }

    suspend fun readWheelItems(context: Context, tag: String): List<GestureActionItem> = withContext(Dispatchers.IO) {
        readActions(context.applicationContext.actionStore.readKey(tag, ""))
    }

    suspend fun apply(context: Context, config: QuickSetupConfig) = withContext(Dispatchers.IO) {
        var committed = false
        var committedConfig: QuickSetupConfig? = null
        try {
            gestureConfigurationWriteMutex.withLock {
                config.validate(Build.VERSION.SDK_INT)
                val app = context.applicationContext
                val defaultName = app.getString(R.string.default_trigger_name)
                val existingTriggers = readTriggers(app.gestureStore.readKey(KEY_FREE_TRIGGERS, ""))
                // Keep the selected trigger identity even if it has been renamed.
                val trigger = existingTriggers.find { it.tag == config.baseTrigger?.tag }
                    ?: resolveDefaultTrigger(existingTriggers, defaultName) { FreeTriggerDataManager.createDefaultTrigger(app) }
                val actionSnapshot = app.actionStore.data.first()
                var existingActions = readTriggerActions(trigger, readActions(actionSnapshot.readKey(trigger.tag, "")))
                if (trigger.tag == LEFT_TRIGGER_TAG) {
                    existingTriggers.find { it.tag == RIGHT_TRIGGER_TAG }?.let { right ->
                        existingActions = mergeLegacyGuideActions(existingActions, readTriggerActions(right, readActions(actionSnapshot.readKey(right.tag, ""))))
                    }
                }
                val triggerActions = mergeTriggerActions(existingActions, config, config.side)
                val updatedTrigger = applyDefaultTriggerConfig(trigger.copy(), config, defaultName).apply {
                    if (isGuideTrigger(tag)) tag = FreeTriggerDataManager.createDefaultTrigger(app).tag
                    actionMap = emptyMap()
                    gestureActionList = null
                }
                val finalTriggers = mergeQuickSetupTriggers(existingTriggers, updatedTrigger)
                val triggerJson = GestureActionItem.toJsonList(triggerActions)
                val wheelJson = GestureActionItem.toJsonList(buildQuickSetupWheelItems(config))
                val wheelTagsKey = stringPreferencesKey(GestureActionDataManager.KEY_ACTION_ITEMS_TAG_LIST)
                readWheelTags(app.actionStore.readKey(GestureActionDataManager.KEY_ACTION_ITEMS_TAG_LIST, ""))
                var previousActions: Map<Preferences.Key<String>, String?> = emptyMap()
                var writtenActions: Map<Preferences.Key<String>, String?> = emptyMap()
                var actionsCommitted = false
                val appearanceKey = stringPreferencesKey("${GestureActionDataManager.KEY_ACTION_CONFIG_PREFIX}$WHEEL_TAG")
                val legacyAppearanceKey = stringPreferencesKey("${GestureActionDataManager.KEY_ACTION_CONFIG_PREFIX}$LEGACY_WHEEL_TAG")

                commitQuickSetup(writeConfiguration = {
                    // The guide edits wheel actions, not appearance. Keep the complete saved ActionConfig.
                    app.actionStore.edit { preferences ->
                        writtenActions = buildMap {
                            putAll(mapOf(
                            Pair(wheelTagsKey, ActionItemsTag.toJsonList(mergeQuickSetupWheelTags(readWheelTags(preferences[wheelTagsKey].orEmpty())))),
                            Pair(stringPreferencesKey(WHEEL_TAG), wheelJson),
                            Pair(stringPreferencesKey(updatedTrigger.tag), triggerJson),
                            stringPreferencesKey(LEFT_TRIGGER_TAG) to null,
                            stringPreferencesKey(RIGHT_TRIGGER_TAG) to null
                            ))
                            // Copy legacy appearance without removing a group that other actions may reference.
                            if (preferences[appearanceKey].isNullOrBlank()) preferences[legacyAppearanceKey]?.takeIf { it.isNotBlank() }?.let {
                                put(appearanceKey, requireNotNull(ActionConfig.toModel(it)).apply { tag = WHEEL_TAG }.toJson())
                            }
                        }
                        previousActions = writtenActions.keys.associateWith { preferences[it] }
                        writtenActions.forEach { (key, value) -> if (value == null) preferences.remove(key) else preferences[key] = value }
                    }
                    actionsCommitted = true
                    app.gestureStore.edit { preferences ->
                        preferences[stringPreferencesKey(KEY_FREE_TRIGGERS)] = FreeTriggerConfig.toJsonList(finalTriggers)
                        preferences.remove(stringPreferencesKey(KEY_CONFIG))
                    }
                    FreeTriggerDataManager.invalidateCache()
                }, publishConfiguration = {
                    committedConfig = buildGuideDraft(updatedTrigger, triggerActions, config.wheelItems)
                        .copy(hasPersistedConfiguration = true)
                    committed = true
                }, rollbackConfiguration = {
                    if (actionsCommitted) app.actionStore.edit { preferences ->
                        previousActions.forEach { (key, previous) ->
                            // Do not undo another editor's intervening write.
                            if (preferences[key] == writtenActions[key]) {
                                if (previous == null) preferences.remove(key) else preferences[key] = previous
                            }
                        }
                    }
                })
            }
        } finally {
            // Subscribers may need the write mutex themselves. Deliver after releasing it,
            // including when the screen was cancelled after its data was committed.
            if (committed) withContext(NonCancellable) {
                produceFreeTriggerEvent(FreeTriggerEvent(isConfig = false, reloadWheelTag = WHEEL_TAG))
            }
        }
        requireNotNull(committedConfig)
    }

    internal fun mergeTriggerActions(existing: List<GestureActionItem>, config: QuickSetupConfig, side: QuickSetupSide): List<GestureActionItem> {
        val result = existing.map { it.copy() }.toMutableList()
        val originalSide = config.baseTrigger?.let(::triggerSide)
        config.gestures.forEach { (gesture, action) ->
            val edited = config.baseTrigger == null || gesture in config.editedGestures
            val movedInward = gesture == QuickGesture.SWIPE_IN && originalSide != side
            if (!edited && !movedInward) return@forEach
            val types = if (gesture == QuickGesture.SWIPE_IN && side == QuickSetupSide.BOTH) {
                listOf(GestureType.SWIPE_LEFT, GestureType.SWIPE_RIGHT)
            } else listOf(quickGestureType(gesture, side))
            types.forEach { type ->
                GesturePhase.entries.forEach { phase ->
                    if (phase != GesturePhase.TD && (!edited || gesture in config.preservedGesturePhases)) return@forEach
                    val key = GestureTypeEvent(type, phase).toActionKey()
                    result.removeAll { it.tag == key }
                    result.add(if (phase == GesturePhase.TD) action.copy(tag = key)
                        else createGuideAction(GestureActionType.NONE).copy(tag = key))
                }
            }
        }
        return result
    }

    internal fun buildTriggerActions(config: QuickSetupConfig, side: QuickSetupSide): List<GestureActionItem> =
        mergeTriggerActions(emptyList(), config.copy(baseTrigger = null), side)

    private fun readTriggers(json: String): List<FreeTriggerConfig> =
        if (json.isBlank()) emptyList() else requireNotNull(FreeTriggerConfig.toModelList(json))
    private fun readActions(json: String): List<GestureActionItem> =
        if (json.isBlank()) emptyList() else requireNotNull(GestureActionItem.toModelList(json))
    private fun readWheelTags(json: String): List<ActionItemsTag> =
        if (json.isBlank()) emptyList() else requireNotNull(ActionItemsTag.toModelList(json))
}

internal fun triggerSide(trigger: FreeTriggerConfig): QuickSetupSide = when {
    trigger.getState(FreeTriggerConfig.FLAG_MIRROR) -> QuickSetupSide.BOTH
    trigger.xRelativeValue == 0f -> QuickSetupSide.LEFT
    else -> QuickSetupSide.RIGHT
}

/** Match runtime migration precedence before clearing the old map on commit. */
internal fun readTriggerActions(trigger: FreeTriggerConfig, persisted: List<GestureActionItem>): List<GestureActionItem> =
    if (trigger.actionMap.isEmpty()) persisted.map { it.copy() }
    else trigger.actionMap.map { (key, link) -> GestureActionItem().apply { tag = key; firstStageLink = link } }

internal fun mergeLegacyGuideActions(left: List<GestureActionItem>, right: List<GestureActionItem>): List<GestureActionItem> =
    left + right.filter { item -> left.none { it.tag == item.tag } }

internal fun buildGuideDraft(trigger: FreeTriggerConfig, actions: List<GestureActionItem>, wheel: List<GestureActionItem>, existing: Boolean = true): QuickSetupConfig {
    val side = triggerSide(trigger)
    val base = trigger.copy().apply { gestureActionList = actions.map { it.copy() } }
    val gestures = if (!existing) QuickSetupConfig.default().gestures else QuickGesture.entries.associateWith { gesture ->
        val key = GestureTypeEvent(quickGestureType(gesture, side), GesturePhase.TD).toActionKey()
        val alternateKey = GestureTypeEvent(GestureType.SWIPE_RIGHT, GesturePhase.TD).toActionKey()
        (actions.find { it.tag == key }
            ?: actions.find { side == QuickSetupSide.BOTH && gesture == QuickGesture.SWIPE_IN && it.tag == alternateKey }
            ?: createGuideAction(GestureActionType.SHOW_ACTION_VIEW, mapOf("actionItemsTag" to QuickSetupRepository.WHEEL_TAG))).copy()
    }
    return QuickSetupConfig(side = side,
        verticalPercent = (trigger.yRelativeValue * 100).roundToInt().coerceIn(0, 100),
        triggerHeightDp = trigger.height.coerceAtLeast(1), gestures = gestures,
        wheelItems = wheel.map { it.copy() }, baseTrigger = base,
        editedGestures = if (existing) emptySet() else QuickGesture.entries.toSet())
}

internal suspend fun commitQuickSetup(writeConfiguration: suspend () -> Unit, publishConfiguration: suspend () -> Unit,
    rollbackConfiguration: suspend () -> Unit = {}) {
    coroutineContext.ensureActive()
    withContext(NonCancellable) {
        try {
            writeConfiguration()
        } catch (failure: Exception) {
            try { rollbackConfiguration() } catch (rollbackFailure: Exception) { failure.addSuppressed(rollbackFailure) }
            throw failure
        }
        publishConfiguration()
    }
    coroutineContext.ensureActive()
}

internal fun buildQuickSetupWheelConfig(baseConfig: ActionConfig = ActionConfig()): ActionConfig =
    baseConfig.copy().apply { tag = QuickSetupRepository.WHEEL_TAG }

internal fun buildQuickSetupWheelItems(config: QuickSetupConfig): List<GestureActionItem> =
    config.wheelItems.map { it.copy() }

internal fun createGuideAction(type: GestureActionType, parameters: Map<String, String> = emptyMap(), name: String? = null): GestureActionItem =
    GestureActionItem().apply { this.name = name; firstStageLink = buildActionUriString(type, parameters) }
internal fun createAccessibilityAction(id: Int): GestureActionItem =
    createGuideAction(GestureActionType.ACC_ACTION, mapOf("accAction" to id.toString()))

internal fun quickGestureType(gesture: QuickGesture, side: QuickSetupSide): GestureType = when (gesture) {
    QuickGesture.TAP -> GestureType.SINGLE_CLICK
    QuickGesture.SWIPE_IN -> if (side == QuickSetupSide.LEFT) GestureType.SWIPE_RIGHT else GestureType.SWIPE_LEFT
    QuickGesture.SWIPE_UP -> GestureType.SWIPE_UP
    QuickGesture.SWIPE_DOWN -> GestureType.SWIPE_DOWN
    QuickGesture.LONG_PRESS -> GestureType.LONG_PRESS
}

internal fun buildQuickSetupTriggers(config: QuickSetupConfig, name: String): List<FreeTriggerConfig> {
    val sides = if (config.side == QuickSetupSide.BOTH) listOf(QuickSetupSide.LEFT, QuickSetupSide.RIGHT) else listOf(config.side)
    val savedGeometry = applyDefaultTriggerConfig(config.baseTrigger?.copy() ?: FreeTriggerConfig(), config, name)
    return sides.map { side ->
        savedGeometry.copy().apply {
            tag = if (side == QuickSetupSide.LEFT) QuickSetupRepository.PREVIEW_LEFT_TRIGGER_TAG else QuickSetupRepository.PREVIEW_RIGHT_TRIGGER_TAG
            setState(FreeTriggerConfig.FLAG_MIRROR, false)
            actionMap = emptyMap()
            gestureActionList = QuickSetupRepository.mergeTriggerActions(config.baseTrigger?.gestureActionList.orEmpty(), config, config.side)
        }
    }
}

internal fun buildActionUriString(type: GestureActionType, parameters: Map<String, String>): String {
    val query = parameters.entries.joinToString("&") { (key, value) ->
        "${java.net.URLEncoder.encode(key, "UTF-8")}=${java.net.URLEncoder.encode(value, "UTF-8")}"
    }
    return "gesture-evo://action/${type.name}" + if (query.isEmpty()) "" else "?$query"
}

internal fun parseActionLink(link: String): Pair<GestureActionType, Map<String, String>> {
    if (link.isBlank()) return GestureActionType.NONE to emptyMap()
    val uri = runCatching { java.net.URI(link) }.getOrNull()
        ?: return GestureActionType.valueOfSafe(link.substringAfter("gesture-evo://action/", "").substringBefore('?')) to emptyMap()
    val type = GestureActionType.valueOfSafe(uri.path.orEmpty().substringAfterLast('/'))
    val parameters = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate { part ->
        val pieces = part.split('=', limit = 2)
        java.net.URLDecoder.decode(pieces[0], "UTF-8") to java.net.URLDecoder.decode(pieces.getOrElse(1) { "" }, "UTF-8")
    }
    return type to parameters
}

internal fun resolveDefaultTrigger(existing: List<FreeTriggerConfig>, defaultTriggerName: String, createFallback: () -> FreeTriggerConfig): FreeTriggerConfig {
    val clean = existing.filterNot { isGuideTrigger(it.tag) }
    return clean.find { it.name == defaultTriggerName } ?: clean.firstOrNull()
        ?: existing.find { it.tag == QuickSetupRepository.LEFT_TRIGGER_TAG }
        ?: existing.find { it.tag == QuickSetupRepository.RIGHT_TRIGGER_TAG } ?: createFallback()
}

internal fun applyDefaultTriggerConfig(trigger: FreeTriggerConfig, config: QuickSetupConfig, defaultTriggerName: String): FreeTriggerConfig = trigger.apply {
    if (name.isBlank()) name = defaultTriggerName
    enable = true
    // Preserve precise values loaded from the runtime unless this control was actually changed.
    if (config.baseTrigger == null || config.triggerHeightDp != config.baseTrigger.height) height = config.triggerHeightDp
    if (config.baseTrigger == null || config.verticalPercent != (config.baseTrigger.yRelativeValue * 100).roundToInt()) {
        yRelativeValue = config.verticalPercent / 100f
    }
    if (config.baseTrigger == null || config.side != triggerSide(config.baseTrigger)) {
        xRelativeValue = if (config.side == QuickSetupSide.RIGHT) 1f else 0f
    }
    setState(FreeTriggerConfig.FLAG_MIRROR, config.side == QuickSetupSide.BOTH)
    if (config.baseTrigger == null) {
        setState(FreeTriggerConfig.FLAG_PORTRAIT, true)
        setState(FreeTriggerConfig.FLAG_LANDSCAPE, true)
        setState(FreeTriggerConfig.FLAG_WHEEL_FOLLOW_TRIGGER_POINT, false)
    }
}

private fun isGuideTrigger(tag: String): Boolean = tag in setOf(QuickSetupRepository.LEFT_TRIGGER_TAG,
    QuickSetupRepository.RIGHT_TRIGGER_TAG, QuickSetupRepository.PREVIEW_LEFT_TRIGGER_TAG, QuickSetupRepository.PREVIEW_RIGHT_TRIGGER_TAG)

internal fun mergeQuickSetupTriggers(existing: List<FreeTriggerConfig>, defaultTrigger: FreeTriggerConfig): List<FreeTriggerConfig> {
    val clean = existing.filterNot { isGuideTrigger(it.tag) }
    val index = clean.indexOfFirst { it.tag == defaultTrigger.tag }
    return if (index >= 0) clean.toMutableList().apply { set(index, defaultTrigger) } else listOf(defaultTrigger) + clean
}

internal fun mergeQuickSetupWheelTags(existing: List<ActionItemsTag>): List<ActionItemsTag> =
    existing.filterNot { it.tag == QuickSetupRepository.LEGACY_WHEEL_TAG || it.tag == ActionConfig.DEFAULT_TAG }
