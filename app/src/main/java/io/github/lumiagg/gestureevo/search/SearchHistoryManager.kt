package io.github.lumiagg.gestureevo.search

import android.content.Context
import io.github.lumiagg.gestureevo.db.SearchHistoryModel
import io.github.lumiagg.gestureevo.db.getSearchHistoryDao
import io.github.lumiagg.gestureevo.utils.MoshiUtils
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.searchStore
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SearchHistoryManager {

    private const val KEY_SEARCH_LAST_DRAFT = "searchLastDraft"
    private const val DEFAULT_HISTORY_LIMIT = 20

    @JsonClass(generateAdapter = true)
    data class SearchDraft(
        val text: String = "",
        val imageUris: List<String> = emptyList()
    )

    /**
     * Retrieves all search history records.
     */
    suspend fun getAll(): List<String> = withContext(Dispatchers.IO) {
        return@withContext getSearchHistoryDao().getAllTexts()
    }

    suspend fun search(
        query: String,
        limit: Int = DEFAULT_HISTORY_LIMIT
    ): List<String> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isEmpty()) return@withContext emptyList()
        return@withContext getSearchHistoryDao().searchTexts(clean, limit)
    }

    suspend fun getLastInput(context: Context): String = withContext(Dispatchers.IO) {
        return@withContext getLastDraft(context).text
    }

    suspend fun getLastDraft(context: Context): SearchDraft = withContext(Dispatchers.IO) {
        val json = context.searchStore.readKey(KEY_SEARCH_LAST_DRAFT, "")
        return@withContext MoshiUtils.toModel<SearchDraft>(json) ?: SearchDraft()
    }

    suspend fun saveDraft(
        context: Context,
        text: String,
        imageUris: List<String>
    ) = withContext(Dispatchers.IO) {
        val clean = text.trim()
        context.searchStore.saveKey(
            KEY_SEARCH_LAST_DRAFT,
            MoshiUtils.toJson(SearchDraft(clean, imageUris.distinct()))
        )
    }

    /**
     * Adds an entry to search history.
     * De-duplicates existing records and updates timestamp to current time.
     */
    suspend fun add(context: Context, text: String) = withContext(Dispatchers.IO) {
        val clean = text.trim()
        saveDraft(context, clean, getLastDraft(context).imageUris)
        if (clean.isEmpty()) return@withContext

        getSearchHistoryDao().insert(
            SearchHistoryModel(
                text = clean,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * Deletes a specific search history entry.
     */
    suspend fun remove(text: String) = withContext(Dispatchers.IO) {
        val clean = text.trim()
        if (clean.isNotEmpty()) {
            getSearchHistoryDao().deleteByText(clean)
        }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        getSearchHistoryDao().deleteAll()
    }
}
