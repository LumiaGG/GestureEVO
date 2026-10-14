package io.github.lumiagg.gestureevo.gesture.ui

/** The same grid ordering and bounds are used by runtime wheels and setup previews. */
internal fun wheelGridSize(itemCount: Int, columns: Int, diameter: Float, gap: Float): Pair<Float, Float> {
    val count = columns.coerceAtLeast(1)
    val rows = ((itemCount + count - 1) / count).coerceAtLeast(1)
    val cell = diameter + gap
    return (diameter + (count - 1) * cell) to (diameter + (rows - 1) * cell)
}

internal fun wheelGridPositions(
    itemCount: Int, columns: Int, diameter: Float, gap: Float,
    centerX: Float, centerY: Float, width: Float, height: Float, mirror: Boolean
): ArrayList<RingItem> {
    val count = columns.coerceAtLeast(1)
    val rows = ((itemCount + count - 1) / count).coerceAtLeast(1)
    val (gridWidth, gridHeight) = wheelGridSize(itemCount, count, diameter, gap)
    val anchorRight = width > 0f && centerX > width / 2f
    val anchorBottom = height > 0f && centerY > height / 2f
    var startX = if (width > 0f) { if (anchorRight) centerX - gridWidth else centerX } else centerX - gridWidth / 2f
    var startY = if (height > 0f) { if (anchorBottom) centerY - gridHeight else centerY } else centerY - gridHeight / 2f
    if (width > 0f) startX = startX.coerceIn(0f, (width - gridWidth).coerceAtLeast(0f))
    if (height > 0f) startY = startY.coerceIn(0f, (height - gridHeight).coerceAtLeast(0f))
    return ArrayList<RingItem>(itemCount).apply {
        repeat(itemCount) { index ->
            val column = index % count
            val row = index / count
            add(RingItem(startX + (if (mirror) column else count - 1 - column) * (diameter + gap),
                startY + (if (anchorBottom) rows - 1 - row else row) * (diameter + gap)))
        }
    }
}

internal fun calculateFittedGridLayout(
    width: Float, height: Float, itemCount: Int, isLeft: Boolean, verticalPercent: Int,
    diameter: Float, gap: Float, columns: Int, margin: Float, marginTop: Float, marginBottom: Float
): FittedWheelLayout {
    val usableWidth = (width - 2f * margin).coerceAtLeast(1f)
    val usableHeight = (height - marginTop - marginBottom).coerceAtLeast(1f)
    val (gridWidth, gridHeight) = wheelGridSize(itemCount, columns, diameter, gap)
    val scale = minOf(1f, usableWidth / gridWidth.coerceAtLeast(1f), usableHeight / gridHeight.coerceAtLeast(1f))
    val innerY = (height * verticalPercent / 100f - marginTop).coerceIn(0f, usableHeight)
    val positions = wheelGridPositions(itemCount, columns, diameter * scale, gap * scale,
        if (isLeft) 0f else usableWidth, innerY, usableWidth, usableHeight, isLeft)
    val shape = resolveWheelAdaptiveShape(isLeft, verticalPercent)
    return FittedWheelLayout(
        cx = if (isLeft) margin else width - margin, cy = innerY + marginTop,
        startAngle = shape.startAngle, sweepAngle = shape.sweepAngle, quadrant = shape.quadrant,
        isLeft = isLeft, isUpper = shape.isUpper,
        slots = positions.mapIndexed { index, position -> WheelSlot(index, position.x + margin, position.y + marginTop, diameter * scale) },
        ringRadii = emptyList(), maxRadius = 0f, scale = scale
    )
}
