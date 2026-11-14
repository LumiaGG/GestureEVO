package io.github.lumiagg.gestureevo.link.radar

import android.graphics.Rect

/**
 * 屏幕上的链接区域信息（LinkGo model/LinkRegion.kt 1:1 移植）
 */
data class LinkRegion(
    val url: String,
    val rects: List<Rect>,
    val isFromDescription: Boolean = false,
    val groupId: Int = 0,
    val isPrecise: Boolean = false,
    val textOffset: Int = 0,
    val nodeBounds: Rect? = null
)
