package io.github.lumiagg.gestureevo.settings.guide

import com.squareup.moshi.JsonClass
import io.github.lumiagg.gestureevo.utils.MoshiUtils

enum class QuickSetupSide { LEFT, RIGHT, BOTH }

enum class QuickGesture { TAP, SWIPE_IN, SWIPE_UP, SWIPE_DOWN, LONG_PRESS }

enum class QuickActionType {
    NONE, WHEEL, BACK, HOME, RECENTS, RECENT_APP, SEARCH, NOTIFICATIONS, QUICK_SETTINGS,
    SCREENSHOT, SCREEN_CONTENT, SCREEN_SEARCH, PIN_POOL, OPEN_APP, CUSTOM
}

@JsonClass(generateAdapter = true)
data class QuickAction(
    val type: QuickActionType,
    val packageName: String? = null,
    val appName: String? = null,
    val userId: Int = 0,
    val customLink: String? = null
)

@JsonClass(generateAdapter = true)
data class QuickSetupConfig(
    val side: QuickSetupSide = QuickSetupSide.RIGHT,
    val verticalPercent: Int = 55,
    val triggerHeightDp: Int = 100,
    val gestures: Map<QuickGesture, QuickAction> = defaultGestures(),
    val wheelItems: List<QuickAction> = defaultWheelItems(),
    val wheelOffsetXDp: Int = 30,
    val wheelRadiusDp: Int = 130
) {
    fun validate(sdkInt: Int) {
        require(verticalPercent in 10..90) { "Trigger position must be between 10 and 90" }
        require(triggerHeightDp in 40..200) { "Trigger height must be between 40 and 200 dp" }
        require(wheelOffsetXDp in 0..120) { "Wheel inset must be between 0 and 120 dp" }
        require(wheelRadiusDp in 80..200) { "Wheel radius must be between 80 and 200 dp" }
        require(gestures.keys == QuickGesture.entries.toSet()) { "Every quick gesture must have an action" }
        require(wheelItems.size >= 2) { "The wheel must contain at least 2 actions" }
        require(wheelItems.none { it.type == QuickActionType.NONE || it.type == QuickActionType.WHEEL }) {
            "Wheel items must be executable actions"
        }
        (gestures.values + wheelItems).forEach { action ->
            require(action.type != QuickActionType.SCREENSHOT || sdkInt >= 28) {
                "The screenshot action requires Android 9 or later"
            }
            if (action.type == QuickActionType.OPEN_APP) {
                require(!action.packageName.isNullOrBlank()) { "An app action requires a package name" }
                require(action.userId >= 0) { "An app action requires a valid user" }
            } else if (action.type != QuickActionType.CUSTOM) {
                require(action.packageName == null && action.userId == 0) {
                    "Only app actions can contain application details"
                }
            }
        }
    }

    fun toJson(): String = MoshiUtils.toJson(this)

    companion object {
        fun default(): QuickSetupConfig = QuickSetupConfig()

        fun fromJson(json: String): QuickSetupConfig? = MoshiUtils.toModel(json)

        private fun defaultGestures(): Map<QuickGesture, QuickAction> = mapOf(
            QuickGesture.TAP to QuickAction(QuickActionType.BACK),
            QuickGesture.SWIPE_IN to QuickAction(QuickActionType.WHEEL),
            QuickGesture.SWIPE_UP to QuickAction(QuickActionType.HOME),
            QuickGesture.SWIPE_DOWN to QuickAction(QuickActionType.NOTIFICATIONS),
            QuickGesture.LONG_PRESS to QuickAction(QuickActionType.SEARCH)
        )

        private fun defaultWheelItems(): List<QuickAction> = listOf(
            QuickAction(QuickActionType.BACK),
            QuickAction(QuickActionType.HOME),
            QuickAction(QuickActionType.RECENTS),
            QuickAction(QuickActionType.SEARCH),
            QuickAction(QuickActionType.QUICK_SETTINGS),
            QuickAction(QuickActionType.SCREEN_SEARCH)
        )
    }
}
