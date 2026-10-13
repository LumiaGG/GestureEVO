package io.github.lumiagg.gestureevo.gesture.ui

import kotlin.math.ceil
import kotlin.math.floor

internal data class GuideWheelBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun relativeTo(centerX: Int, centerY: Int) = GuideWheelBounds(
        left - centerX, top - centerY, right - centerX, bottom - centerY
    )
}

internal fun guideWheelBounds(positions: List<RingItem>, sizes: List<Int>): GuideWheelBounds {
    require(sizes.isNotEmpty() && positions.size >= sizes.size)
    return GuideWheelBounds(
        sizes.indices.minOf { positions[it].x },
        sizes.indices.minOf { positions[it].y },
        sizes.indices.maxOf { positions[it].x + sizes[it] },
        sizes.indices.maxOf { positions[it].y + sizes[it] }
    )
}

internal fun guideWheelScale(bounds: GuideWheelBounds, width: Int, height: Int): Float = minOf(
    1f,
    (width - 2f).coerceAtLeast(1f) / (bounds.right - bounds.left).coerceAtLeast(1f),
    (height - 2f).coerceAtLeast(1f) / (bounds.bottom - bounds.top).coerceAtLeast(1f)
)

internal fun guideWheelCenter(
    relativeBounds: GuideWheelBounds,
    width: Int,
    height: Int,
    requestedX: Int,
    requestedY: Int
): Pair<Int, Int> {
    val minX = ceil(1f - relativeBounds.left).toInt()
    val minY = ceil(1f - relativeBounds.top).toInt()
    val maxX = floor(width - 1f - relativeBounds.right).toInt().coerceAtLeast(minX)
    val maxY = floor(height - 1f - relativeBounds.bottom).toInt().coerceAtLeast(minY)
    return requestedX.coerceIn(minX, maxX) to requestedY.coerceIn(minY, maxY)
}
