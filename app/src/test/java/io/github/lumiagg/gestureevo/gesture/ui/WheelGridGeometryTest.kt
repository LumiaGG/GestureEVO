package io.github.lumiagg.gestureevo.gesture.ui

import org.junit.Assert.*
import org.junit.Test

class WheelGridGeometryTest {
    @Test fun fittedGridsKeepEverySlotInsideLandscapeAndPortraitCards() {
        listOf(180f to 120f, 360f to 600f, 600f to 180f).forEach { (width, height) ->
            listOf(1, 2, 8).forEach { columns ->
                listOf(1, 7, 40).forEach { count ->
                    listOf(false, true).forEach { left ->
                        listOf(0, 50, 100).forEach { percent ->
                            val layout = calculateFittedGridLayout(width, height, count, left, percent, 55f, 10f, columns, 12f, 24f, 20f)
                            assertEquals(count, layout.slots.size)
                            assertTrue(layout.scale > 0f && layout.scale <= 1f)
                            layout.slots.forEach { slot ->
                                assertTrue(slot.x >= 11.99f)
                                assertTrue(slot.y >= 23.99f)
                                assertTrue(slot.x + slot.size <= width - 11.99f)
                                assertTrue(slot.y + slot.size <= height - 19.99f)
                                assertFalse(slot.isCenterItem)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun gridOrderingMirrorsHorizontallyAndAnchorsAtBottom() {
        val right = wheelGridPositions(6, 3, 20f, 5f, 100f, 100f, 200f, 200f, false)
        val left = wheelGridPositions(6, 3, 20f, 5f, 100f, 100f, 200f, 200f, true)
        assertTrue(right[0].x > right[1].x)
        assertTrue(left[0].x < left[1].x)
        val bottom = wheelGridPositions(6, 3, 20f, 5f, 180f, 180f, 200f, 200f, false)
        assertTrue(bottom[0].y > bottom[3].y)
        assertEquals(70f to 45f, wheelGridSize(6, 3, 20f, 5f))
    }
}
