package io.github.lumiagg.gestureevo.search.config

import android.content.Context
import android.util.Log
import io.github.lumiagg.gestureevo.gesture.config.IconType
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.searchStore
import java.util.UUID

object SearchEngineDataManager {
    const val TAG = "SearchEngineDataManager"
    const val KEY_SEARCH_ENGINE_LIST = "key_search_engine_list"
    const val KEY_DEFAULT_ENGINE_TAG = "key_default_engine_tag"
    private const val KEY_SEARCH_ENGINE_INITIALIZED = "key_search_engine_initialized"

    @Volatile
    private var cachedEngineList: List<SearchEngine>? = null
    @Volatile
    private var cachedDefaultTag: String? = null

    fun buildTag(): String {
        return UUID.randomUUID().toString()
    }

    private fun createDefaultEngines(): List<SearchEngine> {
        val bing = SearchEngine().apply {
            tag = buildTag()
            name = "Bing"
            engineType = EngineType.DIRECT_LINK
            searchLink = "https://www.bing.com/search?q=%s"
            iconType = IconType.TEXT
            textIcon = "B"
            showInSearchEngineList = true
        }

        val baidu = SearchEngine().apply {
            tag = buildTag()
            name = "Google"
            engineType = EngineType.DIRECT_LINK
            searchLink = "https://www.google.com/search?q=%s"
            iconType = IconType.TEXT
            textIcon = "G"
            showInSearchEngineList = true
        }

        return listOf(bing, baidu)
    }

    /**
     * Initializes default search engines on first launch.
     */
    suspend fun ensureDefaultEngines(context: Context): Boolean {
        val initialized = context.searchStore.readKey(KEY_SEARCH_ENGINE_INITIALIZED, false)
        if (initialized) return false

        val existingEngines = readSearchEngineList(context)
        if (existingEngines.isNotEmpty()) {
            context.searchStore.saveKey(KEY_SEARCH_ENGINE_INITIALIZED, true)
            return false
        }

        val defaults = createDefaultEngines()
        saveSearchEngineList(context, defaults)
        setDefaultEngineTag(context, defaults.firstOrNull()?.tag)
        context.searchStore.saveKey(KEY_SEARCH_ENGINE_INITIALIZED, true)
        return true
    }

    /**
     * Reads all configured search engines.
     */
    suspend fun readSearchEngineList(context: Context): List<SearchEngine> {
        cachedEngineList?.let { return it }
        val enginesJson = context.searchStore.readKey(KEY_SEARCH_ENGINE_LIST, "")
        try {
            val list = SearchEngine.toModelList(enginesJson) ?: emptyList()
            cachedEngineList = list
            return list
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }

    /**
     * Saves search engine list to persistent store.
     */
    suspend fun saveSearchEngineList(
        context: Context,
        engines: List<SearchEngine>
    ) {
        val sortedEngines =
            engines.filter { it.showInSearchEngineList } + engines.filter { !it.showInSearchEngineList }
        cachedEngineList = sortedEngines
        context.searchStore.saveKey(
            KEY_SEARCH_ENGINE_LIST,
            SearchEngine.toJsonList(sortedEngines).also {
                Log.d(TAG, "saveSearchEngineList: ${sortedEngines.size} engines saved")
            }
        )
    }

    /**
     * Adds a search engine.
     */
    suspend fun addSearchEngine(context: Context, engine: SearchEngine) {
        Log.d(TAG, "addSearchEngine: ${engine.name}")
        val engineList = readSearchEngineList(context).toMutableList()
        // If tag already exists, remove previous entry first
        engineList.removeIf { it.tag == engine.tag }
        engineList.add(engine)
        saveSearchEngineList(context, engineList)
    }

    /**
     * Updates an existing search engine.
     */
    suspend fun updateSearchEngine(
        context: Context,
        engine: SearchEngine
    ): Boolean {
        Log.d(TAG, "updateSearchEngine: ${engine.name}")
        val currentList = readSearchEngineList(context).toMutableList()
        val index = currentList.indexOfFirst { it.tag == engine.tag }
        return if (index != -1) {
            currentList[index] = engine
            saveSearchEngineList(context, currentList)
            true
        } else {
            Log.w(TAG, "updateSearchEngine: Engine not found with tag ${engine.tag}")
            false
        }
    }

    /**
     * Removes a search engine by tag.
     */
    suspend fun removeSearchEngine(context: Context, tag: String) {
        Log.d(TAG, "removeSearchEngine: $tag")
        val engineList = readSearchEngineList(context).toMutableList()
        engineList.removeIf { it.tag == tag }
        saveSearchEngineList(context, engineList)

        // If removing the default engine, clear default selection
        if (getDefaultEngineTag(context) == tag) {
            setDefaultEngineTag(context, null)
        }
    }

    /**
     * Finds a search engine by tag.
     */
    suspend fun findSearchEngineByTag(context: Context, tag: String): SearchEngine? {
        return readSearchEngineList(context).find { it.tag == tag }
    }

    /**
     * Sets the default search engine tag.
     */
    suspend fun setDefaultEngineTag(context: Context, tag: String?) {
        cachedDefaultTag = tag ?: ""
        context.searchStore.saveKey(KEY_DEFAULT_ENGINE_TAG, tag ?: "")
        Log.d(TAG, "setDefaultEngineTag: $tag")
    }

    /**
     * Retrieves the default search engine tag.
     */
    suspend fun getDefaultEngineTag(context: Context): String? {
        val cached = cachedDefaultTag
        if (cached != null) return cached.ifEmpty { null }
        val tag = context.searchStore.readKey(KEY_DEFAULT_ENGINE_TAG, "")
        cachedDefaultTag = tag
        return tag.ifEmpty { null }
    }

    /**
     * Retrieves the default search engine.
     */
    suspend fun getDefaultSearchEngine(context: Context): SearchEngine? {
        val defaultTag = getDefaultEngineTag(context) ?: return null
        return findSearchEngineByTag(context, defaultTag)
    }

    /**
     * Filters search engines by engine type.
     */
    suspend fun getSearchEnginesByType(
        context: Context,
        engineType: EngineType
    ): List<SearchEngine> {
        return readSearchEngineList(context).filter { it.engineType == engineType }
    }

    /**
     * Clears all search engines.
     */
    suspend fun clearAllSearchEngines(context: Context) {
        Log.d(TAG, "clearAllSearchEngines")
        context.searchStore.saveKey(KEY_SEARCH_ENGINE_LIST, "")
        context.searchStore.saveKey(KEY_DEFAULT_ENGINE_TAG, "")
    }

    /**
     * Imports search engines in bulk.
     */
    suspend fun importSearchEngines(
        context: Context,
        engines: List<SearchEngine>,
        replaceExisting: Boolean = false
    ) {
        Log.d(TAG, "importSearchEngines: ${engines.size} engines, replace=$replaceExisting")
        if (replaceExisting) {
            saveSearchEngineList(context, engines)
        } else {
            val existingEngines = readSearchEngineList(context).toMutableList()
            val existingTags = existingEngines.map { it.tag }.toSet()

            // Only add engines that do not already exist
            val newEngines = engines.filter { it.tag !in existingTags }
            existingEngines.addAll(newEngines)
            saveSearchEngineList(context, existingEngines)
        }
    }

    /**
     * Exports search engines as JSON.
     */
    suspend fun exportSearchEnginesJson(context: Context): String {
        val engines = readSearchEngineList(context)
        return SearchEngine.toExportJsonList(engines)
    }
}
