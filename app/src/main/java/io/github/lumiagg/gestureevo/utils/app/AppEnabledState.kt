package io.github.lumiagg.gestureevo.utils.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

/** Returns null when the package is unavailable, rather than treating it as enabled. */
internal fun PackageManager.applicationInfoOrNull(packageName: String): ApplicationInfo? {
    if (packageName.isBlank()) return null
    return try {
        val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getApplicationInfo(
                packageName, PackageManager.ApplicationInfoFlags.of(flags.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            getApplicationInfo(packageName, flags)
        }
        info
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}

fun Context.getAppEnabledState(packageName: String): Boolean? =
    packageManager.applicationInfoOrNull(packageName)?.enabled
