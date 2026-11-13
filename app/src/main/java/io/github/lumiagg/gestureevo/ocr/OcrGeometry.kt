package io.github.lumiagg.gestureevo.ocr

import kotlin.math.ceil
import kotlin.math.floor

/** Geometry shared by the built-in and ColorOS OCR result conversion paths. */
internal object OcrGeometry {
    /**
     * Outer bounding rect of points, floor/ceil expanded and clamped to the image, matching
     * the conversion the built-in engine has always used. Returns null for empty or
     * degenerate (zero-area) point sets.
     */
    fun boundingRect(
        xs: DoubleArray,
        ys: DoubleArray,
        imageWidth: Int,
        imageHeight: Int,
    ): IntArray? {
        if (xs.isEmpty() || ys.isEmpty() || xs.size != ys.size) return null
        val left = floor(xs.min()).toInt().coerceIn(0, imageWidth)
        val right = ceil(xs.max()).toInt().coerceIn(0, imageWidth)
        val top = floor(ys.min()).toInt().coerceIn(0, imageHeight)
        val bottom = ceil(ys.max()).toInt().coerceIn(0, imageHeight)
        return if (right > left && bottom > top) intArrayOf(left, top, right, bottom) else null
    }
}
