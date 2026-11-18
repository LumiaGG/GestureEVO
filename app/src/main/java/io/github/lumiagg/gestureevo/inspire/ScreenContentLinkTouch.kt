package io.github.lumiagg.gestureevo.inspire

import kotlin.math.abs

/** Link presses own their release even after a hold. Moving yields permanently to selection. */
internal class ScreenContentLinkTouch(private val slop: Float) {
    var pending = false
        private set
    var downX = 0f
        private set
    var downY = 0f
        private set

    fun begin(x: Float, y: Float) {
        downX = x
        downY = y
        pending = true
    }

    fun move(x: Float, y: Float): Boolean {
        if (abs(x - downX) > slop || abs(y - downY) > slop) pending = false
        return pending
    }

    fun release(x: Float, y: Float): Boolean {
        val tap = move(x, y)
        cancel()
        return tap
    }

    fun cancel() { pending = false }
}
