package io.github.lumiagg.gestureevo.utils

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.annotation.StyleRes
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.KEY_DYNAMIC_COLOR_SCHEME
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.google.android.material.R as MaterialR

object DynamicColorUtils {
    private const val TAG = "DynamicColorUtils"

    private data class Palette(
        @ColorInt val primary: Int,
        @ColorInt val onPrimary: Int,
        @ColorInt val primaryContainer: Int,
        @ColorInt val onPrimaryContainer: Int,
        @ColorInt val primaryInverse: Int,
        @ColorInt val secondaryContainer: Int,
        @ColorInt val onSecondaryContainer: Int,
        @ColorInt val surface: Int,
        @ColorInt val onSurface: Int,
        @ColorInt val surfaceVariant: Int,
        @ColorInt val onSurfaceVariant: Int,
        @ColorInt val outline: Int,
        @ColorInt val inverseSurface: Int,
        @ColorInt val inverseOnSurface: Int,
        @ColorInt val surfaceContainer: Int,
        @ColorInt val surfaceContainerHigh: Int,
        @ColorInt val surfaceContainerHighest: Int
    )

    private enum class Scheme(val key: String, @StyleRes val overlay: Int) {
        SYSTEM("system", R.style.ThemeOverlay_GestureEVO_Dynamic_System),
        FOREST("forest", R.style.ThemeOverlay_GestureEVO_Dynamic_Forest),
        CORAL("coral", R.style.ThemeOverlay_GestureEVO_Dynamic_Coral),
        OCEAN("ocean", R.style.ThemeOverlay_GestureEVO_Dynamic_Ocean),
        SUNSET("sunset", R.style.ThemeOverlay_GestureEVO_Dynamic_Sunset),
        MIDNIGHT("midnight", R.style.ThemeOverlay_GestureEVO_Dynamic_Midnight),
        PINK("pink", R.style.ThemeOverlay_GestureEVO_Dynamic_Pink);

        companion object {
            fun fromKey(key: String) = entries.firstOrNull { it.key == key } ?: SYSTEM
        }
    }

    private data class PublishedColors(
        val primary: Int = 0,
        val inversePrimary: Int = 0,
        val primaryFixed: Int = 0,
        val onPrimaryContainer: Int = 0,
        val secondaryContainer: Int = 0,
        val inverseOnSurface: Int = 0,
        val surfaceContainer: Int = 0,
        val surfaceContainerHigh: Int = 0,
        val surfaceContainerHighest: Int = 0,
        val onSurface: Int = 0,
        val onSurfaceVariant: Int = 0,
        val outline: Int = 0,
    )

    @Volatile
    private var selectedScheme: Scheme = Scheme.SYSTEM
    private var overlayInstalled = false

    @Volatile
    private var published = PublishedColors()

    val inverseOnSurfaceColor: Int get() = published.inverseOnSurface
    val secondaryContainerColor: Int get() = published.secondaryContainer
    val surfaceContainerHighestColor: Int get() = published.surfaceContainerHighest
    val onPrimaryContainerColor: Int get() = published.onPrimaryContainer
    val primaryFixedColor: Int get() = published.primaryFixed
    val primaryColor: Int get() = published.primary
    val inversePrimaryColor: Int get() = published.inversePrimary
    val surfaceContainerColor: Int get() = published.surfaceContainer
    val surfaceContainerHighColor: Int get() = published.surfaceContainerHigh
    val onSurfaceColor: Int get() = published.onSurface
    val onSurfaceVariantColor: Int get() = published.onSurfaceVariant
    val outlineColor: Int get() = published.outline

    suspend fun loadSavedScheme(context: Context, preferences: androidx.datastore.preferences.core.Preferences? = null) {
        val key = preferences?.readKey(KEY_DYNAMIC_COLOR_SCHEME, Scheme.SYSTEM.key)
            ?: context.settingsStore.readKey(KEY_DYNAMIC_COLOR_SCHEME, Scheme.SYSTEM.key)
        selectedScheme = Scheme.fromKey(key)
    }

    fun updateDynamicColorScheme(context: Context, key: String) {
        selectedScheme = Scheme.fromKey(key)
        // value persisted by caller
    }

    private fun resolvedScheme(): Scheme =
        if (selectedScheme == Scheme.SYSTEM && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) Scheme.FOREST
        else selectedScheme

    fun applyAppWideColors(application: Application) {
        val scheme = resolvedScheme()
        if (scheme == Scheme.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val options = DynamicColorsOptions.Builder()
                .setThemeOverlay(scheme.overlay)
                .build()
            DynamicColors.applyToActivitiesIfAvailable(application, options)
            return
        }
        // Static palette or pre-S fallback
        if (!overlayInstalled) {
            overlayInstalled = true
            application.registerActivityLifecycleCallbacks(object :
                Application.ActivityLifecycleCallbacks {
                override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                    activity.setTheme(R.style.Theme_GestureEVO)
                    activity.theme.applyStyle(scheme.overlay, true)
                    ensureTransparentActivitiesStayClear(activity)
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
        }
    }

    fun dynamicThemedContext(base: Context): Context {
        val themed = ContextThemeWrapper(base, R.style.Theme_GestureEVO)
        val scheme = resolvedScheme()
        if (scheme == Scheme.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val overlay =
                if (themed.isNightMode())
                    MaterialR.style.ThemeOverlay_Material3_DynamicColors_Dark
                else
                    MaterialR.style.ThemeOverlay_Material3_DynamicColors_Light
            themed.theme.applyStyle(overlay, true)
        }
        themed.theme.applyStyle(scheme.overlay, true)
        return themed
    }

    fun captureColor(context: Context, coroutineScope: CoroutineScope) =
        coroutineScope.launch(Dispatchers.Main.immediate) {
            refreshSurfaceColors(context)
        }

    fun captureSystemColorsWithThemedContext(base: Context) {
        captureSystemColors(dynamicThemedContext(base))
    }

    fun captureSystemColors(context: Context) {
        try {
            val fallback = paletteFor(context)
            published = PublishedColors(
                primary = MaterialColors.getColor(
                    context,
                    android.R.attr.colorPrimary,
                    fallback.primary
                ),
                inversePrimary = MaterialColors.getColor(
                    context,
                    MaterialR.attr.colorPrimaryInverse,
                    fallback.primaryInverse
                ),
                primaryFixed = MaterialColors.getColor(
                    context,
                    android.R.attr.colorPrimary,
                    fallback.primaryInverse
                ),
                onPrimaryContainer = MaterialColors.getColor(
                    context,
                    MaterialR.attr.colorOnPrimaryContainer,
                    fallback.onPrimaryContainer
                ),
                secondaryContainer = MaterialColors.getColor(
                    context,
                    MaterialR.attr.colorSecondaryContainer,
                    fallback.secondaryContainer
                ),
                inverseOnSurface = MaterialColors.getColor(
                    context,
                    MaterialR.attr.colorOnSurfaceInverse,
                    fallback.inverseOnSurface
                ),
                surfaceContainer = MaterialColors.getColor(
                    context,
                    MaterialR.attr.colorSurfaceContainer,
                    fallback.surfaceContainer
                ),
                surfaceContainerHigh = MaterialColors.getColor(
                    context,
                    MaterialR.attr.colorSurfaceContainerHigh,
                    fallback.surfaceContainerHigh
                ),
                surfaceContainerHighest = MaterialColors.getColor(
                    context,
                    MaterialR.attr.colorSurfaceContainerHighest,
                    fallback.surfaceContainerHighest
                ),
                onSurface = MaterialColors.getColor(
                    context,
                    MaterialR.attr.colorOnSurface,
                    fallback.onSurface
                ),
                onSurfaceVariant = MaterialColors.getColor(
                    context,
                    MaterialR.attr.colorOnSurfaceVariant,
                    fallback.onSurfaceVariant
                ),
                outline = MaterialColors.getColor(
                    context,
                    MaterialR.attr.colorOutline,
                    fallback.outline
                ),
            )
            logDynamicColors()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving colors: ${e.message}")
        }
    }

    fun useCustomColor(context: Context) {
        val palette = paletteFor(context)
        published = PublishedColors(
            primary = palette.primary,
            inversePrimary = palette.primaryInverse,
            primaryFixed = palette.primaryInverse,
            onPrimaryContainer = palette.onPrimaryContainer,
            secondaryContainer = palette.secondaryContainer,
            inverseOnSurface = palette.inverseOnSurface,
            surfaceContainer = palette.surfaceContainer,
            surfaceContainerHigh = palette.surfaceContainerHigh,
            surfaceContainerHighest = palette.surfaceContainerHighest,
            onSurface = palette.onSurface,
            onSurfaceVariant = palette.onSurfaceVariant,
            outline = palette.outline,
        )
    }

    private fun paletteFor(context: Context): Palette {
        val scheme = resolvedScheme()
        val isDark = context.isNightMode()
        return when (scheme) {
            Scheme.FOREST -> loadPalette(context, "forest", isDark)
            Scheme.CORAL -> loadPalette(context, "coral", isDark)
            Scheme.OCEAN -> loadPalette(context, "ocean", isDark)
            Scheme.SUNSET -> loadPalette(context, "sunset", isDark)
            Scheme.MIDNIGHT -> loadPalette(context, "midnight", isDark)
            Scheme.PINK -> loadPalette(context, "pink", isDark)
            Scheme.SYSTEM -> loadPalette(context, "forest", isDark) // fallback for pre-S
        }
    }

    private val paletteResources = mapOf(
        "forest" to mapOf(
            "primary" to R.color.forest_primary,
            "onPrimary" to R.color.forest_onPrimary,
            "primaryContainer" to R.color.forest_primaryContainer,
            "onPrimaryContainer" to R.color.forest_onPrimaryContainer,
            "primaryInverse" to R.color.forest_primaryInverse,
            "secondaryContainer" to R.color.forest_secondaryContainer,
            "onSecondaryContainer" to R.color.forest_onSecondaryContainer,
            "surface" to R.color.forest_surface,
            "onSurface" to R.color.forest_onSurface,
            "surfaceVariant" to R.color.forest_surfaceVariant,
            "onSurfaceVariant" to R.color.forest_onSurfaceVariant,
            "outline" to R.color.forest_outline,
            "inverseSurface" to R.color.forest_inverseSurface,
            "inverseOnSurface" to R.color.forest_inverseOnSurface,
            "surfaceContainer" to R.color.forest_surfaceContainer,
            "surfaceContainerHigh" to R.color.forest_surfaceContainerHigh,
            "surfaceContainerHighest" to R.color.forest_surfaceContainerHighest,
        ),
        "coral" to mapOf(
            "primary" to R.color.coral_primary,
            "onPrimary" to R.color.coral_onPrimary,
            "primaryContainer" to R.color.coral_primaryContainer,
            "onPrimaryContainer" to R.color.coral_onPrimaryContainer,
            "primaryInverse" to R.color.coral_primaryInverse,
            "secondaryContainer" to R.color.coral_secondaryContainer,
            "onSecondaryContainer" to R.color.coral_onSecondaryContainer,
            "surface" to R.color.coral_surface,
            "onSurface" to R.color.coral_onSurface,
            "surfaceVariant" to R.color.coral_surfaceVariant,
            "onSurfaceVariant" to R.color.coral_onSurfaceVariant,
            "outline" to R.color.coral_outline,
            "inverseSurface" to R.color.coral_inverseSurface,
            "inverseOnSurface" to R.color.coral_inverseOnSurface,
            "surfaceContainer" to R.color.coral_surfaceContainer,
            "surfaceContainerHigh" to R.color.coral_surfaceContainerHigh,
            "surfaceContainerHighest" to R.color.coral_surfaceContainerHighest,
        ),
        "ocean" to mapOf(
            "primary" to R.color.ocean_primary,
            "onPrimary" to R.color.ocean_onPrimary,
            "primaryContainer" to R.color.ocean_primaryContainer,
            "onPrimaryContainer" to R.color.ocean_onPrimaryContainer,
            "primaryInverse" to R.color.ocean_primaryInverse,
            "secondaryContainer" to R.color.ocean_secondaryContainer,
            "onSecondaryContainer" to R.color.ocean_onSecondaryContainer,
            "surface" to R.color.ocean_surface,
            "onSurface" to R.color.ocean_onSurface,
            "surfaceVariant" to R.color.ocean_surfaceVariant,
            "onSurfaceVariant" to R.color.ocean_onSurfaceVariant,
            "outline" to R.color.ocean_outline,
            "inverseSurface" to R.color.ocean_inverseSurface,
            "inverseOnSurface" to R.color.ocean_inverseOnSurface,
            "surfaceContainer" to R.color.ocean_surfaceContainer,
            "surfaceContainerHigh" to R.color.ocean_surfaceContainerHigh,
            "surfaceContainerHighest" to R.color.ocean_surfaceContainerHighest,
        ),
        "sunset" to mapOf(
            "primary" to R.color.sunset_primary,
            "onPrimary" to R.color.sunset_onPrimary,
            "primaryContainer" to R.color.sunset_primaryContainer,
            "onPrimaryContainer" to R.color.sunset_onPrimaryContainer,
            "primaryInverse" to R.color.sunset_primaryInverse,
            "secondaryContainer" to R.color.sunset_secondaryContainer,
            "onSecondaryContainer" to R.color.sunset_onSecondaryContainer,
            "surface" to R.color.sunset_surface,
            "onSurface" to R.color.sunset_onSurface,
            "surfaceVariant" to R.color.sunset_surfaceVariant,
            "onSurfaceVariant" to R.color.sunset_onSurfaceVariant,
            "outline" to R.color.sunset_outline,
            "inverseSurface" to R.color.sunset_inverseSurface,
            "inverseOnSurface" to R.color.sunset_inverseOnSurface,
            "surfaceContainer" to R.color.sunset_surfaceContainer,
            "surfaceContainerHigh" to R.color.sunset_surfaceContainerHigh,
            "surfaceContainerHighest" to R.color.sunset_surfaceContainerHighest,
        ),
        "midnight" to mapOf(
            "primary" to R.color.midnight_primary,
            "onPrimary" to R.color.midnight_onPrimary,
            "primaryContainer" to R.color.midnight_primaryContainer,
            "onPrimaryContainer" to R.color.midnight_onPrimaryContainer,
            "primaryInverse" to R.color.midnight_primaryInverse,
            "secondaryContainer" to R.color.midnight_secondaryContainer,
            "onSecondaryContainer" to R.color.midnight_onSecondaryContainer,
            "surface" to R.color.midnight_surface,
            "onSurface" to R.color.midnight_onSurface,
            "surfaceVariant" to R.color.midnight_surfaceVariant,
            "onSurfaceVariant" to R.color.midnight_onSurfaceVariant,
            "outline" to R.color.midnight_outline,
            "inverseSurface" to R.color.midnight_inverseSurface,
            "inverseOnSurface" to R.color.midnight_inverseOnSurface,
            "surfaceContainer" to R.color.midnight_surfaceContainer,
            "surfaceContainerHigh" to R.color.midnight_surfaceContainerHigh,
            "surfaceContainerHighest" to R.color.midnight_surfaceContainerHighest,
        ),
        "pink" to mapOf(
            "primary" to R.color.pink_primary,
            "onPrimary" to R.color.pink_onPrimary,
            "primaryContainer" to R.color.pink_primaryContainer,
            "onPrimaryContainer" to R.color.pink_onPrimaryContainer,
            "primaryInverse" to R.color.pink_primaryInverse,
            "secondaryContainer" to R.color.pink_secondaryContainer,
            "onSecondaryContainer" to R.color.pink_onSecondaryContainer,
            "surface" to R.color.pink_surface,
            "onSurface" to R.color.pink_onSurface,
            "surfaceVariant" to R.color.pink_surfaceVariant,
            "onSurfaceVariant" to R.color.pink_onSurfaceVariant,
            "outline" to R.color.pink_outline,
            "inverseSurface" to R.color.pink_inverseSurface,
            "inverseOnSurface" to R.color.pink_inverseOnSurface,
            "surfaceContainer" to R.color.pink_surfaceContainer,
            "surfaceContainerHigh" to R.color.pink_surfaceContainerHigh,
            "surfaceContainerHighest" to R.color.pink_surfaceContainerHighest,
        ),
    )

    private fun loadPalette(context: Context, prefix: String, isDark: Boolean): Palette {
        fun c(name: String) =
            ContextCompat.getColor(
                context,
                paletteResources.getValue(prefix).getValue(name)
            )
        return Palette(
            primary = c("primary"),
            onPrimary = c("onPrimary"),
            primaryContainer = c("primaryContainer"),
            onPrimaryContainer = c("onPrimaryContainer"),
            primaryInverse = c("primaryInverse"),
            secondaryContainer = c("secondaryContainer"),
            onSecondaryContainer = c("onSecondaryContainer"),
            surface = c("surface"),
            onSurface = c("onSurface"),
            surfaceVariant = c("surfaceVariant"),
            onSurfaceVariant = c("onSurfaceVariant"),
            outline = c("outline"),
            inverseSurface = c("inverseSurface"),
            inverseOnSurface = c("inverseOnSurface"),
            surfaceContainer = c("surfaceContainer"),
            surfaceContainerHigh = c("surfaceContainerHigh"),
            surfaceContainerHighest = c("surfaceContainerHighest")
        )
    }

    // Checks whether the context is in night mode.
    fun Context.isNightMode(): Boolean {
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }

    private fun logDynamicColors() {
        Log.d(
            TAG,
            "colors => " +
                    "primary=${primaryColor.toHex()}, " +
                    "inversePrimary=${inversePrimaryColor.toHex()}, " +
                    "inverseOnSurface=${inverseOnSurfaceColor.toHex()}, " +
                    "secondaryContainer=${secondaryContainerColor.toHex()}, " +
                    "surfaceContainerHighest=${surfaceContainerHighestColor.toHex()}, " +
                    "onPrimaryContainer=${onPrimaryContainerColor.toHex()}, " +
                    "primaryFixed=${primaryFixedColor.toHex()}, " +
                    "surfaceContainerColor=${surfaceContainerColor.toHex()}, " +
                    "surfaceContainerHighColor=${surfaceContainerHighColor.toHex()}, " +
                    "onSurfaceColor=${onSurfaceColor.toHex()}, " +
                    "onSurfaceVariantColor=${onSurfaceVariantColor.toHex()}, " +
                    "outlineColor=${outlineColor.toHex()}"
        )
    }

    // Extension function: converts color integer to hexadecimal representation.
    private fun Int.toHex() = String.format("#%08X", this)

    private fun ensureTransparentActivitiesStayClear(activity: Activity) {
        val attrs = intArrayOf(android.R.attr.windowIsTranslucent)
        val array = activity.theme.obtainStyledAttributes(attrs)
        val isTranslucent = array.getBoolean(0, false)
        array.recycle()
        if (isTranslucent) {
            activity.window.setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    fun isWallpaperScheme(): Boolean =
        selectedScheme == Scheme.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun refreshSurfaceColors(context: Context) {
        if (isWallpaperScheme()) {
            captureSystemColorsWithThemedContext(context)
        } else {
            useCustomColor(context)
        }
    }

    fun applyBottomSheetSurface(dialog: com.google.android.material.bottomsheet.BottomSheetDialog) {
        val sheet =
            dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
                ?: return
        val ctx = sheet.context
        val surface = surfaceContainerColor.takeIf { it != 0 }
            ?: MaterialColors.getColor(
                ctx,
                com.google.android.material.R.attr.colorSurface,
                0
            )
        val shape = sheet.background as? MaterialShapeDrawable
        if (shape != null) {
            shape.fillColor = ColorStateList.valueOf(surface)
            shape.tintList = ColorStateList.valueOf(surface)
        } else {
            ViewCompat.setBackgroundTintList(sheet, ColorStateList.valueOf(surface))
        }
    }
}
