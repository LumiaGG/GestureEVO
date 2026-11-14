package io.github.lumiagg.gestureevo.link.radar

import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.text.Spanned
import android.text.style.URLSpan
import android.util.Log
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 全屏链接扫描器（LinkGo SelectToSpeakService.scanCurrentScreen 管线 1:1 移植）。
 *
 * 遍历所有可达窗口的无障碍树，从节点文本提取链接（URLSpan 优先，正则次之，
 * 覆盖 contentDescription），并尽可能附加逐字符精确坐标（API 26+），
 * 失败回退控件整框。深度与节点数上限防止极端页面撑爆。
 * 与 LinkGo 一致：不做任何坐标系变换，矩形按平台返回值原样使用。
 */
object LinkRadarScanner {

    private const val TAG = "LinkRadarScanner"

    /** 最近一次扫描的统计（w=窗口数 n=节点数 t=带文本节点数 h=含URL字样节点数），供诊断日志使用。 */
    @Volatile var lastDiagnostics: String = ""

    private const val MAX_SCAN_DEPTH = 64
    private const val MAX_SCAN_NODES = 3000
    private const val MAX_RECT_DEPTH = 32
    private const val VISIBILITY_THRESHOLD = 0.7f

    /**
     * 执行全屏扫描：纯无障碍引擎（LinkGo scanCurrentScreen 1:1）。
     * [service] 为无障碍服务实例（伪装服务或主服务），[patterns] 由调用方编译
     * （用户提取规则优先，空则回落默认正则）。
     */
    suspend fun scan(service: AccessibilityService, patterns: List<Regex>): List<LinkRegion> =
        withContext(Dispatchers.Default) {
            // 与 LinkGo 相同：每次扫描开始清空缓存，缓存仅在本次树快照内有效
            val nodeCoordCache = HashMap<String, List<Rect>>()
            val nodeParentBoundsCache = HashMap<Int, Rect>()
            var scannedNodeCount = 0
            var textNodeCount = 0
            var urlLikeNodeCount = 0

            fun normalize(bounds: Rect): Rect {
                if (bounds.top > bounds.bottom) {
                    val t = bounds.top
                    bounds.top = bounds.bottom
                    bounds.bottom = t
                }
                if (bounds.left > bounds.right) {
                    val l = bounds.left
                    bounds.left = bounds.right
                    bounds.right = l
                }
                return bounds
            }

            /**
             * 处理单行包围盒：计算可见度占比，如果不达标则过滤，达标则返回裁剪后的绘制框
             * （LinkGo processAndFilterLineBox 1:1）
             */
            fun processAndFilterLineBox(
                lineBox: Rect,
                originalHeight: Float,
                parentScreenRect: Rect,
                threshold: Float
            ): Rect? {
                // 1. 计算与父容器的 Y 轴交集（可见部分的 Top 和 Bottom）
                val visibleTop = Math.max(lineBox.top, parentScreenRect.top)
                val visibleBottom = Math.min(lineBox.bottom, parentScreenRect.bottom)

                // 2. 计算可见区域的真实高度
                val visibleHeight = Math.max(0, visibleBottom - visibleTop).toFloat()

                val ratio = if (originalHeight > 0) visibleHeight / originalHeight else 0f

                // 3. 阈值判定：达标返回 Y 轴钳制在父容器范围内的绘制框，不达标丢弃
                if (originalHeight > 0 && ratio >= threshold) {
                    return Rect(lineBox.left, visibleTop, lineBox.right, visibleBottom)
                }
                return null
            }

            /**
             * 获取链接的有效标注框（支持多行链接，并应用 70% 可见度阈值过滤）
             * （LinkGo sliceAndFilterRects 1:1）
             */
            fun sliceAndFilterRects(
                fullList: List<Rect>,
                start: Int,
                length: Int,
                parentScreenRect: Rect,
                threshold: Float = VISIBILITY_THRESHOLD
            ): List<Rect> {
                val result = mutableListOf<Rect>()
                val end = Math.min(start + length, fullList.size)

                // 【关键】找到全体字符中的最大高度，作为标准的“真实行高”
                // 因为被裁剪的行，其 charRect 高度已经被系统提前压缩，如果只用单行高度，比例永远是 1.0
                var globalMaxCharHeight = 0f
                for (rect in fullList) {
                    if (!rect.isEmpty) {
                        globalMaxCharHeight = Math.max(globalMaxCharHeight, rect.height().toFloat())
                    }
                }

                var currentLineBox: Rect? = null

                for (i in start until end) {
                    val charRect = fullList[i]
                    if (charRect.isEmpty) continue

                    val charHeight = charRect.height().toFloat()
                    if (charHeight <= 0) continue

                    // 判断是否换行（如果当前字符的 top 与当前行的 top 差距超过标准行高的一半，认定为换行）
                    val isNewLine = currentLineBox != null &&
                        Math.abs(charRect.top - currentLineBox.top) > (globalMaxCharHeight / 2f)

                    if (currentLineBox == null || isNewLine) {
                        // 1. 结算并判定上一行的可见度
                        if (currentLineBox != null) {
                            processAndFilterLineBox(currentLineBox, globalMaxCharHeight, parentScreenRect, threshold)
                                ?.let { result.add(it) }
                        }

                        // 2. 初始化新的一行
                        currentLineBox = Rect(charRect)
                    } else {
                        // 3. 还在同一行，向右水平扩展当前行的包围盒
                        currentLineBox.union(charRect)
                    }
                }

                // 结算最后一行
                if (currentLineBox != null) {
                    processAndFilterLineBox(currentLineBox, globalMaxCharHeight, parentScreenRect, threshold)
                        ?.let { result.add(it) }
                }

                return result
            }

            /**
             * Requests per-character rects via refreshWithExtraData (API 26+). One accessibility-focus
             * nudge retry; callers fall back to node bounds when this returns empty.
             * （LinkGo tryGetRects 1:1）
             */
            fun tryGetRects(
                node: AccessibilityNodeInfo,
                start: Int,
                length: Int,
                isDesc: Boolean
            ): List<Rect> {
                val nodeText = (if (isDesc) node.contentDescription else node.text)?.toString() ?: ""
                if (nodeText.isEmpty()) return emptyList()
                if (isDesc && node.text.isNullOrEmpty()) return emptyList()

                try {
                    // 1. 获取控件当前的物理显示区域
                    // 对于被父容器截断的控件，getBoundsInScreen 返回的是实际可见的部分
                    val nodeScreen = Rect()
                    node.getBoundsInScreen(nodeScreen)

                    val arguments = Bundle()
                    arguments.putInt(
                        AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0
                    )
                    arguments.putInt(
                        AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, nodeText.length
                    )

                    var success = node.refreshWithExtraData(
                        AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, arguments
                    )
                    if (!success || node.extras.get(
                            AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
                        ) == null
                    ) {
                        // 首次未取到：请求无障碍焦点后立即重试一次（LinkGo 同款，不用 Thread.sleep）
                        node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                        success = node.refreshWithExtraData(
                            AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, arguments
                        )
                    }

                    if (success) {
                        val rawData = node.extras.get(
                            AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
                        )
                        val items = when (rawData) {
                            is Array<*> -> rawData.toList()
                            is List<*> -> rawData
                            else -> null
                        }
                        if (!items.isNullOrEmpty()) {
                            // 存储原始的字符坐标，不在此处做边界裁剪，以便后续行组合时计算真实高度
                            val allRects = items.map { item ->
                                when (item) {
                                    is Rect -> item
                                    is RectF -> Rect().apply { RectF(item.left, item.top, item.right, item.bottom).roundOut(this) }
                                    else -> Rect()
                                }
                            }
                            if (allRects.any { !it.isEmpty }) {
                                nodeCoordCache["${node.viewIdResourceName}_${nodeText.hashCode()}"] = allRects
                                return sliceAndFilterRects(allRects, start, length, nodeScreen)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "获取精准坐标异常", e)
                }
                return emptyList()
            }

            fun findRectsDeeply(
                node: AccessibilityNodeInfo,
                start: Int,
                length: Int,
                isDesc: Boolean,
                depth: Int = 0
            ): List<Rect> {
                // 递归深度上限，防止控件层级极深时无界递归
                if (depth > MAX_RECT_DEPTH) return emptyList()
                val fromSelf = tryGetRects(node, start, length, isDesc)
                if (fromSelf.isNotEmpty()) return fromSelf

                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    val fromChild = findRectsDeeply(child, start, length, isDesc, depth + 1)
                    if (fromChild.isNotEmpty()) return fromChild
                }
                return emptyList()
            }

            fun getPreciseRects(
                node: AccessibilityNodeInfo,
                start: Int,
                length: Int,
                isDesc: Boolean
            ): List<Rect> {
                val source = if (isDesc) node.contentDescription else node.text
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                // Key = 控件 ID + 内容哈希 + 当前物理位置，三重验证
                // （LinkGo 原版读写 key 不一致导致缓存永不命中，此处修正为统一 key）
                val nodeKey = "${node.viewIdResourceName}_${source?.hashCode()}_${bounds.hashCode()}"
                nodeCoordCache[nodeKey]?.let { cached ->
                    return sliceAndFilterRects(cached, start, length, bounds)
                }

                if (isDesc && node.text.isNullOrEmpty()) return emptyList()

                return findRectsDeeply(node, start, length, isDesc)
            }

            fun addRegion(
                node: AccessibilityNodeInfo,
                url: String,
                start: Int,
                length: Int,
                isDesc: Boolean,
                results: MutableList<LinkRegion>,
                nodeBounds: Rect
            ) {
                if (length <= 0) return
                val rects = getPreciseRects(node, start, length, isDesc)
                if (rects.isNotEmpty()) {
                    results.add(
                        LinkRegion(
                            url = url,
                            rects = rects.map { normalize(Rect(it)) },
                            isFromDescription = isDesc,
                            groupId = node.hashCode(),
                            isPrecise = true,
                            textOffset = start,
                            nodeBounds = normalize(Rect(nodeBounds))
                        )
                    )
                } else {
                    results.add(
                        LinkRegion(
                            url = url,
                            rects = listOf(normalize(Rect(nodeBounds))),
                            isFromDescription = isDesc,
                            groupId = node.hashCode(),
                            isPrecise = false,
                            textOffset = start,
                            nodeBounds = normalize(Rect(nodeBounds))
                        )
                    )
                }
            }

            /**
             * 从节点内容提取链接：URLSpan 优先（精确范围），正则次之（多规则 + 贪心区间去重）
             * （LinkGo processContent 1:1）
             */
            fun processContent(
                node: AccessibilityNodeInfo,
                content: String?,
                isDesc: Boolean,
                results: MutableList<LinkRegion>
            ) {
                if (content.isNullOrBlank()) return

                val nodeBounds = normalize(Rect().also { node.getBoundsInScreen(it) })

                // 优先处理 Span（LinkGo 原版带 API>=O 守卫，1:1 保留）
                if (!isDesc && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val text = node.text
                    if (text is Spanned) {
                        val urlSpans = text.getSpans(0, text.length, URLSpan::class.java)
                        if (!urlSpans.isNullOrEmpty()) {
                            for (span in urlSpans) {
                                val url = span.url ?: continue
                                val start = text.getSpanStart(span)
                                val end = text.getSpanEnd(span)
                                addRegion(node, url, start, end - start, false, results, nodeBounds)
                            }
                            if (results.isNotEmpty()) return
                        }
                    }
                }

                // 正则解析：多规则遍历 + 贪心区间去重（LinkGo 1:1）
                class RawMatch(val url: String, val start: Int, val end: Int)
                val allMatches = mutableListOf<RawMatch>()

                for (pattern in patterns) {
                    try {
                        val matcher = pattern.toPattern().matcher(content)
                        while (matcher.find()) {
                            val url = if (matcher.groupCount() > 0) matcher.group(1) else matcher.group() ?: continue
                            allMatches.add(RawMatch(url, matcher.start(), matcher.end()))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "正则解析失败: ${e.message}")
                    }
                }

                if (allMatches.isEmpty()) return

                // 贪心区间去重：按长度降序，有交集的丢弃短的
                val sorted = allMatches.sortedByDescending { it.end - it.start }
                val selected = mutableListOf<RawMatch>()
                for (m in sorted) {
                    val hasOverlap = selected.any { s -> m.start < s.end && m.end > s.start }
                    if (!hasOverlap) selected.add(m)
                }

                // 按位置排序后生成 LinkRegion
                for (m in selected.sortedBy { it.start }) {
                    addRegion(node, m.url, m.start, m.end - m.start, isDesc, results, nodeBounds)
                }
            }

            fun extractLinksFromNode(
                node: AccessibilityNodeInfo,
                results: MutableList<LinkRegion>
            ) {
                val parent = runCatching { node.parent }.getOrNull()
                if (parent != null) {
                    val pBounds = Rect()
                    parent.getBoundsInScreen(pBounds)
                    nodeParentBoundsCache[node.hashCode()] = pBounds
                }

                // 诊断统计：带文本节点数、含 URL 字样的节点数（只记数字不记内容）
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                if (text.isNotBlank() || desc.isNotBlank()) textNodeCount++
                if (text.contains("http", ignoreCase = true) ||
                    desc.contains("http", ignoreCase = true) ||
                    text.contains("www.", ignoreCase = true)
                ) urlLikeNodeCount++

                // 允许精准与非精准共存（LinkGo 1:1）
                processContent(node, text, false, results)
                processContent(node, desc, true, results)
            }

            /**
             * 深度优先遍历无障碍树并提取链接。
             * 带深度与节点规模上限，防止极端复杂页面（节点数千+）时递归过深/对象堆积导致内存尖峰。
             * 层次剪枝：如果子节点已经包含了同样的 URL，父节点不再保留（LinkGo 1:1）。
             */
            fun collectA11yCandidates(
                node: AccessibilityNodeInfo?,
                results: MutableList<LinkRegion>,
                depth: Int
            ) {
                if (node == null) return
                if (depth > MAX_SCAN_DEPTH || scannedNodeCount >= MAX_SCAN_NODES) return
                scannedNodeCount++

                // 1. 先递归子节点 (深度优先)
                val childResults = mutableListOf<LinkRegion>()
                for (i in 0 until node.childCount) {
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    collectA11yCandidates(child, childResults, depth + 1)
                }

                // 2. 提取当前节点
                val currentResults = mutableListOf<LinkRegion>()
                extractLinksFromNode(node, currentResults)

                // 3. 层次剪枝：如果子节点已经包含了同样的 URL，父节点不再保留
                val filteredCurrent = currentResults.filter { cur ->
                    childResults.none { it.url == cur.url }
                }

                results.addAll(childResults)
                results.addAll(filteredCurrent)
            }

            /**
             * 最终排序与去重（LinkGo sortAndFilterResults 1:1）：
             * URL + 首矩形去重 → 按 groupId 分组 → 组间按视觉位置（10px Top 桶）→ 组内按 textOffset
             */
            fun sortAndFilterResults(results: List<LinkRegion>): List<LinkRegion> {
                if (results.isEmpty()) return emptyList()

                // 1. 去重：URL + 第一个矩形位置
                val unique = results.distinctBy { it.url + it.rects.firstOrNull()?.toShortString() }

                // 2. 按 groupId (Node) 分组，这是解决“裁剪/回退”干扰排序的关键
                val nodeGroups = unique.groupBy { it.groupId }

                // 3. 组间排序：基于控件在屏幕上的视觉位置 (使用 10px 容差的 Top 桶)
                val sortedGroups = nodeGroups.values.sortedWith(
                    compareBy(
                        { (it.minOf { l -> l.rects.firstOrNull()?.top ?: 0 } / 10) },
                        { it.minOf { l -> l.rects.firstOrNull()?.left ?: 0 } }
                    )
                )

                // 4. 组内排序：严格遵循文本顺序 (textOffset)
                return sortedGroups.flatMap { group -> group.sortedBy { it.textOffset } }
            }

            // ---- 扫描主体（LinkGo scanCurrentScreen 1:1）----

            val finalResults = mutableListOf<LinkRegion>()
            var windowsScanned = 0

            val currentWindows = runCatching { service.windows }.getOrDefault(emptyList())
            currentWindows.forEachIndexed { _, window ->
                val root = runCatching { window.root }.getOrNull() ?: return@forEachIndexed
                val pkgName = root.packageName?.toString() ?: "Unknown"

                // 排除自身窗口，避免干扰扫描（LinkGo 同款，包名换成本仓库）
                if (pkgName == service.packageName) return@forEachIndexed

                windowsScanned++
                collectA11yCandidates(root, finalResults, 0)
            }

            if (finalResults.isEmpty()) {
                val activeRoot = runCatching { service.rootInActiveWindow }.getOrNull()
                val pkgActive = activeRoot?.packageName?.toString() ?: "Unknown"

                if (pkgActive != service.packageName) {
                    windowsScanned++
                    activeRoot?.let { collectA11yCandidates(it, finalResults, 0) }
                }
            }

            lastDiagnostics =
                "w${windowsScanned} n$scannedNodeCount t$textNodeCount h$urlLikeNodeCount"
            sortAndFilterResults(finalResults)
        }
}
