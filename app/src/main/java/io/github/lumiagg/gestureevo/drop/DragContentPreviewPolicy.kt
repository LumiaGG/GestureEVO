package io.github.lumiagg.gestureevo.drop

import kotlin.math.roundToInt

/** A fixed MMGG-style finger offset; clamping never changes the anchor or snaps around the panel. */
internal object DragContentPreviewPolicy {
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)
    data class Point(val x: Int, val y: Int)
    fun summary(text: CharSequence): String = text.take(160).toString().replace(Regex("\\s+"), " ").trim()
        .let { if (text.length > 160) "$it…" else it }

    fun position(x: Float, y: Float, width: Int, height: Int, screen: Box, gap: Int): Point = Point(
        (x - width / 2f).roundToInt().coerceIn(screen.left, (screen.right - width).coerceAtLeast(screen.left)),
        (y - height - gap).roundToInt().coerceIn(screen.top, (screen.bottom - height).coerceAtLeast(screen.top)),
    )
}
