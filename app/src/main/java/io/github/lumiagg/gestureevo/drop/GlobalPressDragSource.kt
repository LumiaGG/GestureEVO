package io.github.lumiagg.gestureevo.drop

import android.app.KeyguardManager
import android.content.ClipData
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import io.github.lumiagg.gestureevo.gesture.GestureService
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_GLOBAL_PRESS_ENABLED
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_GLOBAL_PRESS_EXCLUDED
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_GLOBAL_PRESS_TIMEOUT
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_GLOBAL_PRESS_ROOT
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_GLOBAL_PRESS_PROTECT
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_INSPIRE_SELECTION_VIBRATE
import io.github.lumiagg.gestureevo.utils.VibratorUtils
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** MMGG PressCaptureSource timing/generation pipeline; both kinds enter EVO's drop panel. */
internal class GlobalPressDragSource(private val service: GestureService, private val panel: () -> ExternalDropFloating?) {
    private data class Settings(val enabled: Boolean, val timeout: Int, val excluded: Set<String>, val vibrate: Boolean,
        val root: Boolean = false, val protect: Boolean = false)
    private val main = Handler(Looper.getMainLooper())
    private val detector = PressTriggerDetector(ViewConfiguration.get(service).scaledTouchSlop.toFloat())
    private val blocker = BackgroundTouchBlocker(service)
    private val capture = PressContentCapture(service)
    private val captureGate = Mutex()
    private var settings = Settings(false, 500, emptySet(), true)
    private var settingsJob: Job? = null
    private var lookup: Job? = null
    private var windowCheck: Job? = null
    private val ownOverlayIds = mutableSetOf<Int>()
    private var source: PressContentCapture.Source? = null
    private var cached: ClipData? = null
    @Volatile private var generation = 0L
    private var active = false
    private var shown = false
    private var preparing = false
    private var readyNotified = false
    private var popupNotified = false
    val isGestureActive: Boolean get() = active || shown || preparing
    private var systemDrag = false
    private var downX = 0f
    private var downY = 0f
    private var latestX = 0f
    private var latestY = 0f
    private val input = ShizukuTouchSource(service, ::onPointer) { cancel("input_disconnected") }
    private val classify = Runnable { if (detector.takeClassification(SystemClock.uptimeMillis())) lookup() }
    private val arm = Runnable {
        if (!allowed()) cancel("confirmation_not_allowed")
        else {
            detector.arm(SystemClock.uptimeMillis())
            notifyReady()
        }
    }
    private val protect = Runnable { protectLongPress() }
    private val expiry = Runnable { cancel("gesture_timeout") }

    fun start() {
        settingsJob = service.launch(Dispatchers.Main) {
            service.settingsStore.data.map {
                Settings(it.readKey(KEY_GLOBAL_PRESS_ENABLED, false), it.readKey(KEY_GLOBAL_PRESS_TIMEOUT, 500).coerceIn(200, 2000),
                    it.readKey(KEY_GLOBAL_PRESS_EXCLUDED, "").split('|').filter(String::isNotBlank).toSet(),
                    it.readKey(KEY_INSPIRE_SELECTION_VIBRATE, true), it.readKey(KEY_GLOBAL_PRESS_ROOT, false),
                    it.readKey(KEY_GLOBAL_PRESS_PROTECT, false))
            }.distinctUntilChanged().collect { updated ->
                cancel("source_settings_changed")
                if (settings.root != updated.root) input.stop()
                settings = updated
                if (updated.enabled && screenReady()) input.start(updated.root) else input.stop()
            }
        }
    }

    private fun screenReady(): Boolean = service.getSystemService(PowerManager::class.java).isInteractive &&
        !service.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private fun allowed(): Boolean = settings.enabled && active && !systemDrag && screenReady() && panel() != null

    private fun onPointer(action: Int, x: Float, y: Float, time: Long) {
        if (kotlin.math.abs(SystemClock.uptimeMillis() - time) > 1000) { cancel("stale_input"); return }
        latestX = x
        latestY = y
        if (action == MotionEvent.ACTION_DOWN) {
            cancel("next_down")
            if (systemDrag || !settings.enabled || !screenReady() || panel() == null || SourceTouchRegions.contains(x, y)) return
            active = true
            downX = x
            downY = y
            detector.start(x, y, time, settings.timeout.toLong())
            main.postAtTime(classify, detector.classificationDeadline())
            main.postAtTime(arm, detector.deadline())
            if (input.rootActive && settings.protect) main.postAtTime(protect,
                detector.protectionDeadline(ViewConfiguration.getLongPressTimeout().toLong()))
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
        if (!detector.isActive) { cancel("pointer_rejected:action=$action"); return }
        if (detector.isFired) display()
        if (shown || preparing) panel()?.updateContentDrag(x, y)
    }

    private fun lookup() {
        if (!allowed()) { cancel("lookup_not_allowed"); return }
        val request = generation
        val x = downX
        val y = downY
        lookup = service.launch(Dispatchers.IO) {
            if (!captureGate.tryLock()) {
                withContext(Dispatchers.Main) { if (generation == request) cancel("capture_busy") }
                return@launch
            }
            try {
                val origin = capture.sourceAt(x, y)
                if (origin == null || origin.packageName in settings.excluded || capture.excludedPackage(origin.packageName)) {
                    withContext(Dispatchers.Main) { if (generation == request) cancel("source_unavailable_or_excluded") }
                    return@launch
                }
                withContext(Dispatchers.Main) { if (generation == request) source = origin }
                val content = capture.capture(origin, x, y)
                withContext(Dispatchers.Main) {
                    if (generation != request || !allowed()) return@withContext
                    if (SystemClock.uptimeMillis() - detector.downTime() > 2000) { cancel("capture_expired"); return@withContext }
                    if (content == null) { cancel("content_unavailable"); return@withContext }
                    cached = content
                    android.util.Log.i("EVO-Press", "content_ready generation=$request elapsedMs=${SystemClock.uptimeMillis() - detector.downTime()} threshold=${settings.timeout}")
                    protectLongPress()
                    notifyReady()
                    if (detector.isFired) display()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                android.util.Log.w("EVO-Press", "Press capture failed", error)
                withContext(Dispatchers.Main) { if (generation == request) cancel("capture_failed") }
            } finally { captureGate.unlock() }
        }
        service.launch(Dispatchers.Main) {
            delay(1800)
            if (generation == request && cached == null && !shown && !preparing) cancel("capture_timeout")
        }
    }

    private fun protectLongPress() {
        if (!settings.protect || !input.rootActive || cached == null || !allowed() ||
            !detector.canProtectLongPress(SystemClock.uptimeMillis(), ViewConfiguration.getLongPressTimeout().toLong())) return
        val request = generation
        if (!blocker.hasRootRequest) android.util.Log.i("EVO-Press", "press_protect generation=$request elapsedMs=${SystemClock.uptimeMillis() - detector.downTime()}")
        blocker.protectLongPress { generation == request && allowed() && (detector.isReady ||
            detector.canProtectLongPress(SystemClock.uptimeMillis(), ViewConfiguration.getLongPressTimeout().toLong())) }
    }

    private fun notifyReady() {
        if (readyNotified || !detector.isReady || cached == null || !allowed()) return
        protectLongPress()
        readyNotified = true
        android.util.Log.i("EVO-Press", "press_ready generation=$generation elapsedMs=${SystemClock.uptimeMillis() - detector.downTime()}")
        if (settings.vibrate) VibratorUtils.vibrator(service, true)
    }

    private fun display() {
        if (shown || preparing || !allowed()) return
        val content = cached ?: return
        val request = generation
        preparing = true
        val accepted = panel()?.beginContentDrag(content, latestX, latestY,
            stillCurrent = { generation == request && allowed() },
            onReady = {
                if (generation == request && allowed()) {
                    preparing = false
                    shown = true
                    cached = null
                    android.util.Log.i("EVO-Press", "source_ready generation=$request sourceWindow=${source?.windowId}")
                }
            },
            onFailed = { reason -> if (generation == request) cancel("panel_$reason") },
        ) == true
        if (!accepted) cancel("panel_begin_failed")
        else blocker.start(rootEnabled = input.rootActive) { generation == request && allowed() }
    }

    fun onWindowEvent(event: AccessibilityEvent) {
        if (!active || (event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
                event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) return
        val origin = source ?: return
        val request = generation
        val eventType = event.eventType
        val eventId = event.windowId
        val changes = if (android.os.Build.VERSION.SDK_INT >= 28 &&
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) event.windowChanges else 0
        ownOverlayIds += SourceTouchRegions.windowIds()
        val sourceRemoved = android.os.Build.VERSION.SDK_INT >= 28 && eventId == origin.windowId &&
            changes and AccessibilityEvent.WINDOWS_CHANGE_REMOVED != 0
        if (sourceRemoved) { cancel("source_removed:event=$eventType,window=$eventId,changes=$changes"); return }
        windowCheck?.cancel()
        windowCheck = service.launch(Dispatchers.IO) {
            try {
                val windows = capture.windowSnapshot()
                withContext(Dispatchers.Main) {
                    if (generation != request || !active) return@withContext
                    windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                        it.packageName == service.packageName }.forEach { ownOverlayIds += it.id }
                    val allowSourceMenu = settings.protect && input.rootActive && blocker.hasRootRequest &&
                        (cached != null || shown || preparing)
                    val reason = PressWindowPolicy.cancellationReason(origin.windowId, origin.packageName, origin.bounds,
                        windows, eventId, eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, false,
                        service.packageName, ownOverlayIds, allowSourceMenu)
                    if (reason != null) {
                        cancel("$reason:event=$eventType,window=$eventId,changes=$changes")
                        return@withContext
                    }
                    if (allowSourceMenu && !popupNotified && windows.none { it.id == origin.windowId }) {
                        popupNotified = true
                        android.util.Log.i("EVO-Press", "source_covered_by_menu generation=$request elapsedMs=${SystemClock.uptimeMillis() - detector.downTime()}")
                        DropDiagnostics.event("drop_source_popup_tolerated", "internal", generation.toInt(), reason = "source_covered")
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                withContext(Dispatchers.Main) { if (generation == request) cancel("window_snapshot_failed") }
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
        if (settings.enabled && screenReady()) input.start(settings.root) else input.stop()
    }
    fun cancel(reason: String = "lifecycle") {
        if (active || shown || preparing) DropDiagnostics.event("drop_source_cancelled", "internal", generation.toInt(),
            reason = reason.substringBefore(':'), flags = (if (shown) 1 else 0) or (if (preparing) 2 else 0))
        if (active || shown || preparing) android.util.Log.i("EVO-Press", "cancel reason=$reason generation=$generation shown=$shown preparing=$preparing sourceWindow=${source?.windowId}")
        generation++
        active = false
        shown = false
        preparing = false
        readyNotified = false
        popupNotified = false
        source = null
        cached = null
        detector.cancel()
        main.removeCallbacks(classify)
        main.removeCallbacks(arm)
        main.removeCallbacks(protect)
        main.removeCallbacks(expiry)
        lookup?.cancel()
        lookup = null
        windowCheck?.cancel()
        windowCheck = null
        ownOverlayIds.clear()
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
