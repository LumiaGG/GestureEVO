// Derived from MMGG (com.leaf.hyperdragshare), GPL-3.0; see MMGG-NOTICE and MMGG-LICENSE.
package io.github.lumiagg.gestureevo.drop;

/** Root evdev press timing and movement, independent of contact-size reporting. */
public final class PressTriggerDetector {
    private static final float MOVE_LIMIT = 36f;
    private boolean active;
    private boolean fired;
    private float downX;
    private float downY;
    private long downTime = -1L;
    private long deadline = -1L;
    private long classificationDeadline = -1L;

    public void start(float x, float y, long time, long confirmMillis) {
        active = true;
        fired = false;
        downX = x;
        downY = y;
        downTime = time;
        deadline = time + confirmMillis;
        classificationDeadline = time + 120L;
    }

    /** MOVE=2 continues one pointer; release, cancellation or a second pointer ends it. */
    public void onPointerEvent(int action, float x, float y, int pointerCount) {
        if (!active) return;
        // Movement after confirmation belongs to the image drag, not press qualification.
        if (fired && action == 2 && pointerCount == 1) return;
        if (action != 2 || pointerCount != 1
                || Math.abs(x - downX) > MOVE_LIMIT || Math.abs(y - downY) > MOVE_LIMIT) cancel();
    }

    public boolean takeClassification(long now) {
        if (!active || fired || classificationDeadline < 0L || now < classificationDeadline) return false;
        classificationDeadline = -1L;
        return true;
    }

    public boolean fire(long now) {
        if (!active || fired || now < deadline) return false;
        fired = true;
        classificationDeadline = -1L;
        return true;
    }

    public void cancel() {
        active = false;
        deadline = -1L;
        classificationDeadline = -1L;
    }

    public boolean isActive() { return active; }
    public boolean isFired() { return fired; }
    public long downTime() { return downTime; }
    public long deadline() { return deadline; }
    public long classificationDeadline() { return classificationDeadline; }
}
