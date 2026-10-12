package io.github.lumiagg.gestureevo.utils

import android.util.AtomicFile
import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import java.io.File

class WebDAVManager {
    private data class Session(val client: OkHttpSardine, val baseUrl: String)
    @Volatile private var session: Session? = null

    // Preserve credential configuration; login does not perform a network probe.
    suspend fun login(serverUrl: String, username: String, password: String): Result<Boolean> = ioResult {
        session = null
        val client = OkHttpSardine().apply { setCredentials(username, password) }
        session = Session(client, serverUrl)
        true
    }

    private suspend fun <T> withSession(path: String, operation: (OkHttpSardine, String) -> T): Result<T> = ioResult {
        val current = session ?: throw IllegalStateException("Not logged in")
        operation(current.client, webDavResourceUrl(current.baseUrl, path))
    }

    suspend fun createDirectory(path: String): Result<Boolean> = withSession(path) { client, url ->
        if (!client.exists(url)) client.createDirectory(url)
        true
    }

    suspend fun uploadFile(localFile: File, remotePath: String): Result<Boolean> = withSession(remotePath) { client, url ->
        client.put(url, localFile, "application/octet-stream")
        true
    }

    suspend fun downloadFile(remotePath: String, localFile: File): Result<Boolean> = withSession(remotePath) { client, url ->
        client.get(url).use { input ->
            val file = AtomicFile(localFile)
            val output = file.startWrite()
            try {
                input.copyTo(output)
                file.finishWrite(output)
            } catch (failure: Exception) {
                file.failWrite(output)
                throw failure
            }
        }
        true
    }

    suspend fun listFiles(path: String): Result<List<DavResource>> = withSession(path) { client, url -> client.list(url) }

    suspend fun fileExists(path: String): Result<Boolean> = withSession(path) { client, url -> client.exists(url) }

    fun logout() {
        session = null
    }
}
