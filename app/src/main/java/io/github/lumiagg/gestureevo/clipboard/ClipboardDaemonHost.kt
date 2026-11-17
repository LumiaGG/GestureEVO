// 来源：wangzhen9331 的 GestureEVO 自用改版（GPL-3.0-only），检测后端按本仓库架构适配。

package io.github.lumiagg.gestureevo.clipboard

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import io.github.lumiagg.gestureevo.BuildConfig
import io.github.lumiagg.gestureevo.utils.shizuku.ShizukuServiceHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.File
import java.io.InputStream

/**
 * Keeps [ClipboardDaemon] running and turns its stdout into change callbacks.
 *
 * The app cannot observe clipboard changes in the background on this ROM, so a process that *can*
 * — one running as shell or root — reports them instead. Two launch paths exist, and the app does
 * not need root when Shizuku is available:
 *
 * - **Shizuku**: the bound user service spawns the daemon (its children inherit the shell uid) and
 *   hands back a pipe on its stdout. No root, no su prompt.
 * - **su**: the daemon runs as a child of su and its stdout doubles as the channel back.
 *
 * Both speak the same READY/CHANGED/PING protocol. The daemon is restarted when it dies, with a
 * capped backoff so a device that refuses both backends does not turn into a spawn loop.
 */
class ClipboardDaemonHost(
    private val context: Context,
    private val backendProvider: () -> String = { ClipboardLinkConfig.BACKEND_AUTO },
    private val onChanged: () -> Unit,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    @Volatile
    private var started = false

    @Volatile
    private var session: DaemonSession? = null

    /** Flipped after a backend fails to become ready twice in a row; the other one is tried next. */
    @Volatile
    private var preferRoot = false

    private var consecutiveNotReady = 0

    fun start() {
        if (started) return
        started = true
        loopJob = scope.launch { supervise() }
    }

    fun stop() {
        started = false
        session?.let { runCatching { it.close() } }
        session = null
        loopJob?.cancel()
        loopJob = null
        scope.cancel()
    }

    private suspend fun supervise() {
        var backoff = 1_000L
        while (started && scope.isActive) {
            val ready = runOnce()
            if (!started) break
            // A daemon that ran fine for a while should come back immediately; one that never got
            // ready is probably blocked on a permission prompt, so back off instead of hammering it.
            if (ready) {
                backoff = 1_000L
                consecutiveNotReady = 0
            } else {
                consecutiveNotReady++
                // 自动模式连续两次未就绪才换边；显式指定的通道不翻转，按原通道重试。
                if (consecutiveNotReady >= 2 && backendProvider() == ClipboardLinkConfig.BACKEND_AUTO) {
                    preferRoot = !preferRoot
                    consecutiveNotReady = 0
                    diagnose("switching backend, preferRoot=" + preferRoot)
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    /** Returns whether the daemon reached READY before dying or timing out. */
    private suspend fun runOnce(): Boolean {
        val current = try {
            openSession()
        } catch (e: Exception) {
            diagnose("launch failed: ${e.javaClass.simpleName}: ${e.message}")
            return false
        } ?: return false
        session = current

        val lines = Channel<String>(Channel.UNLIMITED)
        val pump = scope.launch {
            try {
                current.input.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        lines.trySend(line)
                    }
                }
            } catch (_: Exception) {
                // Falling through closes the channel and ends the loop below.
            } finally {
                lines.close()
            }
        }

        val ready = withTimeoutOrNull(READY_TIMEOUT_MS) {
            while (true) {
                val line = lines.receiveCatching().getOrNull() ?: return@withTimeoutOrNull false
                when {
                    line == "READY" -> return@withTimeoutOrNull true
                    line.startsWith("ERROR") -> {
                        diagnose("daemon: $line")
                        return@withTimeoutOrNull false
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false

        if (!ready) {
            diagnose("daemon not ready")
            runCatching { current.close() }
            pump.cancel()
            session = null
            return false
        }

        diagnose("daemon ready backend=" + current.name)
        for (line in lines) {
            if (!started) break
            if (line == "CHANGED") onChanged()
        }
        pump.cancel()
        session = null
        runCatching { current.close() }
        return true
    }

    private class DaemonSession(val name: String, val input: InputStream, val close: () -> Unit)

    private suspend fun openSession(): DaemonSession? = when (backendProvider()) {
        ClipboardLinkConfig.BACKEND_SHIZUKU ->
            runCatching { shizukuSession() }
                .onFailure { diagnose("shizuku session failed: ${it.javaClass.simpleName}") }
                .getOrNull()

        ClipboardLinkConfig.BACKEND_ROOT ->
            runCatching { suSession() }
                .onFailure { diagnose("su session failed: ${it.javaClass.simpleName}") }
                .getOrNull()

        else -> openAutoSession()
    }

    /** Auto: Shizuku first (no root), su as the fallback, alternating after repeated failures. */
    private suspend fun openAutoSession(): DaemonSession? {
        if (!preferRoot && shizukuReady()) {
            runCatching { return shizukuSession() }
                .onFailure { diagnose("shizuku session failed: ${it.javaClass.simpleName}") }
        }
        return runCatching { suSession() }
            .onFailure { diagnose("su session failed: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    private fun shizukuReady(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private suspend fun shizukuSession(): DaemonSession {
        val service = ShizukuServiceHelper.awaitService()
        val pipe = service.startClipboardDaemon(context.applicationInfo.sourceDir)
        return DaemonSession(
            name = "shizuku",
            input = android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe),
            close = { runCatching { service.stopClipboardDaemon() } },
        )
    }

    private fun suSession(): DaemonSession {
        // The app's own APK is the class path: the daemon class ships inside it, so there is no
        // second artifact to build, sign or keep in sync.
        val apk = context.applicationInfo.sourceDir
        val command = "app_process -Djava.class.path='$apk' /system/bin " +
            ClipboardDaemon::class.java.name
        diagnose("spawn su: $command")
        val child = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        child.outputStream.close()
        return DaemonSession(
            name = "su",
            input = child.inputStream,
            close = { runCatching { child.destroy() } },
        )
    }

    private fun diagnose(message: String) {
        Log.d(TAG, message)
        if (!BuildConfig.DEBUG) return
        runCatching {
            val file = File(context.getExternalFilesDir(null), "clipboard-debug.txt")
            if (file.length() > 64 * 1024) file.delete()
            file.appendText(System.currentTimeMillis().toString() + " [daemon] " + message + "\n")
        }
    }

    private companion object {
        const val TAG = "ClipboardDaemonHost"

        /** Shizuku bind plus a cold app_process start; long enough for a slow device. */
        const val READY_TIMEOUT_MS = 10_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
