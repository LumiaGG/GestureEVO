package io.github.lumiagg.gestureevo.link

import android.content.Context
import com.squareup.moshi.JsonClass
import io.github.lumiagg.gestureevo.utils.MoshiUtils
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore

/** One dispatched link, kept so the user can see where a link went and re-open it. */
@JsonClass(generateAdapter = true)
data class LinkHistoryEntry(
    val id: String,
    val url: String,
    val targetPackage: String,
    val ruleName: String,
    val timestamp: Long,
    val success: Boolean
)

/**
 * Bounded history of dispatched links.
 *
 * Stored as one JSON list under a single key: the list is capped at [MAX_ENTRIES] so the
 * setting never grows without limit, and a single key means a write cannot leave the history
 * half-updated. Old entries are dropped from the front.
 */
object LinkHistoryRepository {
    private const val KEY_HISTORY = "link_dispatch_history"
    private const val MAX_ENTRIES = 200

    suspend fun load(context: Context): List<LinkHistoryEntry> {
        val json = context.settingsStore.readKey(KEY_HISTORY, "")
        if (json.isEmpty()) return emptyList()
        return MoshiUtils.toModel<List<LinkHistoryEntry>>(json).orEmpty()
    }

    suspend fun record(context: Context, entry: LinkHistoryEntry) {
        val current = load(context).toMutableList()
        // Collapse repeats of the same URL so the list stays readable when a user retries.
        current.removeAll { it.url == entry.url }
        current.add(0, entry)
        val trimmed = if (current.size > MAX_ENTRIES) {
            current.subList(0, MAX_ENTRIES).toList()
        } else {
            current.toList()
        }
        context.settingsStore.saveKey(KEY_HISTORY, MoshiUtils.toJson(trimmed))
    }

    suspend fun clear(context: Context) {
        context.settingsStore.saveKey(KEY_HISTORY, "[]")
    }
}
