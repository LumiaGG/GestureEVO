package io.github.lumiagg.gestureevo.utils.icon

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.Xml
import androidx.core.content.res.ResourcesCompat
import java.util.Locale

class IconPackRepository(context: Context) {
    private val pm = context.applicationContext.packageManager

    fun availablePackageNames(): List<String> = buildSet {
        for (action in listOf("org.adw.launcher.THEMES", "com.gau.go.launcherex.theme")) {
            @Suppress("DEPRECATION")
            val activities = pm.queryIntentActivities(Intent(action), 0)
            activities.forEach { add(it.activityInfo.packageName) }
        }
    }.sorted()

    fun openPack(packageName: String) = Pack(packageName)

    inner class Pack internal constructor(private val packageName: String) {
        private val resources: Resources? by lazy {
            try {
                pm.getResourcesForApplication(packageName)
            } catch (e: Exception) {
                Log.w("IconPackRepository", "Unable to load icon pack $packageName", e)
                null
            }
        }

        // Foreign icon-pack resource IDs must be resolved by name at runtime.
        @get:SuppressLint("DiscouragedApi")
        private val index: AppFilterIndex by lazy {
            val res = resources
            if (res == null) AppFilterIndex.EMPTY else try {
                val id = res.getIdentifier("appfilter", "xml", packageName)
                if (id != 0) {
                    res.getXml(id).use(AppFilterIndex::read)
                } else {
                    res.assets.open("appfilter.xml").use { stream ->
                        val parser = Xml.newPullParser()
                        parser.setInput(stream, "UTF-8")
                        AppFilterIndex.read(parser)
                    }
                }
            } catch (e: Exception) {
                Log.w("IconPackRepository", "Unable to read appfilter for $packageName", e)
                AppFilterIndex.EMPTY
            }
        }

        fun iconFor(appPackageName: String): Drawable? {
            val component = pm.getLaunchIntentForPackage(appPackageName)?.component
            val mappedName = index.drawableFor(appPackageName, component?.className)
            loadDrawable(mappedName)?.let { return it }
            // Some packs name drawables after the launcher component without an appfilter entry.
            val fallbackName = component?.let {
                "${it.packageName}_${it.className}".lowercase(Locale.ROOT).replace('.', '_')
            }
            return loadDrawable(fallbackName)
        }

        // Drawable names come from the installed icon pack, not this application R class.
        @SuppressLint("DiscouragedApi")
        private fun loadDrawable(name: String?): Drawable? {
            if (name.isNullOrBlank()) return null
            val res = resources ?: return null
            val id = res.getIdentifier(name, "drawable", packageName)
            if (id == 0) return null
            return try {
                ResourcesCompat.getDrawable(res, id, null)
            } catch (e: Resources.NotFoundException) {
                Log.w("IconPackRepository", "Unable to load $name from $packageName", e)
                null
            }
        }
    }
}
