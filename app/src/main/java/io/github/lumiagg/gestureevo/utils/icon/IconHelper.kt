package io.github.lumiagg.gestureevo.utils.icon

import androidx.core.graphics.drawable.toDrawable

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.KEY_APP_ICON_OVERRIDES
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.KEY_ICON_IMAGE_ROUND_PERCENT
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.KEY_ICON_PACK
import io.github.lumiagg.gestureevo.utils.MoshiUtils
import io.github.lumiagg.gestureevo.utils.app.applicationInfoOrNull
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import com.squareup.moshi.Types
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object IconHelper {
    private data class Configuration(
        val pack: IconPackRepository.Pack? = null,
        val overrides: Map<String, String> = emptyMap(),
        val cache: ConcurrentHashMap<String, Drawable.ConstantState> = ConcurrentHashMap(),
    )

    @Volatile private var configuration = Configuration()
    @Volatile var iconImageRoundPercent = 0f
    private val configurationMutex = Mutex()
    private val overridesAdapter = MoshiUtils.moshi.adapter<Map<String, String>>(
        Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
    )

    suspend fun initIconConfig(context: Context, pkgName: String? = null) = configurationMutex.withLock {
        val percentage = context.settingsStore.readKey(KEY_ICON_IMAGE_ROUND_PERCENT, 0f)
        val packageName = pkgName ?: context.settingsStore.readKey(KEY_ICON_PACK, "")
        val json = context.settingsStore.readKey(KEY_APP_ICON_OVERRIDES, "")
        val overrides = if (json.isBlank()) emptyMap() else try {
            overridesAdapter.fromJson(json).orEmpty()
        } catch (e: Exception) {
            Log.w("IconHelper", "Unable to read icon overrides", e)
            emptyMap()
        }
        iconImageRoundPercent = percentage
        configuration = Configuration(
            packageName.takeIf { it.isNotEmpty() }?.let { IconPackRepository(context).openPack(it) },
            overrides.toMap(),
        )
    }

    fun getIcon(context: Context, pkgName: String): Drawable? {
        // In-flight reads populate their own configuration's cache, never a newly selected pack.
        val current = configuration
        current.cache[pkgName]?.let { return it.newDrawable(context.resources).mutate() }
        val icon = resolveIcon(
            current.overrides[pkgName],
            loadOverride = { readDrawable(context, it) },
            loadPack = { current.pack?.iconFor(pkgName) },
            loadApplication = { getAppIconByPackageName(context, pkgName) },
        )
        icon?.constantState?.let { current.cache[pkgName] = it }
        return icon
    }

    fun getIconDrawable(context: Context, pkgName: String, iconPath: String): Drawable? =
        readDrawable(context, iconPath) ?: getIcon(context, pkgName)

    fun getIconBitmap(iconPath: String): Bitmap? = IconImageIO.read(iconPath)

    private fun readDrawable(context: Context, path: String): Drawable? =
        IconImageIO.read(path)?.let { it.toDrawable(context.resources) }

    suspend fun setAppIconOverride(context: Context, pkgName: String, iconPath: String?) = configurationMutex.withLock {
        val current = configuration
        val previousPath = current.overrides[pkgName]
        val overrides = current.overrides.toMutableMap().apply {
            if (iconPath.isNullOrBlank()) remove(pkgName) else put(pkgName, iconPath)
        }
        val json = if (overrides.isEmpty()) "" else overridesAdapter.toJson(overrides)
        context.settingsStore.saveKey(KEY_APP_ICON_OVERRIDES, json)
        configuration = Configuration(current.pack, overrides.toMap())
        if (!previousPath.isNullOrBlank() && previousPath != iconPath && previousPath !in overrides.values) {
            File(previousPath).takeIf { it.isFile }?.delete()
        }
        Unit
    }

    fun clearMemoryCache() {
        configuration.cache.clear()
    }

    fun getAppIconByPackageName(context: Context, packageName: String): Drawable? {
        val pm = context.packageManager
        val icon = try {
            pm.getApplicationIcon(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            pm.applicationInfoOrNull(packageName)?.let { info ->
                try {
                    pm.getApplicationIcon(info)
                } catch (_: Resources.NotFoundException) {
                    null
                }
            }
        } catch (_: Resources.NotFoundException) {
            null
        }
        return icon ?: ContextCompat.getDrawable(context, R.mipmap.ic_launcher_foreground)
    }
}
