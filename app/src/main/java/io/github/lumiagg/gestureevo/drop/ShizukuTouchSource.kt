package io.github.lumiagg.gestureevo.drop

import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import io.github.lumiagg.gestureevo.IUserService
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.diagnostics.DiagnosticLines
import io.github.lumiagg.gestureevo.gesture.GestureService
import io.github.lumiagg.gestureevo.utils.ToastHelper
import io.github.lumiagg.gestureevo.utils.shizuku.ShizukuServiceHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** MMGG RootTouchSource lifecycle with a shell getevent pipe instead of a root process. */
internal class ShizukuTouchSource(private val service: GestureService,
    private val event: (Int, Float, Float, Long) -> Unit, private val disconnected: () -> Unit) {
    private class Run {
        var job: Job? = null
        @Volatile var cancelled = false
        @Volatile var descriptor: ParcelFileDescriptor? = null
        @Volatile var token: String? = null
        @Volatile var remote: IUserService? = null
        val owner = Binder()
    }
    private var current: Run? = null

    fun start() {
        if (current != null) return
        val run = Run()
        current = run
        run.job = service.launch(Dispatchers.IO) {
            var reported = false
            while (isActive && !run.cancelled) {
                try {
                    check(rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) { "Shizuku permission unavailable" }
                    val shell = ShizukuServiceHelper.awaitService()
                    val id = UUID.randomUUID().toString()
                    run.remote = shell
                    run.token = id
                    val connectedAt = SystemClock.elapsedRealtime()
                    val ready = java.util.concurrent.atomic.AtomicBoolean()
                    val death = IBinder.DeathRecipient { runCatching { run.descriptor?.close() } }
                    shell.asBinder().linkToDeath(death, 0)
                    val deadline = service.launch(Dispatchers.IO) {
                        delay(10_000)
                        if (!ready.get() && run.token == id) runCatching { shell.closeTouchStream(id) }
                    }
                    try {
                        if (run.cancelled) break
                        val pipe = shell.openTouchStream(id, run.owner)
                        run.descriptor = pipe
                        if (run.cancelled || !isActive) { pipe.close(); break }
                        val manager = service.getSystemService(WindowManager::class.java)
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
                            service.launch(Dispatchers.Main) {
                                try { if (!run.cancelled && run.token == id) event(action, point[0], point[1], frameTime) }
                                finally { pendingFrames.decrementAndGet() }
                            }
                        }
                        ParcelFileDescriptor.AutoCloseInputStream(pipe).use { input ->
                            DiagnosticLines.read(input, maxLength = 512, onDiscard = { throw IllegalStateException("Oversized input frame") }) { line ->
                                if (run.cancelled || !isActive) return@read false
                                if (!ready.get()) {
                                    val header = line.split(' ')
                                    check(header.size == 5 && header[0] == "EVO_TOUCH") { "Touch stream not ready" }
                                    device = TouchInputProtocol.Device("", header[1].toInt(), header[2].toInt(), header[3].toInt(), header[4].toInt())
                                    check(device!!.maxX > device!!.minX && device!!.maxY > device!!.minY)
                                    ready.set(true)
                                    deadline.cancel()
                                } else {
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
                        if (!run.cancelled && SystemClock.elapsedRealtime() - connectedAt < 290_000) {
                            throw IllegalStateException("Touch stream ended before its lease expired")
                        }
                    } finally {
                        deadline.cancel()
                        runCatching { shell.asBinder().unlinkToDeath(death, 0) }
                        runCatching { run.descriptor?.close() }
                        run.descriptor = null
                        runCatching { shell.closeTouchStream(id) }
                        if (run.token == id) run.token = null
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    android.util.Log.w("EVO-Press", "Touch input unavailable", error)
                    if (!reported) {
                        reported = true
                        withContext(Dispatchers.Main) { if (!run.cancelled) ToastHelper.showInUIThread(service, service.getString(R.string.global_press_permission_required)) }
                    }
                } finally {
                    withContext(Dispatchers.Main) { if (!run.cancelled) disconnected() }
                }
                delay(3000)
            }
        }
    }

    fun stop() {
        val run = current ?: return
        current = null
        run.cancelled = true
        run.job?.cancel()
        val pipe = run.descriptor
        run.descriptor = null
        val shell = run.remote
        val id = run.token
        run.token = null
        service.launch(NonCancellable + Dispatchers.IO) {
            runCatching { pipe?.close() }
            if (id != null) runCatching { shell?.closeTouchStream(id) }
        }
        disconnected()
    }
}
