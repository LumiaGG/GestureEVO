package io.github.lumiagg.gestureevo.inspire.ui

import android.view.View
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class EdgeScroller(
    private val scrollView: NestedScrollView,
    private val lifecycleOwner: LifecycleOwner? = null,
    private val config: Config = Config()
) {
    data class Config(
        val edgeScrollThreshold: Float = 0.05f,  // Edge area threshold ratio, default 5%
        val maxScrollSpeed: Float = 1f,          // Maximum scroll speed
        val scrollUpdateDelayMs: Long = 16L,     // Scroll update delay in ms
        val minScrollSpeed: Float = 0.2f         // Minimum scroll speed
    )

    private var edgeScrollJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val density = scrollView.context.resources.displayMetrics.density

    init {
        lifecycleOwner?.lifecycle?.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                cleanup()
            }
        })
    }

    fun handleDrag(rawY: Float, dragView: View) {
        val containerHeight = scrollView.height
        if (containerHeight <= 0) return

        // Convert raw touch coordinates to ScrollView-relative coordinates
        val scrollViewCoords = IntArray(2)
        val dragViewCoords = IntArray(2)
        scrollView.getLocationOnScreen(scrollViewCoords)
        dragView.getLocationOnScreen(dragViewCoords)

        val relativeY = rawY + (dragViewCoords[1] - scrollViewCoords[1])
        val touchRatio = relativeY / containerHeight

        edgeScrollJob?.cancel()

        // Check whether scrolling is possible within bounds
        val canScrollUp = scrollView.canScrollVertically(-1)
        val canScrollDown = scrollView.canScrollVertically(1)

        when {
            // Upper edge region and able to scroll up
            touchRatio < config.edgeScrollThreshold && canScrollUp -> {
                val speed = calculateScrollSpeed(touchRatio, config.edgeScrollThreshold)
                startEdgeScroll(speed, isUpward = true)
            }
            // Lower edge region and able to scroll down
            touchRatio > (1 - config.edgeScrollThreshold) && canScrollDown -> {
                val speed = calculateScrollSpeed(
                    1 - touchRatio,
                    config.edgeScrollThreshold
                )
                startEdgeScroll(speed, isUpward = false)
            }

            else -> stopEdgeScroll()
        }
    }

    fun onDragEnd() {
        stopEdgeScroll()
    }

    fun cleanup() {
        stopEdgeScroll()
        scope.cancel()
    }

    private fun calculateScrollSpeed(distanceRatio: Float, threshold: Float): Float {
        // Non-linear speed calculation for smoother acceleration
        val normalizedRatio = (1 - (distanceRatio / threshold))
        val speedFactor = normalizedRatio * normalizedRatio // Quadratic curve for natural progression

        // Ensure speed stays between minimum and maximum bounds
        return (config.minScrollSpeed +
                (config.maxScrollSpeed - config.minScrollSpeed) * speedFactor) * density
    }

    private fun startEdgeScroll(speed: Float, isUpward: Boolean) {
        edgeScrollJob = scope.launch {
            while (isActive) {
                val scrollAmount = if (isUpward) -speed else speed

                // Check whether scrolling can continue
                if ((isUpward && scrollView.canScrollVertically(-1)) ||
                    (!isUpward && scrollView.canScrollVertically(1))
                ) {
                    scrollView.smoothScrollBy(0, scrollAmount.toInt())
                } else {
                    stopEdgeScroll()
                    break
                }

                delay(config.scrollUpdateDelayMs)
            }
        }
    }

    private fun stopEdgeScroll() {
        edgeScrollJob?.cancel()
        edgeScrollJob = null
    }
}
