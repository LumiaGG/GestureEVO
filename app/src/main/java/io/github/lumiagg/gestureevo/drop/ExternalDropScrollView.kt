package io.github.lumiagg.gestureevo.drop

import android.content.Context
import android.util.AttributeSet
import android.view.DragEvent
import android.widget.ScrollView

/** Keeps targets reachable when large fonts or a short display exceed the minimum panel height. */
class ExternalDropScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {
    var onDragPositionChanged: ((DragEvent) -> Unit)? = null

    private var direction = 0
    private val edgeSize = (24 * resources.displayMetrics.density).toInt()
    private val step = (6 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private val autoScroll = object : Runnable {
        override fun run() {
            if (direction == 0 || !canScrollVertically(direction)) return
            scrollBy(0, direction * step)
            postDelayed(this, 32)
        }
    }

    override fun dispatchDragEvent(event: DragEvent): Boolean {
        onDragPositionChanged?.invoke(event)
        when (event.action) {
            DragEvent.ACTION_DRAG_LOCATION -> {
                val nextDirection = when {
                    event.y < edgeSize && canScrollVertically(-1) -> -1
                    event.y > height - edgeSize && canScrollVertically(1) -> 1
                    else -> 0
                }
                if (nextDirection != direction) {
                    stopAutoScroll()
                    direction = nextDirection
                    if (direction != 0) postDelayed(autoScroll, 400)
                }
            }
            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED, DragEvent.ACTION_DROP -> stopAutoScroll()
        }
        return super.dispatchDragEvent(event)
    }

    private fun stopAutoScroll() {
        removeCallbacks(autoScroll)
        direction = 0
    }

    override fun onDetachedFromWindow() {
        stopAutoScroll()
        super.onDetachedFromWindow()
    }
}
