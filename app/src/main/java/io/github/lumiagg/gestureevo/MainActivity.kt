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
import io.github.lumiagg.gestureevo.utils.app.syncAppCatalog
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        lifecycleScope.launch(Dispatchers.Default) {
            SearchEngineDataManager.ensureDefaultEngines(applicationContext)
            val openSearchByDefault = settingsStore.readKey(KEY_DEFAULT_SEARCH_PAGE, false)
            if (openSearchByDefault) {
                QuickSearchActivity.start(applicationContext)
            } else {
                val intent = Intent(applicationContext, SettingsActivity::class.java)
                if (!settingsStore.readKey(BackgroundStabilityActivity.HIDE_TASK, true)) {
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                }
                startActivity(intent)
            }

            delay(300)
            IconHelper.initIconConfig(applicationContext)
            syncAppCatalog(applicationContext)
            SettingsShortcutSearchHelper.syncShortcutsToDatabase(applicationContext)
            finish()
        }
    }

}
