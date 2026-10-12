package io.github.lumiagg.gestureevo.inspire

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import androidx.core.graphics.toRect
import io.github.lumiagg.gestureevo.gesture.GestureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


data class ScreenContentNode(val bound: Rect, val content: String?) {
    val area: Int
        get() = bound.width() * bound.height()
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun <T> processAccessibilityNodesInParallel(
    service: GestureService,
    nodeProcessor: suspend (AccessibilityNodeInfo, Rect) -> T?,
    nodeFilter: (AccessibilityNodeInfo, Rect) -> Boolean
): List<T> = withContext(Dispatchers.Default) {
    val roots = getNeedWindowsRoot(service)

    val availableProcessors = Runtime.getRuntime().availableProcessors()
    val workerCount = availableProcessors.coerceAtLeast(1).coerceAtMost(4)
    val computeDispatcher = Dispatchers.Default.limitedParallelism(workerCount)

    roots.map { rootNode ->
        async {
            val results = ArrayList<MutableList<T>>(workerCount).apply {
                repeat(workerCount) { add(mutableListOf()) }
            }
            val nodeChannel = Channel<Pair<AccessibilityNodeInfo, Rect>>(Channel.BUFFERED)

            val processingJobs = List(workerCount) { workerIndex ->
                launch(computeDispatcher) {
                    val localResults = results[workerIndex]
                    for ((node, bounds) in nodeChannel) {
                        nodeProcessor(node, bounds)?.let { localResults.add(it) }
                    }
                }
            }

            // Dedicated coroutine for BFS traversal while sending nodes to workers
            launch {
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(rootNode)

                while (queue.isNotEmpty()) {
                    val node = queue.removeFirst()
                    val bounds = Rect().also { node.getBoundsInScreen(it) }
                    if (nodeFilter(node, bounds)) {
                        nodeChannel.send(node to bounds)
                        for (i in 0 until node.childCount) {
                            node.getChild(i)?.let { child ->
                                if (child.isVisibleToUser) {
                                    queue.add(child)
                                }
                            }
                        }
                    }
                }

                // Close channel once BFS is complete
                nodeChannel.close()
            }

            // Await completion of all worker coroutines
            processingJobs.joinAll()

            results.flatten()
        }
    }.awaitAll().flatten()
}

@OptIn(ExperimentalCoroutinesApi::class)
suspend fun getScreenContent(
    service: GestureService,
    rect: Rect,
): List<ScreenContentNode> = processAccessibilityNodesInParallel(
    service = service,
    nodeProcessor = { node, outBounds ->
        val text =
            node.text ?: return@processAccessibilityNodesInParallel null
        if (text.isNotEmpty()) {
            if (Rect.intersects(rect, outBounds) || rect.contains(outBounds)) {
                return@processAccessibilityNodesInParallel when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> getCharacterLocations(
                        node,
                        rect
                    )

                    else -> ScreenContentNode(outBounds, text.toString())
                }
            }
        }
        return@processAccessibilityNodesInParallel null
    },
    nodeFilter = { _, outBounds ->
        Rect.intersects(rect, outBounds) || outBounds.contains(rect)
    }
)

@OptIn(ExperimentalCoroutinesApi::class)
suspend fun getScreenView(
    service: GestureService,
    rect: Rect,
    clickMode: Boolean
): List<ScreenContentNode> {

    return processAccessibilityNodesInParallel(
        service = service,
        nodeProcessor = { node, outBounds ->
            val isRoiView = if (clickMode) {
                true
            } else {
                (node.className == "android.widget.TextView" && node.text != null) ||
                        node.className == "android.view.View" && node.contentDescription != null ||
                        node.className == "android.widget.ImageView"
            }
            if (isRoiView) {
                // Send matching node to caller
                return@processAccessibilityNodesInParallel ScreenContentNode(outBounds, null)
            }
            return@processAccessibilityNodesInParallel null
        },
        nodeFilter = { _, outBounds ->
            Rect.intersects(outBounds, rect) || outBounds.contains(rect)
        }
    )
}

fun getNeedWindowsRoot(service: GestureService): MutableList<AccessibilityNodeInfo> {
    val roots = mutableListOf<AccessibilityNodeInfo>()
    val windows: List<AccessibilityWindowInfo> = service.windows
    for (window in windows) {
        if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
        val root = window.root ?: continue
        if (root.childCount > 0) roots.add(root)
    }
    return roots
}

@RequiresApi(Build.VERSION_CODES.O)
fun getCharacterLocations(
    nodeInfo: AccessibilityNodeInfo, rectOutline: Rect
): ScreenContentNode {
    val text = nodeInfo.text
    val bundle = nodeInfo.extras
    bundle.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
    bundle.putInt(
        AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, text.length
    )
    nodeInfo.refreshWithExtraData(
        AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, bundle
    )
    val refreshedExtras = nodeInfo.extras
    val contentBuilder = StringBuilder()
    refreshedExtras.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)
        ?.forEachIndexed { index, rectF ->
            if (rectF != null) {
                val charRect = (rectF as RectF).toRect()
                if (Rect.intersects(rectOutline, charRect) || rectOutline.contains(charRect)) {
                    contentBuilder.append(text[index])
                }
            }
        }
    return ScreenContentNode(rectOutline, contentBuilder.toString())
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
