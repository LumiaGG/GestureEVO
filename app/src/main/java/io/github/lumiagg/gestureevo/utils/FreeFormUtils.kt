import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import io.github.lumiagg.gestureevo.gesture.TriggerSide
import io.github.lumiagg.gestureevo.settings.FreeFormSettingsActivity
import io.github.lumiagg.gestureevo.settings.FreeFormSettingsActivity.Companion.KEY_FREEFORM_FOLLOW_TRIGGER_SIDE
import io.github.lumiagg.gestureevo.utils.Rom
import io.github.lumiagg.gestureevo.utils.ScreenUtil
import io.github.lumiagg.gestureevo.utils.dp2px
import io.github.lumiagg.gestureevo.utils.readKey


object FreeFormUtils {
    // 20mm safe margin converted to dp (20mm / 25.4mm/in * 160dp/in ≈ 126dp)
    const val MIN_BOTTOM_MARGIN_DP = 126

    suspend fun getRect(
        context: Context,
        settingsStore: DataStore<Preferences>,
        forcePortrait: Boolean? = null
    ): Rect {
        val statusBarHeight = ScreenUtil.getStatusBarHeight(context, false)
        val (screenWidth, screenHeight, isPortraitReal) = ScreenUtil.getScreenDimensions(context)
        val isPortrait = forcePortrait ?: isPortraitReal
        val orientation = if (isPortrait) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE

        val followTrigger = settingsStore.readKey(KEY_FREEFORM_FOLLOW_TRIGGER_SIDE, false)
        val minBottomMarginPx = MIN_BOTTOM_MARGIN_DP.dp2px.toInt()
        val targetBottomMargin = maxOf(statusBarHeight, minBottomMarginPx)

        // Constrain width: use configured width when space is sufficient, ensuring neither edge exceeds screen bounds.
        val maxAllowedW = screenWidth.coerceAtLeast(100)
        val w = getWidth(orientation, settingsStore).dp2px.toInt().coerceIn(100, maxAllowedW)

        // Constrain height and bottom margin: keep freeform window bottom > 20mm (≈ 126dp) above screen bottom when height allows.
        val availableVerticalSpace = (screenHeight - statusBarHeight).coerceAtLeast(100)
        val rawH = getHeight(orientation, settingsStore).dp2px.toInt()
        val bottomMargin = if (availableVerticalSpace >= rawH + targetBottomMargin) {
            targetBottomMargin
        } else {
            (screenHeight - statusBarHeight - rawH).coerceAtLeast(0)
        }
        val maxAllowedH = (screenHeight - statusBarHeight - bottomMargin).coerceAtLeast(100)
        val h = rawH.coerceIn(100, maxAllowedH)

        val left = resolveLeft(orientation, settingsStore, w, screenWidth, followTrigger)
        val top = resolveTop(orientation, settingsStore, h, screenHeight, statusBarHeight, bottomMargin, followTrigger)
        Log.d("FreeFormUtils", "getRect: left=$left, top=$top, w=$w, h=$h, followTrigger=$followTrigger, isPortrait=$isPortrait")

        if (Rom.isMiui13Plus(context)) {
            val compensation = Rom.calculateMiuiCompensation(context, screenWidth, screenHeight, isPortrait)
            val launchW = (w * compensation).toInt().coerceAtLeast(100)
            val launchH = (h * compensation).toInt().coerceAtLeast(100)
            // MIUI scales windows anchored at top-left, so targetLeft and targetTop must serve as the start bounds.
            return Rect(left, top, left + launchW, top + launchH)
        }

        val bounds = Rect(left, top, left + w, top + h)
        return fitRectOnScreen(bounds, screenWidth, screenHeight, statusBarHeight, bottomMargin)
    }

    suspend fun getWidth(orientation: Int, settingsStore: DataStore<Preferences>): Int {
        val defaultValue = if (orientation == Configuration.ORIENTATION_PORTRAIT) 230 else 200
        return settingsStore.readKey(
            FreeFormSettingsActivity.getSettingsKey(
                FreeFormSettingsActivity.KEY_WIDTH, orientation
            ), defaultValue
        )
    }

    suspend fun getHeight(orientation: Int, settingsStore: DataStore<Preferences>): Int {
        val defaultValue = if (orientation == Configuration.ORIENTATION_PORTRAIT) 380 else 300
        return settingsStore.readKey(
            FreeFormSettingsActivity.getSettingsKey(
                FreeFormSettingsActivity.KEY_HEIGHT, orientation
            ), defaultValue
        )
    }

    suspend fun getLeft(orientation: Int, settingsStore: DataStore<Preferences>): Int {
        val defaultValue = if (orientation == Configuration.ORIENTATION_PORTRAIT) 70 else 550
        return settingsStore.readKey(
            FreeFormSettingsActivity.getSettingsKey(
                FreeFormSettingsActivity.KEY_LEFT_MARGIN, orientation
            ), defaultValue
        )
    }

    suspend fun getEdgeMargin(orientation: Int, settingsStore: DataStore<Preferences>): Int? {
        val key = FreeFormSettingsActivity.getSettingsKey(
            FreeFormSettingsActivity.KEY_EDGE_MARGIN, orientation
        )
        val value = settingsStore.readKey(key, -1)
        return if (value >= 0) value else null
    }

    private suspend fun calculateEdgeMargin(
        orientation: Int,
        settingsStore: DataStore<Preferences>,
        rawSavedLeft: Int,
        widthPx: Int,
        screenWidth: Int,
        maxLeft: Int,
    ): Int {
        val savedEdgeMargin = getEdgeMargin(orientation, settingsStore)
        if (savedEdgeMargin != null) {
            return savedEdgeMargin.dp2px.toInt()
        }

        val isPortrait = orientation == Configuration.ORIENTATION_PORTRAIT
        val defaultPortraitLeft = 70.dp2px.toInt()
        val defaultLandscapeLeft = 550.dp2px.toInt()
        val defaultLandscapeEdgeMargin = 90.dp2px.toInt()

        // Under default configuration, use the preset symmetric margins directly.
        if (isPortrait && rawSavedLeft == defaultPortraitLeft) {
            return defaultPortraitLeft
        }
        if (!isPortrait && rawSavedLeft == defaultLandscapeLeft) {
            return defaultLandscapeEdgeMargin
        }

        // If the saved left margin is valid within current screen bounds, pick the margin closer to an edge.
        if (rawSavedLeft <= maxLeft) {
            val rightInset = (screenWidth - rawSavedLeft - widthPx).coerceAtLeast(0)
            return minOf(rawSavedLeft, rightInset)
        }

        // If saved left margin exceeds current screen (e.g. folded out from a foldable device), fallback safely.
        val fallbackMargin = if (isPortrait) defaultPortraitLeft else defaultLandscapeEdgeMargin
        return minOf(fallbackMargin, maxLeft / 2)
    }

    private suspend fun resolveLeft(
        orientation: Int,
        settingsStore: DataStore<Preferences>,
        widthPx: Int,
        screenWidth: Int,
        followTrigger: Boolean,
    ): Int {
        val maxLeft = (screenWidth - widthPx).coerceAtLeast(0)
        val rawSavedLeft = getLeft(orientation, settingsStore).dp2px.toInt()
        val savedLeft = rawSavedLeft.coerceIn(0, maxLeft)
        if (!followTrigger) return savedLeft
        val side = TriggerSide.peek()
            ?: (if (TriggerSide.peekLocation()?.isLeft == true) TriggerSide.LEFT else if (TriggerSide.peekLocation()?.isRight == true) TriggerSide.RIGHT else null)
            ?: return savedLeft

        val edgeMargin = calculateEdgeMargin(
            orientation,
            settingsStore,
            rawSavedLeft,
            widthPx,
            screenWidth,
            maxLeft
        )

        return if (side == TriggerSide.LEFT) {
            edgeMargin.coerceIn(0, maxLeft)
        } else {
            (screenWidth - widthPx - edgeMargin).coerceIn(0, maxLeft)
        }
    }

    private suspend fun resolveTop(
        orientation: Int,
        settingsStore: DataStore<Preferences>,
        heightPx: Int,
        screenHeight: Int,
        statusBarHeight: Int,
        bottomMargin: Int,
        followTrigger: Boolean,
    ): Int {
        val maxTop = (screenHeight - heightPx - bottomMargin).coerceAtLeast(statusBarHeight)
        val defaultTop = (getTop(orientation, settingsStore).dp2px.toInt() + statusBarHeight)
            .coerceIn(statusBarHeight, maxTop)
        if (!followTrigger) return defaultTop

        val location = TriggerSide.peekLocation()
        if (location == null || location.y <= 0f) {
            return defaultTop
        }

        // Align the window centered vertically with the trigger position, clamped within visible screen bounds.
        val targetTop = (location.y - heightPx / 2f).toInt()
        return targetTop.coerceIn(statusBarHeight, maxTop)
    }

    private fun fitRectOnScreen(
        rect: Rect,
        screenWidth: Int,
        screenHeight: Int,
        statusBarHeight: Int,
        bottomMargin: Int,
    ): Rect {
        val width = rect.width().coerceIn(1, screenWidth.coerceAtLeast(1))
        val maxAllowedH = (screenHeight - statusBarHeight - bottomMargin).coerceAtLeast(100)
        val height = rect.height().coerceIn(1, maxAllowedH)
        val left = rect.left.coerceIn(0, (screenWidth - width).coerceAtLeast(0))
        val maxTop = (screenHeight - height - bottomMargin).coerceAtLeast(statusBarHeight)
        val top = rect.top.coerceIn(statusBarHeight, maxTop)
        return Rect(left, top, left + width, top + height)
    }

    suspend fun getTop(orientation: Int, settingsStore: DataStore<Preferences>): Int {
        val defaultValue = if (orientation == Configuration.ORIENTATION_PORTRAIT) 250 else 30
        return settingsStore.readKey(
            FreeFormSettingsActivity.getSettingsKey(
                FreeFormSettingsActivity.KEY_TOP_MARGIN, orientation
            ), defaultValue
        )
    }
}
