package io.github.lumiagg.gestureevo.drop

import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import android.accessibilityservice.AccessibilityService
import io.github.lumiagg.gestureevo.share.ImageShareLauncher
import io.github.lumiagg.gestureevo.inspire.ManagedBitmap
import io.github.lumiagg.gestureevo.inspire.captureScreenContent
import io.github.lumiagg.gestureevo.inspire.resolveScreenContentRect
import io.github.lumiagg.gestureevo.utils.AccessibilityBlacklist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Shared screen-content recognition with drag-specific source and sensitive-region guards. */
internal class PressContentCapture(
    private val service: AccessibilityService,
    private val screenshot: suspend () -> Bitmap?,
) {
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
    fun sourcePackageAt(x: Float, y: Float): String? {
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
                    return root.packageName?.toString() ?: continue
                }
                finally { root.recycle() }
            }
            return null
        } finally { windows.forEach { it.recycle() } }
    }

    /** Only application changes invalidate the gesture; window IDs, bounds and popup focus do not. */
    @Suppress("DEPRECATION")
    fun isSourceAppCurrent(packageName: String): Boolean {
        val windows = service.windows
        try {
            val applications = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused) }
                .sortedByDescending { it.isActive }
            for (window in applications) {
                val root = window.root ?: continue
                try {
                    val current = root.packageName?.toString() ?: continue
                    return current == packageName
                } finally { root.recycle() }
            }
            // Accessibility may omit the active app during a window transition. The Shizuku worker
            // independently checks the actual foreground task before injecting any touch.
            return true
        } finally { windows.forEach { it.recycle() } }
    }

    suspend fun capture(x: Float, y: Float): ClipData? = withContext(Dispatchers.IO) {
        val point = Rect(x.toInt() - 1, y.toInt() - 1, x.toInt() + 1, y.toInt() + 1)
        val region = resolveScreenContentRect(service, point) ?: return@withContext captureMiss("no_target")
        val content = captureScreenContent(service, region,
            screenshot = { screenshot()?.let(ManagedBitmap::from) })
            ?: return@withContext captureMiss("selection_unavailable")
        content.use {
            coroutineContext.ensureActive()
            if (!it.prefersImage && it.accessibilityText.isNotBlank()) {
                return@withContext ClipData.newPlainText("EVO press", it.accessibilityText)
            }
            // As in pointer selection, the resolved region remains useful as an image even without text.
            val image = it.image ?: return@withContext captureMiss("no_content")
            val payload = ImageShareLauncher(service.applicationContext).buildPayload(image.requireBitmap(), null)
                ?: return@withContext captureMiss("image_unavailable")
            val uri = payload.contentUris.firstOrNull() ?: return@withContext captureMiss("image_unavailable")
            ClipData.newUri(service.contentResolver, "EVO selection", uri)
        }
    }

    /** Read only the long-click event's current snapshot; do not refresh or traverse its tree. */
    @Suppress("DEPRECATION")
    fun longClickText(event: android.view.accessibility.AccessibilityEvent, x: Float, y: Float, origin: String): String? {
        if (event.isPassword || AccessibilityBlacklist.isBlocked(origin)) return null
        val node = event.source ?: return null
        try {
            val bounds = Rect().also(node::getBoundsInScreen)
            if (node.isPassword || node.packageName?.toString() != origin ||
                !bounds.contains(x.toInt(), y.toInt()) ||
                io.github.lumiagg.gestureevo.inspire.accessibilityImageClass(node.className)) return null
            return node.text?.toString()?.takeIf(String::isNotBlank)
                ?: event.text.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
                    .joinToString("\n").takeIf(String::isNotBlank)
        } finally { node.recycle() }
    }

    private fun captureMiss(reason: String): Nothing? {
        DropDiagnostics.event("drop_capture_unavailable", "internal", 0, reason = reason)
        return null
    }

}
