package io.github.lumiagg.gestureevo.gesture.config

import io.github.lumiagg.gestureevo.utils.MoshiUtils
import io.github.lumiagg.gestureevo.utils.dp2px
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
class ActionConfig {

    // config
    var tag: String = DEFAULT_TAG
    var itemDiameter: Float = 55f.dp2px
    var initRadius: Float = 130f.dp2px
    var itemWarpOffset: Float = 10f.dp2px
    var iconPadding: Float = 10f.dp2px
    var iconCornerRadius: Float = 25f.dp2px
    var iconImageRoundPercent: Float = 0f
    var actionOffsetX: Float = 30f.dp2px
    var actionOffsetY: Float = 30f.dp2px
    var subActionFollowConfig: Boolean = true
    var transparentIconBackground: Boolean = false
    var rectangleLayoutEnabled: Boolean = false
    var rectangleColumns: Int = 4


    fun toJson(format: Boolean = true): String {
        return MoshiUtils.toJson(this, format = format)
    }

    companion object {
        const val DEFAULT_TAG = "TriggerFloatingFixed"

        fun toModel(json: String): ActionConfig? {
            return MoshiUtils.toModel<ActionConfig>(json)
        }

        fun toJsonList(list: List<ActionConfig>): String {
            return MoshiUtils.toJson(list)
        }

        fun toModelList(json: String): List<ActionConfig>? {
            return MoshiUtils.toModel<List<ActionConfig>>(json)
        }
    }
}
