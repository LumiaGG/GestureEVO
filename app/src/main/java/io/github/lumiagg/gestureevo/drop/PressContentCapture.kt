package io.github.lumiagg.gestureevo.drop

import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import android.accessibilityservice.AccessibilityService
import io.github.lumiagg.gestureevo.share.ImageShareLauncher
import io.github.lumiagg.gestureevo.inspire.withAccessibilityNodes
import io.github.lumiagg.gestureevo.inspire.screenViews
import io.github.lumiagg.gestureevo.inspire.smallestScreenView
import io.github.lumiagg.gestureevo.inspire.selectScreenContent
import io.github.lumiagg.gestureevo.inspire.screenContentText
import io.github.lumiagg.gestureevo.inspire.cropByRect
import io.github.lumiagg.gestureevo.inspire.useSoftwareBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Shared screen-content recognition with drag-specific source and sensitive-region guards. */
internal class PressContentCapture(
    private val service: AccessibilityService,
    private val screenshot: suspend () -> Bitmap?,
) {
    data class Source(val packageName: String, val windowId: Int, val bounds: PressWindowPolicy.Bounds)
    private data class CapturedContent(val text: String? = null, val imageBounds: Rect? = null)

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

    suspend fun capture(source: Source, x: Float, y: Float): ClipData? = withContext(Dispatchers.IO) {
        val point = Rect(x.toInt() - 1, y.toInt() - 1, x.toInt() + 1, y.toInt() + 1)
        val content = withAccessibilityNodes(service, windowId = source.windowId,
            expectedPackage = source.packageName, maxNodes = 600, maxDurationMs = 450) { nodes ->
            if (nodes.any { it.info.isPassword && it.bounds.contains(x.toInt(), y.toInt()) })
                return@withAccessibilityNodes null
            // Never read password descendants or summaries containing a password field.
            val excluded = mutableSetOf<Int>()
            nodes.indices.filter { nodes[it].info.isPassword }.forEach { index ->
                var current: Int? = index
                while (current != null) { excluded += current; current = nodes[current].parentIndex }
            }
            nodes.forEachIndexed { index, node ->
                var ancestor = node.parentIndex
                while (ancestor != null) {
                    if (nodes[ancestor].info.isPassword) { excluded += index; break }
                    ancestor = nodes[ancestor].parentIndex
                }
            }
            val target = smallestScreenView(screenViews(nodes, point, clickMode = true, excluded = excluded))
                ?: return@withAccessibilityNodes null
            val targetNode = nodes.withIndex().firstOrNull {
                it.index !in excluded && it.value.readable && it.value.bounds == target.bound
            }?.value ?: return@withAccessibilityNodes null
            val cls = targetNode.info.className?.toString().orEmpty()
            val description = targetNode.info.contentDescription?.toString()?.trim()?.lowercase()
            val image = cls.endsWith("ImageView") || cls == "android.widget.Image" ||
                (targetNode.info.text.isNullOrBlank() && description in setOf("图片", "图像", "image", "photo"))
            if (image) {
                val region = Rect(target.bound)
                val margin = service.resources.displayMetrics.density.toInt().coerceAtLeast(1)
                val sensitiveRegion = Rect(region).apply { inset(-margin, -margin) }
                if (nodes.any { it.info.isPassword && Rect.intersects(sensitiveRegion, it.bounds) }) null
                else CapturedContent(imageBounds = region)
            } else {
                screenContentText(selectScreenContent(nodes, target.bound, excluded)).takeIf { it.isNotBlank() }
                    ?.let { CapturedContent(text = it) }
            }
        } ?: return@withContext null
        if (sourceAt(x, y) != source) return@withContext null
        content.text?.let { return@withContext ClipData.newPlainText("EVO press", it) }
        val region = content.imageBounds ?: return@withContext null
        if (!region.intersect(source.bounds.left, source.bounds.top, source.bounds.right, source.bounds.bottom)) return@withContext null
        val screen = screenshot() ?: return@withContext null
        val crop = try {
            screen.useSoftwareBitmap { software ->
                software.cropByRect(region)?.let {
                    // A full-screen crop may alias its input; the returned image must own its pixels.
                    if (it === software) it.copy(Bitmap.Config.ARGB_8888, false) else it
                }
            }
        } finally { screen.recycle() } ?: return@withContext null
        try {
            coroutineContext.ensureActive()
            if (sourceAt(x, y) != source) return@withContext null
            val payload = ImageShareLauncher(service.applicationContext).buildPayload(crop, null) ?: return@withContext null
            val uri = payload.contentUris.firstOrNull() ?: return@withContext null
            ClipData.newUri(service.contentResolver, "EVO visible image", uri)
        } finally { crop.recycle() }
    }

}
