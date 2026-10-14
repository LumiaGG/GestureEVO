package io.github.lumiagg.gestureevo.gesture.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideWheelGeometryTest {
    @Test
    fun nativeQuarterWheelFitsSmallScreensAndLandscapeAtExtremeSettings() {
        listOf(320 to 296, 360 to 456, 592 to 200).forEach { (width, height) ->
            listOf(80f, 130f, 200f).forEach { radius ->
                (2..8).forEach { itemCount ->
                    (0..3).forEach { quadrant ->
                        listOf(0f, 120f).forEach { inset ->
                            listOf(0.1f, 0.9f).forEach { vertical ->
                                val left = quadrant == 0 || quadrant == 3
                                val requestedX = if (left) 9f + inset else width - 9f - inset
                                val triggerY = (height * vertical).coerceAtMost(height - 100f) + 50f
                                val source = nativeWheel(radius, 55f, 10f, itemCount, quadrant, left)
                                val scale = guideWheelScale(source, width, height)
                                val fitted = nativeWheel(radius * scale, 55f * scale, 10f * scale, itemCount, quadrant, left)
                                val (centerX, centerY) = guideWheelCenter(
                                    fitted, width, height, requestedX.toInt(), triggerY.toInt()
                                )
                                val detail = "${width}x$height radius=$radius items=$itemCount quadrant=$quadrant inset=$inset vertical=$vertical"
                                assertTrue(detail, fitted.left + centerX >= 0f)
                                assertTrue(detail, fitted.top + centerY >= 0f)
                                assertTrue(detail, fitted.right + centerX <= width)
                                assertTrue(detail, fitted.bottom + centerY <= height)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun roomyScreenKeepsNativeSizeAndPreferredCenter() {
        val bounds = nativeWheel(130f, 55f, 10f, 6, 1, false)
        assertEquals(1f, guideWheelScale(bounds, 1080, 1920), 0f)
        assertEquals(900 to 1200, guideWheelCenter(bounds, 1080, 1920, 900, 1200))
    }

    @Test
    fun boundsUseActualCenterAndOuterItemSizes() {
        val bounds = guideWheelBounds(listOf(RingItem(5f, 10f), RingItem(-100f, -50f)), listOf(200, 55))
        assertEquals(GuideWheelBounds(-100f, -50f, 205f, 210f), bounds)
        assertEquals(GuideWheelBounds(-120f, -80f, 185f, 180f), bounds.relativeTo(20, 30))
    }

    @Test
    fun quadrantForEdgeMatchesCardinalDirections() {
        assertEquals(3, quadrantForEdge(isLeft = true, isUpper = true))
        assertEquals(0, quadrantForEdge(isLeft = true, isUpper = false))
        assertEquals(2, quadrantForEdge(isLeft = false, isUpper = true))
        assertEquals(1, quadrantForEdge(isLeft = false, isUpper = false))
    }

    @Test
    fun distributeConcentricSlotsDistributesItemsAcrossRings() {
        // 1 item: Center only
        val single = distributeConcentricSlots(
            itemCount = 1,
            startAngle = 0f,
            baseRadius = 100f,
            itemDiameter = 40f
        )
        assertEquals(1, single.positions.size)
        assertEquals(-50f, single.positions[0].x, 0.01f)
        assertEquals(-50f, single.positions[0].y, 0.01f)
        assertEquals(100f, single.sizes[0], 0.01f)
        assertEquals(listOf(0), single.ringIndices)
        assertTrue(single.ringRadii.isEmpty())

        // 9 items: 1 center + Ring 1 (3 items) + Ring 2 (5 items)
        val multi = distributeConcentricSlots(
            itemCount = 9,
            startAngle = 0f,
            baseRadius = 100f,
            itemDiameter = 40f
        )
        assertEquals(9, multi.positions.size)
        assertEquals(2, multi.ringRadii.size)
        assertEquals(100f, multi.ringRadii[0], 0.01f)
        assertEquals(100f + 40f * 1.3f, multi.ringRadii[1], 0.01f)
        assertEquals(0, multi.ringIndices[0])
        assertEquals(1, multi.ringIndices[1])
        assertEquals(2, multi.ringIndices.last())
    }

    @Test
    fun resolveWheelAdaptiveShapeAdaptsToTriggerPosition() {
        // Middle of screen -> 180° semicircle
        val leftMid = resolveWheelAdaptiveShape(isLeft = true, verticalPercent = 50)
        assertEquals(270f, leftMid.startAngle, 0.01f)
        assertEquals(180f, leftMid.sweepAngle, 0.01f)
        assertTrue(leftMid.isSemicircle)

        val rightMid = resolveWheelAdaptiveShape(isLeft = false, verticalPercent = 50)
        assertEquals(90f, rightMid.startAngle, 0.01f)
        assertEquals(180f, rightMid.sweepAngle, 0.01f)
        assertTrue(rightMid.isSemicircle)

        // Top corner -> 90° quarter circle
        val leftTop = resolveWheelAdaptiveShape(isLeft = true, verticalPercent = 15)
        assertEquals(270f, leftTop.startAngle, 0.01f)
        assertEquals(90f, leftTop.sweepAngle, 0.01f)
        assertFalse(leftTop.isSemicircle)
        assertTrue(leftTop.isUpper)

        val rightTop = resolveWheelAdaptiveShape(isLeft = false, verticalPercent = 15)
        assertEquals(180f, rightTop.startAngle, 0.01f)
        assertEquals(90f, rightTop.sweepAngle, 0.01f)
        assertFalse(rightTop.isSemicircle)
        assertTrue(rightTop.isUpper)

        // Bottom corner -> 90° quarter circle
        val leftBottom = resolveWheelAdaptiveShape(isLeft = true, verticalPercent = 85)
        assertEquals(0f, leftBottom.startAngle, 0.01f)
        assertEquals(90f, leftBottom.sweepAngle, 0.01f)
        assertFalse(leftBottom.isSemicircle)
        assertFalse(leftBottom.isUpper)

        val rightBottom = resolveWheelAdaptiveShape(isLeft = false, verticalPercent = 85)
        assertEquals(90f, rightBottom.startAngle, 0.01f)
        assertEquals(90f, rightBottom.sweepAngle, 0.01f)
        assertFalse(rightBottom.isSemicircle)
        assertFalse(rightBottom.isUpper)
    }

    @Test
    fun calculateFittedWheelLayoutFitsAnyContainerSizeAndEdge() {
        listOf(true, false).forEach { isLeft ->
            listOf(15, 50, 85).forEach { verticalPercent ->
                listOf(1, 4, 8, 15, 25).forEach { count ->
                    val layout = calculateFittedWheelLayout(
                        width = 400f,
                        height = 300f,
                        itemCount = count,
                        isLeft = isLeft,
                        verticalPercent = verticalPercent,
                        baseRadius = 80f,
                        baseItemDiameter = 40f,
                        baseItemWarpOffset = 5f,
                        margin = 16f
                    )
                    assertEquals(count, layout.slots.size)
                    assertTrue(layout.scale in 0.01f..1.0f)
                    layout.slots.forEach { slot ->
                        assertTrue("slot ${slot.index} left >= 0", slot.x >= 0f)
                        assertTrue("slot ${slot.index} top >= 0", slot.y >= 0f)
                        assertTrue("slot ${slot.index} right <= 400", slot.x + slot.size <= 400f)
                        assertTrue("slot ${slot.index} bottom <= 300", slot.y + slot.size <= 300f)
                    }
                }
            }
        }
    }

    private fun nativeWheel(
        radius: Float, itemDiameter: Float, itemWarpOffset: Float, itemCount: Int, quadrant: Int, mirror: Boolean
    ): GuideWheelBounds {
        val positions = mutableListOf(RingItem(-radius / 2, -radius / 2))
        var remaining = itemCount
        var ringRadius = radius
        var reverse = false
        while (remaining > 0) {
            val ring = Ring(quadrant * 90f, 90f, ringRadius, reverse, itemDiameter, itemWarpOffset)
            if (mirror) ring.items.reverse()
            ring.items.forEach { positions.add(RingItem(it.x - itemDiameter / 2, it.y - itemDiameter / 2)) }
            remaining -= ring.items.size
            ringRadius += (itemDiameter * 1.3).toFloat()
            reverse = !reverse
        }
        return guideWheelBounds(positions, listOf(radius.toInt()) + List(itemCount) { itemDiameter.toInt() })
    }
}
