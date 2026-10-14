package io.github.lumiagg.gestureevo.settings.guide

import android.content.Context
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.gesture.config.GestureActionItem
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.gesture.ui.RoundImageTextView
import io.github.lumiagg.gestureevo.gesture.ui.gestureActionIcon

internal val GestureActionItem.guideActionType: GestureActionType
    get() = runCatching { parseActionLink(firstStageLink.orEmpty()).first }.getOrDefault(GestureActionType.NONE)

internal val GestureActionItem.isUnassigned: Boolean
    get() = guideActionType == GestureActionType.NONE &&
        runCatching { parseActionLink(secondStageLink.orEmpty()).first }.getOrDefault(GestureActionType.NONE) == GestureActionType.NONE &&
        subActionListTag.isNullOrBlank()

internal fun GestureActionItem.label(context: Context): String {
    name?.takeIf { it.isNotBlank() }?.let { return it }
    if (guideActionType == GestureActionType.ACC_ACTION) {
        val id = runCatching { parseActionLink(firstStageLink.orEmpty()).second["accAction"]?.toIntOrNull() }.getOrNull()
        val resource = when (id) {
            1 -> R.string.acc_action_select_back
            2 -> R.string.acc_action_select_home
            3 -> R.string.acc_action_select_recents
            4 -> R.string.acc_action_select_notifications
            5 -> R.string.acc_action_select_quick_settings
            9 -> R.string.acc_action_select_screenshot
            else -> null
        }
        resource?.let { return context.getString(it) }
    }
    if (guideActionType == GestureActionType.NONE && !subActionListTag.isNullOrBlank()) {
        return context.getString(R.string.gesture_action_show_action_view)
    }
    return guideActionType.showName(context)
}

internal fun GestureActionItem.description(context: Context): String {
    val type = guideActionType
    val resource = when (type) {
        GestureActionType.NONE -> R.string.quick_setup_action_none_desc
        GestureActionType.SHOW_ACTION_VIEW -> R.string.quick_setup_action_wheel_desc
        GestureActionType.RECENT_APP -> R.string.quick_setup_action_recent_app_desc
        GestureActionType.SEARCH -> R.string.quick_setup_action_search_desc
        GestureActionType.SCREEN_CONTENT -> R.string.quick_setup_action_screen_content_desc
        GestureActionType.SCREEN_SEARCH -> R.string.quick_setup_action_screen_search_desc
        GestureActionType.PIN_POOL -> R.string.quick_setup_action_pin_pool_desc
        GestureActionType.OPEN_APP -> R.string.quick_setup_action_open_app_desc
        GestureActionType.ACC_ACTION -> when (parseActionLink(firstStageLink.orEmpty()).second["accAction"]) {
            "1" -> R.string.quick_setup_action_back_desc
            "2" -> R.string.quick_setup_action_home_desc
            "3" -> R.string.quick_setup_action_recents_desc
            "4" -> R.string.quick_setup_action_notifications_desc
            "5" -> R.string.quick_setup_action_quick_settings_desc
            "9" -> R.string.quick_setup_action_screenshot_desc
            else -> R.string.quick_setup_action_custom_desc
        }
        else -> R.string.quick_setup_action_custom_desc
    }
    return context.getString(resource)
}

internal fun RoundImageTextView.bindGuideAction(action: GestureActionItem) {
    contentDescription = action.label(context)
    setText(action.label(context))
    gestureActionIcon(context, this, action)
}

/** The quick picker changes the first action; advanced settings remain editable in the full editor. */
internal fun replaceGuideAction(original: GestureActionItem, selected: GestureActionItem): GestureActionItem =
    original.copy().apply {
        firstStageLink = selected.firstStageLink
        if (selected.name != null || original.guideActionType == GestureActionType.OPEN_APP) name = selected.name
    }

internal fun QuickGesture.label(context: Context): String = context.getString(when (this) {
    QuickGesture.TAP -> R.string.gesture_type_single_click
    QuickGesture.SWIPE_IN -> R.string.quick_gesture_inward
    QuickGesture.SWIPE_UP -> R.string.gesture_type_swipe_up
    QuickGesture.SWIPE_DOWN -> R.string.gesture_type_swipe_down
    QuickGesture.LONG_PRESS -> R.string.gesture_type_long_press
})

internal fun QuickGesture.instruction(context: Context): String = context.getString(when (this) {
    QuickGesture.TAP -> R.string.quick_practice_tap
    QuickGesture.SWIPE_IN -> R.string.quick_gesture_inward
    QuickGesture.SWIPE_UP -> R.string.quick_practice_up
    QuickGesture.SWIPE_DOWN -> R.string.quick_practice_down
    QuickGesture.LONG_PRESS -> R.string.quick_practice_hold
})
