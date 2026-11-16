package io.github.lumiagg.gestureevo.link.radar

import kotlin.math.hypot

internal data class RadarBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun distance(x: Float, y: Float): Float = hypot(maxOf(left - x, 0f, x - right), maxOf(top - y, 0f, y - bottom))
    fun overlaps(other: RadarBox): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}

internal data class RadarHitArea(val index: Int, val group: Int, val precise: Boolean, val boxes: List<RadarBox>)

/** Geometry only; callers own pointer sessions, drawing and dispatch. */
internal fun findRadarTargets(
    areas: List<RadarHitArea>, x: Float, y: Float, radius: Float,
    previous: Int? = null, hysteresis: Float = 0f,
): List<Int> {
    val nearby = areas.mapNotNull { area ->
        area.boxes.minOfOrNull { it.distance(x, y) }?.takeIf { it <= radius }?.let { area to it }
    }
    val precise = nearby.filter { it.first.precise }
    val candidates = (precise.ifEmpty { nearby }).sortedBy { it.second }
    val closest = candidates.firstOrNull() ?: return emptyList()
    val primary = candidates.firstOrNull { it.first.index == previous && it.second - closest.second < hysteresis }
        ?.first ?: closest.first
    return if (primary.precise) {
        precise.filter { (area, _) -> area.index == primary.index ||
            area.boxes.any { box -> primary.boxes.any(box::overlaps) }
        }.map { it.first.index }
    } else {
        areas.filter { !it.precise && it.group == primary.group }.map { it.index }
    }
}
