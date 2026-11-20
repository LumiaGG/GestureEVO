package io.github.lumiagg.gestureevo.inspire

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.github.lumiagg.gestureevo.utils.AccessibilityBlacklist
import android.accessibilityservice.AccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ScreenContentNode(val bound: Rect, val content: String?)

internal class AccessibilityNodeSnapshot(val info: AccessibilityNodeInfo, val parentIndex: Int?) {
    val bounds = Rect()
    var text: String? = null
    var nativeText: CharSequence? = null
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
internal suspend fun <T> withAccessibilityNodes(
    service: AccessibilityService,
    readText: Boolean = true,
    nodeLimit: Int = Int.MAX_VALUE,
    depthLimit: Int = Int.MAX_VALUE,
    block: (List<AccessibilityNodeSnapshot>) -> T,
): T = withContext(Dispatchers.Default) {
    val snapshots = mutableListOf<AccessibilityNodeSnapshot>()
    val pending = ArrayDeque<Triple<AccessibilityNodeInfo, Int?, Int>>()
    try {
        getWindowRoots(service).asReversed().forEach { pending.addLast(Triple(it, null, 0)) }
        while (pending.isNotEmpty() && snapshots.size < nodeLimit) {
            ensureActive()
            val (node, parentIndex, depth) = pending.removeLast()
            val snapshot = AccessibilityNodeSnapshot(node, parentIndex)
            val index = snapshots.size
            snapshots.add(snapshot)
            node.getBoundsInScreen(snapshot.bounds)
            snapshot.readable = node.isVisibleToUser && !snapshot.bounds.isEmpty
            if (readText && snapshot.readable) {
                snapshot.nativeText = node.text
                val nativeText = snapshot.nativeText?.toString()
                snapshot.fromDescription = nativeText.isNullOrEmpty()
                snapshot.text = node.readAccessibleText(nativeText)
            }
            // Do not prune by container visibility or selection: Flutter virtual
            // containers can have empty bounds while their descendants are visible.
            if (depth < depthLimit) for (childIndex in node.childCount - 1 downTo 0) {
                node.getChild(childIndex)?.let { pending.addLast(Triple(it, index, depth + 1)) }
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

/** Shared text selection: hierarchy deduplication, character clipping and reading order. */
internal fun selectScreenContent(
    snapshots: List<AccessibilityNodeSnapshot>, rect: Rect, excluded: Set<Int>,
): List<ScreenContentNode> {
    val entries = snapshots.mapIndexed { index, snapshot ->
        AccessibilityTextEntry(snapshot.parentIndex, snapshot.text, snapshot.readable && index !in excluded)
    }
    val intersecting = mutableSetOf<Int>()
    val fullyContained = mutableSetOf<Int>()
    snapshots.forEachIndexed { index, snapshot ->
        if (snapshot.text != null) {
            if (snapshot.intersectsSelection(rect)) intersecting.add(index)
            if (rect.contains(snapshot.bounds)) fullyContained.add(index)
        }
    }
    return selectedAccessibilityTextIndices(entries, intersecting, fullyContained).mapNotNull { index ->
        val snapshot = snapshots[index]
        val content = ScreenContentNode(snapshot.bounds, snapshot.text)
        if (!snapshot.fromDescription) {
            getCharacterLocations(snapshot.info, content, rect)
        } else content
    }
}

/** Exclude passwords, their descendants and ancestor summaries from extracted text. */
internal fun protectedAccessibilityNodes(nodes: List<AccessibilityNodeSnapshot>): Set<Int> {
    val excluded = mutableSetOf<Int>()
    val inPassword = BooleanArray(nodes.size)
    nodes.forEachIndexed { index, node ->
        inPassword[index] = node.info.isPassword || (node.parentIndex?.let { inPassword[it] } == true)
        if (inPassword[index]) excluded += index
        if (node.info.isPassword) {
            var ancestor = node.parentIndex
            while (ancestor != null) { excluded += ancestor; ancestor = nodes[ancestor].parentIndex }
        }
    }
    return excluded
}

/** The whole-screen counterpart to selectScreenContent, with identical text protection. */
internal fun preferredScreenTextIndices(
    nodes: List<AccessibilityNodeSnapshot>, protected: Set<Int> = protectedAccessibilityNodes(nodes),
): List<Int> {
    return preferredAccessibilityTextIndices(nodes.mapIndexed { index, node ->
        AccessibilityTextEntry(node.parentIndex, node.text, node.readable && index !in protected)
    })
}

internal fun screenContentText(nodes: List<ScreenContentNode>): String =
    joinAccessibilityTextInReadingOrder(nodes.mapNotNull { node ->
        node.content?.let { AccessibilityTextFragment(it, node.bound.top, node.bound.bottom) }
    })

@Suppress("DEPRECATION")
private fun getWindowRoots(service: AccessibilityService): List<AccessibilityNodeInfo> {
    val windows = service.windows
    return try {
        buildList {
            for (window in windows) {
                if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                val root = window.root ?: continue
                if (AccessibilityBlacklist.isBlocked(root.packageName?.toString())) {
                    root.recycle()
                    continue
                }
                add(root)
            }
        }
    } finally { windows.forEach { it.recycle() } }
}

private fun getCharacterLocations(
    node: AccessibilityNodeInfo,
    fallback: ScreenContentNode,
    selection: Rect,
): ScreenContentNode? {
    val text = fallback.content ?: return fallback
    val data = readAccessibilityCharacterLocations(node, text)
    if (data.textChanged) return null
    val locations = data.bounds ?: return fallback
    val selectionBounds = android.graphics.RectF(selection)
    val selectedText = selectAccessibilityCharacters(text, locations) {
        android.graphics.RectF.intersects(selectionBounds, it)
    } ?: return fallback
    return ScreenContentNode(fallback.bound, selectedText)
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
            val cropped = Bitmap.createBitmap(
                this, safeRect.left, safeRect.top, safeRect.width(), safeRect.height()
            )
            // A full-image crop can return its immutable input; callers own the returned bitmap.
            if (cropped === this) copy(Bitmap.Config.ARGB_8888, false) else cropped
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
