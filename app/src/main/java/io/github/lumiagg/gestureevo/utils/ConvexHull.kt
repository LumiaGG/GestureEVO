package io.github.lumiagg.gestureevo.utils

import kotlin.math.atan2

data class Point(val x: Float, val y: Float)

// Determine orientation of ordered triplet (p, q, r):
// 0 -> collinear, 1 -> clockwise, 2 -> counterclockwise.
private fun orientation(p: Point, q: Point, r: Point): Int {
    val value = (q.y - p.y) * (r.x - q.x) - (q.x - p.x) * (r.y - q.y)
    return when {
        value == 0f -> 0  // Collinear
        value > 0 -> 1    // Clockwise
        else -> 2         // Counterclockwise
    }
}

// Computes the convex hull using Graham scan algorithm.
fun computeConvexHull(points: List<Point>): List<Point> {
    if (points.size < 3) return points

    val start = points.minByOrNull { it.y } ?: return emptyList()
    val sortedPoints = points.filter { it != start }.sortedWith(
        compareBy { atan2((it.y - start.y).toDouble(), (it.x - start.x).toDouble()) })

    val hull = mutableListOf(start)
    for (point in sortedPoints) {
        while (hull.size >= 2 && orientation(hull[hull.size - 2], hull.last(), point) != 2) {
            hull.removeAt(hull.size - 1)
        }
        hull.add(point)
    }
    return hull
}

// Expands the convex hull outward by the given margin.
fun expandConvexHull(convexHull: List<Point>, margin: Float): List<Point> {
    if (convexHull.size < 3) return convexHull

    val expanded = mutableListOf<Point>()

    for (i in convexHull.indices) {
        val prev = convexHull[(i - 1 + convexHull.size) % convexHull.size]
        val current = convexHull[i]
        val next = convexHull[(i + 1) % convexHull.size]

        // Calculate unit vectors for the two adjacent edges of the current point
        val dx1 = current.x - prev.x
        val dy1 = current.y - prev.y
        val len1 = kotlin.math.sqrt(dx1 * dx1 + dy1 * dy1)

        val dx2 = next.x - current.x
        val dy2 = next.y - current.y
        val len2 = kotlin.math.sqrt(dx2 * dx2 + dy2 * dy2)

        // Calculate two normal vectors (rotated 90 degrees clockwise)
        val nx1 = dy1 / len1
        val ny1 = -dx1 / len1

        val nx2 = dy2 / len2
        val ny2 = -dx2 / len2

        // Calculate average normal vector
        val nx = (nx1 + nx2) / 2
        val ny = (ny1 + ny2) / 2

        // Normalize the average normal vector
        val nLen = kotlin.math.sqrt(nx * nx + ny * ny)

        // Expand outward
        val expandedX = current.x + margin * nx / nLen
        val expandedY = current.y + margin * ny / nLen

        expanded.add(Point(expandedX, expandedY))
    }

    return expanded
}

// Checks whether a point is inside a polygon using ray casting.
fun isPointInPolygon(point: Point, polygon: List<Point>): Boolean {
    if (polygon.size < 3) return false

    var inside = false
    var j = polygon.size - 1

    for (i in polygon.indices) {
        if ((polygon[i].y > point.y) != (polygon[j].y > point.y) &&
            point.x < (polygon[j].x - polygon[i].x) * (point.y - polygon[i].y) /
            (polygon[j].y - polygon[i].y) + polygon[i].x
        ) {
            inside = !inside
        }
        j = i
    }

    return inside
}
