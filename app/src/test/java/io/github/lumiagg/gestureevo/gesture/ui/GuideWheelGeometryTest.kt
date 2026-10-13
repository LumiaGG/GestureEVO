package io.github.lumiagg.gestureevo.gesture.ui

import org.junit.Assert.assertEquals
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
