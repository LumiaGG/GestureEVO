package io.github.lumiagg.gestureevo.utils

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.preference.PreferenceDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class PreferenceDataStoreAdapter(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
) : PreferenceDataStore() {
    // PreferenceDataStore getters are synchronous. Retain persisted reads until the caller
    // can provide a preloaded snapshot before preference inflation.
    private fun <T> read(key: Preferences.Key<T>): T? = runBlocking { dataStore.data.first()[key] }

    private fun <T> write(key: Preferences.Key<T>, value: T) {
        scope.launch(Dispatchers.IO) {
            dataStore.edit { it[key] = value }
        }
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean = read(booleanPreferencesKey(key)) ?: defValue
    override fun putBoolean(key: String, value: Boolean) = write(booleanPreferencesKey(key), value)

    override fun getString(key: String, defValue: String?): String? = read(stringPreferencesKey(key)) ?: defValue
    override fun putString(key: String, value: String?) = write(stringPreferencesKey(key), value.orEmpty())

    override fun getInt(key: String, defValue: Int): Int = read(intPreferencesKey(key)) ?: defValue
    override fun putInt(key: String, value: Int) = write(intPreferencesKey(key), value)

    override fun getFloat(key: String, defValue: Float): Float = read(floatPreferencesKey(key)) ?: defValue
    override fun putFloat(key: String, value: Float) = write(floatPreferencesKey(key), value)

    override fun getLong(key: String, defValue: Long): Long = read(longPreferencesKey(key)) ?: defValue
    override fun putLong(key: String, value: Long) = write(longPreferencesKey(key), value)

    override fun getStringSet(key: String, defValues: MutableSet<String>?): Set<String>? =
        read(stringSetPreferencesKey(key)) ?: defValues

    override fun putStringSet(key: String, values: MutableSet<String>?) =
        write(stringSetPreferencesKey(key), values?.toSet().orEmpty())
}
