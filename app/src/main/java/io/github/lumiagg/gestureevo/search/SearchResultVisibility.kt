package io.github.lumiagg.gestureevo.search

import android.content.Context
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.db.HiddenSearchResultModel
import io.github.lumiagg.gestureevo.search.config.SearchResult

internal fun SearchResult.hiddenRecord(context: Context): HiddenSearchResultModel? {
    val key = pinKey() ?: return null
    val name = when (this) {
        is SearchResult.AppResult -> app.name
        is SearchResult.ContactResult -> "${contact.name} - ${contact.phoneNumber}"
        is SearchResult.ShortcutResult -> shortcut.name
        is SearchResult.HistoryResult -> text
        is SearchResult.UrlResult -> url
        is SearchResult.PhoneResult -> phone
        is SearchResult.PasteResult -> text
        is SearchResult.PasteImageResult -> context.getString(R.string.quick_search_paste_image)
        is SearchResult.GestureActionResult -> actionType.showName(context)
        is SearchResult.AppSettingsResult -> context.getString(R.string.quick_search_app_settings)
        is SearchResult.ClearHistoryAction -> return null
    }
    return HiddenSearchResultModel(key, name, System.currentTimeMillis())
}

internal fun filterHiddenResults(
    results: List<SearchResult>,
    hiddenKeys: Set<String>
): List<SearchResult> {
    if (hiddenKeys.isEmpty()) return results
    var filtered: ArrayList<SearchResult>? = null
    results.forEachIndexed { index, result ->
        if (result.pinKey() in hiddenKeys) {
            if (filtered == null) {
                filtered = ArrayList<SearchResult>(results.size).apply {
                    addAll(results.subList(0, index))
                }
            }
        } else {
            filtered?.add(result)
        }
    }
    val visible = filtered ?: return results
    if (visible.none { it is SearchResult.HistoryResult }) {
        visible.removeAll { it is SearchResult.ClearHistoryAction }
    }
    return visible
}

internal fun HiddenSearchResultModel.typeLabelRes(): Int = when (resultKey.substringBefore(':')) {
    "app" -> R.string.hidden_search_type_app
    "contact" -> R.string.hidden_search_type_contact
    "shortcut" -> R.string.hidden_search_type_shortcut
    "history" -> R.string.hidden_search_type_history
    "url" -> R.string.hidden_search_type_url
    "phone" -> R.string.hidden_search_type_phone
    "paste", "paste_image" -> R.string.hidden_search_type_paste
    "gesture" -> R.string.hidden_search_type_gesture
    "settings" -> R.string.hidden_search_type_settings
    else -> R.string.hidden_search_results_title
}
