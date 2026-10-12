package io.github.lumiagg.gestureevo.search

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.util.Log
import androidx.room.withTransaction
import io.github.lumiagg.gestureevo.db.DatabaseManager
import io.github.lumiagg.gestureevo.db.ShortcutModel
import io.github.lumiagg.gestureevo.db.getShortcutDao
import io.github.lumiagg.gestureevo.utils.TextUtil
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import io.github.lumiagg.gestureevo.utils.shortcuts.loadSettingsShortcuts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object SettingsShortcutSearchHelper {

    private const val KEY_LAST_SYNC_SETTINGS_SHORTCUT_TIME = "last_sync_settings_shortcut_time"
    private val syncMutex = Mutex()


    suspend fun syncShortcutsToDatabase(context: Context, force: Boolean = false) = syncMutex.withLock {
        val lastSyncTime = context.settingsStore.readKey(KEY_LAST_SYNC_SETTINGS_SHORTCUT_TIME, 0L)
        if (lastSyncTime != 0L && !force) {
            return@withLock
        }
        // Finish parsing before replacing the cache so a read failure keeps the old shortcuts.
        val shortcuts = try {
            withContext(Dispatchers.IO) { loadSettingsShortcuts(context) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("SettingsShortcutSearch", "Unable to read settings shortcuts; keeping the existing cache", e)
            return@withLock
        }
        DatabaseManager.db.withTransaction {
            getShortcutDao().deleteAll()
            getShortcutDao().insert(*shortcuts.toTypedArray())
        }
        context.settingsStore.saveKey(
            KEY_LAST_SYNC_SETTINGS_SHORTCUT_TIME,
            System.currentTimeMillis()
        )
    }

    fun resolveInfoToUri(context: Context, info: ResolveInfo): String {
        val intent = Intent().apply {
            setClassName("com.android.settings", info.activityInfo.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return intent.toUri(Intent.URI_INTENT_SCHEME)
    }

    suspend fun searchShortcuts(inputSequence: String): List<ShortcutModel> {
        val queryVector = TextUtil.createVector(inputSequence)
        val resultSet = LinkedHashSet<ShortcutModel>()
        resultSet.addAll(
            getShortcutDao().findByName(inputSequence)
        )
        resultSet.addAll(
            getShortcutDao().findByInitialsFK(inputSequence)
        )
        resultSet.addAll(
            getShortcutDao().findByPinYinFK(inputSequence)
        )

        val sortedApps = resultSet
            .map {
                ShortcutSearchScore(
                    shortcut = it,
                    nameScore = TextUtil.calculateSimilarity(queryVector, it.name),
                    initialsScore = TextUtil.calculateSimilarity(queryVector, it.initialsFK),
                    pinyinScore = TextUtil.calculateSimilarity(queryVector, it.pinyinFK)
                )
            }
            .sortedWith(
                compareByDescending<ShortcutSearchScore> { it.nameScore }
                    .thenByDescending { it.initialsScore }
                    .thenByDescending { it.pinyinScore }
            )
            .map { it.shortcut }

        return sortedApps
    }

    private data class ShortcutSearchScore(
        val shortcut: ShortcutModel,
        val nameScore: Double,
        val initialsScore: Double,
        val pinyinScore: Double
    )
}
