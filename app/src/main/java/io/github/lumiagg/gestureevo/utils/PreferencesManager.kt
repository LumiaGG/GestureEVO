package io.github.lumiagg.gestureevo.utils

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
val Context.settingsStore: DataStore<Preferences>
    get() = SettingsSnapshotCache.getStore(applicationContext.settingsDataStore)
val Context.actionStore: DataStore<Preferences> by preferencesDataStore(name = "action")
val Context.gestureStore: DataStore<Preferences> by preferencesDataStore(name = "gesture")

val Context.searchStore: DataStore<Preferences> by preferencesDataStore(name = "search")

@JsonClass(generateAdapter = true)
data class PreferenceEntry(
    val key: String,
    val type: String,
    val value: String
)

@JsonClass(generateAdapter = true)
data class PreferencesBackup(
    val entries: List<PreferenceEntry>
)

suspend inline fun <reified T : Any> DataStore<Preferences>.saveKey(key: String, value: T) {
    edit { preferences ->
        preferences[getPreferencesKey<T>(key)] = value
    }
}

suspend inline fun <reified T : Any> DataStore<Preferences>.readKey(
    key: String,
    defaultValue: T
): T {
    val preferences = data.first()
    return preferences.readKey(key, defaultValue)
}

// Read several values from one consistent snapshot without subscribing repeatedly.
inline fun <reified T : Any> Preferences.readKey(key: String, defaultValue: T): T {
    val preferences = this
    preferences[getPreferencesKey<T>(key)]?.let { return it }
    when (T::class) {
        Int::class -> {
            preferences[floatPreferencesKey(key)]?.let { return it.toInt() as T }
            preferences[doublePreferencesKey(key)]?.let { return it.toInt() as T }
            preferences[longPreferencesKey(key)]?.let { return it.toInt() as T }
        }
        Float::class -> {
            preferences[intPreferencesKey(key)]?.let { return it.toFloat() as T }
            preferences[doublePreferencesKey(key)]?.let { return it.toFloat() as T }
            preferences[longPreferencesKey(key)]?.let { return it.toFloat() as T }
        }
        Double::class -> {
            preferences[floatPreferencesKey(key)]?.let { return it.toDouble() as T }
            preferences[intPreferencesKey(key)]?.let { return it.toDouble() as T }
            preferences[longPreferencesKey(key)]?.let { return it.toDouble() as T }
        }
        Long::class -> {
            preferences[intPreferencesKey(key)]?.let { return it.toLong() as T }
            preferences[floatPreferencesKey(key)]?.let { return it.toLong() as T }
            preferences[doublePreferencesKey(key)]?.let { return it.toLong() as T }
        }
    }
    return defaultValue
}

inline fun <reified T : Any> DataStore<Preferences>.observeKey(
    key: String,
    defaultValue: T
): Flow<T> {
    return data.map { preferences ->
        preferences[getPreferencesKey<T>(key)] ?: run {
            when (T::class) {
                Int::class -> {
                    preferences[floatPreferencesKey(key)]?.toInt() as? T
                        ?: preferences[doublePreferencesKey(key)]?.toInt() as? T
                        ?: preferences[longPreferencesKey(key)]?.toInt() as? T
                }
                Float::class -> {
                    preferences[intPreferencesKey(key)]?.toFloat() as? T
                        ?: preferences[doublePreferencesKey(key)]?.toFloat() as? T
                        ?: preferences[longPreferencesKey(key)]?.toFloat() as? T
                }
                Double::class -> {
                    preferences[floatPreferencesKey(key)]?.toDouble() as? T
                        ?: preferences[intPreferencesKey(key)]?.toDouble() as? T
                        ?: preferences[longPreferencesKey(key)]?.toDouble() as? T
                }
                Long::class -> {
                    preferences[intPreferencesKey(key)]?.toLong() as? T
                        ?: preferences[floatPreferencesKey(key)]?.toLong() as? T
                        ?: preferences[doublePreferencesKey(key)]?.toLong() as? T
                }
                else -> null
            } ?: defaultValue
        }
    }
}

suspend inline fun <reified T : Any> DataStore<Preferences>.removeKey(key: String) {
    edit { preferences ->
        preferences.remove(getPreferencesKey<T>(key))
    }
}

suspend fun DataStore<Preferences>.clear() {
    edit { preferences ->
        preferences.clear()
    }
}

inline fun <reified T : Any> getPreferencesKey(key: String): Preferences.Key<T> {
    return when (T::class) {
        Boolean::class -> booleanPreferencesKey(key)
        Int::class -> intPreferencesKey(key)
        Long::class -> longPreferencesKey(key)
        Float::class -> floatPreferencesKey(key)
        Double::class -> doublePreferencesKey(key)
        String::class -> stringPreferencesKey(key)
        else -> throw IllegalArgumentException("Unsupported type: ${T::class.java.simpleName}")
    } as Preferences.Key<T>
}

// Exports preferences data to a JSON file using Moshi.
suspend fun DataStore<Preferences>.exportToFile(file: File, format: Boolean = false) {
    val preferences = data.first()
    val entries = preferences.asMap().map { (key, value) ->
        PreferenceEntry(
            key = key.name,
            type = value::class.java.simpleName,
            value = value.toString()
        )
    }
    val backup = PreferencesBackup(entries)
    val json = MoshiUtils.toJson(backup, format)
    file.writeText(json)
}

// Restores preferences data from a JSON file using Moshi.
suspend fun DataStore<Preferences>.restoreFromFile(file: File, transformString: (String) -> String = { it }) {
    if (!file.exists()) return

    val json = file.readText()
    val backup = MoshiUtils.toModel<PreferencesBackup>(json) ?: return

    edit { preferences ->
        preferences.clear()

        backup.entries.forEach { entry ->
            when (entry.type) {
                "Boolean" -> preferences[booleanPreferencesKey(entry.key)] = entry.value.toBoolean()
                "Integer" -> preferences[intPreferencesKey(entry.key)] = entry.value.toInt()
                "Long" -> preferences[longPreferencesKey(entry.key)] = entry.value.toLong()
                "Float" -> preferences[floatPreferencesKey(entry.key)] = entry.value.toFloat()
                "Double" -> preferences[doublePreferencesKey(entry.key)] = entry.value.toDouble()
                "String" -> preferences[stringPreferencesKey(entry.key)] = transformString(entry.value)
            }
        }
    }
}

// Exports all application DataStore instances to the specified backup directory.
suspend fun Context.exportAllDataStores(backupDir: File, format: Boolean = false) {
    backupDir.mkdirs()
    settingsStore.exportToFile(File(backupDir, "settings.json"), format)
    actionStore.exportToFile(File(backupDir, "action.json"), format)
    gestureStore.exportToFile(File(backupDir, "gesture.json"), format)
    searchStore.exportToFile(File(backupDir, "search.json"), format)
}

// Restores all application DataStore instances from the specified backup directory.
suspend fun Context.restoreAllDataStores(backupDir: File) {
    val rebase: (String) -> String = { rebaseBackupFilePaths(it, filesDir.absolutePath) }
    settingsStore.restoreFromFile(File(backupDir, "settings.json"), rebase)
    actionStore.restoreFromFile(File(backupDir, "action.json"), rebase)
    gestureStore.restoreFromFile(File(backupDir, "gesture.json"), rebase)
    searchStore.restoreFromFile(File(backupDir, "search.json"), rebase)
}

// Safely exports all DataStore instances with exception handling.
suspend fun Context.exportAllDataStoresSafely(
    backupDir: File,
    format: Boolean = false
): Result<Unit> {
    return try {
        exportAllDataStores(backupDir, format)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }
}

// Safely restores all DataStore instances with exception handling.
suspend fun Context.restoreAllDataStoresSafely(backupDir: File): Result<Unit> {
    return try {
        restoreAllDataStores(backupDir)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }
}
