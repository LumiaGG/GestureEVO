// From chenran0408/MMGG and Leaf-lsgtky/HyperDragShare contributors, GPL-3.0-only; see MMGG-NOTICE.
package io.github.lumiagg.gestureevo.drop;

import android.graphics.Rect;

/** Immutable data copied from AccessibilityNodeInfo before pure classification. */
final class AccessibilityNodeSnapshot {
    final Rect bounds;
    final String className;
    final String text;
    final String contentDescription;
    final boolean visible;
    final boolean editable;
    final boolean password;
    final boolean leaf;
    final boolean insideWebView;
    final int depth;
    final int traversalOrder;

    private AccessibilityNodeSnapshot(Builder builder) {
        this.bounds = builder.bounds == null ? new Rect() : new Rect(builder.bounds);
        this.className = builder.className;
        this.text = builder.text;
        this.contentDescription = builder.contentDescription;
        this.visible = builder.visible;
        this.editable = builder.editable;
        this.password = builder.password;
        this.leaf = builder.leaf;
        this.insideWebView = builder.insideWebView;
        this.depth = builder.depth;
        this.traversalOrder = builder.traversalOrder;
    }

    static final class Builder {
        private Rect bounds;
        private String className;
        private String text;
        private String contentDescription;
        private boolean visible = true;
        private boolean editable;
        private boolean password;
        private boolean leaf = true;
        private boolean insideWebView;
        private int depth;
        private int traversalOrder;

        Builder bounds(Rect value) { bounds = value; return this; }
        Builder className(String value) { className = value; return this; }
        Builder text(String value) { text = value; return this; }
        Builder contentDescription(String value) { contentDescription = value; return this; }
        Builder visible(boolean value) { visible = value; return this; }
        Builder editable(boolean value) { editable = value; return this; }
        Builder password(boolean value) { password = value; return this; }
        Builder leaf(boolean value) { leaf = value; return this; }
        Builder insideWebView(boolean value) { insideWebView = value; return this; }
        Builder depth(int value) { depth = value; return this; }
        Builder traversalOrder(int value) { traversalOrder = value; return this; }
        AccessibilityNodeSnapshot build() { return new AccessibilityNodeSnapshot(this); }
    }
}
