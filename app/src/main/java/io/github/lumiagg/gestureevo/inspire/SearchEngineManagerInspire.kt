package io.github.lumiagg.gestureevo.inspire

import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import io.github.lumiagg.gestureevo.search.QuickSearchEnginePager
import io.github.lumiagg.gestureevo.search.SearchEngineExecutor
import io.github.lumiagg.gestureevo.search.config.EngineType
import io.github.lumiagg.gestureevo.search.config.SearchEngine
import io.github.lumiagg.gestureevo.search.config.SearchEngineDataManager
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_INSPIRE_SHOW_ENGINE
import io.github.lumiagg.gestureevo.settings.SearchEngineSettingsActivity
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.launch

class SearchEngineManagerInspire(
    private val activity: InspireContentActivity,
    private val enginePager: QuickSearchEnginePager,
    private val queryProvider: () -> String = { "" },
    private val imageHandleProvider: () -> ManagedBitmap? = { null },
) {
    private val lifecycleScope: LifecycleCoroutineScope
        get() = activity.lifecycleScope

    fun initialize() = lifecycleScope.launch {
        val showEngine = activity.settingsStore.readKey(KEY_INSPIRE_SHOW_ENGINE, false)
        if (showEngine) {
            initEnginePager()
        }
    }

    private suspend fun initEnginePager() {
        val rowCount = activity.settingsStore
            .readKey(SearchEngineSettingsActivity.KEY_ROW_COUNT, 1)
            .coerceIn(SearchEngineSettingsActivity.MIN_ROW_COUNT, SearchEngineSettingsActivity.MAX_ROW_COUNT)
        val columnCount = activity.settingsStore
            .readKey(SearchEngineSettingsActivity.KEY_COLUMN_COUNT, 5)
            .coerceIn(SearchEngineSettingsActivity.MIN_COLUMN_COUNT, SearchEngineSettingsActivity.MAX_COLUMN_COUNT)
        val showEngineLabel = activity.settingsStore.readKey(
            SearchEngineSettingsActivity.KEY_ENGINE_SHOW_LABEL,
            true
        )
        val showImageBadge = activity.settingsStore.readKey(
            SearchEngineSettingsActivity.KEY_SHOW_IMAGE_BADGE,
            true
        )

        enginePager.configure(
            QuickSearchEnginePager.Config(
                rowCount = rowCount,
                columnCount = columnCount,
                showLabel = showEngineLabel,
                showImageBadge = showImageBadge,
                previewSortMode = false
            )
        )
        enginePager.setCallbacks(
            onEngineClick = { engine -> clickSearchEngine(engine, false) },
            onEngineLongClick = { engine -> clickSearchEngine(engine, true) },
            onOrderChanged = {}
        )
        enginePager.submitList(
            SearchEngineDataManager.readSearchEngineList(activity.applicationContext)
                .filter { it.showInSearchEngineList }
        )
    }

    private fun clickSearchEngine(engine: SearchEngine, isPress: Boolean) {
        val query = if (engine.engineType == EngineType.SHARE_IMAGE_TO_APP) "" else queryProvider()
        SearchEngineExecutor(activity, engine, isPress) {
            InspireDataHolder.clear()
            activity.finish()
        }.execute(query = query, bitmapHandle = imageHandleProvider())
    }
}
