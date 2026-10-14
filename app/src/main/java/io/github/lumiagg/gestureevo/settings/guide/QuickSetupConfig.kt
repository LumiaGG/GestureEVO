package io.github.lumiagg.gestureevo.settings.guide

import com.squareup.moshi.JsonClass
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerConfig
import io.github.lumiagg.gestureevo.gesture.config.GestureActionItem
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.utils.MoshiUtils

enum class QuickSetupSide { LEFT, RIGHT, BOTH }
enum class QuickGesture { TAP, SWIPE_IN, SWIPE_UP, SWIPE_DOWN, LONG_PRESS }

/** A guide draft keeps the runtime action models intact, including all advanced options. */
@JsonClass(generateAdapter = true)
data class QuickSetupConfig(
    val side: QuickSetupSide = QuickSetupSide.RIGHT,
    val verticalPercent: Int = 55,
    val triggerHeightDp: Int = 100,
    val gestures: Map<QuickGesture, GestureActionItem> = defaultGestures(),
    val wheelItems: List<GestureActionItem> = defaultWheelItems(),
    val baseTrigger: FreeTriggerConfig? = null,
    val editedGestures: Set<QuickGesture> = emptySet(),
    val preservedGesturePhases: Set<QuickGesture> = emptySet(),
    val hasPersistedConfiguration: Boolean = false
) {
    fun validate(sdkInt: Int) {
        require(verticalPercent in 0..100) { "Trigger position must be between 0 and 100" }
        require(triggerHeightDp > 0) { "Trigger height must be positive" }
        require(gestures.keys == QuickGesture.entries.toSet()) { "Every quick gesture must have an action" }
        (gestures.values + wheelItems).forEach { action ->
            listOfNotNull(action.firstStageLink, action.secondStageLink).forEach { link ->
                val (type, parameters) = parseActionLink(link)
                if (type == GestureActionType.ACC_ACTION && parameters["accAction"] == "9") {
                    require(sdkInt >= 28) { "The screenshot action requires Android 9 or later" }
                }
                if (type == GestureActionType.OPEN_APP) {
                    require(!parameters["pkg"].isNullOrBlank()) { "An app action requires a package name" }
                    require((parameters["userId"]?.toIntOrNull() ?: 0) >= 0) { "Invalid app profile" }
                }
            }
        }
    }

    fun withGesture(gesture: QuickGesture, action: GestureActionItem, preserveOtherPhases: Boolean = false): QuickSetupConfig {
        val original = gestures.getValue(gesture)
        val replacement = action.copy(tag = original.tag)
        if (original.toJson(false) == replacement.toJson(false)) return this
        return copy(gestures = gestures + (gesture to replacement), editedGestures = editedGestures + gesture,
            preservedGesturePhases = if (preserveOtherPhases) preservedGesturePhases + gesture else preservedGesturePhases - gesture)
    }

    fun toJson(): String = MoshiUtils.toJson(this)
    /** Editing metadata and gesture storage keys do not make a restored draft dirty. */
    fun contentJson(): String = copy(baseTrigger = null, editedGestures = emptySet(), preservedGesturePhases = emptySet(), hasPersistedConfiguration = true,
        gestures = gestures.mapValues { (gesture, action) -> action.copy(tag = gesture.name) }).toJson()
    fun snapshot(): QuickSetupConfig = requireNotNull(fromJson(toJson()))

    companion object {
        fun default(): QuickSetupConfig = QuickSetupConfig()
        fun fromJson(json: String): QuickSetupConfig? = MoshiUtils.toModel(json)

        private fun defaultGestures(): Map<QuickGesture, GestureActionItem> = mapOf(
            QuickGesture.TAP to createAccessibilityAction(1),
            QuickGesture.SWIPE_IN to createGuideAction(GestureActionType.SHOW_ACTION_VIEW,
                mapOf("actionItemsTag" to QuickSetupRepository.WHEEL_TAG)),
            QuickGesture.SWIPE_UP to createAccessibilityAction(2),
            QuickGesture.SWIPE_DOWN to createAccessibilityAction(4),
            QuickGesture.LONG_PRESS to createGuideAction(GestureActionType.SEARCH)
        )

        private fun defaultWheelItems(): List<GestureActionItem> = listOf(
            createAccessibilityAction(1), createAccessibilityAction(2), createAccessibilityAction(3),
            createGuideAction(GestureActionType.SEARCH), createAccessibilityAction(5),
            createGuideAction(GestureActionType.SCREEN_SEARCH)
        )
    }
}
