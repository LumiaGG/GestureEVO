package io.github.lumiagg.gestureevo.settings.guide

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.utils.icon.IconHelper

internal fun QuickAction.label(context: Context): String = when (type) {
    QuickActionType.NONE -> context.getString(R.string.gesture_action_none)
    QuickActionType.WHEEL -> context.getString(R.string.gesture_action_show_action_view)
    QuickActionType.BACK -> context.getString(R.string.acc_action_select_back)
    QuickActionType.HOME -> context.getString(R.string.acc_action_select_home)
    QuickActionType.RECENTS -> context.getString(R.string.acc_action_select_recents)
    QuickActionType.RECENT_APP -> context.getString(R.string.gesture_action_recent_app)
    QuickActionType.NOTIFICATIONS -> context.getString(R.string.acc_action_select_notifications)
    QuickActionType.QUICK_SETTINGS -> context.getString(R.string.acc_action_select_quick_settings)
    QuickActionType.SCREENSHOT -> context.getString(R.string.acc_action_select_screenshot)
    QuickActionType.SEARCH -> context.getString(R.string.gesture_action_search)
    QuickActionType.SCREEN_CONTENT -> context.getString(R.string.gesture_action_screen_content)
    QuickActionType.SCREEN_SEARCH -> context.getString(R.string.gesture_action_screen_search)
    QuickActionType.PIN_POOL -> context.getString(R.string.gesture_action_pin_pool)
    QuickActionType.OPEN_APP -> appName ?: packageName ?: context.getString(R.string.gesture_action_open_app)
    QuickActionType.CUSTOM -> appName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.external_action_settings_title)
}

internal fun QuickAction.description(context: Context): String = when (type) {
    QuickActionType.NONE -> context.getString(R.string.quick_setup_action_none_desc)
    QuickActionType.WHEEL -> context.getString(R.string.quick_setup_action_wheel_desc)
    QuickActionType.BACK -> context.getString(R.string.quick_setup_action_back_desc)
    QuickActionType.HOME -> context.getString(R.string.quick_setup_action_home_desc)
    QuickActionType.RECENTS -> context.getString(R.string.quick_setup_action_recents_desc)
    QuickActionType.RECENT_APP -> context.getString(R.string.quick_setup_action_recent_app_desc)
    QuickActionType.NOTIFICATIONS -> context.getString(R.string.quick_setup_action_notifications_desc)
    QuickActionType.QUICK_SETTINGS -> context.getString(R.string.quick_setup_action_quick_settings_desc)
    QuickActionType.SCREENSHOT -> context.getString(R.string.quick_setup_action_screenshot_desc)
    QuickActionType.SEARCH -> context.getString(R.string.quick_setup_action_search_desc)
    QuickActionType.SCREEN_CONTENT -> context.getString(R.string.quick_setup_action_screen_content_desc)
    QuickActionType.SCREEN_SEARCH -> context.getString(R.string.quick_setup_action_screen_search_desc)
    QuickActionType.PIN_POOL -> context.getString(R.string.quick_setup_action_pin_pool_desc)
    QuickActionType.OPEN_APP -> packageName?.let { context.getString(R.string.app_activity_label, appName ?: "", it) }
        ?: context.getString(R.string.quick_setup_action_open_app_desc)
    QuickActionType.CUSTOM -> customLink?.takeIf { it.isNotBlank() } ?: context.getString(R.string.quick_setup_action_custom_desc)
}

internal fun QuickAction.icon(context: Context): Drawable? {
    if (type == QuickActionType.OPEN_APP && packageName != null) return IconHelper.getIcon(context, packageName)
    val resource = when (type) {
        QuickActionType.NONE -> R.drawable.baseline_clear_24
        QuickActionType.WHEEL -> R.drawable.baseline_pie_chart_24
        QuickActionType.BACK -> R.drawable.baseline_arrow_back_24
        QuickActionType.HOME -> R.drawable.baseline_home_24
        QuickActionType.RECENTS -> R.drawable.baseline_menu_24
        QuickActionType.RECENT_APP -> R.drawable.history_24px
        QuickActionType.NOTIFICATIONS -> R.drawable.baseline_circle_notifications_24
        QuickActionType.QUICK_SETTINGS -> R.drawable.baseline_settings_24
        QuickActionType.SCREENSHOT -> R.drawable.baseline_screenshot_24
        QuickActionType.SCREEN_CONTENT -> R.drawable.outline_center_focus_weak_24
        QuickActionType.PIN_POOL -> R.drawable.pinboard_24px
        QuickActionType.CUSTOM -> R.drawable.ic_baseline_insert_link_24
        else -> R.drawable.search_24px
    }
    return ContextCompat.getDrawable(context, resource)
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
    QuickGesture.SWIPE_IN -> R.string.quick_practice_inward
    QuickGesture.SWIPE_UP -> R.string.quick_practice_up
    QuickGesture.SWIPE_DOWN -> R.string.quick_practice_down
    QuickGesture.LONG_PRESS -> R.string.quick_practice_hold
})
