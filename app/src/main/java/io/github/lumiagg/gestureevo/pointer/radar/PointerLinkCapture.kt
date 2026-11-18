package io.github.lumiagg.gestureevo.pointer.radar

import android.accessibilityservice.AccessibilityService
import androidx.annotation.MainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** One bounded capture shared by consumers of the current pointer session, created on demand. */
@MainThread
internal class PointerLinkCapture(private val service: AccessibilityService, private val scope: CoroutineScope) {
    private var session: CoroutineScope? = null
    private var capture: Deferred<List<LinkRegion>>? = null
    private var windowId: Int? = null
    val active: Boolean get() = session != null

    fun start() {
        stop()
        session = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Main.immediate)
    }
    fun stop() { session?.cancel(); session = null; invalidate() }
    fun invalidate() { capture?.cancel(); capture = null; windowId = null }

    @Suppress("DEPRECATION")
    suspend fun get(): List<LinkRegion> {
        val owner = checkNotNull(session) { "Pointer link capture has no active session" }
        val root = service.rootInActiveWindow
        val currentWindow = try { root?.windowId } finally { root?.recycle() }
        if (windowId != currentWindow) invalidate()
        windowId = currentWindow
        val request = capture ?: owner.async(start = CoroutineStart.LAZY) {
            LinkRadarScanner.scan(service)
        }.also { capture = it }
        return request.await()
    }
}

internal fun findPointerLinks(regions: List<LinkRegion>, x: Float, y: Float, radius: Float): List<LinkRegion> {
    val areas = regions.mapIndexed { index, link ->
        RadarHitArea(index, link.groupId, link.isPrecise, link.rects.map {
            RadarBox(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
        })
    }
    return findRadarTargets(areas, x, y, radius).map { regions[it] }.distinctBy { it.url }
}
