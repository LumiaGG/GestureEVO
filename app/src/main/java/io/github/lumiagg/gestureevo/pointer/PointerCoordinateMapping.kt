package io.github.lumiagg.gestureevo.pointer

import io.github.lumiagg.gestureevo.utils.Point

/** A comparable coordinate space lets a menu reject input after its touch area is resized. */
internal data class PointerCoordinateMapping(
    val screenWidth: Int,
    val screenHeight: Int,
    val touchLeft: Int,
    val touchTop: Int,
    val touchWidth: Int,
    val touchHeight: Int,
) {
    fun map(input: Point, offset: Point): Point = Point(
        rebasePointerPosition(((input.x - touchLeft) / touchWidth).coerceIn(0f, 1f) * screenWidth, offset.x, screenWidth),
        rebasePointerPosition(((input.y - touchTop) / touchHeight).coerceIn(0f, 1f) * screenHeight, offset.y, screenHeight),
    )
}
