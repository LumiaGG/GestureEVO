package io.github.lumiagg.gestureevo.pointer

import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.constraintlayout.widget.ConstraintLayout
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import io.github.lumiagg.gestureevo.diagnostics.LogLevel
import io.github.lumiagg.gestureevo.diagnostics.LogModule
import io.github.lumiagg.gestureevo.gesture.GestureService
import io.github.lumiagg.gestureevo.link.previewLinkTarget
import io.github.lumiagg.gestureevo.pointer.radar.LinkRegion
import io.github.lumiagg.gestureevo.pointer.radar.findPointerLinks
import io.github.lumiagg.gestureevo.pointer.radar.openPointerLinks
import io.github.lumiagg.gestureevo.pointer.ui.PointerHoverMenuView
import io.github.lumiagg.gestureevo.settings.ActionViewSettingsActivity.Companion.KEY_MAIN_SHOW_VIBRATE
import io.github.lumiagg.gestureevo.utils.Point
import io.github.lumiagg.gestureevo.utils.VibratorUtils
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** All methods run on Main. Preview jobs belong to the menu; accepted commands belong to the service. */
internal class PointerHoverMenuController(
    private val service: GestureService,
    private val parent: ConstraintLayout,
    private val captureLinks: suspend () -> List<LinkRegion>,
    private val onEnterSelection: (Point) -> Unit,
    private val onReturnToPointer: (Point) -> Unit,
    private val onScreenAction: suspend (PointerHoverAction, Point, () -> Boolean) -> Unit,
    private val onSelectionPreview: (Point?) -> Unit,
    private val isCoordinateSpaceCurrent: () -> Boolean,
) {
    private val state = PointerHoverState()
    private val menuLazy = lazy {
        PointerHoverMenuView(service).also {
            val params = ConstraintLayout.LayoutParams(0, 0).apply {
                startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }
            parent.addView(it, (parent.childCount - 1).coerceAtLeast(0), params)
        }
    }
    private val menu get() = menuLazy.value
    private var scope: CoroutineScope? = null
    private var selectJob: Job? = null
    private var anchor = Point(0f, 0f)
    private var screenPoint = Point(0f, 0f)
    private var window: PointerWindow? = null
    private var links = emptyList<LinkRegion>()
    private var vibrate = false
    private val selection = PointerMenuSelection()
    private var lastInputY = 0f
    private val baseActions = listOf(
        PointerHoverAction.SELECT,
        PointerHoverAction.LONG_PRESS,
        PointerHoverAction.DOUBLE_TAP,
    )
    private val linkActions = baseActions + PointerHoverAction.OPEN_LINK
    private var actions = baseActions

    val ownsInteraction: Boolean get() = state.hasOpened || state.phase == PointerHoverState.Phase.FINISHED
    val canShow: Boolean get() = state.phase == PointerHoverState.Phase.IDLE
    val isShowing: Boolean get() = state.phase == PointerHoverState.Phase.MENU

    fun reset() {
        closePreview()
        state.reset()
        window = null
    }

    fun show(point: Point, radius: Float, inputY: Float) {
        if (!state.show()) return
        val targetWindow = PointerWindow.capture(service)
        if (targetWindow == null) { state.cancel(); return }
        window = targetWindow
        selection.reset(inputY)
        lastInputY = inputY
        actions = baseActions
        anchor = Point(point.x, point.y)
        val origin = IntArray(2)
        parent.getLocationOnScreen(origin)
        screenPoint = Point(point.x + origin[0], point.y + origin[1])
        menu.show(point.x, point.y, baseActions)
        val owner = CoroutineScope(service.coroutineContext + SupervisorJob(service.coroutineContext[Job]) + Dispatchers.Main.immediate)
        scope = owner
        owner.launch {
            vibrate = service.settingsStore.readKey(KEY_MAIN_SHOW_VIBRATE, false)
        }
        owner.launch {
            try {
                val captured = captureLinks()
                if (scope !== owner || !isShowing || !targetWindow.matches(service)) return@launch
                links = findPointerLinks(captured, screenPoint.x, screenPoint.y, radius * 1.15f)
                if (links.isEmpty()) return@launch
                actions = linkActions
                menu.showLink(service.getString(if (links.size > 1) R.string.pointer_hover_choose_link else R.string.link_radar_open_link))
                if (links.size == 1) {
                    val hit = links.first()
                    val preview = previewLinkTarget(service, hit.url, hit.sourcePackage, hit.sourceText)
                    if (scope !== owner || !isShowing || !targetWindow.matches(service)) return@launch
                    if (preview != null) {
                        menu.showLink(service.getString(R.string.link_radar_open_in, preview.label), preview.icon)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLogger.event(LogModule.ACTION, "pointer_link_preview_failed", LogLevel.WARN, error = error)
            }
        }
    }

    fun move(inputY: Float) {
        if (!isShowing) return
        if (!isCoordinateSpaceCurrent()) { cancel(); return }
        val movedVertically = kotlin.math.abs(inputY - lastInputY) > service.resources.displayMetrics.density
        lastInputY = inputY
        val index = selection.move(inputY, actions.size, actions.indexOf(state.selected),
            service.resources.getDimension(R.dimen.pointer_hover_menu_step))
        if (index != null) {
            val hit = actions[index]
            state.highlight(hit)
            menu.highlight(hit)
            if (vibrate) VibratorUtils.vibrator(service, false)
        }
        onSelectionPreview(anchor.takeIf { state.isSelectionPreviewActive })
        if (index != null || movedVertically) scheduleModeChange(state.selected)
    }

    private fun scheduleModeChange(action: PointerHoverAction?) {
        selectJob?.cancel()
        selectJob = null
        if (action == PointerHoverAction.SELECT) {
            selectJob = scope?.launch {
                delay(300)
                if (!isShowing || state.selected != action) return@launch
                if (!isCoordinateSpaceCurrent()) { cancel(); return@launch }
                if (window?.matches(service) != true) { dismiss(); return@launch }
                if (state.enterSelection()) {
                    closePreview()
                    onEnterSelection(anchor)
                }
            }
        }
    }

    /** True means this release belongs to the menu, including a cancelled/outside release. */
    fun release(side: String?): Boolean {
        if (!ownsInteraction || state.phase == PointerHoverState.Phase.SELECTING) return false
        val action = state.release()
        val target = Point(screenPoint.x, screenPoint.y)
        val targetWindow = window
        val capturedLinks = links.toList()
        closePreview()
        if (action != null && isCoordinateSpaceCurrent() && targetWindow?.matches(service) == true) {
            service.launch(Dispatchers.Main.immediate) {
                if (!targetWindow.matches(service)) return@launch
                try {
                    if (action == PointerHoverAction.OPEN_LINK) {
                        openPointerLinks(service, capturedLinks, side)
                    } else {
                        onScreenAction(action, target) { targetWindow.matches(service) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    AppLogger.event(LogModule.ACTION, "pointer_menu_action_failed", LogLevel.WARN, error = error)
                }
            }
        }
        return true
    }

    fun onWindowEvent(event: AccessibilityEvent): Boolean {
        if (!ownsInteraction) return false
        val target = window ?: return false
        val changed = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                event.packageName?.toString() != service.packageName || target.packageName == service.packageName
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> !target.matches(service)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> event.windowId == target.id
            else -> false
        }
        if (changed) dismiss()
        return changed
    }

    fun cancel() { closePreview(); state.cancel() }
    fun dismiss() {
        val returnToPointer = isShowing
        closePreview()
        state.dismiss()
        window = null
        if (returnToPointer) onReturnToPointer(anchor)
    }
    fun refreshTheme() { if (isShowing) menu.refreshTheme() }

    private fun closePreview() {
        scope?.cancel()
        scope = null
        selectJob = null
        links = emptyList()
        vibrate = false
        onSelectionPreview(null)
        if (menuLazy.isInitialized()) menu.visibility = View.GONE
    }
}

/** No node survives capture. Recheck before injecting, since closing overlays can change focus. */
private data class PointerWindow(val id: Int, val packageName: String) {
    fun matches(service: GestureService): Boolean = capture(service) == this

    companion object {
        @Suppress("DEPRECATION")
        fun capture(service: GestureService): PointerWindow? {
            val root = service.rootInActiveWindow ?: return null
            return try {
                root.packageName?.toString()?.let { PointerWindow(root.windowId, it) }
            } finally { root.recycle() }
        }
    }
}
