package io.github.lumiagg.gestureevo.diagnostics

import android.app.Activity
import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.edit
import io.github.lumiagg.gestureevo.BuildConfig
import io.github.lumiagg.gestureevo.utils.CurrentPackageProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object AppLogger {
    private val context: Context get() = io.github.lumiagg.gestureevo.EvoApplication.application
    lateinit var store: LogStore
        private set
    private lateinit var fatalStore: LogStore
    private lateinit var stores: List<LogStore>
    private lateinit var preferences: android.content.SharedPreferences
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(512)) { runnable ->
        Thread(runnable, "GestureEVO-logs").apply { isDaemon = true }
    }
    private val dropped = AtomicLong()
    private val totalDropped = AtomicLong()
    private val failedWrites = AtomicLong()
    private val maintenance = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastFailure = "none"
    @Volatile private var lastFailureAt = 0L
    @Volatile var foreground: Boolean = false
        private set
    @Volatile private var detailedUntilElapsed = 0L
    @Volatile private var enabledValue = true
    val enabled: Boolean get() = enabledValue
    val detailed: Boolean get() = enabled && SystemClock.elapsedRealtime() < detailedUntilElapsed
    val root: File get() = File(context.noBackupFilesDir, "logs")

    fun initialize(application: Application) {
        preferences = context.getSharedPreferences("diagnostic_logging", Context.MODE_PRIVATE)
        enabledValue = preferences.getBoolean("enabled", true)
        val main = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses
            ?.firstOrNull { it.pid == Process.myPid() }?.processName == context.packageName
        store = LogStore(File(root, if (main) "main" else "exception"), totalLimit = (if (main) 17L else 2L) * 1024 * 1024)
        fatalStore = LogStore(File(root, if (main) "crash-main" else "crash-exception"),
            fileLimit = 256 * 1024, totalLimit = 512 * 1024)
        stores = listOf("main", "exception", "crash-main", "crash-exception").map { name ->
            when (name) {
                store.directory.name -> store
                fatalStore.directory.name -> fatalStore
                else -> LogStore(File(root, name), totalLimit = when (name) {
                    "main" -> 17L * 1024 * 1024
                    "exception" -> 2L * 1024 * 1024
                    else -> 512L * 1024
                })
            }
        }
        if (!main) return
        ProblemSessions.initialize(File(context.noBackupFilesDir, "diagnostic_sessions.json"))
        SystemLogCapture.rememberTarget(context.packageName)
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) { started++; foreground = true }
            override fun onActivityStopped(activity: Activity) { started = (started - 1).coerceAtLeast(0); foreground = started > 0 }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        event(LogModule.APP, "process_started", fields = mapOf("pid" to Process.myPid(),
            "versionName" to BuildConfig.VERSION_NAME, "versionCode" to BuildConfig.VERSION_CODE))
    }

    fun setEnabled(value: Boolean) {
        enabledValue = value
        preferences.edit { putBoolean("enabled", value) }
        if (!value) {
            detailedUntilElapsed = 0
            SystemLogCapture.stop()
            maintenance.launch { runCatching { ProblemSessions.finish("logging_disabled") } }
        }
        event(LogModule.APP, "logging_enabled")
    }

    fun setDetailed(value: Boolean) {
        detailedUntilElapsed = if (value && enabled) SystemClock.elapsedRealtime() + 30 * 60 * 1000L else 0
        event(LogModule.APP, if (value) "diagnostics_enabled" else "diagnostics_disabled")
    }

    internal fun detailDeadline(): Long = detailedUntilElapsed
    internal fun restoreDetailDeadline(expected: Long, previous: Long) {
        if (detailedUntilElapsed == expected) detailedUntilElapsed = previous
    }

    private fun lose(reason: String, writeFailure: Boolean = false) {
        dropped.incrementAndGet()
        totalDropped.incrementAndGet()
        if (writeFailure) failedWrites.incrementAndGet()
        lastFailure = reason
        lastFailureAt = System.currentTimeMillis()
    }

    fun health(): LogHealth = LogHealth(totalDropped.get(), failedWrites.get(), writer.queue.size, lastFailure, lastFailureAt)

    internal fun storageFailure() {
        failedWrites.incrementAndGet()
        lastFailure = "io_error"
        lastFailureAt = System.currentTimeMillis()
    }

    fun event(module: LogModule, event: String, level: LogLevel = LogLevel.INFO, trace: String? = null,
              fields: Map<String, Any?> = emptyMap(), error: Throwable? = null) {
        if (!::store.isInitialized || !enabled || (level == LogLevel.DEBUG && !detailed)) return
        if (!event.matches(Regex("[a-z0-9_]{1,80}"))) return
        val record = runCatching { LogRecord(System.currentTimeMillis(), level, module, event, trace,
            LogPrivacy.fields(error?.let(LogErrors::fields).orEmpty() + fields), error?.let(LogPrivacy::stack),
            session = ProblemSessions.active?.id) }.getOrElse { lose("encoding_failed"); return }
        try {
            writer.execute {
                val lost = dropped.getAndSet(0)
                var reported = lost == 0L
                try {
                    if (lost > 0) reported = store.append(LogRecord(System.currentTimeMillis(), LogLevel.WARN, LogModule.APP,
                        "records_dropped", fields = mapOf("dropped" to lost.toString()), session = record.session))
                    if (!store.append(record)) lose("write_rejected", true)
                } catch (_: Exception) {
                    lose("io_error", true)
                } finally {
                    if (!reported) dropped.addAndGet(lost)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { lose("queue_full") }
    }

    /** Independent short write: never wait behind the normal writer queue during an uncaught exception. */
    fun crash(thread: Thread, error: Throwable) {
        if (!::store.isInitialized || !enabled) return
        runCatching {
            fatalStore.append(LogRecord(System.currentTimeMillis(), LogLevel.ERROR, LogModule.APP, "uncaught_exception",
                fields = LogPrivacy.fields(LogErrors.fields(error) + mapOf("thread" to thread.name)), error = LogPrivacy.stack(error),
                session = ProblemSessions.active?.id), waitMs = 100).also { if (!it) lose("write_rejected", true) }
        }.onFailure { lose("io_error", true) }
    }

    suspend fun <T> ordered(operation: () -> T): T = withContext(Dispatchers.IO) {
        val result = CompletableDeferred<T>()
        // Queue full is a visible management error, never silently skip clear/export.
        writer.execute { runCatching(operation).fold({ result.complete(it) }, { result.completeExceptionally(it) }) }
        withTimeout(10_000) { result.await() }
    }

    fun actionState(): Map<String, Any?> = mapOf(
        "foreground" to foreground,
        "overlay" to runCatching { Settings.canDrawOverlays(context) }.getOrNull(),
        "accessibility" to CurrentPackageProvider.currentServiceConnected(),
        "shizuku" to SystemLogCapture.shizukuAvailable(),
    )

    fun environment(): String = buildString {
        appendLine("GestureEVO ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android ${android.os.Build.VERSION.RELEASE}; API ${android.os.Build.VERSION.SDK_INT}")
        appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine("App: ${context.packageName}; process: ${Process.myPid()}")
        appendLine("Captured at: ${java.util.Date()}")
        actionState().forEach { (key, value) -> appendLine("$key: ${value ?: "unknown"}") }
        appendLine("Vendor background launch permission: unknown")
        appendLine("A submitted launch request does not confirm that the target activity or macro ran.")
        appendLine("Exception messages, Intent extras and user content are excluded.")
        appendLine("Logger health: ${health()}")
    }

    fun allStores(): List<LogStore> = stores
}

data class LogHealth(val dropped: Long, val failedWrites: Long, val queued: Int, val lastFailure: String, val lastFailureAt: Long)

private fun CurrentPackageProvider.currentServiceConnected(): Boolean = accessibilityService() != null
