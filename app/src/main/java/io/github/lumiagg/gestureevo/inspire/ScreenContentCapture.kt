package io.github.lumiagg.gestureevo.inspire

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import io.github.lumiagg.gestureevo.utils.dp2px
import java.io.Closeable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

internal val screenContentClickThreshold: Float get() = 20f.dp2px

/** Pointer previews, screen selections and press-drags resolve regions through the same rules. */
internal suspend fun resolveScreenContentRect(
    service: AccessibilityService,
    selection: Rect,
    allowRegion: Boolean = true,
): Rect? {
    val rect = Rect(minOf(selection.left, selection.right), minOf(selection.top, selection.bottom),
        maxOf(selection.left, selection.right), maxOf(selection.top, selection.bottom))
    val narrow = rect.width() < screenContentClickThreshold
    val short = rect.height() < screenContentClickThreshold
    if (!narrow && !short) return rect.takeIf { allowRegion }
    return withAccessibilityNodes(service, readText = !(narrow && short)) { nodes ->
        val views = nodes.asSequence().filter {
            it.readable && Rect.intersects(rect, it.bounds) &&
                ((narrow && short) || it.text != null || accessibilityImageClass(it.info.className))
        }.map { it.bounds }.toList()
        if (narrow && short) views.minByOrNull { it.width().toLong() * it.height() }?.let(::Rect)
        else views.takeIf { it.isNotEmpty() }?.let { entries ->
            Rect(entries.first()).apply { entries.drop(1).forEach(::union) }
        }
    }
}

internal class CapturedScreenContent(
    val accessibilityText: String,
    val image: ManagedBitmap?,
    val prefersImage: Boolean,
) : Closeable {
    override fun close() { image?.close() }
}

private data class SelectionContent(val text: String, val prefersImage: Boolean)

/** Own both capture branches until they join, including cancellation and screenshot ownership. */
internal suspend fun captureScreenContent(
    service: AccessibilityService,
    region: Rect,
    screenshot: suspend () -> ManagedBitmap?,
): CapturedScreenContent? {
    if (region.isEmpty) return null
    val selection = Rect(region)
    var image: ManagedBitmap? = null
    try {
        val content = coroutineScope {
            val text = async {
                withAccessibilityNodes(service) { nodes ->
                    if (nodes.any { it.info.isPassword && Rect.intersects(selection, it.bounds) }) null
                    else {
                        val targets = nodes.filter { it.readable && it.bounds == selection }
                        val imageTarget = targets.any { accessibilityImageClass(it.info.className) }
                        val textTarget = targets.any {
                            !accessibilityImageClass(it.info.className) && !it.info.text.isNullOrBlank()
                        }
                        // A clipped image may extend beyond the selected region. A real text child still wins.
                        val imageCoversSelection = nodes.any {
                            it.readable && accessibilityImageClass(it.info.className) && it.bounds.contains(selection)
                        }
                        SelectionContent(
                            screenContentText(selectScreenContent(nodes, selection, protectedAccessibilityNodes(nodes))),
                            imageTarget || (!textTarget && imageCoversSelection),
                        )
                    }
                }
            }
            val picture = async { image = screenshot()?.use { it.cropByRect(selection) } }
            val result = text.await()
            picture.await()
            result
        } ?: return null
        return CapturedScreenContent(content.text, image, content.prefersImage).also { image = null }
    } finally {
        image?.close()
    }
}
