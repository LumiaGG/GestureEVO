// Derived from MMGG (com.leaf.hyperdragshare), GPL-3.0; see MMGG-NOTICE and MMGG-LICENSE.
package io.github.lumiagg.gestureevo.drop;

/** Root evdev hold-then-move detection, independent of contact-size reporting. */
public final class PressTriggerDetector {
    private static final float MOVE_LIMIT = 36f;
    private final float dragThreshold;
    private boolean active;
    private boolean armed;
    private boolean fired;
    private float downX;
    private float downY;
    private float lastX;
    private float lastY;
    private float anchorX;
    private float anchorY;
    private long downTime = -1L;
    private long deadline = -1L;
    private long classificationDeadline = -1L;

    public PressTriggerDetector(float dragThreshold) {
        this.dragThreshold = dragThreshold;
    }

    public void start(float x, float y, long time, long confirmMillis) {
        active = true;
        armed = false;
        fired = false;
        lastX = downX = x;
        lastY = downY = y;
        downTime = time;
        deadline = time + confirmMillis;
        classificationDeadline = time + 120L;
    }

    /** MOVE=2 continues one pointer; release, cancellation or a second pointer ends it. */
    public void onPointerEvent(int action, float x, float y, int pointerCount) {
        if (!active) return;
        if (action != 2 || pointerCount != 1) { cancel(); return; }
        lastX = x;
        lastY = y;
        if (fired) return;
        if (armed) {
            float dx = x - anchorX;
            float dy = y - anchorY;
            if (dx * dx + dy * dy > dragThreshold * dragThreshold) {
                fired = true;
                classificationDeadline = -1L;
            }
        } else if (Math.abs(x - downX) > MOVE_LIMIT || Math.abs(y - downY) > MOVE_LIMIT) {
            cancel();
        }
    }

    public boolean takeClassification(long now) {
        if (!active || fired || classificationDeadline < 0L || now < classificationDeadline) return false;
        classificationDeadline = -1L;
        return true;
    }

    public void arm(long now) {
        if (!active || armed || now < deadline) return;
        armed = true;
        anchorX = lastX;
        anchorY = lastY;
    }

    public void cancel() {
        active = false;
        armed = false;
        deadline = -1L;
        classificationDeadline = -1L;
    }

    public boolean isActive() { return active; }
    public boolean isReady() { return active && armed; }
    public boolean isFired() { return fired; }
    public long downTime() { return downTime; }
    public long deadline() { return deadline; }
    public long classificationDeadline() { return classificationDeadline; }
}
