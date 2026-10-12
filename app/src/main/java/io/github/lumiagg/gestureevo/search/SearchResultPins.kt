package io.github.lumiagg.gestureevo.search

import io.github.lumiagg.gestureevo.search.config.SearchResult

// Type prefixes prevent unrelated results with the same text from sharing a pin.
internal fun SearchResult.pinKey(): String? = when (this) {
    is SearchResult.AppResult -> "app:${app.userId}:${app.pkgName}"
    is SearchResult.ContactResult -> "contact:${contact.contactId}:${contact.phoneNumber}"
    is SearchResult.ShortcutResult -> shortcut.uri?.takeIf { it.isNotBlank() }?.let { "shortcut:$it" }
    is SearchResult.HistoryResult -> "history:$text"
    is SearchResult.UrlResult -> "url:$url"
    is SearchResult.PhoneResult -> "phone:$phone"
    is SearchResult.PasteResult -> "paste:$text"
    is SearchResult.PasteImageResult -> "paste_image:$uri"
    is SearchResult.GestureActionResult -> "gesture:${actionType.name}"
    is SearchResult.AppSettingsResult -> "settings:gesture_evo"
    is SearchResult.ClearHistoryAction -> null
}

internal fun applyResultPins(
    results: List<SearchResult>,
    pinOrder: Map<String, Int>
): List<SearchResult> {
    if (pinOrder.isEmpty()) return results
    val pinned = ArrayList<IndexedValue<SearchResult>>()
    val remaining = ArrayList<SearchResult>(results.size)
    for (result in results) {
        val rank = result.pinKey()?.let { pinOrder[it] }
        if (rank == null) remaining.add(result) else pinned.add(IndexedValue(rank, result))
    }
    if (pinned.isEmpty()) return results
    // Only sort matching pins, retaining the relative order of all other results.
    pinned.sortBy { it.index }
    return ArrayList<SearchResult>(results.size).apply {
        pinned.forEach { add(it.value) }
        addAll(remaining)
    }
}
