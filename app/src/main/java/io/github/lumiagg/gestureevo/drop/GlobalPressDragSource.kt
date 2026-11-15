package io.github.lumiagg.gestureevo.drop

import io.github.lumiagg.gestureevo.floating.OverlayTouchRegions
import android.app.KeyguardManager
import android.content.ClipData
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.content.Context
import android.view.WindowManager
import io.github.lumiagg.gestureevo.ITouchInterruptionGuard
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import io.github.lumiagg.gestureevo.drop.DropPreferences.KEY_GLOBAL_PRESS_ENABLED
import io.github.lumiagg.gestureevo.drop.DropPreferences.KEY_GLOBAL_PRESS_ALLOWED
import io.github.lumiagg.gestureevo.drop.DropPreferences.KEY_GLOBAL_PRESS_TIMEOUT
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_INSPIRE_SELECTION_VIBRATE
import io.github.lumiagg.gestureevo.utils.VibratorUtils
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** MMGG PressCaptureSource timing/generation pipeline; both kinds enter EVO's drop panel. */
internal class GlobalPressDragSource(
    private val context: Context,
    private val scope: CoroutineScope,
    private val capture: PressContentCapture,
    private val panel: () -> ContentDragTarget?,
) {
    private data class Settings(val enabled: Boolean, val timeout: Int, val whitelist: Set<String>, val vibrate: Boolean)
    private val main = Handler(Looper.getMainLooper())
    private val detector = PressTriggerDetector(ViewConfiguration.get(context).scaledTouchSlop.toFloat())
    private val blocker = BackgroundTouchBlocker(context, scope)
    private val captureGate = Mutex()
    private var settings = Settings(false, 500, emptySet(), true)
    private var settingsJob: Job? = null
    private var sourceCheck: Job? = null
    private var preparation: Job? = null
    private var preparationTimeout: Job? = null
    private var windowCheck: Job? = null
    private var interruptionAllowed: AtomicBoolean? = null
    private var source: PressContentCapture.Source? = null
    @Volatile private var generation = 0L
    private var active = false
    private var shown = false
    private var preparing = false
    private var readyNotified = false
    val isGestureActive: Boolean get() = active || shown || preparing
    private var systemDrag = false
    private var captureX = 0f
    private var captureY = 0f
    private var latestX = 0f
    private var latestY = 0f
    private val input = TouchInputSource(context, scope, ::onPointer) { cancel("input_disconnected") }
    private val arm = Runnable {
        if (!allowed()) cancel("confirmation_not_allowed")
        else {
            detector.arm(SystemClock.uptimeMillis())
            captureX = latestX
            captureY = latestY
            notifyReady()
        }
    }
    private val expiry = Runnable { cancel("gesture_timeout") }

    fun start() {
        settingsJob = scope.launch(Dispatchers.Main) {
            DropPreferences.ensureGlobalPressWhitelist(context)
            context.settingsStore.data.map {
                Settings(it.readKey(KEY_GLOBAL_PRESS_ENABLED, false), it.readKey(KEY_GLOBAL_PRESS_TIMEOUT, 500).coerceIn(200, 2000),
                    it.readKey(KEY_GLOBAL_PRESS_ALLOWED, "").split('|').filter(String::isNotBlank).toSet(),
                    it.readKey(KEY_INSPIRE_SELECTION_VIBRATE, true))
            }.distinctUntilChanged().collect { updated ->
                cancel("source_settings_changed")
                settings = updated
                if (updated.enabled && updated.whitelist.isNotEmpty() && screenReady()) input.start() else input.stop()
            }
        }
    }

    private fun screenReady(): Boolean = context.getSystemService(PowerManager::class.java).isInteractive &&
        !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private fun allowed(): Boolean = settings.enabled && active && !systemDrag && screenReady() && panel() != null

    private fun onPointer(action: Int, x: Float, y: Float, time: Long) {
        if (kotlin.math.abs(SystemClock.uptimeMillis() - time) > 1000) { cancel("stale_input"); return }
        latestX = x
        latestY = y
        if (action == MotionEvent.ACTION_DOWN) {
            cancel("next_down")
            if (systemDrag || !settings.enabled || !screenReady() || panel() == null || OverlayTouchRegions.contains(x, y)) return
            active = true
            detector.start(x, y, time, settings.timeout.toLong())
            checkSource(x, y)
            main.postAtTime(arm, detector.deadline())
            main.postDelayed(expiry, 15_000)
            return
        }
        if (!active) return
        if (!allowed()) { cancel("pointer_not_allowed"); return }
        if (action == MotionEvent.ACTION_UP) {
            if (shown) panel()?.updateContentDrag(x, y, release = true)
            cancel("pointer_up")
            return
        }
        detector.onPointerEvent(action, x, y, 1)
        if (!detector.isActive) {
            cancel(if (action == MotionEvent.ACTION_MOVE) "moved_before_hold" else "contact_cancelled")
            return
        }
        if (detector.isFired) prepareDrag()
        if (shown || preparing) panel()?.updateContentDrag(x, y)
    }

    /** Lock only the source window on DOWN; no content tree traversal or screenshots yet. */
    private fun checkSource(x: Float, y: Float) {
        val request = generation
        sourceCheck = scope.launch(Dispatchers.IO) {
            try {
                val origin = capture.sourceAt(x, y)
                val rejection = when {
                    origin == null -> "source_unavailable"
                    origin.packageName !in settings.whitelist -> "app_not_allowed"
                    capture.excludedPackage(origin.packageName) -> "protected_package"
                    else -> null
                }
                withContext(Dispatchers.Main) {
                    if (generation != request || !allowed()) return@withContext
                    if (rejection != null || origin == null) {
                        cancel(rejection ?: "source_unavailable")
                        return@withContext
                    }
                    source = origin
                    notifyReady()
                    if (detector.isFired) prepareDrag()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                withContext(Dispatchers.Main) { if (generation == request) cancel("source_check_failed") }
            }
        }
    }

    private fun notifyReady() {
        if (readyNotified || !detector.isReady || source == null || !allowed()) return
        readyNotified = true
        android.util.Log.i("EVO-Press", "press_ready generation=$generation elapsedMs=${SystemClock.uptimeMillis() - detector.downTime()}")
        if (settings.vibrate) VibratorUtils.vibrator(context, true)
    }

    private fun prepareDrag() {
        if (shown || preparing || !detector.isFired || !allowed()) return
        val origin = source ?: return // The lightweight source check may still be in flight.
        preparing = true
        val request = generation
        val started = SystemClock.uptimeMillis()
        val x = captureX
        val y = captureY
        val inputX = latestX
        val inputY = latestY
        val overEvoWindow = OverlayTouchRegions.contains(inputX, inputY)
        @Suppress("DEPRECATION")
        val displayId = context.getSystemService(WindowManager::class.java).defaultDisplay.displayId
        val permitted = AtomicBoolean(true).also { interruptionAllowed = it }
        val guard = object : ITouchInterruptionGuard.Stub() {
            override fun isCurrent(): Boolean = permitted.get()
        }
        preparation = scope.launch(Dispatchers.IO) {
            if (!captureGate.tryLock()) {
                withContext(Dispatchers.Main) { if (generation == request) cancel("capture_busy") }
                return@launch
            }
            try {
                if (overEvoWindow || !capture.isSourceAppCurrent(origin)) {
                    withContext(Dispatchers.Main) { if (generation == request) cancel("source_changed") }
                    return@launch
                }
                coroutineScope {
                    val content = async { capture.capture(x, y) }
                    val interruption = async {
                        try {
                            input.interruptSourceTouch(origin.packageName, inputX, inputY, displayId,
                                SystemClock.uptimeMillis() + 500, guard)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            android.util.Log.w("EVO-TouchBlock", "shizuku_cancel_failed type=${error.javaClass.simpleName}")
                            false
                        }
                    }
                    val payload = content.await()
                    if (payload == null) {
                        withContext(Dispatchers.Main) { if (generation == request) cancel("content_unavailable") }
                        return@coroutineScope
                    }
                    val interrupted = interruption.await()
                    val sourceCurrent = capture.isSourceAppCurrent(origin)
                    withContext(Dispatchers.Main) {
                        if (generation != request || !permitted.get() || !allowed()) return@withContext
                        if (!sourceCurrent) { cancel("source_app_changed"); return@withContext }
                        if (SystemClock.uptimeMillis() - started > 1800) { cancel("preparation_timeout"); return@withContext }
                        preparationTimeout?.cancel()
                        preparationTimeout = null
                        DropDiagnostics.event("drop_source_prepared", "internal", request.toInt(),
                            reason = if (interrupted) "interrupted" else "interruption_failed")
                        blocker.reportCancellationResult(interrupted)
                        beginPanel(payload, request)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                android.util.Log.w("EVO-Press", "Press preparation failed", error)
                withContext(Dispatchers.Main) { if (generation == request) cancel("preparation_failed") }
            } finally { captureGate.unlock() }
        }
        // Main-thread watchdog revokes Binder permission even if a remote call outlives cancellation.
        preparationTimeout = scope.launch(Dispatchers.Main) {
            delay(1800)
            if (generation == request && preparing && !shown) cancel("preparation_timeout")
        }
    }

    private fun beginPanel(content: ClipData, request: Long) {
        if (generation != request || !allowed()) return
        val accepted = panel()?.beginContentDrag(content, latestX, latestY,
            stillCurrent = { generation == request && allowed() },
            onReady = {
                if (generation == request && allowed()) {
                    preparing = false
                    shown = true
                    android.util.Log.i("EVO-Press", "source_ready generation=$request")
                }
            },
            onFailed = { reason -> if (generation == request) cancel("panel_$reason") },
        ) == true
        if (!accepted) cancel("panel_begin_failed")
        else blocker.start { generation == request && allowed() }
    }

    fun onWindowEvent(event: AccessibilityEvent) {
        if (!active || (event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
                event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) return
        val origin = source ?: return
        val request = generation
        windowCheck?.cancel()
        windowCheck = scope.launch(Dispatchers.IO) {
            try {
                val current = capture.isSourceAppCurrent(origin)
                withContext(Dispatchers.Main) {
                    if (generation == request && active && !current) cancel("source_app_changed")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                withContext(Dispatchers.Main) { if (generation == request) cancel("source_check_failed") }
            }
        }
    }

    fun onSystemDragStarted() {
        systemDrag = true
        cancel("system_drag_started")
    }
    fun onSystemDragEnded() { systemDrag = false }
    fun onScreenChanged() {
        cancel("screen_changed")
        if (settings.enabled && settings.whitelist.isNotEmpty() && screenReady()) input.start() else input.stop()
    }
    fun cancel(reason: String = "lifecycle") {
        if (active || shown || preparing) DropDiagnostics.event("drop_source_cancelled", "internal", generation.toInt(),
            reason = reason.substringBefore(':'), flags = (if (shown) 1 else 0) or (if (preparing) 2 else 0))
        if (active || shown || preparing) android.util.Log.i("EVO-Press", "cancel reason=$reason generation=$generation shown=$shown preparing=$preparing")
        generation++
        // A remote Binder call may outlive coroutine cancellation. Revoke its guard immediately.
        interruptionAllowed?.set(false)
        interruptionAllowed = null
        preparation?.cancel()
        preparation = null
        active = false
        shown = false
        preparing = false
        readyNotified = false
        source = null
        detector.cancel()
        main.removeCallbacks(arm)
        main.removeCallbacks(expiry)
        sourceCheck?.cancel()
        sourceCheck = null
        preparationTimeout?.cancel()
        preparationTimeout = null
        windowCheck?.cancel()
        windowCheck = null
        panel()?.cancelContentDrag()
        blocker.stop()
    }
    fun close() {
        settingsJob?.cancel()
        settingsJob = null
        cancel("source_closed")
        input.stop()
        blocker.close()
    }
}
