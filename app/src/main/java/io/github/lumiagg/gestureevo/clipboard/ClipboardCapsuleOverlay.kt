// 来源：LinkGo（github.com/moting1117/LinkGo，作者 mting1117/墨汀，All Rights Reserved），
// 经作者授权移植至本 GPL-3.0-only 项目；按本仓库架构适配（窗口类型、设置来源、键盘避让等）。

package io.github.lumiagg.gestureevo.clipboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue
import android.view.*
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 剪贴板后台触发的悬浮胶囊（升级版）。
 *
 * 1. 材质与轮廓：半透明毛玻璃背景 + 1.5dp 微轮廓轨道 + 环绕整个胶囊外轮廓的动态倒计时进度条；
 * 2. 交互手势：支持向贴边外侧快速甩出（Fling to Dismiss）手势，极速关闭；
 * 3. 触控反馈：按下（Action Down）触发 0.96x 物理下陷与微触感震动反馈；
 * 4. 贴边自适应与持久化。
 */
class ClipboardCapsuleOverlay(private val context: Context) {

    companion object {
        private const val TAG = "ClipboardCapsule"
        private const val ENTER_ANIM_MS = 260L
        private const val EXIT_ANIM_MS = 200L
        private const val SNAP_ANIM_MS = 160L
        private const val EDGE_MARGIN_DP = 6
        private const val ELEVATION_DP = 14

        private val DARK_BG = 0xF2202124.toInt()
        private val LIGHT_BG = 0xF8FFFFFF.toInt()
        private val DARK_TEXT = 0xFFE8EAED.toInt()
        private val LIGHT_TEXT = 0xFF202124.toInt()
        private const val GOOGLE_BLUE_LIGHT = 0xFF1B73E8.toInt()
        private const val GOOGLE_BLUE_DARK = 0xFF8AB4F8.toInt()
        private const val LONG_PRESS_TIMEOUT_MS = 320L
        private const val STAGGER_DELAY_MS = 45L
    }

    private val appContext = context.applicationContext
    // TYPE_ACCESSIBILITY_OVERLAY is attached with the accessibility service's window token; a
    // WindowManager obtained from the application context cannot add that type.
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val touchSlop = ViewConfiguration.get(appContext).scaledTouchSlop

    private var capsuleContainer: ViewGroup? = null
    private var currentArrayLayout: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var pendingShowJob: Job? = null
    private val imeListener = { isShown: Boolean, imeHeight: Int ->
        handleKeyboardStateChanged(isShown, imeHeight)
    }
    private var imeAvoidAnimator: ValueAnimator? = null
    private var targetParamsY: Int? = null
    private var velocityTracker: VelocityTracker? = null

    private var currentEdge: String = "right"
    private var autoDismissMs: Long = 5000L
    private var useDynamicAccent: Boolean = false
    private var dismissOnOutside: Boolean = true

    private var screenW = 0
    private var screenH = 0
    private var capsuleW = 0
    private var capsuleH = 0

    private var downRawX = 0f
    private var downRawY = 0f
    private var downParamsX = 0
    private var downParamsY = 0
    private var isLongPressActive = false
    private var longPressView: View? = null

    private var baseParamsY = 0

    private val dismissRunnable = Runnable { dismissInternal() }
    private val longPressRunnable = Runnable {
        isLongPressActive = true
        longPressView?.let { v ->
            val inner = (v as? ViewGroup)?.getChildAt(0) ?: v
            // 长按激活：内部胶囊浮起 1.05x 并触发明确的震动提示
            inner.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150).start()
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /**
     * 极简高级悬浮胶囊容器 (集成真实物理弥散阴影与无边框纯净设计)
     */
    private class CapsuleProgressContainer(context: Context) : FrameLayout(context) {
        var isNight: Boolean = false
            set(value) {
                field = value
                updateThemePaints(value)
                invalidate()
            }

        private val shadowPaddingPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            10f,
            context.resources.displayMetrics
        )
        private val strokeWidthPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            1.0f,
            context.resources.displayMetrics
        )

        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
        }

        private val roundPath = Path()
        private val capsuleRect = RectF()

        init {
            setWillNotDraw(false)
            clipChildren = false
            clipToPadding = false
            val p = shadowPaddingPx.toInt()
            setPadding(p, p, p, p)
            updateThemePaints(false)
        }

        private fun updateThemePaints(night: Boolean) {
            val shadowColor = if (night) Color.argb(120, 0, 0, 0) else Color.argb(35, 0, 0, 0)
            val shadowRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics)
            val shadowDy = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2.5f, resources.displayMetrics)
            
            // 实体颜色与 shadowColor 同步为半透明淡色，避免在路径抗锯齿边缘产生纯黑重叠锯齿
            shadowPaint.color = shadowColor
            shadowPaint.setShadowLayer(shadowRadius, 0f, shadowDy, shadowColor)

            bgPaint.color = if (night) DARK_BG else LIGHT_BG
            
            // 深色模式下的微轮廓描边
            borderPaint.color = Color.argb(0x28, 255, 255, 255)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            roundPath.reset()
            val left = paddingLeft.toFloat()
            val top = paddingTop.toFloat()
            val right = (w - paddingRight).toFloat()
            val bottom = (h - paddingBottom).toFloat()
            capsuleRect.set(left, top, right, bottom)
            val radius = (bottom - top) / 2f
            roundPath.addRoundRect(capsuleRect, radius, radius, Path.Direction.CW)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return

            // 1. 绘制底层柔和弥散阴影
            canvas.drawPath(roundPath, shadowPaint)
            // 2. 绘制胶囊平滑背景面板
            canvas.drawPath(roundPath, bgPaint)
            // 3. 仅在深色模式下绘制精致微轮廓描边
            if (isNight) {
                canvas.drawPath(roundPath, borderPaint)
            }
        }
    }

    /**
     * 胶囊单项数据模型
     */
    data class CapsuleItemData(
        val icon: Drawable,
        val label: String,
        val onTap: (isReverse: Boolean) -> Unit
    )

    /**
     * 阵列显示多个胶囊（方案 B：纵向排布）。
     */
    fun showArray(items: List<CapsuleItemData>, config: ClipboardLinkConfig) {
        if (items.isEmpty()) return
        pendingShowJob?.cancel()

        // 配置由调用方（watcher）以内存快照传入：0ms 同步挂载首帧，无协程往返。
        autoDismissMs = config.autoDismissMs
        useDynamicAccent = true
        dismissOnOutside = true
        showArrayInternal(items, config.edge, config.yRatio)
    }

    fun dismiss() {
        handler.post { dismissInternal() }
    }

    fun destroy() {
        pendingShowJob?.cancel()
        scope.cancel()
        removeCurrentNow()
    }

    private fun showArrayInternal(
        items: List<CapsuleItemData>,
        edge: String,
        yRatio: Float
    ) {
        removeCurrentNow()

        val size = screenSize()
        screenW = size.first
        screenH = size.second

        val night = isNightMode()
        val accent = themeAccentColor(night, useDynamicAccent)
        val bg = if (night) DARK_BG else LIGHT_BG
        val textColor = if (night) DARK_TEXT else LIGHT_TEXT

        // 垂直阵列容器
        val arrayLayout = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (edge == "left") Gravity.START else Gravity.END
        }

        items.forEachIndexed { index, item ->
            val innerCapsule = buildCapsule(
                icon = item.icon,
                label = item.label,
                accent = accent,
                textColor = textColor,
                bg = bg,
                isNight = night
            )
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (index > 0) {
                    topMargin = dp(6)
                }
            }
            arrayLayout.addView(innerCapsule, lp)
        }
        
        // 外层安全容器（不均等精准 Padding：左8上6右8下26，结合 Hit-Testing 实现边缘完全穿透下层，完美容纳阴影与下滑动效）
        val windowRoot = FrameLayout(appContext).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(dp(8), dp(6), dp(8), dp(26))
            addView(arrayLayout, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                if (edge == "left") Gravity.START else Gravity.END
            ))
        }

        currentArrayLayout = arrayLayout
        capsuleContainer = windowRoot
        currentEdge = edge

        // 对齐 OctoClip：标准悬浮窗 flags，无需承担输入法分发与局部形变
        var windowFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (dismissOnOutside) {
            windowFlags = windowFlags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            windowFlags,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        layoutParams = params

        windowRoot.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        capsuleW = windowRoot.measuredWidth
        capsuleH = windowRoot.measuredHeight
        if (capsuleW > 0 && capsuleH > 0) {
            params.width = capsuleW
            params.height = capsuleH
        } else {
            // A themed context without the app's Material styles can measure 0; letting the
            // window wrap itself still shows something instead of an invisible 0x0 view.
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            diagnose("measure produced ${capsuleW}x${capsuleH}, wrapping instead")
        }

        baseParamsY = clampY((yRatio * screenH).toInt() - capsuleH / 2)
        params.x = snappedX(edge)

        // 若当前输入法已经展开，初始挂载即直接就位在避让位置，杜绝进场横纵动画叠加导致的斜向飞行
        val curImeHeight = ImeInsetsManager.currentImeHeight
        val curScreenH = if (screenH > 0) screenH else screenSize().second
        val capsuleBottom = baseParamsY + capsuleH
        val originalBottomMargin = (curScreenH - capsuleBottom).coerceAtLeast(dp(12))
        val imeTop = curScreenH - curImeHeight

        val initialY = if (ImeInsetsManager.isKeyboardShown && curImeHeight > 0 && capsuleBottom + originalBottomMargin > imeTop) {
            clampY(baseParamsY - curImeHeight)
        } else {
            baseParamsY
        }

        params.y = initialY
        targetParamsY = initialY
        Log.i(TAG, "[CAPSULE-SHOW] 展示胶囊: baseParamsY=$baseParamsY, initialY=$initialY, isImeShown=${ImeInsetsManager.isKeyboardShown}, imeHeight=$curImeHeight, capsuleW=$capsuleW, capsuleH=$capsuleH, screenH=$screenH")

        windowRoot.setOnTouchListener { v, event -> handleArrayTouch(v, event, items, arrayLayout) }

        try {
            windowManager.addView(windowRoot, params)

            // 对齐 OctoClip：单一事实源监听，杜绝视图过渡帧的频繁打断
            ImeInsetsManager.addListener(imeListener)

            Log.i(TAG, "[CAPSULE-WINDOW-ADDED] 悬浮窗已成功挂载到系统 WindowManager: w=$capsuleW, h=$capsuleH, x=${params.x}, y=${params.y}")
            diagnose(
                "addView ok w=$capsuleW h=$capsuleH x=${params.x} y=${params.y} " +
                    "screen=${screenW}x$screenH links=${items.size} edge=$edge"
            )
        } catch (e: Exception) {
            Log.e(TAG, "[CAPSULE-WINDOW-ERROR] windowManager.addView 挂载失败: ${e.message}", e)
            diagnose("addView failed: ${e.javaClass.simpleName}: ${e.message}")
            stopImeTracking()
            capsuleContainer = null
            layoutParams = null
            return
        }

        // 满帧硬件加速阶梯式错峰进场平移动画
        val dir = if (edge == "left") -1 else 1
        val childCount = arrayLayout.childCount
        for (i in 0 until childCount) {
            val child = arrayLayout.getChildAt(i)
            child.translationX = dir * (capsuleW + dp(24)).toFloat()
            child.alpha = 0f
            child.animate()
                .translationX(0f)
                .alpha(1f)
                .setStartDelay(i * STAGGER_DELAY_MS)
                .setDuration(ENTER_ANIM_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        // 安排自动消失
        scheduleDismiss()
    }

    private fun scheduleDismiss() {
        handler.removeCallbacks(dismissRunnable)
        // 0 = 不自动消失：胶囊要一直停留到用户操作。
        if (autoDismissMs > 0L) handler.postDelayed(dismissRunnable, autoDismissMs)
    }

    private fun buildCapsule(
        icon: Drawable,
        label: String,
        accent: Int,
        textColor: Int,
        bg: Int,
        isNight: Boolean
    ): CapsuleProgressContainer {
        val container = CapsuleProgressContainer(appContext).apply {
            this.isNight = isNight
        }

        val row = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        val iconView = ImageView(appContext).apply {
            val adapted = adaptIconForTheme(icon, isNight, textColor)
            setImageDrawable(adapted)
            val s = dp(20)
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
        row.addView(iconView)

        // 文本定制：无目标应用时直接展示（点击进系统选择器）；否则展示 "在 xx 中打开"
        val display = when {
            label == "手动选择" -> {
                SpannableStringBuilder().append(coloredLabel(label, accent))
            }
            else -> {
                SpannableStringBuilder()
                    .append("在")
                    .append(coloredLabel(label, accent))
                    .append("中打开")
            }
        }
        val textView = TextView(appContext).apply {
            text = display
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(textColor)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            maxWidth = dp(200)
            ellipsize = TextUtils.TruncateAt.END
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(8)
            layoutParams = lp
        }
        row.addView(textView)

        container.addView(
            row,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        return container
    }

    private fun adaptIconForTheme(drawable: Drawable, isNight: Boolean, textColor: Int): Drawable {
        val mutated = drawable.mutate()
        // 针对单色矢量图（如 VectorDrawable）进行深浅模式染色，彩色 App 图标（如 BitmapDrawable/AdaptiveIcon）保持原彩
        if (drawable is android.graphics.drawable.VectorDrawable) {
            mutated.setTint(if (isNight) Color.WHITE else textColor)
        }
        return mutated
    }

    private fun coloredLabel(label: String, accent: Int): CharSequence {
        val span = SpannableString(label)
        span.setSpan(ForegroundColorSpan(accent), 0, span.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return span
    }

    private var activeTouchedIndex: Int = 0

    private fun handleArrayTouch(
        v: View,
        event: MotionEvent,
        items: List<CapsuleItemData>,
        arrayLayout: LinearLayout
    ): Boolean {
        if (event.action == MotionEvent.ACTION_OUTSIDE) {
            // 用户点击了胶囊窗口外部的屏幕区域：立即平滑滑出退场
            dismissInternal()
            return false
        }

        val params = layoutParams ?: return false

        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 1. 精确 Hit-Testing：检查触控点是否落在实心圆角胶囊内部
                val hitIndex = findTouchedChildIndex(event.x, event.y, arrayLayout)
                if (hitIndex < 0) {
                    // 点在胶囊外部的留白/阴影区域
                    velocityTracker?.recycle()
                    velocityTracker = null
                    if (dismissOnOutside) {
                        // 开启外部点击关闭时，点击窗口内留白区域也主动平滑退场
                        dismissInternal()
                    }
                    return false
                }
                activeTouchedIndex = hitIndex

                v.animate().cancel()
                arrayLayout.animate().cancel()
                arrayLayout.alpha = 1f
                downRawX = event.rawX
                downRawY = event.rawY
                downParamsX = params.x
                downParamsY = params.y
                isLongPressActive = false
                longPressView = v

                val touchedChild = arrayLayout.getChildAt(activeTouchedIndex)
                touchedChild?.animate()?.scaleX(0.96f)?.scaleY(0.96f)?.setDuration(100)?.start()

                // 暂停自动消失，启动长按 320ms 检测
                handler.removeCallbacks(dismissRunnable)
                handler.removeCallbacks(longPressRunnable)
                handler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!isLongPressActive && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    // 用户在未长按时发生了明显移动，取消长按判定（视为手势操作）
                    handler.removeCallbacks(longPressRunnable)
                }

                if (isLongPressActive) {
                    // 仅在长按激活后才允许跟随手指拖动位置！
                    params.x = (downParamsX + dx.toInt()).coerceIn(-dp(16), screenW - capsuleW + dp(16))
                    params.y = clampY(downParamsY + dy.toInt())
                    try {
                        windowManager.updateViewLayout(v, params)
                    } catch (_: Exception) {
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                longPressView = null
                val touchedChild = arrayLayout.getChildAt(activeTouchedIndex)
                touchedChild?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(120)?.start()

                if (isLongPressActive) {
                    // 长按拖动结束：吸附到最近侧边并持久化位置
                    isLongPressActive = false
                    snapToEdge(v)
                    scheduleDismiss()
                } else {
                    velocityTracker?.computeCurrentVelocity(1000)
                    val xVel = velocityTracker?.xVelocity ?: 0f
                    val yVel = velocityTracker?.yVelocity ?: 0f
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY

                    val absDx = abs(dx)
                    val absDy = abs(dy)
                    val threshold = dp(24)

                    // 1. 水平滑动判定（左右滑动均可关闭）：水平位移大于垂直位移，且位移或速度达到阈值
                    val isSwipeHorizontal = (absDx > absDy) && (absDx > threshold || abs(xVel) > 600f)

                    // 2. 垂直下滑判定（反转小窗模式打开）：垂直位移大于水平位移，向下划且位移或速度达到阈值
                    val isSwipeDown = (dy > absDx) && (dy > threshold || yVel > 600f)

                    val targetItem = items.getOrNull(activeTouchedIndex) ?: items.firstOrNull()

                    if (isSwipeHorizontal) {
                        // 水平左右均可划走关闭：向对应方向滑出
                        flingDismiss(v, dx > 0)
                    } else if (isSwipeDown) {
                        // 下滑：触发确认微震，先播放专属子胶囊下移动画，动画结束彻底卸载 Window 后再触发跳转（彻底杜绝 frozen-foreground 冲突）
                        v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        slideDownDismiss(v, arrayLayout, activeTouchedIndex) {
                            targetItem?.onTap?.invoke(true)
                        }
                    } else {
                        // 原地轻点：播放专属子胶囊 100ms 丝滑收缩淡出动画，动画结束彻底卸载 Window 后再触发跳转
                        tapDismiss(v, arrayLayout, activeTouchedIndex) {
                            targetItem?.onTap?.invoke(false)
                        }
                    }
                }

                velocityTracker?.recycle()
                velocityTracker = null
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                longPressView = null
                isLongPressActive = false
                val touchedChild = arrayLayout.getChildAt(activeTouchedIndex)
                touchedChild?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(120)?.start()
                scheduleDismiss()
                velocityTracker?.recycle()
                velocityTracker = null
                return true
            }
        }
        return false
    }

    /**
     * 精确 Hit-Testing：基于子 View 实际外形进行标准的药丸（Pill）几何检测，确保全尺寸 100% 灵敏命中
     */
    private fun findTouchedChildIndex(localX: Float, localY: Float, arrayLayout: LinearLayout): Int {
        val count = arrayLayout.childCount
        if (count == 0) return -1
        val leftOffset = arrayLayout.left
        val topOffset = arrayLayout.top
        val adjX = localX - leftOffset
        val adjY = localY - topOffset

        for (i in 0 until count) {
            val child = arrayLayout.getChildAt(i)
            val solidLeft = child.left.toFloat()
            val solidTop = child.top.toFloat()
            val solidRight = child.right.toFloat()
            val solidBottom = child.bottom.toFloat()
            if (solidRight <= solidLeft || solidBottom <= solidTop) continue

            // 1. 快速矩形排斥
            if (adjX < solidLeft || adjX > solidRight || adjY < solidTop || adjY > solidBottom) {
                continue
            }

            // 2. 精确药丸圆角几何检测
            val h = solidBottom - solidTop
            val r = h / 2f
            val cx1 = solidLeft + r
            val cx2 = solidRight - r
            val cy = solidTop + r

            if (adjX < cx1) {
                // 左侧半圆弧区域
                val dx = adjX - cx1
                val dy = adjY - cy
                if (dx * dx + dy * dy <= r * r) {
                    return i
                }
            } else if (adjX > cx2) {
                // 右侧半圆弧区域
                val dx = adjX - cx2
                val dy = adjY - cy
                if (dx * dx + dy * dy <= r * r) {
                    return i
                }
            } else {
                // 中间主体矩形区域
                return i
            }
        }
        return -1
    }

    /**
     * 单击极速离场动效：仅对被轻点的单个目标胶囊播放收缩淡出动画，其余项原地快速淡出
     * 保持 Window 在发起跳转期间挂载，赋予后台启动 Activity 豁免权
     */
    private fun tapDismiss(v: View, arrayLayout: LinearLayout, targetIndex: Int, onEnd: () -> Unit) {
        handler.removeCallbacks(dismissRunnable)
        val childCount = arrayLayout.childCount
        val targetChild = arrayLayout.getChildAt(targetIndex) ?: (v as? ViewGroup)?.getChildAt(0) ?: v

        if (childCount > 1) {
            for (i in 0 until childCount) {
                val other = arrayLayout.getChildAt(i)
                if (other != targetChild) {
                    other.animate().cancel()
                    other.animate().alpha(0f).setDuration(80).start()
                }
            }
        }

        targetChild.animate().cancel()
        targetChild.animate()
            .scaleX(0.92f)
            .scaleY(0.92f)
            .alpha(0f)
            .setDuration(100)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                // 1. 先触发跳转（此时 Window 依然挂载在 WindowManager 中，为进程提供合法的前台交互豁免）
                onEnd()
                // 2. 延迟 120ms 安全移除 Window，确保 startActivity 穿透完成且动画自然无撕裂
                handler.postDelayed({ removeCurrentNow() }, 120)
            }
            .start()
    }

    /**
     * 垂直下滑动效：仅被手指拉动的单个目标胶囊向下位移并放大淡出，其余项原地快速淡出
     */
    private fun slideDownDismiss(v: View, arrayLayout: LinearLayout, targetIndex: Int, onEnd: () -> Unit) {
        handler.removeCallbacks(dismissRunnable)
        val childCount = arrayLayout.childCount
        val targetChild = arrayLayout.getChildAt(targetIndex) ?: (v as? ViewGroup)?.getChildAt(0) ?: v

        if (childCount > 1) {
            for (i in 0 until childCount) {
                val other = arrayLayout.getChildAt(i)
                if (other != targetChild) {
                    other.animate().cancel()
                    other.animate().alpha(0f).setDuration(80).start()
                }
            }
        }

        targetChild.animate().cancel()
        targetChild.animate()
            .translationY(dp(22).toFloat())
            .scaleX(1.10f)
            .scaleY(1.10f)
            .alpha(0f)
            .setDuration(140)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                // 1. 先触发跳转
                onEnd()
                // 2. 延迟 120ms 安全移除 Window
                handler.postDelayed({ removeCurrentNow() }, 120)
            }
            .start()
    }

    private fun flingDismiss(v: View, toRight: Boolean) {
        handler.removeCallbacks(dismissRunnable)
        val inner = (v as? ViewGroup)?.getChildAt(0)
        val targetX = if (toRight) (capsuleW + dp(40)).toFloat() else -(capsuleW + dp(40)).toFloat()

        if (inner is LinearLayout && inner.childCount > 1) {
            val childCount = inner.childCount
            for (i in 0 until childCount) {
                val child = inner.getChildAt(i)
                child.animate().cancel()
                val anim = child.animate()
                    .translationX(targetX)
                    .alpha(0f)
                    .setStartDelay(i * 25L)
                    .setDuration(160)
                    .setInterpolator(AccelerateInterpolator())
                if (i == childCount - 1) {
                    anim.withEndAction { removeCurrentNow() }
                }
                anim.start()
            }
        } else {
            val target = inner ?: v
            target.animate().cancel()
            target.animate()
                .translationX(targetX)
                .alpha(0f)
                .setDuration(180)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { removeCurrentNow() }
                .start()
        }
    }

    /**
     * 拖动释放：按胶囊中心所在半屏判边，吸附到对应边缘，并持久化。
     */
    private fun snapToEdge(v: View) {
        val params = layoutParams ?: return
        if (screenW <= 0 || capsuleW <= 0) return
        val centerX = params.x + capsuleW / 2
        val newEdge = if (centerX < screenW / 2) "left" else "right"
        currentEdge = newEdge
        val targetX = snappedX(newEdge)
        val dx = (targetX - params.x).toFloat()
        val inner = (v as? ViewGroup)?.getChildAt(0) ?: v

        inner.animate().cancel()
        if (dx != 0f) {
            inner.animate()
                .translationX(dx)
                .setDuration(SNAP_ANIM_MS)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    runCatching {
                        params.x = targetX
                        windowManager.updateViewLayout(v, params)
                        inner.translationX = 0f
                    }
                }
                .start()
        }

        val yRatio = ((params.y + capsuleH / 2).toFloat() / screenH).coerceIn(0f, 1f)
        baseParamsY = params.y
        persistAnchor(newEdge, yRatio)

        handleKeyboardStateChanged(ImeInsetsManager.isKeyboardShown, ImeInsetsManager.currentImeHeight)
    }

    private fun persistAnchor(edge: String, yRatio: Float) {
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    appContext.settingsStore.saveKey(ClipboardLinkConfig.KEY_CAPSULE_EDGE, edge)
                    appContext.settingsStore.saveKey(ClipboardLinkConfig.KEY_CAPSULE_Y_RATIO, yRatio)
                } catch (e: Exception) {
                    Log.w(TAG, "persistAnchor failed", e)
                }
            }
        }
    }

    private fun snappedX(edge: String): Int {
        val margin = dp(EDGE_MARGIN_DP)
        val safetyPadding = dp(16)
        return if (edge == "left") {
            margin - safetyPadding
        } else {
            (screenW - capsuleW - margin + safetyPadding)
        }
    }

    private fun stopImeTracking() {
        ImeInsetsManager.removeListener(imeListener)
        imeAvoidAnimator?.cancel()
        imeAvoidAnimator = null
        targetParamsY = null
    }

    private fun dismissInternal() {
        val v = capsuleContainer ?: return
        stopImeTracking()
        currentArrayLayout = null
        handler.removeCallbacks(dismissRunnable)
        val inner = (v as? ViewGroup)?.getChildAt(0)
        val dir = if (currentEdge == "left") -1 else 1

        // 立即释放引用，允许新胶囊在退场动画期间正常挂载
        capsuleContainer = null
        layoutParams = null

        val safeRemove = {
            // 仅移除自己对应的旧视图，不会误伤新挂载的胶囊
            v.animate().cancel()
            runCatching { windowManager.removeViewImmediate(v) }
        }

        if (inner is LinearLayout && inner.childCount > 1) {
            val childCount = inner.childCount
            for (i in 0 until childCount) {
                val child = inner.getChildAt(i)
                child.animate().cancel()
                val anim = child.animate()
                    .translationX(dir * (capsuleW + dp(24)).toFloat())
                    .alpha(0f)
                    .setStartDelay(i * STAGGER_DELAY_MS)
                    .setDuration(EXIT_ANIM_MS)
                    .setInterpolator(AccelerateInterpolator())
                if (i == childCount - 1) {
                    anim.withEndAction { safeRemove() }
                }
                anim.start()
            }
        } else {
            val target = inner ?: v
            target.animate().cancel()
            target.animate()
                .translationX(dir * (capsuleW + dp(24)).toFloat())
                .alpha(0f)
                .setDuration(EXIT_ANIM_MS)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { safeRemove() }
                .start()
        }
    }

    private fun removeCurrentNow() {
        stopImeTracking()
        currentArrayLayout?.animate()?.cancel()
        currentArrayLayout = null
        handler.removeCallbacks(dismissRunnable)
        handler.removeCallbacks(longPressRunnable)
        longPressView = null
        isLongPressActive = false
        val v = capsuleContainer
        capsuleContainer = null
        layoutParams = null
        if (v != null) {
            v.animate().cancel()
            runCatching { windowManager.removeViewImmediate(v) }
        }
        screenW = 0
        screenH = 0
        capsuleW = 0
        capsuleH = 0
    }

    private fun handleKeyboardStateChanged(isShown: Boolean, imeHeight: Int) {
        if (isLongPressActive) return
        val lp = layoutParams ?: return

        val curScreenH = if (screenH > 0) screenH else screenSize().second
        val targetY = if (isShown && imeHeight > 0) {
            val capsuleBottom = baseParamsY + capsuleH
            val originalBottomMargin = (curScreenH - capsuleBottom).coerceAtLeast(dp(12))
            val imeTop = curScreenH - imeHeight
            // 键盘侵占原本底部空间时向上等量平移 imeHeight，精准保持胶囊距离工作区底部的原有相对间距
            if (capsuleBottom + originalBottomMargin > imeTop) {
                clampY(baseParamsY - imeHeight)
            } else {
                baseParamsY
            }
        } else {
            baseParamsY
        }

        if (targetParamsY == targetY && (lp.y == targetY || imeAvoidAnimator?.isRunning == true)) {
            return
        }
        targetParamsY = targetY

        val currentY = lp.y
        val deltaY = targetY - currentY
        if (deltaY == 0) {
            imeAvoidAnimator?.cancel()
            imeAvoidAnimator = null
            return
        }

        // 动画时长对标 OctoClip 工业级规范：回落 280ms，避让 200ms
        val duration = if (deltaY > 0) 280L else 200L
        Log.i(TAG, "[OCTO-IME-ANIM] 触发键盘位置响应: isShown=$isShown, height=$imeHeight, 当前y=$currentY -> 目标y=$targetY, 耗时=${duration}ms")

        imeAvoidAnimator?.cancel()
        imeAvoidAnimator = ValueAnimator.ofInt(currentY, targetY).apply {
            this.duration = duration
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f) // 现代系统级标准柔和减速贝塞尔曲线，消除大位移跳步与掉帧感
            addUpdateListener { anim ->
                val curView = capsuleContainer ?: return@addUpdateListener
                val curLp = layoutParams ?: return@addUpdateListener
                val animVal = anim.animatedValue as Int
                if (curLp.y != animVal) {
                    curLp.y = animVal
                    try {
                        windowManager.updateViewLayout(curView, curLp)
                    } catch (_: Exception) {}
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    val curView = capsuleContainer ?: return
                    val curLp = layoutParams ?: return
                    if (curLp.y != targetY) {
                        curLp.y = targetY
                        runCatching { windowManager.updateViewLayout(curView, curLp) }
                    }
                }
            })
            start()
        }
    }

    private fun clampY(y: Int): Int {
        val min = dp(8)
        return y.coerceIn(min, (screenH - capsuleH - dp(8)).coerceAtLeast(min))
    }

    private fun isNightMode(): Boolean {
        return (appContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun themeAccentColor(isNight: Boolean, useDynamic: Boolean): Int {
        if (useDynamic) {
            val accent = runCatching { DynamicColorUtils.primaryColor }.getOrNull()
            if (accent != null && accent != 0) return accent
        }
        return if (isNight) GOOGLE_BLUE_DARK else GOOGLE_BLUE_LIGHT
    }

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val m = windowManager.currentWindowMetrics
            m.bounds.width() to m.bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val p = android.graphics.Point()
            display.getRealSize(p)
            p.x to p.y
        }
    }

    private fun capsuleBackground(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(24).toFloat()
        setColor(color)
    }

    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    /** Debug-build breadcrumb; this ROM has the app log buffers off. */
    private fun diagnose(message: String) {
        if (!io.github.lumiagg.gestureevo.BuildConfig.DEBUG) return
        runCatching {
            val file = java.io.File(appContext.getExternalFilesDir(null), "clipboard-debug.txt")
            if (file.length() > 64 * 1024) file.delete()
            file.appendText(System.currentTimeMillis().toString() + " [capsule] " + message + System.lineSeparator())
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        appContext.resources.displayMetrics
    ).toInt()
}