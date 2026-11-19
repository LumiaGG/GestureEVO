package io.github.lumiagg.gestureevo.drop

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowInsetsCompat
import io.github.lumiagg.gestureevo.drop.DropPreferences.EXTERNAL_DROP_POSITION_LEFT
import io.github.lumiagg.gestureevo.drop.DropPreferences.EXTERNAL_DROP_POSITION_RIGHT
import io.github.lumiagg.gestureevo.floating.BaseFloatingWindow
import io.github.lumiagg.gestureevo.gesture.ui.RoundImageTextView
import io.github.lumiagg.gestureevo.utils.ScreenUtil
import io.github.lumiagg.gestureevo.utils.dp2px
import kotlinx.coroutines.CoroutineScope

/** Shows the hovered drop action in a separate window so it does not participate in hit testing. */
internal class DragActionPreview(
    private val context: Context,
    scope: CoroutineScope,
    themedContext: Context,
    private val position: String,
    private val insetSource: View,
    title: String,
    windowType: Int = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
) : BaseFloatingWindow(context, scope, "DragActionPreview") {
    private val sidebar = position == EXTERNAL_DROP_POSITION_LEFT || position == EXTERNAL_DROP_POSITION_RIGHT
    private val icon = RoundImageTextView(themedContext).apply {
        alpha = 0f
        setTransparentBackground(true)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val params = WindowManager.LayoutParams().apply {
        configureAccessibilityOverlay(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            width = 100.dp2px.toInt(),
            height = 100.dp2px.toInt(),
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            allowDisplayCutout = sidebar,
            title = title,
        )
        type = windowType
        if (sidebar) {
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
        }
    }

    fun attach(shouldRetry: () -> Boolean) {
        updatePosition()
        attachViewSafely(icon, params, shouldRetry = shouldRetry)
    }

    fun detach() {
        icon.animate().cancel()
        icon.alpha = 0f
        detachViewSafely(icon)
    }

    fun show(bindIcon: (RoundImageTextView) -> Unit) {
        icon.animate().cancel()
        icon.setBadge(null)
        bindIcon(icon)
        icon.animate().alpha(1f).setDuration(FADE_MILLIS).start()
    }

    fun hide() {
        icon.animate().cancel()
        icon.animate().alpha(0f).setDuration(FADE_MILLIS).start()
    }

    @SuppressLint("RtlHardcoded")
    fun updatePosition() {
        if (sidebar) {
            val bounds = windowManager.dropSafeBounds(context, insetSource)
            val horizontalFraction = if (position == EXTERNAL_DROP_POSITION_LEFT) 0.7f else 0.3f
            params.gravity = Gravity.TOP or Gravity.LEFT
            params.x = (bounds.left + bounds.width() * horizontalFraction - params.width / 2f)
                .toInt().coerceIn(bounds.left, (bounds.right - params.width).coerceAtLeast(bounds.left))
            params.y = (bounds.top + bounds.height() * 0.25f - params.height / 2f)
                .toInt().coerceIn(bounds.top, (bounds.bottom - params.height).coerceAtLeast(bounds.top))
        } else {
            params.y = ScreenUtil.getScreenHeight(context) / 3
        }
        updateViewLayoutSafely(icon, params)
    }

    private companion object {
        const val FADE_MILLIS = 150L
    }
}

internal fun WindowManager.dropSafeBounds(context: Context, insetSource: View): Rect {
    val bounds: Rect
    val windowInsets: WindowInsetsCompat?
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val metrics = currentWindowMetrics
        bounds = Rect(metrics.bounds)
        windowInsets = WindowInsetsCompat.toWindowInsetsCompat(metrics.windowInsets)
    } else {
        bounds = Rect(0, 0, ScreenUtil.getScreenWidth(context), ScreenUtil.getScreenHeight(context))
        windowInsets = insetSource.rootWindowInsets?.let {
            WindowInsetsCompat.toWindowInsetsCompat(it, insetSource)
        }
    }
    val insets = windowInsets?.getInsetsIgnoringVisibility(
        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
    )
    if (insets != null) {
        bounds.left += insets.left
        bounds.top += insets.top
        bounds.right -= insets.right
        bounds.bottom -= insets.bottom
    }
    return bounds
}
