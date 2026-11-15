package io.github.lumiagg.gestureevo.drop

/** Persisted drag settings. Keep key values stable across upgrades. */
internal object DropPreferences {
    const val KEY_GLOBAL_PRESS_ENABLED = "key_global_press_enabled"
    const val KEY_GLOBAL_PRESS_TIMEOUT = "key_global_press_timeout"
    const val KEY_GLOBAL_PRESS_EXCLUDED = "key_global_press_excluded"
    const val KEY_EXTERNAL_DROP_ENABLED = "key_external_drop_enabled"
    const val KEY_EXTERNAL_DROP_CONVERSION = "key_external_drop_conversion"
    const val KEY_EXTERNAL_DROP_POSITION = "key_external_drop_position"
    const val EXTERNAL_DROP_POSITION_BOTTOM = "bottom"
    const val EXTERNAL_DROP_POSITION_LEFT = "left"
    const val EXTERNAL_DROP_POSITION_RIGHT = "right"
    const val KEY_EXTERNAL_DROP_SHOW_ENGINES = "key_external_drop_show_engines"
    const val KEY_EXTERNAL_DROP_SIDEBAR_MAX_COLUMNS = "key_external_drop_sidebar_max_columns"
    const val KEY_EXTERNAL_DROP_SIDEBAR_MAX_HEIGHT_PERCENT = "key_external_drop_sidebar_max_height_percent"
    const val KEY_EXTERNAL_DROP_SIDEBAR_FILL_PRIORITY = "key_external_drop_sidebar_fill_priority"
    const val EXTERNAL_DROP_FILL_HEIGHT = "height"
    const val EXTERNAL_DROP_FILL_COLUMNS = "columns"
    const val KEY_EXTERNAL_DROP_BACKGROUND_BLUR = "key_external_drop_background_blur"
    const val KEY_EXTERNAL_DROP_SYSTEM_DRAG_BLUR = "key_external_drop_system_drag_blur"
    const val KEY_EXTERNAL_DROP_CARD_BACKGROUND_BLUR = "key_external_drop_card_background_blur"
}
