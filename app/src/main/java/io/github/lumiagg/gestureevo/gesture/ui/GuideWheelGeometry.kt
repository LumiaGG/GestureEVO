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

internal fun guideWheelBounds(positions: List<RingItem>, sizes: List<Number>): GuideWheelBounds {
    require(sizes.isNotEmpty() && positions.size >= sizes.size)
    return GuideWheelBounds(
        sizes.indices.minOf { positions[it].x },
        sizes.indices.minOf { positions[it].y },
        sizes.indices.maxOf { positions[it].x + sizes[it].toFloat() },
        sizes.indices.maxOf { positions[it].y + sizes[it].toFloat() }
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

internal fun quadrantForEdge(isLeft: Boolean, isUpper: Boolean): Int = when {
    isLeft && isUpper -> 3
    isLeft && !isUpper -> 0
    !isLeft && isUpper -> 2
    else -> 1
}

internal data class WheelSlot(
    val index: Int,
    val x: Float,
    val y: Float,
    val size: Float,
    val centerX: Float = x + size / 2f,
    val centerY: Float = y + size / 2f,
    val isCenterItem: Boolean = false,
    val ringIndex: Int = 0
)

internal data class RelativeWheelDistribution(
    val positions: List<RingItem>,
    val sizes: List<Float>,
    val ringRadii: List<Float>,
    val ringIndices: List<Int>
)

internal data class FittedWheelLayout(
    val cx: Float,
    val cy: Float,
    val startAngle: Float,
    val sweepAngle: Float,
    val quadrant: Int,
    val isLeft: Boolean,
    val isUpper: Boolean,
    val slots: List<WheelSlot>,
    val ringRadii: List<Float>,
    val maxRadius: Float,
    val scale: Float
)

internal fun distributeConcentricSlots(
    itemCount: Int,
    startAngle: Float,
    sweepAngle: Float = 90f,
    baseRadius: Float,
    itemDiameter: Float,
    itemWarpOffset: Float = 0f,
    mirror: Boolean = false,
    includeCenterItem: Boolean = true
): RelativeWheelDistribution {
    val relativePositions = mutableListOf<RingItem>()
    val relativeSizes = mutableListOf<Float>()
    val unscaledRadii = mutableListOf<Float>()
    val ringIndices = mutableListOf<Int>()

    var remaining = itemCount

    if (includeCenterItem && remaining > 0) {
        relativePositions.add(RingItem(-baseRadius / 2f, -baseRadius / 2f))
        relativeSizes.add(baseRadius)
        ringIndices.add(0)
        remaining--
    }

    var ringRadius = baseRadius
    var reverse = false
    var ringNumber = 1

    while (remaining > 0) {
        val ring = Ring(
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            radius = ringRadius,
            reverse = reverse,
            itemDiameter = itemDiameter,
            itemWarpOffset = itemWarpOffset
        )
        if (ring.items.isEmpty()) {
            break
        }
        if (mirror) {
            ring.items.reverse()
        }
        unscaledRadii.add(ringRadius)
        val takeCount = minOf(remaining, ring.items.size)
        for (i in 0 until takeCount) {
            val item = ring.items[i]
            relativePositions.add(RingItem(item.x - itemDiameter / 2f, item.y - itemDiameter / 2f))
            relativeSizes.add(itemDiameter)
            ringIndices.add(ringNumber)
        }
        remaining -= takeCount
        ringRadius += (itemDiameter * 1.3f)
        reverse = !reverse
        ringNumber++
    }

    return RelativeWheelDistribution(
        positions = relativePositions,
        sizes = relativeSizes,
        ringRadii = unscaledRadii,
        ringIndices = ringIndices
    )
}

internal fun calculateFittedWheelLayout(
    width: Float,
    height: Float,
    itemCount: Int,
    isLeft: Boolean,
    isUpper: Boolean,
    baseRadius: Float,
    baseItemDiameter: Float,
    baseItemWarpOffset: Float = 0f,
    margin: Float = 16f
): FittedWheelLayout {
    val quadrant = quadrantForEdge(isLeft, isUpper)
    val mirror = isLeft
    val startAngle = quadrant * 90f
    val sweepAngle = 90f

    val distribution = distributeConcentricSlots(
        itemCount = itemCount,
        startAngle = startAngle,
        sweepAngle = sweepAngle,
        baseRadius = baseRadius,
        itemDiameter = baseItemDiameter,
        itemWarpOffset = baseItemWarpOffset,
        mirror = mirror,
        includeCenterItem = true
    )

    if (distribution.positions.isEmpty()) {
        return FittedWheelLayout(
            cx = if (isLeft) margin else width - margin,
            cy = if (isUpper) margin else height - margin,
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            quadrant = quadrant,
            isLeft = isLeft,
            isUpper = isUpper,
            slots = emptyList(),
            ringRadii = emptyList(),
            maxRadius = baseRadius,
            scale = 1f
        )
    }

    val bounds = guideWheelBounds(distribution.positions, distribution.sizes)
    val usableW = (width - 2f * margin).coerceAtLeast(100f)
    val usableH = (height - 2f * margin).coerceAtLeast(100f)
    val boundsW = (bounds.right - bounds.left).coerceAtLeast(1f)
    val boundsH = (bounds.bottom - bounds.top).coerceAtLeast(1f)
    val scale = minOf(1f, usableW / boundsW, usableH / boundsH)

    val scaledLeft = bounds.left * scale
    val scaledTop = bounds.top * scale
    val scaledRight = bounds.right * scale
    val scaledBottom = bounds.bottom * scale

    val cx = if (isLeft) {
        margin - scaledLeft
    } else {
        width - margin - scaledRight
    }

    val cy = if (isUpper) {
        margin - scaledTop
    } else {
        height - margin - scaledBottom
    }

    val slots = distribution.positions.indices.map { i ->
        val size = distribution.sizes[i] * scale
        val slotX = cx + distribution.positions[i].x * scale
        val slotY = cy + distribution.positions[i].y * scale
        WheelSlot(
            index = i,
            x = slotX,
            y = slotY,
            size = size,
            centerX = slotX + size / 2f,
            centerY = slotY + size / 2f,
            isCenterItem = (i == 0),
            ringIndex = distribution.ringIndices[i]
        )
    }

    val scaledRadii = distribution.ringRadii.map { it * scale }
    val maxRadius = (distribution.ringRadii.lastOrNull() ?: baseRadius) * scale + (baseItemDiameter * scale * 0.8f)

    return FittedWheelLayout(
        cx = cx,
        cy = cy,
        startAngle = startAngle,
        sweepAngle = sweepAngle,
        quadrant = quadrant,
        isLeft = isLeft,
        isUpper = isUpper,
        slots = slots,
        ringRadii = scaledRadii,
        maxRadius = maxRadius,
        scale = scale
    )
}
