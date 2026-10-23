package io.github.lumiagg.gestureevo.utils.icon

import kotlin.math.min

internal data class IconEditGeometry(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val cornerRadius: Float,
)

/** Positions are fractions of half the output size; scale 1 fits the complete source. */
internal fun iconEditGeometry(
    sourceWidth: Int,
    sourceHeight: Int,
    outputWidth: Int,
    outputHeight: Int,
    scale: Float,
    roundPercent: Float,
    offsetX: Float,
    offsetY: Float,
): IconEditGeometry {
    require(sourceWidth > 0 && sourceHeight > 0 && outputWidth > 0 && outputHeight > 0)
    val fit = min(outputWidth.toFloat() / sourceWidth, outputHeight.toFloat() / sourceHeight)
    val width = sourceWidth * fit * scale.coerceIn(0.25f, 3f)
    val height = sourceHeight * fit * scale.coerceIn(0.25f, 3f)
    return IconEditGeometry(
        (outputWidth - width) / 2f + offsetX.coerceIn(-1f, 1f) * outputWidth / 2f,
        (outputHeight - height) / 2f + offsetY.coerceIn(-1f, 1f) * outputHeight / 2f,
        width,
        height,
        min(width, height) * roundPercent.coerceIn(0f, 1f) / 2f,
    )
}
