package io.github.lumiagg.gestureevo.gesture.config

import android.view.ViewConfiguration
import com.squareup.moshi.JsonClass
import io.github.lumiagg.gestureevo.utils.dp2px

@JsonClass(generateAdapter = true)
data class GestureTimingConfig(
    var longPressTimeoutMs: Int = 0,
    var doubleTapTimeoutMs: Int = 0,
    var pauseTimeoutMs: Int = 0,
    var swipeThresholdDp: Int = 0,
    var backThresholdDp: Int = 0
) {
    fun resolvedLongPressTimeoutMs(): Long {
        return longPressTimeoutMs
            .takeIf { it > 0 }
            ?.coerceIn(MIN_LONG_PRESS_TIMEOUT_MS, MAX_LONG_PRESS_TIMEOUT_MS)
            ?.toLong()
            ?: defaultLongPressTimeoutMs().toLong()
    }

    fun resolvedDoubleTapTimeoutMs(): Long {
        return doubleTapTimeoutMs
            .takeIf { it > 0 }
            ?.coerceIn(MIN_DOUBLE_TAP_TIMEOUT_MS, MAX_DOUBLE_TAP_TIMEOUT_MS)
            ?.toLong()
            ?: defaultDoubleTapTimeoutMs().toLong()
    }

    fun resolvedPauseTimeoutMs(): Long {
        return pauseTimeoutMs
            .takeIf { it > 0 }
            ?.coerceIn(MIN_PAUSE_TIMEOUT_MS, MAX_PAUSE_TIMEOUT_MS)
            ?.toLong()
            ?: DEFAULT_PAUSE_TIMEOUT_MS.toLong()
    }

    fun resolvedSwipeThresholdPx(): Float {
        val dpValue = swipeThresholdDp
            .takeIf { it > 0 }
            ?.coerceIn(MIN_SWIPE_THRESHOLD_DP, MAX_SWIPE_THRESHOLD_DP)
            ?: DEFAULT_SWIPE_THRESHOLD_DP
        return dpValue.dp2px
    }

    fun resolvedBackThresholdPx(): Float {
        val dpValue = backThresholdDp
            .takeIf { it > 0 }
            ?.coerceIn(MIN_BACK_THRESHOLD_DP, MAX_BACK_THRESHOLD_DP)
            ?: DEFAULT_BACK_THRESHOLD_DP
        return dpValue.dp2px
    }

    companion object {
        const val DEFAULT_PAUSE_TIMEOUT_MS = 220
        const val DEFAULT_SWIPE_THRESHOLD_DP = 50
        const val DEFAULT_BACK_THRESHOLD_DP = 35

        const val MIN_LONG_PRESS_TIMEOUT_MS = 200
        const val MAX_LONG_PRESS_TIMEOUT_MS = 1200

        const val MIN_DOUBLE_TAP_TIMEOUT_MS = 120
        const val MAX_DOUBLE_TAP_TIMEOUT_MS = 600

        const val MIN_PAUSE_TIMEOUT_MS = 10
        const val MAX_PAUSE_TIMEOUT_MS = 600

        const val MIN_SWIPE_THRESHOLD_DP = 10
        const val MAX_SWIPE_THRESHOLD_DP = 160

        const val MIN_BACK_THRESHOLD_DP = 10
        const val MAX_BACK_THRESHOLD_DP = 120

        fun defaultLongPressTimeoutMs(): Int {
            return ViewConfiguration.getLongPressTimeout()
                .coerceIn(MIN_LONG_PRESS_TIMEOUT_MS, MAX_LONG_PRESS_TIMEOUT_MS)
        }

        fun defaultDoubleTapTimeoutMs(): Int {
            return ViewConfiguration.getDoubleTapTimeout()
                .coerceIn(MIN_DOUBLE_TAP_TIMEOUT_MS, MAX_DOUBLE_TAP_TIMEOUT_MS)
        }
    }
}
