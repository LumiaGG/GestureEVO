package io.github.lumiagg.gestureevo.link

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.lumiagg.gestureevo.utils.MoshiUtils

internal inline fun <reified T> Preferences.readLinkList(key: String): List<T> =
    MoshiUtils.toModel<List<T>>(this[stringPreferencesKey(key)].orEmpty()).orEmpty()

/** Read and transform inside one DataStore transaction, including concurrent statistics writes. */
internal suspend inline fun <reified T> DataStore<Preferences>.updateLinkList(
    key: String, crossinline update: (List<T>) -> List<T>,
) {
    edit { prefs -> prefs[stringPreferencesKey(key)] = MoshiUtils.toJson(update(prefs.readLinkList<T>(key))) }
}
