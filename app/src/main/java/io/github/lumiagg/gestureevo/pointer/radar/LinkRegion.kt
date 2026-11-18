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
