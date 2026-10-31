package io.github.lumiagg.gestureevo.inspire

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import io.github.lumiagg.gestureevo.gesture.GestureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ScreenContentNode(val bound: Rect, val content: String?) {
    val area: Int
        get() = bound.width() * bound.height()
}

private class AccessibilityNodeSnapshot(val info: AccessibilityNodeInfo, val parentIndex: Int?) {
    val bounds = Rect()
    var text: String? = null
    var fromDescription = false
    var readable = false

    fun intersectsSelection(selection: Rect): Boolean {
        if (!Rect.intersects(selection, bounds)) return false
        if (!fromDescription) return true
        return hasSubstantialAccessibilityOverlap(selection.left, selection.right, bounds.left, bounds.right) &&
                hasSubstantialAccessibilityOverlap(selection.top, selection.bottom, bounds.top, bounds.bottom)
    }
}

@Suppress("DEPRECATION") // Accessibility node pooling is still used before Android 13.
private suspend fun <T> withAccessibilityNodes(
    service: GestureService,
    readText: Boolean = true,
    block: (List<AccessibilityNodeSnapshot>) -> T,
): T = withContext(Dispatchers.Default) {
    val snapshots = mutableListOf<AccessibilityNodeSnapshot>()
    val pending = ArrayDeque<Pair<AccessibilityNodeInfo, Int?>>()
    try {
        getWindowRoots(service).asReversed().forEach { pending.addLast(it to null) }
        while (pending.isNotEmpty()) {
            ensureActive()
            val (node, parentIndex) = pending.removeLast()
            val snapshot = AccessibilityNodeSnapshot(node, parentIndex)
            val index = snapshots.size
            snapshots.add(snapshot)
            node.getBoundsInScreen(snapshot.bounds)
            snapshot.readable = node.isVisibleToUser && !snapshot.bounds.isEmpty
            if (readText && snapshot.readable) {
                val nativeText = node.text?.toString()
                snapshot.fromDescription = nativeText.isNullOrEmpty()
                snapshot.text = node.readAccessibleText(nativeText)
            }
            // Do not prune by container visibility or selection: Flutter virtual
            // containers can have empty bounds while their descendants are visible.
            for (childIndex in node.childCount - 1 downTo 0) {
                node.getChild(childIndex)?.let { pending.addLast(it to index) }
            }
        }
        block(snapshots)
    } finally {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            snapshots.forEach { it.info.recycle() }
            pending.forEach { it.first.recycle() }
        }
    }
}

suspend fun getScreenContent(service: GestureService, rect: Rect): List<ScreenContentNode> =
    withAccessibilityNodes(service) { snapshots ->
        val entries = snapshots.map { AccessibilityTextEntry(it.parentIndex, it.text, it.readable) }
        val intersecting = mutableSetOf<Int>()
        val fullyContained = mutableSetOf<Int>()
        snapshots.forEachIndexed { index, snapshot ->
            if (snapshot.text != null) {
                if (snapshot.intersectsSelection(rect)) intersecting.add(index)
                if (rect.contains(snapshot.bounds)) fullyContained.add(index)
            }
        }
        selectedAccessibilityTextIndices(entries, intersecting, fullyContained).mapNotNull { index ->
            val snapshot = snapshots[index]
            val content = ScreenContentNode(snapshot.bounds, snapshot.text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !snapshot.fromDescription) {
                getCharacterLocations(snapshot.info, content, rect)
            } else {
                content
            }
        }
    }

suspend fun getScreenView(
    service: GestureService,
    rect: Rect,
    clickMode: Boolean,
): List<ScreenContentNode> = withAccessibilityNodes(service, readText = !clickMode) { snapshots ->
    snapshots.mapNotNull { snapshot ->
        val isTarget = clickMode || snapshot.text != null || snapshot.info.className == "android.widget.ImageView"
        if (snapshot.readable && isTarget && Rect.intersects(rect, snapshot.bounds)) {
            ScreenContentNode(snapshot.bounds, null)
        } else {
            null
        }
    }
}

private fun getWindowRoots(service: GestureService): List<AccessibilityNodeInfo> = buildList {
    for (window in service.windows) {
        if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
        window.root?.let(::add)
    }
}

@RequiresApi(Build.VERSION_CODES.O)
private fun getCharacterLocations(
    node: AccessibilityNodeInfo,
    fallback: ScreenContentNode,
    selection: Rect,
): ScreenContentNode? {
    val text = fallback.content ?: return fallback
    val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
    if (key !in node.availableExtraData) return fallback
    val arguments = Bundle().apply {
        putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
        putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, text.length)
    }
    if (!node.refreshWithExtraData(key, arguments)) return fallback
    // The selected snapshot no longer describes this node if its text has changed.
    if (node.text?.toString() != text) return null
    @Suppress("DEPRECATION") // The untyped overload also supports Android 8-12.
    val locations = node.extras.getParcelableArray(key) ?: return fallback
    if (locations.any { it != null && it !is RectF }) return fallback
    val selectionBounds = RectF(selection)
    val selectedText = selectAccessibilityCharacters(text, locations) {
        RectF.intersects(selectionBounds, it as RectF)
    } ?: return fallback
    return ScreenContentNode(Rect(selection), selectedText)
}

private fun AccessibilityNodeInfo.readAccessibleText(nativeText: String?): String? {
    if (!nativeText.isNullOrEmpty()) return nativeText
    val textGranularities = AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER or
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD
    val supportsTextSelection = isEditable || (movementGranularities and textGranularities) != 0 ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isTextSelectable)
    return accessibilityNodeText(
        text = nativeText,
        description = contentDescription,
        className = className,
        isClickable = isClickable,
        isLongClickable = isLongClickable,
        isCheckable = isCheckable,
        isScrollable = isScrollable,
        hasTextSelection = supportsTextSelection,
    )
}

fun Bitmap.cropByRect(rect: Rect): Bitmap? {
    // Ensure crop bounds stay within bitmap dimensions
    val safeRect = Rect(
        rect.left.coerceIn(0, width),
        rect.top.coerceIn(0, height),
        rect.right.coerceIn(0, width),
        rect.bottom.coerceIn(0, height)
    )

    // Ensure crop bounds are valid
    return if (safeRect.width() > 0 && safeRect.height() > 0) {
        try {
            Bitmap.createBitmap(
                this, safeRect.left, safeRect.top, safeRect.width(), safeRect.height()
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    } else {
        null
    }
}

fun ManagedBitmap.cropByRect(rect: Rect): ManagedBitmap? =
    acquire()?.use { handle ->
        handle.requireBitmap().cropByRect(rect)?.let(ManagedBitmap::from)
    }
