package io.github.lumiagg.gestureevo.drop;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PressTriggerDetectorTest {
    private final PressTriggerDetector detector = new PressTriggerDetector(8f);

    @Test
    public void readinessRequiresHoldAndEndsOnCancellationOrNextPress() {
        assertFalse(detector.isReady());
        detector.start(100f, 100f, 0L, 500L);
        detector.arm(499L);
        assertFalse(detector.isReady());
        detector.arm(500L);
        assertTrue(detector.isReady());
        assertFalse(detector.isFired());
        detector.cancel();
        assertFalse(detector.isReady());
        detector.arm(1000L);
        assertFalse(detector.isReady());
        detector.start(100f, 100f, 1000L, 500L);
        assertFalse(detector.isReady());
        detector.arm(1500L);
        assertTrue(detector.isReady());
        detector.start(100f, 100f, 2000L, 500L);
        assertFalse(detector.isReady());
    }

    @Test
    public void holdingAndSmallJitterDoNotTrigger() {
        detector.start(100f, 100f, 0L, 500L);
        detector.arm(500L);
        assertFalse(detector.isFired());
        detector.onPointerEvent(2, 104f, 104f, 1);
        detector.arm(2000L);
        assertFalse(detector.isFired());
        assertTrue(detector.isActive());
    }

    @Test
    public void movementAfterHoldTriggersAndDraggingContinues() {
        detector.start(100f, 100f, 0L, 500L);
        detector.arm(500L);
        detector.onPointerEvent(2, 109f, 100f, 1);
        assertTrue(detector.isFired());
        detector.onPointerEvent(2, 500f, 600f, 1);
        assertTrue(detector.isActive());
        assertTrue(detector.isFired());
    }

    @Test
    public void movementUsesDistanceInEveryDirection() {
        float[][] offsets = {{9f, 0f}, {-9f, 0f}, {0f, 9f}, {0f, -9f}, {6f, 6f}};
        for (float[] offset : offsets) {
            detector.start(100f, 100f, 0L, 500L);
            detector.arm(500L);
            detector.onPointerEvent(2, 100f + offset[0], 100f + offset[1], 1);
            assertTrue(detector.isFired());
        }
    }

    @Test
    public void movementBeforeHoldDoesNotCountTowardsActivation() {
        detector.start(100f, 100f, 0L, 500L);
        detector.arm(499L);
        detector.onPointerEvent(2, 120f, 100f, 1);
        assertFalse(detector.isFired());
        detector.arm(500L);
        detector.onPointerEvent(2, 128f, 100f, 1);
        assertFalse(detector.isFired());
        detector.onPointerEvent(2, 129f, 100f, 1);
        assertTrue(detector.isFired());
    }

    @Test
    public void scrollingBeforeHoldCancels() {
        detector.start(100f, 100f, 0L, 500L);
        detector.onPointerEvent(2, 137f, 100f, 1);
        detector.arm(500L);
        assertFalse(detector.isActive());
        assertFalse(detector.isFired());
    }

    @Test
    public void releaseCancelAndMultiplePointersNeverActivate() {
        int[][] events = {{1, 1}, {3, 1}, {2, 2}};
        for (int[] event : events) {
            detector.start(100f, 100f, 0L, 500L);
            detector.arm(500L);
            detector.onPointerEvent(event[0], 200f, 200f, event[1]);
            assertFalse(detector.isActive());
            assertFalse(detector.isFired());
        }
    }

    @Test
    public void newPressResetsActivationAndKeepsClassificationTiming() {
        detector.start(100f, 100f, 0L, 500L);
        assertFalse(detector.takeClassification(119L));
        assertTrue(detector.takeClassification(120L));
        assertFalse(detector.takeClassification(121L));
        detector.arm(500L);
        detector.onPointerEvent(2, 109f, 100f, 1);
        assertTrue(detector.isFired());
        detector.cancel();
        detector.start(50f, 50f, 1000L, 1000L);
        detector.arm(1999L);
        detector.onPointerEvent(2, 59f, 50f, 1);
        assertFalse(detector.isFired());
        detector.arm(2000L);
        detector.onPointerEvent(2, 68f, 50f, 1);
        assertTrue(detector.isFired());
    }
}
