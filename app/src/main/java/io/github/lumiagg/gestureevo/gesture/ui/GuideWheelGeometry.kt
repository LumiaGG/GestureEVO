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

internal data class WheelAdaptiveShape(
    val startAngle: Float,
    val sweepAngle: Float,
    val isSemicircle: Boolean,
    val isUpper: Boolean,
    val quadrant: Int
)

internal fun resolveWheelAdaptiveShape(isLeft: Boolean, verticalPercent: Int): WheelAdaptiveShape {
    return when {
        verticalPercent < 25 -> {
            val quadrant = if (isLeft) 3 else 2
            val startAngle = if (isLeft) 270f else 180f
            WheelAdaptiveShape(
                startAngle = startAngle,
                sweepAngle = 90f,
                isSemicircle = false,
                isUpper = true,
                quadrant = quadrant
            )
        }
        verticalPercent > 75 -> {
            val quadrant = if (isLeft) 0 else 1
            val startAngle = if (isLeft) 0f else 90f
            WheelAdaptiveShape(
                startAngle = startAngle,
                sweepAngle = 90f,
                isSemicircle = false,
                isUpper = false,
                quadrant = quadrant
            )
        }
        else -> {
            val startAngle = if (isLeft) 270f else 90f
            val quadrant = if (isLeft) 3 else 1
            WheelAdaptiveShape(
                startAngle = startAngle,
                sweepAngle = 180f,
                isSemicircle = true,
                isUpper = verticalPercent < 50,
                quadrant = quadrant
            )
        }
    }
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
        relativePositions.add(RingItem(-itemDiameter / 2f, -itemDiameter / 2f))
        relativeSizes.add(itemDiameter)
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
    verticalPercent: Int = 50,
    baseRadius: Float = 130f,
    baseItemDiameter: Float = 55f,
    baseItemWarpOffset: Float = 10f,
    margin: Float = 16f
): FittedWheelLayout {
    val shape = resolveWheelAdaptiveShape(isLeft, verticalPercent)
    val mirror = isLeft

    val distribution = distributeConcentricSlots(
        itemCount = itemCount,
        startAngle = shape.startAngle,
        sweepAngle = shape.sweepAngle,
        baseRadius = baseRadius,
        itemDiameter = baseItemDiameter,
        itemWarpOffset = baseItemWarpOffset,
        mirror = mirror,
        includeCenterItem = true
    )

    if (distribution.positions.isEmpty()) {
        return FittedWheelLayout(
            cx = if (isLeft) margin else width - margin,
            cy = if (shape.isUpper) margin else height - margin,
            startAngle = shape.startAngle,
            sweepAngle = shape.sweepAngle,
            quadrant = shape.quadrant,
            isLeft = isLeft,
            isUpper = shape.isUpper,
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

    val cy = if (shape.isSemicircle) {
        val preferredY = height * (verticalPercent / 100f)
        val minY = margin - scaledTop
        val maxY = height - margin - scaledBottom
        if (minY <= maxY) preferredY.coerceIn(minY, maxY) else height / 2f
    } else if (shape.isUpper) {
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
        startAngle = shape.startAngle,
        sweepAngle = shape.sweepAngle,
        quadrant = shape.quadrant,
        isLeft = isLeft,
        isUpper = shape.isUpper,
        slots = slots,
        ringRadii = scaledRadii,
        maxRadius = maxRadius,
        scale = scale
    )
}
