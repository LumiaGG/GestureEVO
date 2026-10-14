package io.github.lumiagg.gestureevo

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.lumiagg.gestureevo.search.QuickSearchActivity
import io.github.lumiagg.gestureevo.search.config.SearchEngineDataManager
import io.github.lumiagg.gestureevo.settings.BackgroundStabilityActivity
import io.github.lumiagg.gestureevo.settings.SearchEngineSettingsActivity.Companion.KEY_DEFAULT_SEARCH_PAGE
import io.github.lumiagg.gestureevo.settings.SettingsActivity
import io.github.lumiagg.gestureevo.settings.SettingsGuideActivity
import io.github.lumiagg.gestureevo.settings.guide.SettingsGuideState
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            // Decide onboarding before creating defaults; initialize icons before inflating its preview.
            val showGuide = startupValue(false) { SettingsGuideState.shouldShowOnLaunch(applicationContext) }
            withContext(Dispatchers.IO) {
                startupValue(Unit) { SearchEngineDataManager.ensureDefaultEngines(applicationContext) }
                startupValue(Unit) { IconHelper.initIconConfig(applicationContext) }
            }
            val search = startupValue(false) { settingsStore.readKey(KEY_DEFAULT_SEARCH_PAGE, false) }
            val hideTask = startupValue(true) { settingsStore.readKey(BackgroundStabilityActivity.HIDE_TASK, true) }
            if (!showGuide && search) {
                QuickSearchActivity.start(this@MainActivity)
            } else {
                startActivity(Intent(this@MainActivity, if (showGuide) SettingsGuideActivity::class.java else SettingsActivity::class.java).apply {
                    if (showGuide) putExtra(SettingsGuideActivity.EXTRA_FROM_LAUNCHER, true)
                    if (!hideTask) flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                })
            }
            EvoApplication.application.refreshSearchCatalogs()
            finish()
        }
    }

    private suspend fun <T> startupValue(fallback: T, read: suspend () -> T): T = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("Startup", "Unable to initialize startup settings", e)
        fallback
    }
}
