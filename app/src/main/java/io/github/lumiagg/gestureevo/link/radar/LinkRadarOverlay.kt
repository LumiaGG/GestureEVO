package io.github.lumiagg.gestureevo.link.radar

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.annotation.MainThread
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.floating.BaseFloatingWindow
import io.github.lumiagg.gestureevo.gesture.GestureService
import io.github.lumiagg.gestureevo.link.LinkLauncher
import io.github.lumiagg.gestureevo.link.previewLinkTarget
import io.github.lumiagg.gestureevo.pointer.PointerFloating
import io.github.lumiagg.gestureevo.settings.ActionViewSettingsActivity.Companion.KEY_MAIN_SHOW_VIBRATE
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

/** Session coordinator. Shares node capture, pointer monitoring and overlay attachment with EVO. */
@MainThread
class LinkRadarOverlay(
    private val service: GestureService,
    private val pointerStateProvider: () -> PointerFloating.PointerSnapshot?,
    private val triggerSideProvider: () -> String? = { null },
) : BaseFloatingWindow(service, service, "LinkRadarOverlay") {
    private val overlayView by lazy { LinkRadarView(service) }
    private val layoutParams = WindowManager.LayoutParams().apply {
        configureAccessibilityOverlay(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            title = "GestureEVO:LinkRadarOverlay")
    }
    private var sessionScope: CoroutineScope? = null
    private var previewJob: Job? = null
    private var dismissJob: Job? = null
    private var regions = emptyList<LinkRegion>()
    private var areas = emptyList<RadarHitArea>()
    private var targets = emptyList<Int>()
    private var pointer: PointerFloating.PointerSnapshot? = null
    private var lastHapticAt = 0L
    private var vibrate = false
    private var dispatched = false

    fun startSession() {
        stopSession()
        sessionScope = CoroutineScope(service.coroutineContext + SupervisorJob(service.coroutineContext[Job]) + Dispatchers.Main.immediate)
        dispatched = false
        pointer = pointerStateProvider()
        attachViewSafely(overlayView, layoutParams, shouldRetry = { sessionScope != null })
        sessionScope?.launch { vibrate = service.settingsStore.readKey(KEY_MAIN_SHOW_VIBRATE, false) }
        sessionScope?.launch {
            regions = try { LinkRadarScanner.scan(service) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Log.w("LinkRadar", "Screen link capture failed", error); emptyList() }
            areas = hitAreas(regions)
            onPointerPositionChanged(pointer)
        }
    }

    fun stopSession() {
        sessionScope?.cancel()
        sessionScope = null
        previewJob = null
        dismissJob = null
        regions = emptyList()
        areas = emptyList()
        targets = emptyList()
        pointer = null
        vibrate = false
        if (isAttachRequested(overlayView)) detachViewSafely(overlayView)
        overlayView.clear()
    }

    fun destroy() = stopSession()
    fun refreshTheme() { if (sessionScope != null) overlayView.refreshTheme() }

    private fun hitAreas(links: List<LinkRegion>): List<RadarHitArea> = links.mapIndexed { index, link ->
        RadarHitArea(index, link.groupId, link.isPrecise, link.rects.map {
            RadarBox(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
        })
    }

    /** Called by the pointer's existing position monitor; no second polling loop. */
    fun onPointerPositionChanged(snapshot: PointerFloating.PointerSnapshot?) {
        if (sessionScope == null || dispatched) return
        pointer = snapshot
        overlayView.setLinks(if (snapshot == null) emptyList() else regions)
        if (snapshot == null) { clearTarget(); return }
        val hits = hitTargets(snapshot, hysteresis = 5f * service.resources.displayMetrics.density)
        if (hits.isEmpty()) {
            if (targets.isNotEmpty() && dismissJob?.isActive != true) {
                dismissJob = sessionScope?.launch { delay(70); clearTarget() }
            }
        } else {
            dismissJob?.cancel(); dismissJob = null
            if (hits != targets) {
                targets = hits
                showTarget(hits.map { regions[it] })
            }
        }
    }

    private fun hitTargets(snapshot: PointerFloating.PointerSnapshot, hysteresis: Float = 0f): List<Int> =
        findRadarTargets(areas, snapshot.centerX, snapshot.centerY, snapshot.radiusPx * 1.15f,
            targets.firstOrNull(), hysteresis)

    private fun clearTarget() {
        val hadTarget = targets.isNotEmpty() || previewJob != null
        dismissJob?.cancel(); dismissJob = null
        previewJob?.cancel(); previewJob = null
        targets = emptyList()
        if (hadTarget) overlayView.clearTarget()
    }

    private fun showTarget(links: List<LinkRegion>) {
        val hits = links.distinctBy { it.url }
        previewJob?.cancel()
        if (vibrate && SystemClock.uptimeMillis() - lastHapticAt > 80) {
            lastHapticAt = SystemClock.uptimeMillis()
            VibratorUtils.vibrator(service, false)
        }
        if (hits.size > 1) {
            overlayView.show(hits, service.getString(R.string.link_radar_release_select, hits.size))
            return
        }
        overlayView.show(hits, service.getString(R.string.link_radar_open_link))
        previewJob = sessionScope?.launch {
            val preview = try { predict(hits.first()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                Log.w("LinkRadar", "Link target preview failed", error)
                service.getString(R.string.link_radar_open_link) to null
            }
            overlayView.show(hits, preview.first, preview.second)
        }
    }

    private suspend fun predict(link: LinkRegion): Pair<String, Drawable?> {
        val target = previewLinkTarget(service, link.url, link.sourcePackage, link.sourceText)
        return if (target != null) service.getString(R.string.link_radar_open_in, target.label) to target.icon
        else service.getString(R.string.link_radar_release_choose_app) to null
    }

    fun hasTarget(): Boolean = sessionScope != null && !dispatched &&
        pointerStateProvider()?.let { hitTargets(it).isNotEmpty() } == true

    fun onPointerTapRequested(): Boolean {
        if (sessionScope == null || dispatched) return false
        val snapshot = pointerStateProvider() ?: return false
        // Check the current pointer against this session's captured link regions.
        val hits = hitTargets(snapshot).map { regions[it] }.distinctBy { it.url }
        if (hits.isEmpty()) return false
        val triggerSide = triggerSideProvider()
        dispatched = true
        clearTarget()
        overlayView.clear()
        // Opening is an accepted action and survives the pointer session closing on release.
        service.launch(Dispatchers.Main.immediate) {
            if (hits.size == 1) {
                val hit = hits.first()
                LinkLauncher.openUrl(service, hit.url, hit.sourcePackage, hit.sourceText, side = triggerSide)
            } else {
                service.startActivity(Intent(service, LinkSelectionActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putStringArrayListExtra(LinkSelectionActivity.EXTRA_URLS, ArrayList(hits.map { it.url }))
                    putStringArrayListExtra(LinkSelectionActivity.EXTRA_SOURCE_PACKAGES, ArrayList(hits.map { it.sourcePackage }))
                    putStringArrayListExtra(LinkSelectionActivity.EXTRA_SOURCE_TEXTS, ArrayList(hits.map { it.sourceText }))
                    putExtra(io.github.lumiagg.gestureevo.gesture.TriggerSide.EXTRA_TRIGGER_SIDE, triggerSide)
                })
            }
        }
        return true
    }
}
