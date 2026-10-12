package io.github.lumiagg.gestureevo.gesture.config

import io.github.lumiagg.gestureevo.utils.MoshiUtils
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
class ActionItemsTag {
    var tag: String = ""
    var name: String = ""

    fun toJson(format: Boolean = true): String {
        return MoshiUtils.toJson(this, format = format)
    }

    companion object {
        fun toModel(json: String): ActionItemsTag? {
            return MoshiUtils.toModel<ActionItemsTag>(json)
        }

        fun toJsonList(list: List<ActionItemsTag>): String {
            return MoshiUtils.toJson(list)
        }

        fun toModelList(json: String): List<ActionItemsTag>? {
            return MoshiUtils.toModel<List<ActionItemsTag>>(json)
        }
    }
}
