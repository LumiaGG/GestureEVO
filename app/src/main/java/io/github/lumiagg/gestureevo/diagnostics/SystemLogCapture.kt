package io.github.lumiagg.gestureevo.diagnostics

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import io.github.lumiagg.gestureevo.BuildConfig
import io.github.lumiagg.gestureevo.IUserService
import io.github.lumiagg.gestureevo.utils.shizuku.ShizukuServiceHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resumeWithException

 data class CaptureStatus(val running: Boolean = false, val result: String = "idle", val count: Int = 0,
    val exitCode: Int? = null, val discardedLines: Int = 0)

/** Continuous capture with cancellable local reads, a bounded Binder executor, and a remote hard deadline. */
object SystemLogCapture {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableStatus = MutableStateFlow(CaptureStatus())
    val status = mutableStatus.asStateFlow()
    private var job: Job? = null
    @Volatile private var activeToken: String? = null
    private val targets = RecentTargets()
    private fun pool(name: String) = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2)) {
        runnable -> Thread(runnable, name).apply { isDaemon = true }
    }
    private val binder = pool("diagnostic-binder")
    private val readers = pool("diagnostic-reader")
    fun rememberTarget(pkg: String?) = targets.remember(pkg)

    fun shizukuAvailable(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun closeRemote(service: IUserService?, token: String) {
        if (service == null) return
        runCatching { binder.execute { runCatching { service.closeDiagnosticStream(token) } } }
    }

    private suspend fun <T> awaitWorker(pool: ThreadPoolExecutor, late: (T) -> Unit = {}, operation: () -> T): T =
        suspendCancellableCoroutine { continuation ->
            val task = FutureTask<Unit> {
                try {
                    val result = operation()
                    continuation.resume(result) { _, value, _ -> late(value) }
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
            continuation.invokeOnCancellation { task.cancel(true) }
            try { pool.execute(task) } catch (e: java.util.concurrent.RejectedExecutionException) {
                continuation.resumeWithException(e)
            }
        }

    fun start(useRoot: Boolean) {
        if (!AppLogger.enabled || mutableStatus.value.running) return
        val token = UUID.randomUUID().toString()
        activeToken = token
        mutableStatus.value = CaptureStatus(true, "starting")
        val captureJob = scope.launch(start = CoroutineStart.LAZY) {
            val owner = currentCoroutineContext().job
            val count = AtomicInteger()
            val discarded = AtomicInteger()
            var result = "stream_closed"
            var code: Int? = null
            var input: ParcelFileDescriptor.AutoCloseInputStream? = null
            var relay: DiagnosticRelay? = null
            var service: IUserService? = null
            val startWall = System.currentTimeMillis()
            var expired = false
            val deadline = launch {
                delay(60_000)
                expired = true
                owner.cancel()
            }
            AppLogger.event(LogModule.SYSTEM, "capture_started", trace = token,
                fields = mapOf("method" to if (useRoot) "root" else "shizuku"))
            try {
                if (!useRoot && !shizukuAvailable()) {
                    result = "permission_missing"
                } else {
                    val descriptor = if (useRoot) {
                        val pipe = ParcelFileDescriptor.createPipe()
                        try {
                            relay = DiagnosticRelay(listOf("su", "-c", DiagnosticRelay.rootCommand),
                                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]))
                            pipe[0]
                        } catch (e: Exception) { pipe.forEach { it.close() }; throw e }
                    } else {
                        service = withTimeout(5000) { ShizukuServiceHelper.awaitService() }
                        val connected = requireNotNull(service)
                        withTimeout(5000) {
                            awaitWorker(binder, late = { fd: ParcelFileDescriptor ->
                                runCatching { fd.close() }
                                closeRemote(connected, token)
                            }) { connected.openDiagnosticStream(token) }
                        }
                    }
                    input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                    val stream = requireNotNull(input)
                    if (activeToken == token) mutableStatus.value = CaptureStatus(true, "running")
                    val terminal = awaitWorker(readers) {
                        var terminalReason = "stream_closed"
                        var terminalCode: Int? = null
                        DiagnosticLines.read(stream, onDiscard = { discarded.incrementAndGet() }) { line ->
                            if (!owner.isActive) false
                            else if (line.startsWith("GEVO_END ")) {
                                val parts = line.split(' ')
                                terminalReason = parts.getOrNull(1)?.takeIf { it in setOf("output_limit", "stream_closed", "command_failed") } ?: "stream_closed"
                                terminalCode = parts.getOrNull(2)?.toIntOrNull()
                                false
                            } else {
                                val timestamp = line.trimStart().substringBefore(' ').toDoubleOrNull()?.times(1000)?.toLong()
                                if (timestamp != null && timestamp >= startWall) {
                                    val metadata = classify(line, targets.snapshot() + BuildConfig.APPLICATION_ID)
                                    if (metadata != null) {
                                        AppLogger.event(LogModule.SYSTEM, "launch_observed", trace = token,
                                            fields = metadata + ("systemTimeMs" to timestamp))
                                        count.incrementAndGet()
                                        if (activeToken == token) mutableStatus.value = CaptureStatus(true, "running", count.get(), discardedLines = discarded.get())
                                    }
                                }
                                if (count.get() >= 1000) { terminalReason = "event_limit"; false } else true
                            }
                        }
                        terminalReason to terminalCode
                    }
                    result = terminal.first
                    code = terminal.second
                }
            } catch (e: TimeoutCancellationException) {
                result = "service_timeout"
            } catch (e: CancellationException) {
                result = if (expired) "complete" else "stopped"
                throw e
            } catch (e: Exception) {
                result = when (e) {
                    is java.util.concurrent.RejectedExecutionException -> "collector_busy"
                    is android.os.DeadObjectException -> "service_disconnected"
                    is android.os.RemoteException -> "protocol_unsupported"
                    is SecurityException -> "permission_missing"
                    else -> if (useRoot) "root_unavailable" else "service_disconnected"
                }
                AppLogger.event(LogModule.SYSTEM, "capture_failed", LogLevel.WARN, token,
                    mapOf("reason" to result), e)
            } finally {
                deadline.cancel()
                runCatching { input?.close() }
                runCatching { relay?.close() }
                closeRemote(service, token)
                AppLogger.event(LogModule.SYSTEM, "capture_finished", trace = token,
                    fields = mapOf("reason" to result, "count" to count.get(), "exitCode" to code, "dropped" to discarded.get()))
                if (activeToken == token) mutableStatus.value = CaptureStatus(false, result, count.get(), code, discarded.get())
            }
        }
        job = captureJob
        captureJob.invokeOnCompletion {
            if (activeToken == token && mutableStatus.value.running) mutableStatus.value = mutableStatus.value.copy(running = false, result = "stopped")
        }
        captureJob.start()
    }

    fun stop() { job?.cancel() }
    suspend fun stopAndJoin() { job?.cancelAndJoin() }

    internal fun classify(line: String, packages: List<String>): Map<String, Any?>? {
        val pkg = packages.firstOrNull { Regex("(?<![A-Za-z0-9_.])${Regex.escape(it)}(?![A-Za-z0-9_.])").containsMatchIn(line) } ?: return null
        val message = line.substringBefore("Intent {").substringBefore("intent {")
        val reason = when {
            message.contains("Background activity start [", true) ||
                (message.contains("background", true) && Regex("\\b(denied|deny|denial|blocked|blocking|abort|aborted)\\b", RegexOption.IGNORE_CASE).containsMatchIn(message)) -> "background_launch_blocked"
            message.contains("Permission Denial", true) -> "permission_denied"
            message.contains("START u") -> "launch_requested"
            message.contains("Displayed ") -> "activity_displayed"
            else -> return null
        }
        return mapOf("package" to pkg, "reason" to reason, "source" to "logcat")
    }
}
