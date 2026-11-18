package io.github.lumiagg.gestureevo.pointer.radar

internal fun enclosingRadarBox(boxes: List<RadarBox>): RadarBox? = boxes.takeIf { it.isNotEmpty() }?.let {
    RadarBox(it.minOf { box -> box.left }, it.minOf { box -> box.top },
        it.maxOf { box -> box.right }, it.maxOf { box -> box.bottom })
}

/** Place persistent actions beside their frames without covering another action or link. */
internal fun placeRadarActions(
    anchors: Map<Int, RadarBox>, obstacles: Map<Int, List<RadarBox>>, viewport: RadarBox,
    actionWidth: Float, actionHeight: Float, gap: Float,
): Map<Int, RadarBox> {
    if (actionWidth <= 0 || actionHeight <= 0 ||
        actionWidth > viewport.right - viewport.left || actionHeight > viewport.bottom - viewport.top) return emptyMap()
    val placed = linkedMapOf<Int, RadarBox>()
    for ((key, anchor) in anchors) {
        if (!anchor.overlaps(viewport)) continue
        val centerX = (anchor.left + anchor.right - actionWidth) / 2
        val centerY = (anchor.top + anchor.bottom - actionHeight) / 2
        val candidates = listOf(
            centerX to anchor.top - gap - actionHeight,
            centerX to anchor.bottom + gap,
            anchor.right + gap to centerY,
            anchor.left - gap - actionWidth to centerY,
        )
        val bounds = candidates.map { (x, y) ->
            val left = x.coerceIn(viewport.left, viewport.right - actionWidth)
            val top = y.coerceIn(viewport.top, viewport.bottom - actionHeight)
            RadarBox(left, top, left + actionWidth, top + actionHeight)
        }.firstOrNull { candidate ->
            obstacles.all { (group, boxes) -> group == key || boxes.none(candidate::overlaps) } &&
                placed.values.none(candidate::overlaps)
        }
        if (bounds != null) placed[key] = bounds
    }
    return placed
}
