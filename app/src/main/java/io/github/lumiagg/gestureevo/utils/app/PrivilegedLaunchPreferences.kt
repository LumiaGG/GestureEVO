package io.github.lumiagg.gestureevo.utils.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.ioResult
import java.io.File

internal object PrivilegedLaunchPreferences {
    private const val ROOT_AUTHORIZED = "root_authorized"

    private var dataStore: DataStore<Preferences>? = null

    @Synchronized
    private fun store(context: Context): DataStore<Preferences> {
        val directory = context.applicationContext.noBackupFilesDir
        return dataStore ?: PreferenceDataStoreFactory.create {
            File(directory, "privileged_launch.preferences_pb")
        }.also { dataStore = it }
    }

    suspend fun isRootAuthorized(context: Context): Boolean = ioResult {
        store(context).readKey(ROOT_AUTHORIZED, false)
    }.getOrDefault(false)

    suspend fun setRootAuthorized(context: Context, authorized: Boolean) {
        store(context).saveKey(ROOT_AUTHORIZED, authorized)
    }
}
