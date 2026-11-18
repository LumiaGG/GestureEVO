package io.github.lumiagg.gestureevo.pointer.radar

import android.graphics.Rect

/** Value snapshot only: no accessibility node escapes the shared reader's lifetime. */
data class LinkRegion(
    val url: String,
    val rects: List<Rect>,
    val isPrecise: Boolean,
    val groupId: Int,
    val textOffset: Int,
    val sourcePackage: String,
    val sourceText: String,
)

/** Match the enclosing frame drawn for a link, including wrapped lines. */
internal fun LinkRegion.frameBounds(): Rect =
    Rect(rects.first()).apply { rects.drop(1).forEach(::union) }

internal fun Rect.toRadarBox(offsetX: Int = 0, offsetY: Int = 0): RadarBox =
    RadarBox((left - offsetX).toFloat(), (top - offsetY).toFloat(),
        (right - offsetX).toFloat(), (bottom - offsetY).toFloat())
