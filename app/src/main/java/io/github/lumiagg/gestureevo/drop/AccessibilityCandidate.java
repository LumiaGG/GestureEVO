// From chenran0408/MMGG and Leaf-lsgtky/HyperDragShare contributors, GPL-3.0-only; see MMGG-NOTICE.
package io.github.lumiagg.gestureevo.drop;

import android.graphics.Rect;

/** Candidate selected from an immutable accessibility tree snapshot. */
final class AccessibilityCandidate {
    enum Kind {
        TEXT,
        IMAGE_REGION
    }

    final Kind kind;
    final Rect bounds;
    final String text;
    final boolean strongImage;
    final boolean leaf;
    final int depth;
    final int traversalOrder;

    AccessibilityCandidate(
            Kind kind,
            AccessibilityNodeSnapshot snapshot,
            String text,
            boolean strongImage) {
        this.kind = kind;
        this.bounds = new Rect(snapshot.bounds);
        this.text = text;
        this.strongImage = strongImage;
        this.leaf = snapshot.leaf;
        this.depth = snapshot.depth;
        this.traversalOrder = snapshot.traversalOrder;
    }

    boolean contains(float x, float y) {
        return x >= bounds.left && x <= bounds.right && y >= bounds.top && y <= bounds.bottom;
    }

    long area() {
        return Math.max(0L, (long) bounds.width() * (long) bounds.height());
    }

    boolean isInside(AccessibilityCandidate other) {
        return other != null && other.bounds.contains(bounds);
    }
}
