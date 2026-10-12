package io.github.lumiagg.gestureevo.settings

import android.content.Context
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore

object WebDAVSettings {
    private val SERVER_URL = "webdav_server_url"
    private val USERNAME = "webdav_username"
    private val PASSWORD = "webdav_password"

    suspend fun saveCredentials(
        context: Context,
        serverUrl: String,
        username: String,
        password: String
    ) {
        context.settingsStore.saveKey(SERVER_URL, serverUrl)
        context.settingsStore.saveKey(USERNAME, username)
        context.settingsStore.saveKey(PASSWORD, password)
    }

    suspend fun getServerUrl(context: Context): String {
        return context.settingsStore.readKey(SERVER_URL, "")
    }

    suspend fun getUsername(context: Context): String {
        return context.settingsStore.readKey(USERNAME, "")
    }

    suspend fun getPassword(context: Context): String {
        return context.settingsStore.readKey(PASSWORD, "")
    }

    suspend fun hasCredentials(context: Context): Boolean {
        val serverUrl = getServerUrl(context)
        val username = getUsername(context)
        getPassword(context)

        return serverUrl.isNotBlank() && username.isNotBlank()
    }
}
