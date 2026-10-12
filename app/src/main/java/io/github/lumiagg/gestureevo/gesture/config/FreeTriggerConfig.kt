package io.github.lumiagg.gestureevo.gesture.config

import android.content.Context
import io.github.lumiagg.gestureevo.gesture.ui.GestureType
import io.github.lumiagg.gestureevo.settings.ui.GestureAngleStorge
import io.github.lumiagg.gestureevo.utils.MoshiUtils
import io.github.lumiagg.gestureevo.utils.getBit
import io.github.lumiagg.gestureevo.utils.setBit
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
class FreeTriggerConfig {
    var tag: String = ""
    var name: String = ""
    var state: Int = FLAG_PORTRAIT or FLAG_BLACKLIST
    var xRelativeValue: Float = 0f
    var yRelativeValue: Float = 0f
    var width: Int = 25
    var height: Int = 50
    var gravity: Int = 0
    var enable: Boolean = true

    var blackList: List<String> = ArrayList()
    var whiteList: List<String> = ArrayList()
    var actionMap: Map<String, String> = mapOf()
    var quadrantValue: Int = 0
    var feedbackColor: Int = 0
    var gestureActionList: List<GestureActionItem>? = null
    var gestureAngleMap: Map<GestureType, GestureAngleStorge> = mapOf()
    var gestureTimingConfig: GestureTimingConfig = GestureTimingConfig()

    fun getGestureAction(key: String): GestureActionItem? {
        return gestureActionList?.find { it.tag == key }
    }

    suspend fun updateGestureActionList(context: Context) {
        gestureActionList = GestureActionDataManager.readActionItemList(context, tag)
    }

    fun getQuadrant(isMirror: Boolean = false): ArrayList<Boolean> {
        val quadrant = ArrayList<Boolean>().apply {
            for (i in 0..3) {
                add(quadrantValue.getBit(i))
            }
        }

        if (isMirror) {
            // When mirroring along Y axis, swap 0-1 and 2-3
            val temp0 = quadrant[0]
            quadrant[0] = quadrant[1]
            quadrant[1] = temp0

            val temp2 = quadrant[2]
            quadrant[2] = quadrant[3]
            quadrant[3] = temp2
        }

        return quadrant
    }

    fun setQuadrant(quadrant: List<Boolean>) {
        // Calculate quadrant values
        quadrantValue = 0
        quadrant.forEachIndexed { index, it ->
            quadrantValue = quadrantValue.setBit(index, it)
        }
    }

    fun getState(flag: Int): Boolean {
        return state and flag == flag
    }

    fun setState(flag: Int, isTrue: Boolean) {
        state = if (isTrue) {
            state or flag
        } else {
            state and flag.inv()
        }
    }

    fun reverseState(flag: Int) {
        state = state xor flag
    }

    fun toJson(format: Boolean = true): String {
        return MoshiUtils.toJson(this, format = format)
    }


    companion object {
        const val FLAG_PORTRAIT: Int = 1.shl(0)
        const val FLAG_LANDSCAPE: Int = 1.shl(1)

        const val FLAG_WHITELIST: Int = 1.shl(5)
        const val FLAG_BLACKLIST: Int = 1.shl(6)

        const val FLAG_MIRROR: Int = 1.shl(7)

        const val FLAG_FEEDBACK: Int = 1.shl(8)

        const val FLAG_WHEEL_FOLLOW_TRIGGER_POINT: Int = 1.shl(9)

        const val FLAG_BELOW_KEYBOARD: Int = 1.shl(10)


        fun toModel(json: String): FreeTriggerConfig? {
            return MoshiUtils.toModel<FreeTriggerConfig>(json)
        }

        fun toJsonList(list: List<FreeTriggerConfig>): String {
            return MoshiUtils.toJson(list)
        }

        fun toModelList(json: String): List<FreeTriggerConfig>? {
            return MoshiUtils.toModel<List<FreeTriggerConfig>>(json)
        }
    }
}
