package io.github.lumiagg.gestureevo.db

import androidx.room.PrimaryKey
import io.github.lumiagg.gestureevo.quick.ui.fullKeyToDoubleKey
import io.github.lumiagg.gestureevo.quick.ui.fullKeyToT9
import io.github.lumiagg.gestureevo.utils.PinyinUtils

open class BaseModel {
    @PrimaryKey(autoGenerate = true)
    var id: Int = 0
    var state: Int = 0
    var isRemove: Boolean = false

    var name: String = ""
        set(value) {
            this.pinyinFK = PinyinUtils.getPinyinParse(value)
            val initials = PinyinUtils.getPinyinFP(value)
            this.initialsFK = if (initials.length > 1) initials else ""

            this.pinyinDK = fullKeyToDoubleKey(pinyinFK)
            this.initialsDK = fullKeyToDoubleKey(initialsFK)

            this.pinyinT9 = fullKeyToT9(pinyinFK)
            this.initialsT9 = fullKeyToT9(initialsFK)

            field = value
        }

    var pinyinFK: String = ""
    var initialsFK: String = ""
    var pinyinDK: String = ""
    var initialsDK: String = ""
    var pinyinT9: String = ""
    var initialsT9: String = ""

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

}
