package io.github.lumiagg.gestureevo.gesture.ui

import android.content.Context
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.dp2px
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore

data class GestureFluidConfig(
    val color: Int,
    val style: Style,
    val sizeScale: Float,
    val strokeWidthDp: Float,
    val strokeColor: Int,
) {
    enum class Style(val key: String) {
        CLASSIC("classic"),
        CAPSULE("capsule"),
        ARC("arc");

        companion object {
            fun fromKey(key: String): Style = when (key) {
                SHARP_LEGACY, CAPSULE.key -> CAPSULE
                WIDE_LEGACY, ARC.key -> ARC
                else -> CLASSIC
            }

            private const val SHARP_LEGACY = "sharp"
            private const val WIDE_LEGACY = "wide"
        }
    }

    val resolvedColor: Int
        get() = if (color != 0) color else DynamicColorUtils.secondaryContainerColor

    val resolvedStrokeColor: Int
        get() = if (strokeColor != 0) strokeColor else resolvedColor

    val strokeWidthPx: Float
        get() = strokeWidthDp.dp2px

    val baseRadiusPx: Float
        get() = when (style) {
            Style.CLASSIC -> 80f
            Style.CAPSULE -> 64f
            Style.ARC -> 92f
        }.dp2px * sizeScale

    val peekFactor: Float
        get() = when (style) {
            Style.CLASSIC -> 0.6f
            Style.CAPSULE -> 0.5f
            Style.ARC -> 0.72f
        }

    val controlFactor: Float
        get() = when (style) {
            Style.CLASSIC -> 0.5f
            Style.CAPSULE -> 0.5f
            Style.ARC -> 0.5f
        }

    val maxTravelPx: Float
        get() = FluidRippleView.MAX_BULGE_DISTANCE_DP.dp2px * sizeScale

    companion object {
        val DEFAULT = GestureFluidConfig(
            color = 0,
            style = Style.CLASSIC,
            sizeScale = 1f,
            strokeWidthDp = 0f,
            strokeColor = 0,
        )
    }
}

object GestureFluidAppearance {
    @Volatile
    var current: GestureFluidConfig = GestureFluidConfig.DEFAULT
        private set

    suspend fun refresh(context: Context) {
        val store = context.applicationContext.settingsStore
        current = GestureFluidConfig(
            color = store.readKey(AppearanceSettingsActivity.KEY_GESTURE_FLUID_COLOR, 0),
            style = GestureFluidConfig.Style.fromKey(
                store.readKey(
                    AppearanceSettingsActivity.KEY_GESTURE_FLUID_STYLE,
                    GestureFluidConfig.Style.CLASSIC.key
                )
            ),
            sizeScale = store.readKey(AppearanceSettingsActivity.KEY_GESTURE_FLUID_SIZE, 1f)
                .coerceIn(SIZE_MIN, SIZE_MAX),
            strokeWidthDp = store.readKey(AppearanceSettingsActivity.KEY_GESTURE_FLUID_STROKE, 0f)
                .coerceIn(STROKE_MIN, STROKE_MAX),
            strokeColor = store.readKey(AppearanceSettingsActivity.KEY_GESTURE_FLUID_STROKE_COLOR, 0),
        )
    }

    const val SIZE_MIN = 0.75f
    const val SIZE_MAX = 1.5f
    const val STROKE_MIN = 0f
    const val STROKE_MAX = 6f
}
