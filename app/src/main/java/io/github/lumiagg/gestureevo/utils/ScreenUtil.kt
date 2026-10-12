package io.github.lumiagg.gestureevo.utils

import android.content.Context
import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.WindowInsets

/**
 * Screen utility class for handling screen-related operations
 */
object ScreenUtil {
    private var screenStatusBarHeight = 0

    data class ScreenDimensions(
        val width: Int,
        val height: Int,
        val isPortrait: Boolean
    )

    fun getScreenDimensions(context: Context): ScreenDimensions {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (windowManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val physicalBounds = windowManager.maximumWindowMetrics.bounds
                val visualBounds = windowManager.currentWindowMetrics.bounds
                val isPortrait = visualBounds.width() < visualBounds.height()
                val minD = minOf(physicalBounds.width(), physicalBounds.height())
                val maxD = maxOf(physicalBounds.width(), physicalBounds.height())
                val w = if (isPortrait) minD else maxD
                val h = if (isPortrait) maxD else minD
                return ScreenDimensions(w, h, isPortrait)
            } else {
                val point = Point()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealSize(point)
                return ScreenDimensions(point.x, point.y, point.x < point.y)
            }
        }
        val dm = context.resources.displayMetrics
        return ScreenDimensions(dm.widthPixels, dm.heightPixels, dm.widthPixels < dm.heightPixels)
    }

    /**
     * Get screen width excluding navigation bar
     */
    fun getScreenWidth(context: Context): Int = getScreenDimensions(context).width

    /**
     * Get screen height excluding navigation bar
     */
    fun getScreenHeight(context: Context): Int = getScreenDimensions(context).height

    /**
     * Get real screen width including navigation bar
     */
    fun getScreenRealWidth(context: Context, useCache: Boolean = false): Int =
        getScreenDimensions(context).width

    /**
     * Get real screen height including navigation bar
     */
    fun getScreenRealHeight(context: Context, useCache: Boolean = false): Int =
        getScreenDimensions(context).height

    /**
     * Get status bar height
     */
    fun getStatusBarHeight(context: Context, useCache: Boolean = true): Int {
        if (!useCache || screenStatusBarHeight == 0) {
            screenStatusBarHeight = getStatusBarHeightInternal(context)
        }
        return screenStatusBarHeight
    }

    /**
     * Check if the coordinate is on the left side of the screen
     */
    fun isScreenLeft(context: Context, x: Int): Boolean =
        x <= getScreenWidth(context) / 2

    /**
     * Get display context for the external display
     */
    fun getDisplayContext(context: Context): Context =
        context.createDisplayContext(getExternalDisplay(context))

    /**
     * Get display orientation
     */
    fun getDisplayOrientation(context: Context): Int {
        return if (getScreenDimensions(context).isPortrait) {
            Configuration.ORIENTATION_PORTRAIT
        } else {
            Configuration.ORIENTATION_LANDSCAPE
        }
    }



    private fun getScreenRealWidthInternal(context: Context): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val point = Point()
        windowManager.defaultDisplay.getRealSize(point)
        return point.x
    }

    private fun getScreenRealHeightInternal(context: Context): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val point = Point()
        windowManager.defaultDisplay.getRealSize(point)
        return point.y
    }

    private fun getStatusBarHeightInternal(context: Context): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = context.getSystemService(WindowManager::class.java)
            return manager.maximumWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
        }
        return getLegacyStatusBarHeight(context)
    }

    // Before API 30 a service has no WindowMetrics or decor view to obtain insets from.
    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    private fun getLegacyStatusBarHeight(context: Context): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            context.resources.getDimensionPixelSize(resourceId)
        } else 0
    }

    private fun getExternalDisplay(context: Context): Display {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.displays
        return displays[displays.size - 1]
    }
}
