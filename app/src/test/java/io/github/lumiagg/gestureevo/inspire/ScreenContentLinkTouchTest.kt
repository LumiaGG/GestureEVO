package io.github.lumiagg.gestureevo.inspire

import org.junit.Assert.*
import org.junit.Test

class ScreenContentLinkTouchTest {
    private val touch = ScreenContentLinkTouch(slop = 8f)

    @Test fun shortTapWithinSlopOpensOnce() {
        touch.begin(100f, 200f)
        assertTrue(touch.move(104f, 204f))
        assertTrue(touch.release(105f, 203f))
        assertFalse(touch.release(105f, 203f))
    }

    @Test fun dragYieldsToSelectionAndKeepsOriginalAnchor() {
        touch.begin(100f, 200f)
        assertFalse(touch.move(120f, 220f))
        assertEquals(100f, touch.downX, 0f)
        assertEquals(200f, touch.downY, 0f)
        assertFalse(touch.release(120f, 220f))
    }

    @Test fun returningAfterDragNeverRearmsLink() {
        touch.begin(100f, 200f)
        assertFalse(touch.move(100f, 209f))
        assertFalse(touch.move(100f, 200f))
        assertFalse(touch.release(100f, 200f))
    }

    @Test fun releaseOutsideSlopWithoutMoveEventDoesNotOpen() {
        touch.begin(100f, 200f)
        assertFalse(touch.release(109f, 200f))
    }

    @Test fun stationaryHoldKeepsLinkOwnershipUntilRelease() {
        touch.begin(100f, 200f)
        repeat(20) { assertTrue(touch.move(100f, 200f)) }
        assertTrue(touch.release(100f, 200f))
    }

    @Test fun cancelForMultitouchOrWindowClosePreventsRelease() {
        touch.begin(100f, 200f)
        touch.cancel()
        assertFalse(touch.release(100f, 200f))
    }

    @Test fun newSessionAfterCancellationCanTap() {
        touch.begin(100f, 200f)
        touch.cancel()
        touch.begin(300f, 400f)
        assertTrue(touch.release(300f, 400f))
    }

    @Test fun movementAtThresholdRemainsTap() {
        touch.begin(100f, 200f)
        assertTrue(touch.release(108f, 200f))
    }
}
