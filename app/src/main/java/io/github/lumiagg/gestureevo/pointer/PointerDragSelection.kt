package io.github.lumiagg.gestureevo.pointer

import io.github.lumiagg.gestureevo.utils.Point
import kotlin.math.abs
import kotlin.math.hypot

/** Selection state is synchronous so release never depends on a pending View.post callback. */
internal class PointerDragSelection(private val dragThreshold: Float, private val returnThreshold: Float) {
    var start = Point(0f, 0f)
        private set
    var end = start
        private set
    var isOverSize = false
        private set
    var isBackToOrigin = false
        private set

    fun begin(start: Point, end: Point = start) {
        this.start = start
        isOverSize = false
        isBackToOrigin = false
        update(end)
    }

    fun shouldUseDragSelection(point: Point): Boolean =
        abs(point.x - start.x.toInt()) >= dragThreshold && abs(point.y - start.y.toInt()) >= dragThreshold

    fun update(point: Point) {
        end = point
        if (!isOverSize) {
            isOverSize = shouldUseDragSelection(point)
        } else {
            isBackToOrigin = hypot((point.x.toInt() - start.x.toInt()).toFloat(),
                (point.y.toInt() - start.y.toInt()).toFloat()) < returnThreshold
        }
    }
}
