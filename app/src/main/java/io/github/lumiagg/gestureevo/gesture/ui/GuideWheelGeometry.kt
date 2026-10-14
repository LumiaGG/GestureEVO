package io.github.lumiagg.gestureevo.gesture.ui

import io.github.lumiagg.gestureevo.gesture.config.ActionViewState
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

internal fun quarterWheelOuterRadius(itemCount: Int, radius: Float, diameter: Float, gap: Float): Pair<Float, Int> {
    var ringRadius = radius
    var remaining = itemCount - 1
    var rings = 0
    repeat(10) {
        if (remaining <= 0) return@repeat
        val ring = Ring(0f, 90f, ringRadius, rings % 2 != 0, diameter, gap)
        remaining -= ring.items.size
        rings++
        ringRadius += diameter * 1.3f
    }
    return (ringRadius + diameter / 2f) to rings
}

internal fun resolveConfiguredWheelShape(isLeft: Boolean, verticalPercent: Int,
    quadrants: List<Boolean>?, cornerThresholdPercent: Float = 25f): WheelAdaptiveShape {
    if (quadrants == null) return resolveWheelAdaptiveShape(isLeft, verticalPercent, cornerThresholdPercent)
    val state = ActionViewState().apply { setAngleByQuadrant(quadrants) }
    return WheelAdaptiveShape(state.startAngle, state.sweepAngle, state.sweepAngle >= 180f,
        verticalPercent < 50, (state.startAngle / 90f).toInt())
}

internal fun resolveWheelAdaptiveShape(isLeft: Boolean, verticalPercent: Int, cornerThresholdPercent: Float = 25f): WheelAdaptiveShape {
    return when {
        verticalPercent < cornerThresholdPercent -> {
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
        verticalPercent > 100f - cornerThresholdPercent -> {
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
    verticalPercent: Int = 50,
    baseRadius: Float = 130f,
    baseItemDiameter: Float = 55f,
    baseItemWarpOffset: Float = 10f,
    margin: Float = 16f,
    marginTop: Float = margin,
    marginBottom: Float = margin,
    shapeOverride: WheelAdaptiveShape? = null,
    mirror: Boolean = isLeft
): FittedWheelLayout {
    val shape = shapeOverride ?: resolveWheelAdaptiveShape(isLeft, verticalPercent)

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
            cy = if (shape.isUpper) marginTop else height - marginBottom,
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

    // Extents relative to origin (0, 0)
    val extentLeft = maxOf(0f, -bounds.left)
    val extentRight = maxOf(0f, bounds.right)
    val extentUp = maxOf(0f, -bounds.top)
    val extentDown = maxOf(0f, bounds.bottom)
    val totalWidth = (bounds.right - bounds.left).coerceAtLeast(1f)
    val totalHeight = (bounds.bottom - bounds.top).coerceAtLeast(1f)

    val usableW = (width - 2f * margin).coerceAtLeast(10f)
    val usableH = (height - marginTop - marginBottom).coerceAtLeast(10f)

    // Optimal scale that fits width and height
    val scaleX = usableW / totalWidth
    val scaleH = usableH / totalHeight
    val fitScale = minOf(1f, scaleX, scaleH)

    // Determine scale and cy with directional distance checks and flexible center
    val scale: Float
    val cy: Float

    if (shape.isSemicircle) {
        val preferredY = (height * (verticalPercent / 100f)).coerceIn(marginTop, height - marginBottom)
        val minCy = marginTop + extentUp * fitScale
        val maxCy = height - marginBottom - extentDown * fitScale

        if (minCy <= maxCy) {
            if (preferredY in minCy..maxCy) {
                scale = fitScale
                cy = preferredY
            } else if (preferredY < minCy) {
                val maxAllowedShift = usableH * 0.10f
                val shift = minOf(minCy - preferredY, maxAllowedShift)
                val targetCy = minCy - shift
                val scaleUp = if (extentUp > 0f) (targetCy - marginTop) / extentUp else fitScale
                scale = minOf(fitScale, scaleUp).coerceAtLeast(0.001f)
                cy = targetCy
            } else {
                val maxAllowedShift = usableH * 0.10f
                val shift = minOf(preferredY - maxCy, maxAllowedShift)
                val targetCy = maxCy + shift
                val scaleDown = if (extentDown > 0f) (height - marginBottom - targetCy) / extentDown else fitScale
                scale = minOf(fitScale, scaleDown).coerceAtLeast(0.001f)
                cy = targetCy
            }
        } else {
            scale = fitScale.coerceAtLeast(0.001f)
            cy = (marginTop + height - marginBottom) / 2f
        }
    } else if (shape.isUpper) {
        scale = fitScale.coerceAtLeast(0.001f)
        cy = marginTop + extentUp * scale
    } else {
        scale = fitScale.coerceAtLeast(0.001f)
        cy = height - marginBottom - extentDown * scale
    }

    val scaledLeft = bounds.left * scale
    val scaledRight = bounds.right * scale
    val cx = if (isLeft) {
        margin - scaledLeft
    } else {
        width - margin - scaledRight
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
