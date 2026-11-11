// From MMGG / HyperDragShare contributors, GPL-3.0-only; see drop/MMGG-NOTICE.
package io.github.lumiagg.gestureevo.drop;

import static org.junit.Assert.assertEquals;

import android.view.MotionEvent;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class EvdevTouchParserTest {
    @Test
    public void currentFrameAxesSurviveLaterTrackingAndButtonEdges() {
        List<Frame> frames = new ArrayList<>();
        List<Integer> widths = new ArrayList<>();
        EvdevTouchParser parser = new EvdevTouchParser((action, x, y, major, minor, widthMajor) -> {
            frames.add(new Frame(action, x, y, major, minor));
            widths.add(widthMajor);
        });
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_SLOT, 0);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_POSITION_X, 10);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_POSITION_Y, 20);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_TOUCH_MAJOR, 35);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_TOUCH_MINOR, 22);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_WIDTH_MAJOR, 40);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_TRACKING_ID, 42);
        parser.consume(EvdevTouchParser.EV_KEY, EvdevTouchParser.BTN_TOUCH, 1);
        parser.consume(EvdevTouchParser.EV_SYN, EvdevTouchParser.SYN_REPORT, 0);

        assertEquals(Arrays.asList(new Frame(MotionEvent.ACTION_DOWN, 10, 20, 35, 22)), frames);
        assertEquals(Arrays.asList(40), widths);
    }

    @Test
    public void emitsStableDownMoveAndUp() {
        List<Frame> frames = new ArrayList<>();
        EvdevTouchParser parser = new EvdevTouchParser((action, x, y, major, minor, widthMajor) ->
                frames.add(new Frame(action, x, y, major, minor)));

        pointerDown(parser, 0, 42, 10, 20, 18, 9);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_POSITION_X, 12);
        parser.consume(EvdevTouchParser.EV_SYN, EvdevTouchParser.SYN_REPORT, 0);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_TRACKING_ID, -1);
        parser.consume(EvdevTouchParser.EV_SYN, EvdevTouchParser.SYN_REPORT, 0);

        assertEquals(Arrays.asList(
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP), actions(frames));
        assertEquals(new Frame(MotionEvent.ACTION_DOWN, 10, 20, 18, 9), frames.get(0));
        assertEquals(new Frame(MotionEvent.ACTION_MOVE, 12, 20, 18, 9), frames.get(1));
        assertEquals(new Frame(MotionEvent.ACTION_UP, 12, 20, 18, 9), frames.get(2));
    }

    @Test
    public void secondPointerDuringGestureIsIgnoredAndPrimaryKeepsTracking() {
        List<Frame> frames = new ArrayList<>();
        EvdevTouchParser parser = new EvdevTouchParser((a, x, y, major, minor, width) ->
                frames.add(new Frame(a, x, y, major, minor)));

        pointerDown(parser, 0, 42, 10, 20);
        pointerDown(parser, 1, 43, 30, 40);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_SLOT, 0);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_POSITION_X, 12);
        parser.consume(EvdevTouchParser.EV_SYN, EvdevTouchParser.SYN_REPORT, 0);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_SLOT, 1);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_POSITION_X, 80);
        parser.consume(EvdevTouchParser.EV_SYN, EvdevTouchParser.SYN_REPORT, 0);
        pointerUp(parser, 0);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_SLOT, 1);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_POSITION_X, 90);
        parser.consume(EvdevTouchParser.EV_SYN, EvdevTouchParser.SYN_REPORT, 0);
        pointerUp(parser, 1);
        pointerDown(parser, 2, 44, 50, 60);

        assertEquals(Arrays.asList(
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_DOWN), actions(frames));
        assertEquals(12f, frames.get(1).x, 0f);
        assertEquals(50f, frames.get(3).x, 0f);
    }

    @Test
    public void gestureStartingWithTwoPointersIsIgnoredUntilEverySlotEnds() {
        List<Frame> frames = new ArrayList<>();
        EvdevTouchParser parser = new EvdevTouchParser((a, x, y, major, minor, width) ->
                frames.add(new Frame(a, x, y, major, minor)));

        // Both contacts reported before the first SYN: a gesture that starts multi-touch stays ignored.
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_SLOT, 0);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_TRACKING_ID, 1);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_POSITION_X, 10);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_POSITION_Y, 20);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_SLOT, 1);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_TRACKING_ID, 2);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_POSITION_X, 30);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_POSITION_Y, 40);
        parser.consume(EvdevTouchParser.EV_SYN, EvdevTouchParser.SYN_REPORT, 0);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_SLOT, 0);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_POSITION_X, 15);
        parser.consume(EvdevTouchParser.EV_SYN, EvdevTouchParser.SYN_REPORT, 0);
        pointerUp(parser, 0);
        pointerUp(parser, 1);
        pointerDown(parser, 2, 3, 50, 60);

        assertEquals(Arrays.asList(MotionEvent.ACTION_DOWN), actions(frames));
        assertEquals(50f, frames.get(0).x, 0f);
    }

    @Test
    public void missingNewAxisDoesNotReusePreviousContactsPosition() {
        List<Frame> frames = new ArrayList<>();
        EvdevTouchParser parser = new EvdevTouchParser((a, x, y, major, minor, width) -> frames.add(new Frame(a, x, y, major, minor)));
        pointerDown(parser, 0, 1, 10, 20);
        pointerUp(parser, 0);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_TRACKING_ID, 2);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_POSITION_X, 60);
        parser.consume(EvdevTouchParser.EV_SYN, EvdevTouchParser.SYN_REPORT, 0);
        assertEquals(2, frames.size());
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_POSITION_Y, 70);
        parser.consume(EvdevTouchParser.EV_SYN, EvdevTouchParser.SYN_REPORT, 0);
        assertEquals(MotionEvent.ACTION_DOWN, frames.get(2).action);
        assertEquals(70f, frames.get(2).y, 0f);
    }

    @Test
    public void releaseUsesCoordinatesFromTheFinalInputFrame() {
        List<Frame> frames = new ArrayList<>();
        EvdevTouchParser parser = new EvdevTouchParser((a, x, y, major, minor, width) ->
                frames.add(new Frame(a, x, y, major, minor)));
        pointerDown(parser, 0, 1, 10, 20);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_POSITION_X, 60);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_POSITION_Y, 70);
        pointerUp(parser, 0);
        assertEquals(Arrays.asList(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), actions(frames));
        assertEquals(60f, frames.get(1).x, 0f);
        assertEquals(70f, frames.get(1).y, 0f);
    }

    private static List<Integer> actions(List<Frame> frames) {
        List<Integer> actions = new ArrayList<>(frames.size());
        for (Frame frame : frames) {
            actions.add(frame.action);
        }
        return actions;
    }

    private static void pointerDown(
            EvdevTouchParser parser,
            int slot,
            int trackingId,
            int x,
            int y) {
        pointerDown(parser, slot, trackingId, x, y, 0, 0);
    }

    private static void pointerDown(
            EvdevTouchParser parser,
            int slot,
            int trackingId,
            int x,
            int y,
            int touchMajor,
            int touchMinor) {
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_SLOT, slot);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_TRACKING_ID, trackingId);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_POSITION_X, x);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_POSITION_Y, y);
        if (touchMajor != 0) {
            parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_TOUCH_MAJOR, touchMajor);
        }
        if (touchMinor != 0) {
            parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_TOUCH_MINOR, touchMinor);
        }
        parser.consume(EvdevTouchParser.EV_SYN, EvdevTouchParser.SYN_REPORT, 0);
    }

    private static void pointerUp(EvdevTouchParser parser, int slot) {
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_SLOT, slot);
        parser.consume(EvdevTouchParser.EV_ABS, EvdevTouchParser.ABS_MT_TRACKING_ID, -1);
        parser.consume(EvdevTouchParser.EV_SYN, EvdevTouchParser.SYN_REPORT, 0);
    }

    private static final class Frame {
        final int action;
        final float x;
        final float y;
        final int major;
        final int minor;

        Frame(int action, float x, float y, int major, int minor) {
            this.action = action;
            this.x = x;
            this.y = y;
            this.major = major;
            this.minor = minor;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Frame)) {
                return false;
            }
            Frame frame = (Frame) other;
            return action == frame.action
                    && Float.compare(x, frame.x) == 0
                    && Float.compare(y, frame.y) == 0
                    && major == frame.major
                    && minor == frame.minor;
        }

        @Override
        public int hashCode() {
            int result = action;
            result = 31 * result + Float.floatToIntBits(x);
            result = 31 * result + Float.floatToIntBits(y);
            result = 31 * result + major;
            result = 31 * result + minor;
            return result;
        }

        @Override
        public String toString() {
            return "Frame{" + action + "," + x + "," + y + "," + major + "," + minor + "}";
        }
    }
}
