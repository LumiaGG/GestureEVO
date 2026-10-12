package io.github.lumiagg.gestureevo.inspire.ui

import android.view.View
import io.github.lumiagg.gestureevo.utils.dp2px

internal object InspireContentLayoutMetrics {
    const val COLLAPSED_PREVIEW_HEIGHT_DP = 150
    const val EXPANDED_PREVIEW_FALLBACK_HEIGHT_DP = 360
    const val BIG_BANG_CONTENT_HEIGHT_DP = 300
    const val IMAGE_ACTION_BUTTONS_HEIGHT_DP = 35
    const val TEXT_ACTION_BUTTONS_TOP_MARGIN_DP = 8

    val COLLAPSED_PREVIEW_HEIGHT_PX: Int get() = COLLAPSED_PREVIEW_HEIGHT_DP.dp2px.toInt()
    val EXPANDED_PREVIEW_FALLBACK_HEIGHT_PX: Int get() = EXPANDED_PREVIEW_FALLBACK_HEIGHT_DP.dp2px.toInt()
    val BIG_BANG_CONTENT_HEIGHT_PX: Int get() = BIG_BANG_CONTENT_HEIGHT_DP.dp2px.toInt()
    val IMAGE_ACTION_BUTTONS_HEIGHT_PX: Int get() = IMAGE_ACTION_BUTTONS_HEIGHT_DP.dp2px.toInt()
    val TOKEN_ACTION_BUTTONS_HEIGHT_PX: Int
        get() = (IMAGE_ACTION_BUTTONS_HEIGHT_DP * 2 + TEXT_ACTION_BUTTONS_TOP_MARGIN_DP).dp2px.toInt()
    val RESERVED_BOTTOM_GAP_PX: Int get() = 8.dp2px.toInt()
}

internal fun View?.measuredOrEstimatedHeight(estimatedHeight: Int): Int {
    return this?.measuredHeight?.takeIf { it > 0 } ?: estimatedHeight
}
