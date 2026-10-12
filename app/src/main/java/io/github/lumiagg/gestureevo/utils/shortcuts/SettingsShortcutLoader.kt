package io.github.lumiagg.gestureevo.utils.shortcuts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import io.github.lumiagg.gestureevo.db.ShortcutModel

internal fun loadSettingsShortcuts(context: Context): List<ShortcutModel> {
    val packageName = "com.android.settings"
    val pm = context.packageManager
    val resources = pm.getResourcesForApplication(packageName)
    @Suppress("DEPRECATION")
    val baseInfo = ApplicationInfo(pm.getApplicationInfo(packageName, 0)).apply {
        // Keep split resources for labels, but open the manifest from the base APK only.
        splitSourceDirs = null
        splitPublicSourceDirs = null
    }
    val manifestResources = pm.getResourcesForApplication(baseInfo)
    val entries = manifestResources.assets.openXmlResourceParser("AndroidManifest.xml").use { parser ->
        readSettingsActivities(parser, packageName) {
            val labelId = parser.getAttributeResourceValue(ANDROID_XML_NAMESPACE, "label", 0)
            if (labelId == 0) {
                parser.getAttributeValue(ANDROID_XML_NAMESPACE, "label").orEmpty()
            } else {
                try {
                    resources.getString(labelId)
                } catch (_: Resources.NotFoundException) {
                    ""
                }
            }
        }
    }
    val shortcuts = linkedMapOf<String, ShortcutModel>()
    for (entry in entries) {
        if (entry.label in shortcuts) continue
        val component = ComponentName(packageName, entry.className)
        val activity = try {
            @Suppress("DEPRECATION")
            pm.getActivityInfo(component, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            continue
        }
        if (!activity.exported || !activity.enabled || !activity.applicationInfo.enabled) continue
        if (!activity.permission.isNullOrBlank() &&
            context.checkSelfPermission(activity.permission) != PackageManager.PERMISSION_GRANTED
        ) continue
        val intent = Intent(entry.launchAction).apply {
            this.component = component
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        shortcuts[entry.label] = ShortcutModel().apply {
            name = entry.label
            uri = intent.toUri(Intent.URI_INTENT_SCHEME)
        }
    }
    return shortcuts.values.toList()
}
