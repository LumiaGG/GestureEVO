package io.github.lumiagg.gestureevo

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.lumiagg.gestureevo.search.SettingsShortcutSearchHelper
import io.github.lumiagg.gestureevo.search.QuickSearchActivity
import io.github.lumiagg.gestureevo.search.config.SearchEngineDataManager
import io.github.lumiagg.gestureevo.settings.BackgroundStabilityActivity
import io.github.lumiagg.gestureevo.settings.SearchEngineSettingsActivity.Companion.KEY_DEFAULT_SEARCH_PAGE
import io.github.lumiagg.gestureevo.settings.SettingsActivity
import io.github.lumiagg.gestureevo.settings.SettingsGuideActivity
import io.github.lumiagg.gestureevo.settings.guide.SettingsGuideState
import io.github.lumiagg.gestureevo.utils.app.syncAppCatalog
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        lifecycleScope.launch(Dispatchers.Default) {
            val showGuide = SettingsGuideState.shouldShowOnLaunch(applicationContext)
            SearchEngineDataManager.ensureDefaultEngines(applicationContext)
            val openSearchByDefault = settingsStore.readKey(KEY_DEFAULT_SEARCH_PAGE, false)
            if (!showGuide && openSearchByDefault) {
                withContext(Dispatchers.Main) {
                    QuickSearchActivity.start(applicationContext)
                }
            } else {
                val destination = if (showGuide) {
                    SettingsGuideActivity::class.java
                } else {
                    SettingsActivity::class.java
                }
                val intent = Intent(applicationContext, destination)
                if (showGuide) {
                    intent.putExtra(SettingsGuideActivity.EXTRA_FROM_LAUNCHER, true)
                }
                if (!settingsStore.readKey(BackgroundStabilityActivity.HIDE_TASK, true)) {
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                }
                withContext(Dispatchers.Main) {
                    startActivity(intent)
                }
            }

            delay(300)
            IconHelper.initIconConfig(applicationContext)
            syncAppCatalog(applicationContext)
            SettingsShortcutSearchHelper.syncShortcutsToDatabase(applicationContext)
            withContext(Dispatchers.Main) {
                finish()
            }
        }
    }

}
