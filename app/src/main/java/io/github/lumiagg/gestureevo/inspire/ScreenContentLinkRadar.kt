package io.github.lumiagg.gestureevo.inspire

import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.view.doOnLayout
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import io.github.lumiagg.gestureevo.diagnostics.LogLevel
import io.github.lumiagg.gestureevo.diagnostics.LogModule
import io.github.lumiagg.gestureevo.gesture.GestureService
import io.github.lumiagg.gestureevo.link.previewLinkTarget
import io.github.lumiagg.gestureevo.pointer.radar.LinkRadarScanner
import io.github.lumiagg.gestureevo.pointer.radar.LinkRadarView
import io.github.lumiagg.gestureevo.pointer.radar.LinkRegion
import io.github.lumiagg.gestureevo.pointer.radar.RadarHitArea
import io.github.lumiagg.gestureevo.pointer.radar.frameBounds
import io.github.lumiagg.gestureevo.pointer.radar.toRadarBox
import io.github.lumiagg.gestureevo.pointer.radar.findRadarTargets
import io.github.lumiagg.gestureevo.pointer.radar.openPointerLinks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** One capture per selector session. Selection permanently retires its link hit areas. */
internal class ScreenContentLinkRadar(
    private val service: GestureService,
    private val view: LinkRadarView,
    private val onAccepted: () -> Unit,
) {
    private var session: CoroutineScope? = null
    private var sourceWindowId: Int? = null
    private var sourceBounds: Rect? = null
    private var links = emptyList<LinkRegion>()
    private var pressed = emptyList<LinkRegion>()
    private var hitAreas = emptyList<RadarHitArea>()
    private var triggerSide: String? = null

    fun start(windowId: Int, side: String?) {
        stop()
        sourceBounds = readWindowBounds(windowId) ?: return
        sourceWindowId = windowId
        triggerSide = side
        val owner = CoroutineScope(service.coroutineContext + SupervisorJob(service.coroutineContext[Job]) + Dispatchers.Main.immediate)
        session = owner
        owner.launch {
            try {
                val result = LinkRadarScanner.scan(service, windowId)
                if (session !== owner || !sourceIsCurrent()) return@launch
                links = result
                view.visibility = if (result.isEmpty()) View.GONE else View.VISIBLE
                view.setLinks(result)
                hitAreas = result.mapIndexed { index, link ->
                    RadarHitArea(index, link.groupId, link.isPrecise, listOf(link.frameBounds().toRadarBox()))
                }
                val groups = result.groupBy { it.groupId }.mapValues { (_, group) -> group.distinctBy { it.url } }
                view.showActionPrompts(groups, service.getString(R.string.screen_content_radar_open),
                    service.getString(R.string.screen_content_radar_choose))
                // Only visible prompts need local routing previews; no per-candidate network requests.
                view.doOnLayout {
                    if (session !== owner) return@doOnLayout
                    val visible = view.visibleActionKeys.toList()
                    owner.launch {
                        for (key in visible) {
                            val link = groups[key]?.singleOrNull() ?: continue
                            try {
                                val target = previewLinkTarget(service, link.url, link.sourcePackage, link.sourceText)
                                if (session !== owner) return@launch
                                view.updateActionPrompt(key, if (target == null) service.getString(R.string.screen_content_radar_choose_app)
                                    else service.getString(R.string.link_radar_open_in, target.label), target?.icon)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                AppLogger.event(LogModule.OVERLAY, "screen_content_link_preview_failed", LogLevel.WARN, error = error)
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (session === owner) {
                    AppLogger.event(LogModule.OVERLAY, "screen_content_link_capture_failed", LogLevel.WARN, error = error)
                    stop()
                }
            }
        }
    }

    fun stop() {
        session?.cancel()
        session = null
        sourceWindowId = null
        sourceBounds = null
        hitAreas = emptyList()
        links = emptyList()
        pressed = emptyList()
        view.clear()
        view.visibility = View.GONE
    }

    fun onWindowEvent(event: AccessibilityEvent) {
        if (sourceWindowId == null || event.packageName?.toString() == service.packageName) return
        val sourceGeometryChanged = event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            readWindowBounds(sourceWindowId) != sourceBounds
        if (sourceGeometryChanged || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            (event.windowId == sourceWindowId && (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))) {
            stop()
        }
    }

    fun press(x: Float, y: Float): Boolean {
        if (!sourceIsCurrent()) return false
        cancelPress()
        pressed = hitsAt(x, y)
        if (pressed.isEmpty()) return false
        view.highlightLinks(pressed)
        return true
    }

    private fun hitsAt(x: Float, y: Float): List<LinkRegion> =
        view.actionLinksAt(x, y).ifEmpty {
            findRadarTargets(hitAreas, x, y, 0f).map { links[it] }.distinctBy { it.url }
        }

    @Suppress("DEPRECATION")
    private fun readWindowBounds(windowId: Int?): Rect? {
        if (windowId == null) return null
        val windows = service.windows
        return try {
            windows.firstOrNull { it.id == windowId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                ?.let { window -> Rect().also { window.getBoundsInScreen(it) } }
        } finally {
            windows.forEach { it.recycle() }
        }
    }

    private fun sourceIsCurrent(): Boolean {
        if (session == null) return false
        if (readWindowBounds(sourceWindowId) != sourceBounds) {
            stop()
            return false
        }
        return true
    }

    fun cancelPress() {
        pressed = emptyList()
        view.highlightLinks(emptyList())
    }

    fun release(x: Float, y: Float): Boolean {
        if (!sourceIsCurrent()) return false
        val hits = hitsAt(x, y)
        val accepted = pressed.takeIf { it.isNotEmpty() && it == hits } ?: run {
            cancelPress()
            return false
        }
        val side = triggerSide
        stop()
        // Accepted commands belong to the service and survive selector cleanup.
        service.launch(Dispatchers.Main.immediate) { openPointerLinks(service, accepted, side) }
        onAccepted()
        return true
    }
}
