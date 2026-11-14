package io.github.lumiagg.gestureevo.drop

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.utils.process.ProcessOutputLines
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import io.github.lumiagg.gestureevo.utils.ToastHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One input stream owns a gesture; backend changes always cancel the current gesture first. */
internal class TouchInputSource(private val context: Context, private val scope: CoroutineScope,
    private val event: (Int, Float, Float, Long) -> Unit, private val disconnected: () -> Unit) {
    private class Run {
        var job: Job? = null
        @Volatile var cancelled = false
        @Volatile var connection: TouchInputConnection? = null
        var useRoot = false
        var rootFailed = false
    }
    private var current: Run? = null
    var rootActive = false
        private set

    fun start(rootEnabled: Boolean = false) {
        if (current != null) return
        val run = Run().apply { useRoot = rootEnabled }
        current = run
        PressInputStatus.update(if (rootEnabled) R.string.global_press_status_root_connecting else R.string.global_press_status_standard_connecting)
        run.job = scope.launch(Dispatchers.IO) {
            var reported = false
            while (isActive && !run.cancelled) {
                withContext(Dispatchers.Main) {
                    if (!run.cancelled) PressInputStatus.update(when {
                        run.useRoot -> R.string.global_press_status_root_connecting
                        run.rootFailed -> R.string.global_press_status_falling_back
                        else -> R.string.global_press_status_standard_connecting
                    })
                }
                try {
                    val connection: TouchInputConnection = if (run.useRoot) RootTouchConnection() else ShizukuTouchConnection()
                    run.connection = connection
                    val ready = java.util.concurrent.atomic.AtomicBoolean()
                    val deadline = scope.launch(Dispatchers.IO) {
                        delay(10_000)
                        if (!ready.get() && run.connection === connection) {
                            runCatching { connection.close() }
                        }
                    }
                    try {
                        if (run.cancelled) break
                        val pipe = connection.open()
                        if (run.cancelled || !isActive) { pipe.close(); break }
                        val manager = context.getSystemService(WindowManager::class.java)
                        var device: TouchInputProtocol.Device? = null
                        var eventTime = 0L
                        val pendingFrames = java.util.concurrent.atomic.AtomicInteger()
                        val parser = EvdevTouchParser { action, x, y, _, _, _ ->
                            val metrics = DisplayMetrics()
                            @Suppress("DEPRECATION")
                            manager.defaultDisplay.getRealMetrics(metrics)
                            @Suppress("DEPRECATION")
                            val point = TouchInputProtocol.map(x, y, checkNotNull(device), metrics.widthPixels, metrics.heightPixels, manager.defaultDisplay.rotation)
                            val frameTime = eventTime
                            check(pendingFrames.incrementAndGet() <= 64) { "Touch delivery queue exceeded its budget" }
                            scope.launch(Dispatchers.Main) {
                                try { if (!run.cancelled && run.connection === connection) event(action, point[0], point[1], frameTime) }
                                finally { pendingFrames.decrementAndGet() }
                            }
                        }
                        ParcelFileDescriptor.AutoCloseInputStream(pipe).use { input ->
                            ProcessOutputLines.read(input, maxLength = 512, onDiscard = { throw IllegalStateException("Oversized input frame") }) { line ->
                                if (run.cancelled || !isActive) return@read false
                                if (!ready.get()) {
                                    val header = line.split(' ')
                                    check(header.size == 5 && header[0] == "EVO_TOUCH") { "Touch stream not ready" }
                                    device = TouchInputProtocol.Device("", header[1].toInt(), header[2].toInt(), header[3].toInt(), header[4].toInt())
                                    check(device!!.maxX > device!!.minX && device!!.maxY > device!!.minY)
                                    ready.set(true)
                                    deadline.cancel()
                                    scope.launch(Dispatchers.Main) {
                                        if (!run.cancelled && run.connection === connection) {
                                            rootActive = run.useRoot
                                            PressInputStatus.update(when {
                                                run.useRoot -> R.string.global_press_status_root_ready
                                                run.rootFailed -> R.string.global_press_status_fallback_ready
                                                else -> R.string.global_press_status_standard_ready
                                            })
                                        }
                                    }
                                } else {
                                    // Accept the terminator emitted by older touch workers until they are replaced.
                                    if (line.startsWith("GEVO_END")) return@read false
                                    val timestamp = TouchInputProtocol.eventTime(line, SystemClock.uptimeMillis())
                                    if (timestamp == null) {
                                        parser.cancel()
                                    } else {
                                        eventTime = timestamp
                                        TouchInputProtocol.event(line)?.let { values ->
                                            reported = false
                                            if (values[0] == 0 && values[1] == 3) throw IllegalStateException("Input frames lost")
                                            parser.consume(values[0], values[1], values[2])
                                        }
                                    }
                                }
                                true
                            }
                        }
                        if (!run.cancelled) throw IllegalStateException("Touch stream ended unexpectedly")
                    } finally {
                        deadline.cancel()
                        runCatching { connection.close() }
                        if (run.connection === connection) run.connection = null
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    android.util.Log.w("EVO-Press", "Touch input unavailable", error)
                    val rootFailed = run.useRoot
                    if (rootFailed) { run.useRoot = false; run.rootFailed = true }
                    withContext(Dispatchers.Main) {
                        if (!run.cancelled) {
                            PressInputStatus.update(if (rootFailed) R.string.global_press_status_falling_back else R.string.global_press_status_unavailable)
                            if (!reported) {
                                reported = true
                                ToastHelper.showInUIThread(context, context.getString(if (rootFailed)
                                    R.string.global_press_root_fallback else R.string.global_press_permission_required))
                            }
                        }
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        if (!run.cancelled) { rootActive = false; disconnected() }
                    }
                }
                delay(3000)
            }
        }
    }

    fun stop() {
        rootActive = false
        PressInputStatus.update(R.string.global_press_status_stopped)
        val run = current ?: return
        current = null
        run.cancelled = true
        run.job?.cancel()
        val connection = run.connection
        run.connection = null
        scope.launch(NonCancellable + Dispatchers.IO) {
            runCatching { connection?.close() }
        }
        disconnected()
    }
}
