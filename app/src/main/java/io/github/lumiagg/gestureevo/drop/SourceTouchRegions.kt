package io.github.lumiagg.gestureevo.drop

import android.view.View
import android.view.WindowManager
import java.util.Collections
import java.util.WeakHashMap

/** Like MMGG OverlayTouchRegions, prevent presses on EVO windows from reading the app underneath. */
internal object SourceTouchRegions {
    private val views = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    @Synchronized fun attached(view: View) { views.add(view) }
    @Synchronized fun detached(view: View) { views.remove(view) }
    @Suppress("DEPRECATION")
    @Synchronized fun windowIds(): Set<Int> = views.filter { it.isAttachedToWindow }
        .mapNotNull { view ->
            val node = view.createAccessibilityNodeInfo() ?: return@mapNotNull null
            try { node.windowId.takeIf { it >= 0 } } finally { node.recycle() }
        }.toSet()
    @Synchronized fun contains(x: Float, y: Float): Boolean = views.any { view ->
        val params = view.layoutParams as? WindowManager.LayoutParams
        if (!view.isAttachedToWindow || !view.isShown || params == null ||
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0) false
        else {
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            x >= position[0] && y >= position[1] && x < position[0] + view.width && y < position[1] + view.height
        }
    }
}
