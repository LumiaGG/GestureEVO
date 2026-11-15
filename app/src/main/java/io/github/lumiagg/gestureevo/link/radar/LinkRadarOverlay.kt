package io.github.lumiagg.gestureevo.link.radar

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.animation.ValueAnimator
import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import io.github.lumiagg.gestureevo.diagnostics.LogModule
import io.github.lumiagg.gestureevo.floating.BaseFloatingWindow
import io.github.lumiagg.gestureevo.gesture.GestureService
import io.github.lumiagg.gestureevo.link.LinkDispatcher
import io.github.lumiagg.gestureevo.link.LinkLauncher
import io.github.lumiagg.gestureevo.link.LinkRuleRepository
import io.github.lumiagg.gestureevo.pointer.PointerFloating
import io.github.lumiagg.gestureevo.utils.CurrentPackageProvider
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.HapticHelper
import io.github.lumiagg.gestureevo.utils.UrlDetector
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 链接雷达（LinkGo RadarFinderOverlay 的 GEVO 移植版）。
 *
 * 与 LinkGo 的对应关系：
 * - LinkGo 的探照圆球 → GEVO 真实指针（位置由 [pointerStateProvider] 提供）
 * - LinkGo 的手势会话 → GEVO 指针会话（呼出指针 = 开始，松手 = 结束，每次会话扫描一次）
 * - LinkGo 松手分发 → 指针点击/拖选松手时命中链接即按规则分发
 * - LinkGo 的取图/框选/边缘手势分支 → 不移植
 *
 * 保留 1:1 的部分：三层碰撞判定（文字直触>宿主大框>整框回退）、高亮框+ModernPill 角标、
 * 「在 X 中打开」胶囊（含深浅色材质/110ms 淡切/150-90ms HUD 动效）、触感状态机
 * （40ms 防抖 + 80ms 唤起保护 + lastPhysicalTargetId）、70ms 磁滞消退。
 */
class LinkRadarOverlay(
    private val service: GestureService,
    private val pointerStateProvider: () -> PointerFloating.PointerSnapshot?,
) : BaseFloatingWindow(service, service, "LinkRadarOverlay") {

    private val overlayView by lazy { RadarOverlayView(service) { hits -> dispatchHits(hits) } }
    private val layoutParams = WindowManager.LayoutParams()
    private var pollJob: Job? = null
    private var scanJob: Job? = null
    private var sessionActive = false
    private var sessionGeneration = 0

    // GEVO 指针会话比 LinkGo 长（指针开启期间用户会正常滚动页面）：
    // 页面一滚就需要重扫，否则链接区域全是旧位置（幻影吸附/错位）。
    @Volatile private var scanRunning = false
    @Volatile private var rescanPending = false
    private var lastRescanAt = 0L
    private var lastScanCount = -1
    private var rescanScheduledJob: Job? = null

    // 空结果重试（LinkGo runIdentificationFlow 同款）：树未就绪的空扫描自动补扫
    private var emptyRetryJob: Job? = null
    private var emptyRetryCount = 0


    init {
        layoutParams.configureAccessibilityOverlay(
            // LinkGo 同款窗口参数 + FLAG_NOT_TOUCHABLE：GEVO 指针的触控层必须继续接收手指，
            // 本层只做绘制不拦截触摸（这是与 LinkGo 唯一的窗口参数差异）。
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            format = android.graphics.PixelFormat.TRANSLUCENT,
            gravity = Gravity.FILL,
            title = "GestureEVO:LinkRadarOverlay",
        )
    }

    /**
     * 指针会话开始：重置状态 → 挂载 → 异步预扫描。
     * 每次呼出清理上一会话；关闭由指针隐藏回调负责。
     */
    fun startSession() {
        if (sessionActive) stopSession()
        sessionActive = true
        emptyRetryCount = 0
        service.launch(Dispatchers.Main) {
            overlayView.resetForSession()
            // 退场动画会把整层 View 的 alpha 动画到 0（View 常驻复用不销毁），
            // 每次呼出必须还原，否则下一会话整层透明：只剩震动、无气泡。
            overlayView.alpha = 1f
            overlayView.scaleX = 1f
            overlayView.scaleY = 1f
            attachViewSafely(overlayView, layoutParams)
        }
        // 每次呼出都重扫：指针隐藏期间页面可能已切换
        lastScanCount = -1
        launchScan()
        startPolling()
    }

    /** 指针会话结束：卸载全屏层。 */
    fun stopSession() {
        if (!sessionActive) return
        sessionActive = false
        sessionGeneration++
        pollJob?.cancel(); pollJob = null
        scanJob?.cancel(); scanJob = null
        rescanScheduledJob?.cancel(); rescanScheduledJob = null
        emptyRetryJob?.cancel(); emptyRetryJob = null
        emptyRetryCount = 0
        scanRunning = false
        rescanPending = false
        service.launch(Dispatchers.Main) {
            overlayView.resetForSession()
            detachViewSafely(overlayView)
        }
    }

    fun refreshTheme() {
        if (sessionActive) overlayView.invalidate()
    }

    fun destroy() = stopSession()

    /**
     * 页面变化驱动的重扫入口（GEVO 指针会话比 LinkGo 长：指针开启期间用户会正常滚动
     * 页面，一次扫描的链接区域会全部过期）。节流 + 到点补扫，绝不静默丢弃请求。
     */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!sessionActive) return
        // 自身浮层（指针、面板、雷达本身）的内容变化不触发重扫。
        val pkg = event.packageName?.toString() ?: return
        if (pkg == service.packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> requestRescan()
        }
    }

    private fun requestRescan() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRescanAt < RESCAN_MIN_INTERVAL_MS) {
            rescanPending = true
            scheduleRescan(now - lastRescanAt)
            return
        }
        lastRescanAt = now
        launchScan()
    }

    private fun scheduleRescan(elapsed: Long) {
        if (rescanScheduledJob?.isActive == true) return
        rescanScheduledJob = service.launch(Dispatchers.Main.immediate) {
            delay((RESCAN_MIN_INTERVAL_MS - elapsed).coerceAtLeast(16L))
            rescanScheduledJob = null
            if (sessionActive && rescanPending && !scanRunning) {
                lastRescanAt = SystemClock.elapsedRealtime()
                rescanPending = false
                launchScan()
            }
        }
    }

    private fun launchScan() {
        if (scanRunning) {
            rescanPending = true
            return
        }
        scanRunning = true
        val generation = sessionGeneration
        emptyRetryJob?.cancel()
        scanJob?.cancel()
        scanJob = service.launch(Dispatchers.Main.immediate) {
            try {
                do {
                    rescanPending = false
                    val links = try {
                        LinkRadarScanner.scan(service, compilePatterns())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w("LinkRadar", "Link scan failed", error)
                        emptyList()
                    }
                    Log.d("LinkRadar", "雷达扫描完成：链接 ${links.size} 个")
                    withContext(Dispatchers.Main) {
                        if (!sessionActive || generation != sessionGeneration) return@withContext
                        overlayView.setLinks(links)
                        if (links.size != lastScanCount) {
                            lastScanCount = links.size
                            AppLogger.event(
                                LogModule.OVERLAY, "link_radar_scan",
                                fields = mapOf(
                                    "count" to links.size,
                                    "package" to (CurrentPackageProvider.currentPackageFlow.value ?: ""),
                                    // 扫描统计：w=窗口数 n=节点数 t=带文本数 h=含URL字样数
                                    "method" to LinkRadarScanner.lastDiagnostics,
                                )
                            )
                        }
                    }
                    // 空结果重试（LinkGo runIdentificationFlow 同款节奏）：
                    // 刚切应用/转场时无障碍树可能未就绪，自动补扫直到拿到结果或次数用尽
                    if (links.isEmpty()) {
                        withContext(Dispatchers.Main) { scheduleEmptyRetry() }
                    } else {
                        withContext(Dispatchers.Main) { emptyRetryCount = 0 }
                    }
                } while (rescanPending && sessionActive)
            } finally {
                if (generation == sessionGeneration) scanRunning = false
            }
        }
    }

    /** 空结果补扫调度（LinkGo 同款：普通 4 次 × 150ms，次数封顶防无限扫）。 */
    private fun scheduleEmptyRetry() {
        if (emptyRetryCount >= MAX_EMPTY_RETRIES || !sessionActive) return
        emptyRetryCount++
        emptyRetryJob?.cancel()
        emptyRetryJob = service.launch(Dispatchers.Main.immediate) {
            delay(EMPTY_RETRY_INTERVAL_MS)
            if (!sessionActive) return@launch
            launchScan()
        }
    }

    /** 30ms 轮询：把 GEVO 指针位置喂给绘制层（对应 LinkGo 的逐 MOVE 碰撞判定）。 */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = service.launch(Dispatchers.Main) {
            while (sessionActive) {
                val snapshot = pointerStateProvider()
                if (snapshot == null) {
                    // 指针图标隐藏（会话结束/动画中）：收起高亮与胶囊
                    overlayView.hideVisual()
                } else {
                    overlayView.updatePointer(snapshot.centerX, snapshot.centerY, snapshot.radiusPx)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * 指针点击/拖选松手拦截：命中链接 = LinkGo 的「松手分发」。
     * 返回 true 表示已消费本次手势（不向屏幕合成点击）。
     */
    fun onPointerTapRequested(x: Float, y: Float): Boolean {
        if (!sessionActive) return false
        val snapshot = pointerStateProvider() ?: return false
        overlayView.updatePointer(snapshot.centerX, snapshot.centerY, snapshot.radiusPx)
        return overlayView.dispatchHit()
    }

    private fun dispatchHits(hits: List<LinkRegion>) {
        val sourcePackage = CurrentPackageProvider.currentPackageFlow.value ?: ""
        AppLogger.event(
            LogModule.OVERLAY, "link_radar_dispatch",
            fields = mapOf(
                "source" to "pointer_radar",
                "package" to sourcePackage,
                "count" to hits.size,
                "reason" to "radar",
            )
        )
        // LinkGo：单链接直达（WindowRouter.openBrowser 对应我们的 LinkLauncher.openUrl）；
        // 多链接 → LinkSelectionActivity 选择页（LinkGo 同款流程）
        if (hits.size > 1) {
            val intent = Intent(service, LinkSelectionActivity::class.java).apply {
                putStringArrayListExtra(LinkSelectionActivity.EXTRA_URLS, ArrayList(hits.map { it.url }))
                putExtra(LinkSelectionActivity.EXTRA_SOURCE_PACKAGE, sourcePackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.startActivity(intent)
            return
        }
        val target = hits.first()
        service.launch {
            LinkLauncher.openUrl(
                service.applicationContext,
                target.url,
                sourcePackage = sourcePackage
            )
        }
    }

    /** 用户提取规则优先，空则回落 LinkGo UrlUtils.DEFAULT_REGEX（LinkGo 1:1）。 */
    private suspend fun compilePatterns(): List<Regex> {
        val user = LinkRuleRepository.loadExtractPatterns(service)
            .filter { it.isEnabled && it.pattern.isNotBlank() }
        val compiled = user.mapNotNull { ep ->
            runCatching {
                Regex(ep.pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            }.getOrNull()
        }
        if (compiled.isNotEmpty()) return compiled
        return listOf(Regex(DEFAULT_REGEX, RegexOption.IGNORE_CASE))
    }

    private companion object {
        /** LinkGo UrlUtils.DEFAULT_REGEX 1:1 */
        const val DEFAULT_REGEX =
            "(?:[a-zA-Z0-9+.-]+://|[a-zA-Z0-9+.-]+%3A%2F%2F)[^\\s\\u4e00-\\u9fa5]+(?<![.,!?])"
        const val POLL_INTERVAL_MS = 30L

        /** 页面变化重扫的节流间隔（GEVO 指针会话比 LinkGo 长，页面会滚动，需要跟扫）。 */
        const val RESCAN_MIN_INTERVAL_MS = 300L

        /** 空结果重试（LinkGo runIdentificationFlow 同款）：普通呼出 4 次 × 150ms。 */
        const val MAX_EMPTY_RETRIES = 4
        const val EMPTY_RETRY_INTERVAL_MS = 150L
    }

    // ==================================================================
    // 全屏绘制层（LinkGo RadarFinderView 裁剪移植：去掉圆球/取图/框选/边缘手势）
    // ==================================================================

    private inner class RadarOverlayView(
        context: Context,
        private val onDispatch: (List<LinkRegion>) -> Unit,
    ) : View(context) {

        private val viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val density = resources.displayMetrics.density
        private val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

        // ---- 状态（LinkGo RadarFinderView 同名字段）----
        private var links: List<LinkRegion> = emptyList()
        private var hitLinks: List<LinkRegion> = emptyList()
        private var hitBoxRect: Rect? = null

        /** GEVO 指针中心与半径（对应 LinkGo 的 currentBallX/Y + ballRadiusPx） */
        private var currentPointerX = 0f
        private var currentPointerY = 0f
        private var pointerRadiusPx = 15f * density

        // 胶囊展示信息 (LinkGo 同名字段)
        private var currentAppIcon: Bitmap? = null
        private var prefixText: String = ""
        private var accentText: String = ""
        private var suffixText: String = ""
        private var badgeText: String? = null // 多链接角标 (例如 "2", "3", "9+")
        private var isManualSelect: Boolean = false

        // HUD 动画状态
        private var hudAlpha = 0f
        private var hudScale = 1f
        private var isHudShowing = false
        private var hudAnimator: ValueAnimator? = null

        // 磁滞消退守护 (LinkGo 同款 70ms)
        private var activeTargetLinks: List<LinkRegion> = emptyList()
        private var dismissGraceRunnable: Runnable? = null
        private val DISMISS_GRACE_MS = 70L

        // 触感反馈状态机 (LinkGo 同款：40ms 防抖 + 80ms 唤起保护 + lastPhysicalTargetId)
        private var lastHapticTime = 0L
        private val HAPTIC_DEBOUNCE_MS = 40L
        private var initialHapticSuppressedUntil = 0L
        private var lastPhysicalTargetId: String? = null

        // 单链接异步预测协程句柄与内存极速缓存 (0ms 秒弹首帧)
        private var predictionJob: Job? = null
        private val localTargetCache = mutableMapOf<String, Pair<Bitmap?, Triple<String, String, String>>>()

        // ---- 主题与材质色 (LinkGo 同款，主题色走 GEVO 动态取色) ----
        private val themeColor: Int
            get() = DynamicColorUtils.primaryColor
        private val surfaceColor = if (isDark) Color.parseColor("#202124") else Color.WHITE
        private val onPrimaryColor = if (isDark) Color.BLACK else Color.WHITE
        private val darkBgColor = 0xFF202124.toInt()
        private val lightBgColor = 0xFFFFFFFF.toInt()
        private val darkTextColor = 0xFFE8EAED.toInt()
        private val lightTextColor = 0xFF202124.toInt()

        // ---- 画笔（LinkGo 同名同参）----
        private val highlightBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = if (isDark) 4.5f else 5.0f
            pathEffect = CornerPathEffect(12f)
        }
        private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val pillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            isFakeBoldText = true
        }
        private val capsuleShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { style = Paint.Style.FILL }
        private val capsuleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply { style = Paint.Style.FILL }
        private val capsuleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.0f * density
        }
        private val capsuleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14f * density
            isFakeBoldText = true
        }
        private val capsuleAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14f * density
            isFakeBoldText = true
        }
        private val capsuleBadgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val capsuleBadgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.8f * density
        }
        private val capsuleBadgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11.5f * density
            typeface = Typeface.DEFAULT_BOLD
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        private val tempPath = Path()

        init {
            // 启用软件绘制以获得最细腻平滑的高精度阴影（LinkGo 同款）
            setLayerType(LAYER_TYPE_SOFTWARE, null)

            // 配置胶囊材质色彩（LinkGo init 1:1）
            val darkBg = 0xF4202124.toInt()
            val lightBg = 0xF8FFFFFF.toInt()
            capsuleBgPaint.color = if (isDark) darkBg else lightBg

            val shadowColor = if (isDark) Color.argb(120, 0, 0, 0) else Color.argb(35, 0, 0, 0)
            val shadowRadius = 8f * density
            val shadowDy = 2.5f * density
            capsuleShadowPaint.color = shadowColor
            capsuleShadowPaint.setShadowLayer(shadowRadius, 0f, shadowDy, shadowColor)

            capsuleBorderPaint.color = Color.argb(0x28, 255, 255, 255)
        }

        /** 每次指针会话开始重置状态（LinkGo 每次新建 View 的等效操作）。 */
        fun resetForSession() {
            animate().cancel()
            localTargetCache.clear()
            links = emptyList()
            hitLinks = emptyList()
            hitBoxRect = null
            activeTargetLinks = emptyList()
            dismissGraceRunnable?.let { removeCallbacks(it) }
            dismissGraceRunnable = null
            predictionJob?.cancel()
            predictionJob = null
            hudAnimator?.cancel()
            hudAnimator = null
            hudAlpha = 0f
            hudScale = 1f
            isHudShowing = false
            prefixText = ""
            accentText = ""
            suffixText = ""
            badgeText = null
            isManualSelect = false
            currentAppIcon = null
            lastPhysicalTargetId = null
            lastHapticTime = 0L
            initialHapticSuppressedUntil = System.currentTimeMillis() + 80L
            invalidate()
        }

        fun setLinks(links: List<LinkRegion>) {
            hideVisual()
            this.links = links
        }

        /** 指针隐藏时收起高亮与胶囊（不清扫描结果，会话仍在）。 */
        fun hideVisual() {
            dismissGraceRunnable?.let { removeCallbacks(it) }
            dismissGraceRunnable = null
            predictionJob?.cancel()
            hitLinks = emptyList()
            hitBoxRect = null
            activeTargetLinks = emptyList()
            if (isHudShowing || hudAlpha > 0f) {
                hudAnimator?.cancel()
                hudAnimator = null
                hudAlpha = 0f
                isHudShowing = false
            }
            invalidate()
        }

        /** GEVO 指针位置喂入（对应 LinkGo 的 MOVE 事件 + updatePointerCoordinates）。 */
        fun updatePointer(x: Float, y: Float, radiusPx: Float) {
            currentPointerX = x
            currentPointerY = y
            pointerRadiusPx = radiusPx
            checkCollision()
            invalidate()
        }

        /**
         * 松手分发（LinkGo ACTION_UP 命中分支 1:1）：
         * 命中 → tick 触感 → 交回控制器跳转 → 80ms 敏捷退场；未命中 → 收拢胶囊淡出。
         * 返回 true 表示本次松手被雷达消费。
         */
        fun dispatchHit(): Boolean {
            dismissGraceRunnable?.let {
                removeCallbacks(it)
                dismissGraceRunnable = null
            }

            val currentHits = hitLinks
            if (currentHits.isNotEmpty()) {
                // 命中链接松手：清脆一致的 tick 触感（LinkGo 同款）
                HapticHelper.tick(context, this)

                // 单/多链接的跳转交回控制器（多链接 v1 取最近，LinkGo 走选择页暂不移植）
                onDispatch(currentHits)

                // 命中目标：全屏 Overlay 敏捷退场（LinkGo 同款 80ms）
                // 退场后整层 View 仍挂载复用，动画结束必须还原 alpha/scale，
                // 否则下次会话整层透明（只剩震动无气泡）。
                animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(80).withEndAction {
                    hudAlpha = 0f
                    isHudShowing = false
                    hitLinks = emptyList()
                    hitBoxRect = null
                    alpha = 1f
                    scaleX = 1f
                    scaleY = 1f
                    invalidate()
                }.start()
                return true
            }

            // 未命中（移到空白处放弃）：立即收拢胶囊，全屏极速淡出，毫无拖沓（LinkGo 同款 70ms）
            hudAnimator?.cancel()
            hudAlpha = 0f
            isHudShowing = false
            animate().alpha(0f).setDuration(70).withEndAction {
                hitLinks = emptyList()
                hitBoxRect = null
                alpha = 1f
                invalidate()
            }.start()
            return false
        }

        /**
         * 碰撞判定（LinkGo checkCollision 1:1，裁去取图分支）：
         * 三级优先级聚合 + 5dp 磁滞 + 触感状态机 + 胶囊淡切 + 70ms 磁滞消退。
         */
        private fun checkCollision() {
            if (links.isEmpty()) return

            val ballCenterX = currentPointerX
            val ballCenterY = currentPointerY
            val detectRadius = pointerRadiusPx * 1.15f

            // 1. 扫描计算每个链接到圆心的最短欧氏距离（区分文字精准距离与宿主大框距离）
            var minDistanceToAnyLink = Float.MAX_VALUE
            val preciseDirectCandidates = mutableListOf<Pair<LinkRegion, Float>>()
            val fallbackCandidates = mutableListOf<Pair<LinkRegion, Float>>()

            for (link in links) {
                val distPrecise = shortestDistance(link.rects, ballCenterX, ballCenterY)
                val distBox = link.nodeBounds?.let { boxDistance(it, ballCenterX, ballCenterY) } ?: distPrecise

                if (link.isPrecise) {
                    // 宿主大框层级已移除：滑入消息卡片空白处不再吸附，碰到链接文字才命中
                    if (distPrecise <= detectRadius) {
                        preciseDirectCandidates.add(link to distPrecise)
                    }
                } else if (minOf(distPrecise, distBox) <= detectRadius) {
                    fallbackCandidates.add(link to minOf(distPrecise, distBox))
                }
            }

            // 2. 聚合判定处理（LinkGo 三级优先级 1:1，宿主大框层级已移除）
            val matchedList = mutableListOf<LinkRegion>()
            var hitBoxRect: Rect? = null

            if (preciseDirectCandidates.isNotEmpty()) {
                // === 优先级 1：指针直接触碰到了精准文字（最高优先级）===
                preciseDirectCandidates.sortBy { it.second }

                // 磁滞防抖
                val currentTargetId = lastPhysicalTargetId
                var primaryLink = preciseDirectCandidates[0].first
                if (currentTargetId != null && preciseDirectCandidates.size > 1) {
                    val activeIndex = preciseDirectCandidates.indexOfFirst { it.first.url == currentTargetId }
                    if (activeIndex > 0) {
                        val firstDist = preciseDirectCandidates[0].second
                        val activeDist = preciseDirectCandidates[activeIndex].second
                        if (activeDist - firstDist < 5f * density) {
                            primaryLink = preciseDirectCandidates[activeIndex].first
                        }
                    }
                }

                val overlappingPrecise = preciseDirectCandidates.filter { candidate ->
                    candidate.first != primaryLink &&
                        isRectsOverlapping(candidate.first.rects, primaryLink.rects)
                }.map { it.first }

                if (overlappingPrecise.isNotEmpty()) {
                    matchedList.add(primaryLink)
                    matchedList.addAll(overlappingPrecise)
                } else {
                    matchedList.add(primaryLink)
                }
            } else if (fallbackCandidates.isNotEmpty()) {
                // === 优先级 3：纯未定位回退大框 ===
                fallbackCandidates.sortBy { it.second }
                val firstFallback = fallbackCandidates.first().first
                val targetGroupId = firstFallback.groupId
                hitBoxRect = firstFallback.nodeBounds ?: firstFallback.rects.firstOrNull()

                val groupAllUnprecise = links.filter { it.groupId == targetGroupId && !it.isPrecise }
                if (groupAllUnprecise.size >= 2) {
                    matchedList.addAll(groupAllUnprecise)
                } else {
                    matchedList.add(firstFallback)
                }
            }

            // 3. 物理命中状态与触感反馈状态机（LinkGo 1:1）
            this.hitBoxRect = hitBoxRect
            hitLinks = matchedList

            if (matchedList.isNotEmpty()) {
                // 扫到任何有效链接，立即取消消退延时！
                dismissGraceRunnable?.let {
                    removeCallbacks(it)
                    dismissGraceRunnable = null
                }

                // A. 物理碰撞与即时震动判定：
                // 只要当前命中的主要目标与上次不同（从空白滑入链接，或在不同链接之间切换），
                // 立即同步触发震动！
                val primaryTarget = matchedList.first()
                val targetId = if (matchedList.size > 1) {
                    "group_${primaryTarget.groupId}_${matchedList.size}"
                } else {
                    primaryTarget.url
                }
                if (targetId != lastPhysicalTargetId) {
                    lastPhysicalTargetId = targetId
                    val now = System.currentTimeMillis()
                    if (now > initialHapticSuppressedUntil && (now - lastHapticTime >= HAPTIC_DEBOUNCE_MS)) {
                        lastHapticTime = now
                        HapticHelper.tick(context, this)
                    }
                }

                // B. 胶囊视觉展示状态机（带磁滞平滑，整体离屏淡入淡出）
                val isNewTarget = matchedList != activeTargetLinks
                if (isNewTarget) {
                    switchCapsuleWithFade(matchedList)
                }

                if (!isHudShowing) {
                    animateHud(show = true)
                }
            } else {
                // 指针离开所有有效链接滑入空白处（LinkGo 1:1）
                lastPhysicalTargetId = null
                hitBoxRect = null

                // 智能空间距离感知极速消退机制
                val isFarAway = minDistanceToAnyLink > (detectRadius * 1.35f)

                if (isHudShowing) {
                    if (isFarAway) {
                        dismissGraceRunnable?.let {
                            removeCallbacks(it)
                            dismissGraceRunnable = null
                        }
                        activeTargetLinks = emptyList()
                        animateHud(show = false)
                    } else if (dismissGraceRunnable == null) {
                        // 行隙临界区：保留仅 70ms 瞬态微缓冲，杜绝行隙抽搐
                        dismissGraceRunnable = Runnable {
                            if (hitLinks.isEmpty()) {
                                activeTargetLinks = emptyList()
                                animateHud(show = false)
                            }
                            dismissGraceRunnable = null
                        }
                        postDelayed(dismissGraceRunnable, DISMISS_GRACE_MS)
                    }
                }
            }
            invalidate()
        }

        /** 点到单个矩形的最近点距离（矩形内为 0）。 */
        private fun boxDistance(rect: Rect, x: Float, y: Float): Float {
            val cx = x.coerceIn(rect.left.toFloat(), rect.right.toFloat())
            val cy = y.coerceIn(rect.top.toFloat(), rect.bottom.toFloat())
            val dx = x - cx
            val dy = y - cy
            return kotlin.math.hypot(dx, dy)
        }

        /** 点到矩形列表的最近距离（LinkGo distanceToRects 语义）。 */
        private fun shortestDistance(rects: List<Rect>, x: Float, y: Float): Float =
            rects.minOfOrNull { boxDistance(it, x, y) } ?: Float.MAX_VALUE

        /** 检查两个矩形列表是否存在真实的几何相交重叠（LinkGo isRectsOverlapping 1:1）。 */
        private fun isRectsOverlapping(rects1: List<Rect>, rects2: List<Rect>): Boolean {
            for (r1 in rects1) {
                for (r2 in rects2) {
                    if (Rect.intersects(r1, r2)) return true
                }
            }
            return false
        }

        /**
         * 胶囊整体（连同阴影）平滑淡出淡入切换新目标，离屏统一合成（LinkGo 1:1）
         */
        private fun switchCapsuleWithFade(matchedList: List<LinkRegion>) {
            if (!isHudShowing || hudAlpha < 0.1f) {
                activeTargetLinks = matchedList
                updateCapsuleInfo(matchedList)
                animateHud(show = true)
                return
            }

            isHudShowing = true
            hudAnimator?.cancel()
            val currentAlpha = hudAlpha
            var hasSwitchedData = false

            hudAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 110
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    val fraction = anim.animatedFraction
                    if (fraction < 0.35f) {
                        // 前 35% 时间（约 38ms）：整体极速淡出至 0.05f
                        val outF = fraction / 0.35f
                        hudAlpha = currentAlpha + (0.05f - currentAlpha) * outF
                    } else {
                        // 达到切换点瞬间：更新目标数据与尺寸排版
                        if (!hasSwitchedData) {
                            hasSwitchedData = true
                            activeTargetLinks = matchedList
                            updateCapsuleInfo(matchedList)
                        }
                        // 后 65% 时间（约 72ms）：整体敏捷淡入至 1.0f
                        val inF = (fraction - 0.35f) / 0.65f
                        hudAlpha = 0.05f + (1.0f - 0.05f) * inF
                        hudScale = 1.0f
                    }
                    invalidate()
                }
                start()
            }
        }

        private fun animateHud(show: Boolean) {
            isHudShowing = show
            hudAnimator?.cancel()
            val startScale = hudScale
            val targetScale = if (show) 1.0f else 0.75f
            val startAlpha = hudAlpha
            val targetAlpha = if (show) 1.0f else 0.0f
            hudAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (show) 150 else 90
                interpolator = if (show) OvershootInterpolator(1.2f) else DecelerateInterpolator()
                addUpdateListener {
                    val f = it.animatedFraction
                    hudScale = startScale + (targetScale - startScale) * f
                    hudAlpha = startAlpha + (targetAlpha - startAlpha) * f
                    invalidate()
                }
                start()
            }
        }

        /**
         * 胶囊信息预测（LinkGo updateCapsuleInfo 1:1，预测引擎换成 GEVO 规则系统）：
         * 多链接 → 「链接选择」+ 角标；单链接 → 缓存秒弹 → 本地规则预测 → 异步补齐。
         */
        private fun updateCapsuleInfo(matchedList: List<LinkRegion>) {
            if (matchedList.size > 1) {
                // 取消进行中的单链接预测，防止乱序回调覆盖
                predictionJob?.cancel()
                predictionJob = null

                // 多链接聚合形态：图标、「链接选择」、数字角标
                val multiIcon = context.applicationInfo.loadIcon(context.packageManager)
                currentAppIcon = drawableToBitmap(
                    multiIcon,
                    (20 * density).roundToInt(),
                    tintColor = if (isDark) Color.WHITE else lightTextColor
                )

                prefixText = ""
                accentText = "链接选择"
                suffixText = ""
                badgeText = if (matchedList.size > 9) "9+" else matchedList.size.toString()
            } else {
                // 取消前一个可能仍在解析的单链接协程，确保仅最新目标生效
                predictionJob?.cancel()
                val link = matchedList.first()
                badgeText = null

                // 1. 极速内存缓存检查：若曾预测过该 URL，0ms 同步秒弹，消除展示延迟
                val cached = localTargetCache[link.url]
                if (cached != null) {
                    currentAppIcon = cached.first
                    prefixText = cached.second.first
                    accentText = cached.second.second
                    suffixText = cached.second.third
                    invalidate()
                    return
                }

                // 2. 首次未命中缓存：先赋予安全即时默认排版，保证首帧 0 延迟秒弹
                if (accentText.isEmpty()) {
                    prefixText = "打开"
                    accentText = "链接"
                    suffixText = ""
                    invalidate()
                }

                predictionJob = viewScope.launch {
                    try {
                        val sourcePackage = CurrentPackageProvider.currentPackageFlow.value ?: ""
                        val normalized = UrlDetector.normalizeForOpening(link.url) ?: link.url

                        val rules = LinkRuleRepository.loadRules(context)
                        val rule = LinkDispatcher.findRule(normalized, rules, sourcePackage)
                        val targetPackage = rule?.targetPackage?.takeIf { it.isNotEmpty() }

                        var icon: Bitmap?
                        var prefix: String
                        var accent: String
                        var suffix: String

                        if (targetPackage != null) {
                            // 规则命中：显示目标应用名与图标（LinkGo「在 X 中打开」同款）
                            val label = runCatching {
                                val pm = context.packageManager
                                pm.getApplicationLabel(pm.getApplicationInfo(targetPackage, 0)).toString()
                            }.getOrDefault(targetPackage)
                            val drawable = runCatching {
                                IconHelper.getIcon(context, targetPackage)
                            }.getOrNull() ?: context.applicationInfo.loadIcon(context.packageManager)
                            icon = drawableToBitmap(drawable, (20 * density).roundToInt())
                            prefix = "在"
                            accent = label
                            suffix = "中打开"
                        } else {
                            // 无规则命中：显示系统默认解析目标；解析不到则「手动选择」（LinkGo 语义）
                            val resolved = resolveDefaultHandler(normalized)
                            if (resolved != null) {
                                icon = resolved.first
                                prefix = "在"
                                accent = resolved.second
                                suffix = "中打开"
                            } else {
                                icon = neutralIcon()
                                prefix = ""
                                accent = "手动选择"
                                suffix = ""
                            }
                        }

                        currentAppIcon = icon
                        prefixText = prefix
                        accentText = accent
                        suffixText = suffix
                        // 写入内存高速缓存
                        localTargetCache[link.url] = Pair(icon, Triple(prefix, accent, suffix))
                        invalidate()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        Log.w("LinkRadar", "预测失败: ${e.message}")
                    }
                }
            }
        }

        private fun resolveDefaultHandler(url: String): Pair<Bitmap, String>? {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            val resolveInfo = runCatching {
                context.packageManager.resolveActivity(intent, 0)
            }.getOrNull() ?: return null
            val pkg = resolveInfo?.activityInfo?.packageName ?: return null
            if (pkg == "android" || resolveInfo.activityInfo?.applicationInfo == null) return null
            val drawable = runCatching {
                context.packageManager.getApplicationIcon(pkg)
            }.getOrNull() ?: return null
            val label = runCatching {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            return drawableToBitmap(drawable, (20 * density).roundToInt()) to label
        }

        private fun neutralIcon(): Bitmap = drawableToBitmap(
            context.applicationInfo.loadIcon(context.packageManager),
            (20 * density).roundToInt()
        )

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val screenW = width.toFloat()
            val screenH = height.toFloat()
            if (screenW <= 0 || screenH <= 0) return

            // --- 1. 高亮框与角标绘制 (LinkGo drawHighlightedLinks 1:1) ---
            if (hitLinks.isNotEmpty()) {
                highlightBorderPaint.color = themeColor
                drawHighlightedLinks(canvas)
            }

            // --- 2. 悬浮胶囊绘制 (LinkGo 同款：hudAlpha 有值且文案非空才画) ---
            if (hudAlpha > 0.01f && accentText.isNotEmpty()) {
                drawCapsule(canvas, screenW, screenH)
            }
        }

        /**
         * 高亮框与角标绘制：100% 像素级对齐 LinkGo drawHighlightedLinks
         */
        private fun drawHighlightedLinks(canvas: Canvas) {
            if (hitLinks.isEmpty()) return

            val box = hitBoxRect
            if (box != null) {
                // === 绘制宿主大框 ===
                val rectF = RectF(box).apply { inset(-4f * density, -4f * density) }
                val cornerRadius = 12f * density
                tempPath.reset()
                tempPath.addRoundRect(rectF, cornerRadius, cornerRadius, Path.Direction.CW)
                canvas.drawPath(tempPath, highlightBorderPaint)

                // 绘制角标：位于大框左上角
                val isMulti = hitLinks.size > 1
                val badgeVal = if (isMulti) hitLinks.size.toString() else "1"
                drawModernPill(canvas, box, badgeVal, isConflict = isMulti)
            } else {
                // === 绘制精准文字小矩形 ===
                val allRects = mutableListOf<Rect>()
                for (link in hitLinks) {
                    allRects.addAll(link.rects)
                }
                if (allRects.isEmpty()) return

                val processed = processRects(allRects)
                val combinedPath = Path()
                for (r in processed) {
                    val rectF = RectF(r).apply { inset(-6f, -3f) }
                    tempPath.reset()
                    tempPath.addRect(rectF, Path.Direction.CW)
                    combinedPath.op(tempPath, Path.Op.UNION)
                }

                // 仅绘制高亮边缘（圆角霓虹边框）
                canvas.drawPath(combinedPath, highlightBorderPaint)

                // 绘制 Modern Pill 角标
                val firstRect = processed.firstOrNull() ?: return
                val isMulti = hitLinks.size > 1
                val badgeVal = if (isMulti) hitLinks.size.toString() else "1"
                drawModernPill(canvas, firstRect, badgeVal, isConflict = isMulti)
            }
        }

        /** 同构行内合并算法（LinkGo processRects 1:1） */
        private fun processRects(rects: List<Rect>): List<Rect> {
            val processed = mutableListOf<Rect>()
            val sorted = rects.sortedWith(compareBy({ it.top }, { it.left }))
            for (rect in sorted) {
                if (processed.isEmpty()) { processed.add(Rect(rect)); continue }
                val last = processed.last()
                val lastCenterY = (last.top + last.bottom) / 2f
                val currentCenterY = (rect.top + rect.bottom) / 2f
                val isSameLine = Math.abs(lastCenterY - currentCenterY) < (last.height() * 0.4f)
                if (isSameLine && Math.abs(rect.left - last.right) < 50) {
                    last.right = Math.max(last.right, rect.right)
                    last.top = Math.min(last.top, rect.top)
                    last.bottom = Math.max(last.bottom, rect.bottom)
                } else {
                    processed.add(Rect(rect))
                }
            }
            return processed
        }

        /** 绘制 Modern Pill 角标 (100% 对齐 LinkGo drawModernPill) */
        private fun drawModernPill(canvas: Canvas, rect: Rect, text: String, isConflict: Boolean) {
            pillBgPaint.color = themeColor
            pillBgPaint.alpha = 255
            pillStrokePaint.color = surfaceColor
            pillStrokePaint.alpha = 255
            pillTextPaint.color = onPrimaryColor

            val textSize = if (isConflict) 24f else 28f
            val padding = if (isConflict) 16f else 18f
            pillTextPaint.textSize = textSize
            val textWidth = pillTextPaint.measureText(text)

            val iconSize = if (isConflict) 24f else 0f
            val verticalMargin = if (isConflict) 6f else 0f
            val fm = pillTextPaint.fontMetrics
            val textHeight = fm.descent - fm.ascent

            val totalWidth = if (isConflict) {
                Math.max(textWidth, iconSize) + (padding * 2)
            } else {
                textWidth + (padding * 2)
            }

            val height = if (isConflict) {
                padding + iconSize + verticalMargin + textHeight + padding - 8f
            } else {
                44f
            }

            val radius = if (isConflict) totalWidth / 2f else height / 2f
            val pillTop = Math.max(rect.top.toFloat(), 10f)
            val targetX = rect.left.toFloat() - totalWidth - 6f
            val pillLeft = if (targetX < 10f) rect.left.toFloat() + 6f else targetX
            val pillRect = RectF(pillLeft, pillTop, pillLeft + totalWidth, pillTop + height)

            canvas.drawRoundRect(pillRect, radius, radius, pillBgPaint)
            canvas.drawRoundRect(pillRect, radius, radius, pillStrokePaint)

            if (isConflict) {
                val centerX = pillRect.centerX()
                var currentY = pillRect.top + padding
                val iconPaint = Paint(pillStrokePaint).apply {
                    strokeWidth = 3f
                    color = onPrimaryColor
                    style = Paint.Style.STROKE
                }
                val iconCenterY = currentY + iconSize / 2f
                val leftRing = RectF(centerX - 10f, iconCenterY - 5f, centerX + 2f, iconCenterY + 5f)
                val rightRing = RectF(centerX - 2f, iconCenterY - 5f, centerX + 10f, iconCenterY + 5f)
                canvas.drawArc(leftRing, 90f, 270f, false, iconPaint)
                canvas.drawArc(rightRing, -90f, 270f, false, iconPaint)
                canvas.drawLine(centerX - 2f, iconCenterY, centerX + 2f, iconCenterY, iconPaint)

                currentY += iconSize + verticalMargin
                val textCenterY = currentY - fm.ascent
                canvas.drawText(text, centerX, textCenterY, pillTextPaint)
            } else {
                val textCenterY = pillRect.centerY() - (fm.ascent + fm.descent) / 2
                canvas.drawText(text, pillRect.centerX(), textCenterY, pillTextPaint)
            }
        }

        /**
         * 悬浮胶囊绘制（LinkGo drawCapsule 材质 1:1），位置改为**紧贴命中链接**：
         * 默认在链接包围盒上方居中、留 8dp 间距；上方放不下移到下方；左右/上下
         * 越界钳制在屏幕内；无锚点时回退屏幕 18% 黄金位。
         */
        private fun drawCapsule(canvas: Canvas, screenW: Float, screenH: Float) {
            val textColor = if (isDark) darkTextColor else lightTextColor
            val accentColor = themeColor

            capsuleTextPaint.color = textColor
            capsuleAccentPaint.color = accentColor

            // 计算文本排版尺寸
            val prefixW = if (prefixText.isNotEmpty()) capsuleTextPaint.measureText(prefixText) else 0f
            val accentW = capsuleAccentPaint.measureText(accentText)
            val suffixW = if (suffixText.isNotEmpty()) capsuleTextPaint.measureText(suffixText) else 0f
            var totalContentW = prefixW + accentW + suffixW

            // 多链接角标尺寸计算 (采用高精度 Micro-Pill Tag 规范)
            val badge = badgeText
            var badgeW = 0f
            val badgeH = 18f * density
            if (badge != null) {
                val badgePadH = 6f * density
                val textW = capsuleBadgeTextPaint.measureText(badge)
                badgeW = Math.max(badgeH, textW + badgePadH * 2f)
                totalContentW += (badgeW + 6 * density) // 6dp margin
            }

            val padH = 14f * density
            val padV = 10f * density
            val iconSize = 20f * density
            val iconGap = 8f * density

            val capsuleW = iconSize + iconGap + totalContentW + padH * 2f
            val capsuleH = iconSize + padV * 2f

            // 屏幕 18% 高度黄金舒适区（LinkGo 原版固定位置，先保证显示稳定）
            val capsuleCenterX = screenW / 2f
            val capsuleCenterY = screenH * 0.18f

            canvas.save()
            canvas.translate(capsuleCenterX, capsuleCenterY)
            canvas.scale(hudScale, hudScale)

            val rectLeft = -capsuleW / 2f
            val rectTop = -capsuleH / 2f
            val rectRight = capsuleW / 2f
            val rectBottom = capsuleH / 2f
            val capsuleRadius = capsuleH / 2f

            // 整个胶囊（包含底层阴影、实体面板、图标、文字与角标）整体离屏合批渲染
            val shadowPad = 24f * density
            val overallAlpha = (hudAlpha * 255).roundToInt().coerceIn(0, 255)
            val layerSaveCount = canvas.saveLayerAlpha(
                rectLeft - shadowPad,
                rectTop - shadowPad,
                rectRight + shadowPad,
                rectBottom + shadowPad + 6f * density,
                overallAlpha
            )

            // 1. 底层柔和弥散阴影：内缩 2.5dp 消除边缘黑边
            val shadowInset = 2.5f * density
            val shadowRect = RectF(rectLeft + shadowInset, rectTop + shadowInset, rectRight - shadowInset, rectBottom - shadowInset)
            val shadowRadius = (capsuleH - shadowInset * 2f) / 2f
            val shadowPath = Path().apply {
                addRoundRect(shadowRect, shadowRadius, shadowRadius, Path.Direction.CW)
            }
            capsuleShadowPaint.alpha = 255
            canvas.drawPath(shadowPath, capsuleShadowPaint)

            // 2. 实体胶囊平滑背景面板 (100% 实心不透明，彻底遮挡住正下方的阴影)
            val capsulePath = Path().apply {
                addRoundRect(RectF(rectLeft, rectTop, rectRight, rectBottom), capsuleRadius, capsuleRadius, Path.Direction.CW)
            }
            capsuleBgPaint.alpha = 255
            canvas.drawPath(capsulePath, capsuleBgPaint)

            // 3. 深色模式下的精致微轮廓描边
            if (isDark) {
                capsuleBorderPaint.alpha = 0x28
                canvas.drawPath(capsulePath, capsuleBorderPaint)
            }

            // 4. 绘制 20dp 目标图标
            val iconLeft = rectLeft + padH
            val iconTop = rectTop + padV
            currentAppIcon?.let { iconBmp ->
                val src = Rect(0, 0, iconBmp.width, iconBmp.height)
                val dst = RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
                val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 255 }
                canvas.drawBitmap(iconBmp, src, dst, iconPaint)
            }

            // 5. 绘制文本排版
            var currentX = iconLeft + iconSize + iconGap
            val fontMetrics = capsuleTextPaint.fontMetrics
            val textBaselineY = (rectTop + padV) + iconSize / 2f - (fontMetrics.ascent + fontMetrics.descent) / 2f

            if (prefixText.isNotEmpty()) {
                capsuleTextPaint.alpha = 255
                canvas.drawText(prefixText, currentX, textBaselineY, capsuleTextPaint)
                currentX += prefixW
            }

            capsuleAccentPaint.alpha = 255
            canvas.drawText(accentText, currentX, textBaselineY, capsuleAccentPaint)
            currentX += accentW

            if (suffixText.isNotEmpty()) {
                capsuleTextPaint.alpha = 255
                canvas.drawText(suffixText, currentX, textBaselineY, capsuleTextPaint)
            }

            // 6. 绘制多链接角标 (Material 3 次级 Tag 规范)
            if (badge != null) {
                currentX += 6 * density
                val badgeTop = (rectTop + padV) + (iconSize - badgeH) / 2f
                val badgeRect = RectF(currentX, badgeTop, currentX + badgeW, badgeTop + badgeH)

                val badgeBgColor = if (isDark) 0xFF35383B.toInt() else 0xFFF1F3F4.toInt()
                val badgeBorderColor = if (isDark) 0xFF4D5155.toInt() else 0xFFDADCE0.toInt()
                val badgeTextColor = if (isDark) 0xFFFFFFFF.toInt() else 0xFF1F1F1F.toInt()

                // A. 实体背景底板
                capsuleBadgeBgPaint.color = badgeBgColor
                capsuleBadgeBgPaint.alpha = 255
                canvas.drawRoundRect(badgeRect, badgeH / 2f, badgeH / 2f, capsuleBadgeBgPaint)

                // B. 微轮廓描边
                capsuleBadgeBorderPaint.color = badgeBorderColor
                capsuleBadgeBorderPaint.alpha = 255
                canvas.drawRoundRect(badgeRect, badgeH / 2f, badgeH / 2f, capsuleBadgeBorderPaint)

                // C. 加粗高对比度数字
                capsuleBadgeTextPaint.color = badgeTextColor
                capsuleBadgeTextPaint.alpha = 255
                val bFm = capsuleBadgeTextPaint.fontMetrics
                val bTextBaseline = badgeTop + badgeH / 2f - (bFm.ascent + bFm.descent) / 2f
                canvas.drawText(badge, badgeRect.centerX(), bTextBaseline, capsuleBadgeTextPaint)
            }

            canvas.restoreToCount(layerSaveCount)
            canvas.restore()
        }

        /** （LinkGo drawableToBitmap 1:1） */
        private fun drawableToBitmap(drawable: Drawable, size: Int, tintColor: Int? = null): Bitmap {
            val mutated = drawable.mutate()
            if (tintColor != null) {
                mutated.setTint(tintColor)
            } else if (mutated is BitmapDrawable && mutated.bitmap != null) {
                return Bitmap.createScaledBitmap(mutated.bitmap, size, size, true)
            }
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            mutated.setBounds(0, 0, size, size)
            mutated.draw(canvas)
            return bitmap
        }

        /** 空闲清理：View 销毁时取消协程 */
        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            animate().cancel()
            dismissGraceRunnable?.let { removeCallbacks(it) }
            dismissGraceRunnable = null
            predictionJob?.cancel()
            hudAnimator?.cancel()
        }
    }
}
