package io.github.lumiagg.gestureevo.search

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.UserManager
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.db.DatabaseManager
import io.github.lumiagg.gestureevo.db.DynamicShortcutDao
import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import io.github.lumiagg.gestureevo.diagnostics.LogLevel
import io.github.lumiagg.gestureevo.diagnostics.LogModule
import io.github.lumiagg.gestureevo.search.config.SearchResult
import io.github.lumiagg.gestureevo.utils.PinyinUtils
import io.github.lumiagg.gestureevo.utils.shortcuts.DynamicShortcuts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Search index for one visit to Quick Search. Room remains the source of imported shortcuts. */
internal class DynamicShortcutSearch(
    private val context: Context,
    private val dao: DynamicShortcutDao = DatabaseManager.db.dynamicShortcutDao(),
) {
    private data class Entry(
        val result: SearchResult.ShortcutResult,
        val tokens: List<String>,
    )

    private var entries: List<Entry>? = null

    suspend fun search(query: String): List<SearchResult.ShortcutResult> {
        if (Build.VERSION.SDK_INT < 25 || query.isBlank()) return emptyList()
        val index = entries ?: try {
            loadEntries().also { entries = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.event(LogModule.APP, "dynamic_shortcut_search_load_failed", LogLevel.WARN, error = e)
            return emptyList()
        }
        return withContext(Dispatchers.Default) {
            index.mapNotNull { entry ->
                ensureActive()
                entry.result.takeIf { entry.tokens.any { it.contains(query, ignoreCase = true) } }
            }
        }
    }

    private suspend fun loadEntries(): List<Entry> {
        val records = dao.getImported()
        if (records.isEmpty()) return emptyList()
        return withContext(Dispatchers.Default) {
            val currentUser = context.getSystemService(UserManager::class.java)
                .getSerialNumberForUser(Process.myUserHandle())
            records.map { record ->
                ensureActive()
                val appLabel = if (record.userSerial == currentUser) record.appLabel else
                    context.getString(R.string.dynamic_shortcuts_profile_label, record.appLabel, record.userSerial)
                val result = SearchResult.ShortcutResult(
                    title = context.getString(R.string.quick_search_dynamic_shortcut_label, record.label, appLabel),
                    uri = DynamicShortcuts.uri(record),
                    dynamicPackage = record.packageName,
                )
                val tokens = listOf(record.label, record.appLabel).flatMap { label ->
                    listOf(label, PinyinUtils.getPinyinParse(label), PinyinUtils.getPinyinFP(label))
                }
                Entry(result, tokens)
            }
        }
    }
}
