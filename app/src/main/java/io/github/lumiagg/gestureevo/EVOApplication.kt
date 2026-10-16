package io.github.lumiagg.gestureevo

import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.MlKitOcr
import io.github.lumiagg.gestureevo.utils.AppCrashHandler
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import io.github.lumiagg.gestureevo.utils.app.syncAppCatalogIfNeeded
import io.github.lumiagg.gestureevo.search.SettingsShortcutSearchHelper


class EvoApplication : Application() {
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun refreshSearchCatalogs() {
        maintenanceScope.launch {
            try { syncAppCatalogIfNeeded(applicationContext) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w("Startup", "Unable to refresh app catalog", e) }
            try { SettingsShortcutSearchHelper.syncShortcutsToDatabase(applicationContext) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w("Startup", "Unable to refresh shortcuts", e) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        application = this
        AppLogger.initialize(this)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(AppCrashHandler(this, previousHandler))
        val preferences = runBlocking { settingsStore.data.first() }
        applySavedLanguage(preferences.readKey(AppearanceSettingsActivity.KEY_APP_LANGUAGE, ""))
        runBlocking { DynamicColorUtils.loadSavedScheme(this@EvoApplication, preferences) }
        DynamicColorUtils.applyAppWideColors(this)
        // Settings views read the shared palette while inflating, before any service starts.
        DynamicColorUtils.refreshSurfaceColors(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        DynamicColorUtils.refreshSurfaceColors(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            MlKitOcr.releasePreload()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        MlKitOcr.releasePreload()
    }

    private fun applySavedLanguage(languageTag: String) {
        val locales = if (languageTag.isBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(languageTag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    companion object {
        lateinit var application: EvoApplication private set
    }
}
