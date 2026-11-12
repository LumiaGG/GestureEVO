package io.github.lumiagg.gestureevo.drop

import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.lumiagg.gestureevo.gesture.GestureService
import io.github.lumiagg.gestureevo.inspire.OcrImageTextProcessor
import io.github.lumiagg.gestureevo.share.ImageShareLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** MMGG RootNodeCapture filtering adapted to EVO's accessibility/screenshot/OCR permission path. */
internal class PressContentCapture(private val service: GestureService) {
    data class Source(val packageName: String, val windowId: Int, val bounds: PressWindowPolicy.Bounds)
    private data class Node(val bounds: Rect, val text: String?, val description: String?,
        val control: Boolean, val password: Boolean, val parent: Int?, val snapshot: AccessibilityNodeSnapshot)

    fun excludedPackage(packageName: String): Boolean {
        if (EvoSourcePackages.contains(packageName) || packageName == "com.android.systemui") return true
        val keyboard = runCatching {
            android.content.ComponentName.unflattenFromString(android.provider.Settings.Secure.getString(
                service.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD))?.packageName
        }.getOrNull()
        val launcher = runCatching {
            service.packageManager.resolveActivity(android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        }.getOrNull()
        return packageName == keyboard || packageName == launcher
    }

    @Suppress("DEPRECATION")
    fun sourceAt(x: Float, y: Float): Source? {
        val windows = service.windows
        try {
            for (window in windows) {
                val bounds = Rect().also(window::getBoundsInScreen)
                if (!bounds.contains(x.toInt(), y.toInt())) continue
                if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return null
                if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                    val overlayRoot = window.root
                    try { if (EvoSourcePackages.contains(overlayRoot?.packageName?.toString())) return null }
                    finally { overlayRoot?.recycle() }
                }
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || !window.isActive) continue
                val root = window.root ?: continue
                try {
                    if (!root.isVisibleToUser) return null
                    return Source(root.packageName?.toString() ?: continue, window.id,
                        PressWindowPolicy.Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom))
                }
                finally { root.recycle() }
            }
            return null
        } finally { windows.forEach { it.recycle() } }
    }

    @Suppress("DEPRECATION")
    fun windowSnapshot(): List<PressWindowPolicy.Window> {
        val windows = service.windows
        try {
            return windows.map { window ->
                val bounds = Rect().also(window::getBoundsInScreen)
                val root = window.root
                val packageName = try { root?.packageName?.toString() } finally { root?.recycle() }
                PressWindowPolicy.Window(window.id, window.type, packageName,
                    PressWindowPolicy.Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom), window.isActive, window.isFocused)
            }
        } finally { windows.forEach { it.recycle() } }
    }

    @Suppress("DEPRECATION")
    suspend fun capture(source: Source, x: Float, y: Float): ClipData? = withContext(Dispatchers.IO) {
        val roots = service.windows
        var root: AccessibilityNodeInfo? = null
        val windowBounds = Rect()
        try {
            for (window in roots) {
                if (window.id == source.windowId && window.type == AccessibilityWindowInfo.TYPE_APPLICATION && window.isActive) {
                    window.getBoundsInScreen(windowBounds)
                    root = window.root
                    break
                }
            }
        } finally { roots.forEach { it.recycle() } }
        val start = root ?: return@withContext null
        val pending = ArrayDeque<Pair<AccessibilityNodeInfo, Int?>>()
        pending.add(start to null)
        val nodes = mutableListOf<Node>()
        val started = SystemClock.uptimeMillis()
        try {
            while (pending.isNotEmpty()) {
                coroutineContext.ensureActive()
                if (nodes.size >= 600 || SystemClock.uptimeMillis() - started > 450) return@withContext null
                val (node, parent) = pending.removeLast()
                try {
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (node.isVisibleToUser && node.packageName?.toString() != source.packageName) return@withContext null
                    val visible = node.isVisibleToUser && !bounds.isEmpty
                    val index = nodes.size
                    val cls = node.className?.toString().orEmpty()
                    val snapshot = AccessibilityNodeSnapshot.Builder().bounds(bounds)
                        .className(cls).text(node.text?.toString()?.take(16000))
                        .contentDescription(node.contentDescription?.toString()?.take(2000)).visible(visible)
                        .editable(node.isEditable).password(node.isPassword)
                        .leaf(node.childCount == 0)
                        .insideWebView(cls.contains("WebView") || (parent != null && nodes[parent].snapshot.insideWebView))
                        .depth(if (parent == null) 0 else nodes[parent].snapshot.depth + 1).traversalOrder(index).build()
                    nodes += Node(if (visible || node.isPassword) bounds else Rect(), snapshot.text,
                        snapshot.contentDescription, node.isClickable || node.isLongClickable, node.isPassword, parent, snapshot)
                    for (child in 0 until node.childCount.coerceAtMost(600)) node.getChild(child)?.let { pending.add(it to index) }
                } finally { node.recycle() }
            }
        } finally { pending.forEach { it.first.recycle() } }
        if (sourceAt(x, y) != source) return@withContext null
        val atPoint = nodes.indices.filter { nodes[it].bounds.contains(x.toInt(), y.toInt()) }
        if (atPoint.any { nodes[it].password }) return@withContext null
        if (atPoint.any { index ->
                val node = nodes[index]
                (holdLabel(node.text) || holdLabel(node.description)) && atPoint.any { nodes[it].control && nodes[it].bounds.contains(node.bounds) }
            }) return@withContext null
        val protected = mutableSetOf<Int>()
        nodes.indices.filter { nodes[it].password }.forEach { index ->
            var current: Int? = index
            while (current != null) { protected += current; current = nodes[current].parent }
        }
        fun safe(index: Int): Boolean {
            var current: Int? = index
            while (current != null) { if (nodes[current].password) return false; current = nodes[current].parent }
            return index !in protected
        }
        val metrics = service.resources.displayMetrics
        val classifier = AccessibilityNodeClassifier(metrics.density, windowBounds.right, windowBounds.bottom)
        val buckets = classifier.classify(nodes.indices.filter(::safe).map { nodes[it].snapshot })
        val chosen = AccessibilityCandidateSelector.select(buckets, x, y)
        if (chosen != null && !chosen.isImage) return@withContext ClipData.newPlainText("EVO press", chosen.candidate.text)
        val region = if (chosen != null) Rect(chosen.candidate.bounds) else {
            // Only a complete safe window traversal permits a bounded OCR fallback.
            val width = (windowBounds.width() * .78f).toInt().coerceAtLeast(1)
            val height = (windowBounds.height() * .34f).toInt().coerceAtLeast(1)
            val left = (x.toInt() - width / 2).coerceIn(windowBounds.left, windowBounds.right - width)
            val top = (y.toInt() - height / 2).coerceIn(windowBounds.top, windowBounds.bottom - height)
            Rect(left, top, left + width, top + height)
        }
        if (!region.intersect(windowBounds)) return@withContext null
        val protectedRegion = Rect(region).apply { inset(-metrics.density.toInt().coerceAtLeast(1), -metrics.density.toInt().coerceAtLeast(1)) }
        if (nodes.any { it.password && Rect.intersects(protectedRegion, it.bounds) }) return@withContext null
        val screen = service.takeScreenshot() ?: return@withContext null
        val crop = try {
            if (!region.intersect(0, 0, screen.width, screen.height)) return@withContext null
            val software = screen.copy(Bitmap.Config.ARGB_8888, false) ?: return@withContext null
            try { Bitmap.createBitmap(software, region.left, region.top, region.width(), region.height()).let {
                if (it === software) it.copy(Bitmap.Config.ARGB_8888, false) else it
            } } finally { software.recycle() }
        } finally { screen.recycle() } ?: return@withContext null
        try {
            coroutineContext.ensureActive()
            if (sourceAt(x, y) != source) return@withContext null
            if (chosen != null) {
                val payload = ImageShareLauncher(service.applicationContext).buildPayload(crop, null) ?: return@withContext null
                val uri = payload.contentUris.firstOrNull() ?: return@withContext null
                ClipData.newUri(service.contentResolver, "EVO visible image", uri)
            } else {
                val text = OcrImageTextProcessor(service).process(crop).imageTextNodes
                    .filter { it.bound.contains((x - region.left).toInt(), (y - region.top).toInt()) }
                    .mapNotNull { it.content }.joinToString("\n")
                text.takeIf { it.isNotBlank() }?.let { ClipData.newPlainText("EVO press OCR", it) }
            }
        } finally { crop.recycle() }
    }

    private fun holdLabel(value: String?): Boolean = value?.replace(Regex("\\s+"), "")?.lowercase() in setOf(
        "按住说话", "按住录音", "按住发送", "松开发送", "松开结束录音", "松开取消", "上滑取消", "手指上滑取消发送", "松开转文字",
        "holdtotalk", "presstotalk", "holdtorecord", "releasetosend", "releasetocancel", "releasetostoprecording", "slideuptocancel")
}
