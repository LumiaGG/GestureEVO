package io.github.lumiagg.gestureevo.notification

import io.github.lumiagg.gestureevo.databinding.NotificationItemViewBinding

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.graphics.Color
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.constraintlayout.utils.widget.ImageFilterView
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView

import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.NotificationFloatingViewBinding
import io.github.lumiagg.gestureevo.floating.BaseFloatingWindow
import io.github.lumiagg.gestureevo.gesture.GestureService
import io.github.lumiagg.gestureevo.notification.NotificationService.Companion.produceNotificationEvent
import io.github.lumiagg.gestureevo.notification.NotificationService.NotificationInfo
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.DEFAULT_NOTIFICATION_IMAGE_DISPLAY_SECONDS
import io.github.lumiagg.gestureevo.settings.InspireSettingsActivity.Companion.KEY_NOTIFICATION_IMAGE_DISPLAY_SECONDS
import io.github.lumiagg.gestureevo.utils.VibratorUtils
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.ScreenUtil
import io.github.lumiagg.gestureevo.utils.dp2px
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import io.github.lumiagg.gestureevo.utils.postOnMain
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

class NotificationFloating(
    private val context: GestureService,
) : BaseFloatingWindow(context, context, TAG) {
    companion object {
        private const val TAG = "NotificationFloating"
        const val KEY_NOTIFICATION_FLOATING_LOC_X = "notification_floating_loc_x"
        const val KEY_NOTIFICATION_FLOATING_LOC_Y = "notification_floating_loc_y"
    }

    private var binding: NotificationFloatingViewBinding = NotificationFloatingViewBinding.inflate(
        LayoutInflater.from(context)
    )
    private val screenWidth: Int
        get() = ScreenUtil.getScreenWidth(context)

    private val screenHeight: Int
        get() = ScreenUtil.getScreenHeight(context)


    // View cache
    private val viewCache = mutableMapOf<String, MaterialCardView>()
    private var currentNotifications = listOf<NotificationInfo>()
    private val MAX_NOTIFICATIONS = 3

    private var notificationJob: Job? = null
    private var isPinned = false
    private val layoutParams = WindowManager.LayoutParams()
    private var isAttached = false
    private var visibilityAnimator: ObjectAnimator? = null
    private var isDockedToRight = false
    private var dockedYRatio: Float = 0.5f

    private val componentCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            this@NotificationFloating.onConfigurationChanged(newConfig)
        }
        override fun onLowMemory() {}
    }

    var enable = true
        set(value) {
            if (value != field) {
                if (!value) {
                    hide()
                }
                field = value
            }
        }

    init {
        initAll()
    }

    private fun initAll() {
        initFloatingWindow()
        initTouchListener()
        context.registerComponentCallbacks(componentCallbacks)
    }

    private fun initFloatingWindow() {
        layoutParams.apply {
            configureAccessibilityOverlay(
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                width = WindowManager.LayoutParams.WRAP_CONTENT,
                height = WindowManager.LayoutParams.WRAP_CONTENT,
                y = screenHeight / 2,
                allowDisplayCutout = false,
                title = "GestureEVO:NotificationFloating",
            )
        }
        binding.root.visibility = View.GONE
        binding.root.alpha = 0f
        context.launch(Dispatchers.IO) {
            val savedX = context.settingsStore.readKey(KEY_NOTIFICATION_FLOATING_LOC_X, 0)
            val savedY =
                context.settingsStore.readKey(KEY_NOTIFICATION_FLOATING_LOC_Y, screenHeight / 2)
            context.postOnMain {
                val currentScreenWidth = screenWidth
                val currentScreenHeight = screenHeight
                isDockedToRight = savedX > currentScreenWidth / 2 || savedX > 0
                dockedYRatio = (savedY.toFloat() / currentScreenHeight.coerceAtLeast(1)).coerceIn(0.05f, 0.95f)

                val rootWidth = binding.root.width.takeIf { it > 0 }
                    ?: binding.root.measuredWidth.takeIf { it > 0 }
                    ?: 56.dp2px.toInt()
                val rootHeight = binding.root.height.takeIf { it > 0 }
                    ?: binding.root.measuredHeight.takeIf { it > 0 }
                    ?: 56.dp2px.toInt()

                layoutParams.x = if (isDockedToRight) (currentScreenWidth - rootWidth).coerceAtLeast(0) else 0
                layoutParams.y = (dockedYRatio * currentScreenHeight - rootHeight / 2f).roundToInt()
                    .coerceIn(0, (currentScreenHeight - rootHeight).coerceAtLeast(0))
                if (isAttached && binding.root.isAttachedToWindow) {
                    updateViewLayoutSafely(binding.root, layoutParams)
                }
            }
        }
    }

    private fun ensureAttached() {
        if (binding.root.isAttachedToWindow) {
            isAttached = true
            return
        }
        addViewSafely(binding.root, layoutParams) { isAttached = true }
        isAttached = binding.root.isAttachedToWindow
    }

    private fun detachIfNeeded(cancelAnimator: Boolean = true) {
        if (cancelAnimator) {
            visibilityAnimator?.cancel()
            visibilityAnimator = null
        }
        detachViewSafely(binding.root) { isAttached = false }
    }

    private val dragHandler = DragHandler()
    private val selectionHandler = SelectionHandler()

    @SuppressLint("ClickableViewAccessibility")
    private fun initTouchListener() {
        binding.root.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragHandler.onTouchDown(event, view)
                    selectionHandler.onTouchDown(event, view)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (dragHandler.longPressStarted) {
                        dragHandler.onTouchMove(event, view, windowManager)
                    } else {
                        selectionHandler.onTouchMove(event)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (dragHandler.longPressStarted) {
                        dragHandler.onTouchUp(event, view, windowManager)
                    } else {
                        selectionHandler.onTouchUp(event, view, dragHandler)
                    }
                    false
                }

                else -> false
            }
        }
        binding.root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (dragHandler.isMoving) return@addOnLayoutChangeListener
            val width = right - left
            val height = bottom - top
            if (width <= 0 || height <= 0) return@addOnLayoutChangeListener
            val oldWidth = oldRight - oldLeft
            val oldHeight = oldBottom - oldTop
            if (width != oldWidth || height != oldHeight) {
                val currentScreenWidth = screenWidth
                val currentScreenHeight = screenHeight
                val targetX = if (isDockedToRight) (currentScreenWidth - width).coerceAtLeast(0) else 0
                val targetY = layoutParams.y.coerceIn(0, (currentScreenHeight - height).coerceAtLeast(0))
                if (layoutParams.x != targetX || layoutParams.y != targetY) {
                    layoutParams.x = targetX
                    layoutParams.y = targetY
                    if (isAttached && binding.root.isAttachedToWindow) {
                        updateViewLayoutSafely(binding.root, layoutParams)
                    }
                }
            }
        }
    }

    private fun createIconCardFromXml(pkg: String): MaterialCardView {
        // Retrieve from cache if available
        return viewCache.getOrPut(pkg) {
            val inflater = LayoutInflater.from(DynamicColorUtils.dynamicThemedContext(context))
            val view = NotificationItemViewBinding.inflate(inflater).root
            view.findViewById<ImageFilterView>(R.id.icon)
                ?.setImageDrawable(IconHelper.getIcon(context, pkg))
            view
        }
    }

    private fun handleClick(pkg: String) = context.launch {
        produceNotificationEvent(NotificationEvent(pkg, freeForm = true))
        hide()
    }

    private fun handleLongPress(pkg: String) = context.launch {
        produceNotificationEvent(NotificationEvent(pkg, freeForm = false))
        hide()
    }

    private fun handleSwipe() = context.launch {
        hide()
    }

    private fun updateViews(notificationInfoList: List<NotificationInfo>) {
        // Evict stale cached views
        val newPackages =
            notificationInfoList.take(MAX_NOTIFICATIONS).map { it.packageName }.toSet()
        viewCache.keys.toList().forEach { pkg ->
            if (pkg !in newPackages) {
                viewCache.remove(pkg)
            }
        }

        // Update views
        binding.root.removeAllViews()
        notificationInfoList.take(MAX_NOTIFICATIONS).forEach { info ->
            val icon = createIconCardFromXml(info.packageName)
            // Ensure view has no existing parent before attaching
            icon.parent?.let { (it as ViewGroup).removeView(icon) }
            binding.root.addView(icon)
        }
        refreshTheme()
    }

    fun show(notificationInfoList: List<NotificationInfo>) = context.postOnMain {
        if (!enable) return@postOnMain
        val currentScreenWidth = screenWidth
        val currentScreenHeight = screenHeight
        val rootWidth = binding.root.width.takeIf { it > 0 }
            ?: binding.root.measuredWidth.takeIf { it > 0 }
            ?: 56.dp2px.toInt()
        val rootHeight = binding.root.height.takeIf { it > 0 }
            ?: binding.root.measuredHeight.takeIf { it > 0 }
            ?: 56.dp2px.toInt()
        layoutParams.x = if (isDockedToRight) (currentScreenWidth - rootWidth).coerceAtLeast(0) else 0
        layoutParams.y = (dockedYRatio * currentScreenHeight - rootHeight / 2f).roundToInt()
            .coerceIn(0, (currentScreenHeight - rootHeight).coerceAtLeast(0))
        if (isAttached && binding.root.isAttachedToWindow) {
            updateViewLayoutSafely(binding.root, layoutParams)
        }
        ensureAttached()
        visibilityAnimator?.cancel()

        if (notificationInfoList != currentNotifications) {
            currentNotifications = notificationInfoList
            updateViews(notificationInfoList)
        }
        refreshTheme()
        restartAutoHideTimer()

        if (binding.root.visibility != View.VISIBLE) {
            binding.root.alpha = 0f
            binding.root.visibility = View.VISIBLE
            visibilityAnimator = ObjectAnimator.ofFloat(binding.root, View.ALPHA, binding.root.alpha, 1f).apply {
                duration = 200
                start()
            }
        }
    }

    fun hide() = context.postOnMain {
        notificationJob?.cancel()
        notificationJob = null
        clearPinnedState()
        if (!binding.root.isAttachedToWindow) {
            cleanup()
            detachIfNeeded()
            return@postOnMain
        }
        visibilityAnimator?.cancel()
        if (binding.root.isVisible) {
            val hideAnimator = ObjectAnimator.ofFloat(binding.root, View.ALPHA, binding.root.alpha, 0.0f)
            visibilityAnimator = hideAnimator.apply {
                duration = 200
                addListener(object : Animator.AnimatorListener {
                    override fun onAnimationStart(p0: Animator) {}
                    override fun onAnimationEnd(p0: Animator) {
                        if (visibilityAnimator !== hideAnimator) return
                        visibilityAnimator = null
                        binding.root.visibility = View.GONE
                        cleanup()
                        detachIfNeeded(cancelAnimator = false)
                    }

                    override fun onAnimationCancel(p0: Animator) {
                        visibilityAnimator = null
                        if (!isAttachRequested(binding.root)) {
                            cleanup()
                            detachIfNeeded(cancelAnimator = false)
                        }
                    }
                    override fun onAnimationRepeat(p0: Animator) {}
                })
                start()
            }
        } else {
            cleanup()
            detachIfNeeded()
        }
    }

    fun update(notificationInfoList: List<NotificationInfo>) = context.postOnMain {
        if (!enable) return@postOnMain
        if (binding.root.isAttachedToWindow && binding.root.isVisible) {
            if (notificationInfoList.isEmpty()) {
                hide()
            } else if (notificationInfoList != currentNotifications) {
                currentNotifications = notificationInfoList
                updateViews(notificationInfoList)
                restartAutoHideTimer()
            } else {
                restartAutoHideTimer()
            }
        }
    }

    fun refreshTheme() {
        val surface = DynamicColorUtils.surfaceContainerColor
        val primary = DynamicColorUtils.primaryColor
        viewCache.values.forEach { card ->
            card.setCardBackgroundColor(surface)
            card.strokeWidth = if (isPinned) 4.dp2px.toInt() else 0
            card.strokeColor = if (isPinned) primary else Color.TRANSPARENT
        }
    }

    fun cleanup() {
        viewCache.clear()
        currentNotifications = emptyList()
        binding.root.removeAllViews()
    }

    fun destroy() = context.postOnMain {
        runCatching { context.unregisterComponentCallbacks(componentCallbacks) }
        notificationJob?.cancel()
        notificationJob = null
        visibilityAnimator?.cancel()
        visibilityAnimator = null
        clearPinnedState()
        binding.root.animate().cancel()
        binding.root.visibility = View.GONE
        cleanup()
        detachIfNeeded(cancelAnimator = false)
    }

    fun pinnedMode() {
        notificationJob?.cancel()
        notificationJob = null
        if (isPinned) return
        isPinned = true
        context.postOnMain {
            refreshTheme()

            VibratorUtils.vibrator(context, true)

            binding.root.animate()
                .scaleX(1.1f)
                .scaleY(1.1f)
                .setDuration(150)
                .withEndAction {
                    binding.root.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(150)
                        .start()
                }
                .start()
        }
    }

    fun clearPinnedState() {
        isPinned = false
        viewCache.forEach { (_, card) ->
            // Restore default stroke
            card.strokeWidth = 0
            card.strokeColor = Color.TRANSPARENT
        }
    }

    fun onConfigurationChanged(newConfig: Configuration? = null) {
        repositionAfterOrientationChange()
    }

    fun repositionAfterOrientationChange() {
        context.postOnMain {
            val currentScreenWidth = screenWidth
            val currentScreenHeight = screenHeight
            val rootWidth = binding.root.width.takeIf { it > 0 }
                ?: binding.root.measuredWidth.takeIf { it > 0 }
                ?: 56.dp2px.toInt()
            val rootHeight = binding.root.height.takeIf { it > 0 }
                ?: binding.root.measuredHeight.takeIf { it > 0 }
                ?: 56.dp2px.toInt()

            val targetX = if (isDockedToRight) (currentScreenWidth - rootWidth).coerceAtLeast(0) else 0
            val targetY = (dockedYRatio * currentScreenHeight - rootHeight / 2f).roundToInt()
                .coerceIn(0, (currentScreenHeight - rootHeight).coerceAtLeast(0))

            layoutParams.x = targetX
            layoutParams.y = targetY
            if (isAttached && binding.root.isAttachedToWindow) {
                updateViewLayoutSafely(binding.root, layoutParams)
            }
        }
        binding.root.postDelayed({
            val currentScreenWidth = screenWidth
            val currentScreenHeight = screenHeight
            val rootWidth = binding.root.width.takeIf { it > 0 }
                ?: binding.root.measuredWidth.takeIf { it > 0 }
                ?: 56.dp2px.toInt()
            val rootHeight = binding.root.height.takeIf { it > 0 }
                ?: binding.root.measuredHeight.takeIf { it > 0 }
                ?: 56.dp2px.toInt()

            val targetX = if (isDockedToRight) (currentScreenWidth - rootWidth).coerceAtLeast(0) else 0
            val targetY = (dockedYRatio * currentScreenHeight - rootHeight / 2f).roundToInt()
                .coerceIn(0, (currentScreenHeight - rootHeight).coerceAtLeast(0))

            layoutParams.x = targetX
            layoutParams.y = targetY
            if (isAttached && binding.root.isAttachedToWindow) {
                updateViewLayoutSafely(binding.root, layoutParams)
            }
        }, 150L)
    }

    private suspend fun restartAutoHideTimer() {
        notificationJob?.cancel()
        notificationJob = null
        if (isPinned || currentNotifications.isEmpty()) return

        val displaySeconds = context.settingsStore.readKey(
            KEY_NOTIFICATION_IMAGE_DISPLAY_SECONDS,
            DEFAULT_NOTIFICATION_IMAGE_DISPLAY_SECONDS
        ).coerceAtLeast(0)
        if (displaySeconds == 0) return

        notificationJob = context.launch {
            delay(displaySeconds * 1000L)
            hide()
        }
    }


    private inner class DragHandler {
        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        var isMoving = false
            private set
        var longPressStarted = false
            private set
        private val longPressTimeout = 500L
        private var longPressRunnable: Runnable? = null

        fun onTouchDown(event: MotionEvent, view: View) {
            val layoutParams = view.layoutParams as WindowManager.LayoutParams
            initialX = layoutParams.x
            initialY = layoutParams.y
            initialTouchX = event.rawX
            initialTouchY = event.rawY
            isMoving = false
            longPressStarted = false

            longPressRunnable = Runnable {
                VibratorUtils.vibrator(context, true)
                longPressStarted = true
            }.also {
                view.postDelayed(it, longPressTimeout)
            }
        }

        fun onTouchMove(event: MotionEvent, view: View, windowManager: WindowManager) {
            val deltaX = (event.rawX - initialTouchX)
            val deltaY = (event.rawY - initialTouchY)
            val distance = hypot(deltaX, deltaY)

            if (longPressStarted && distance > 10f.dp2px) {
                isMoving = true
                pinnedMode()

                val layoutParams = view.layoutParams as WindowManager.LayoutParams
                layoutParams.x = initialX + deltaX.toInt()
                layoutParams.y = initialY + deltaY.toInt()
                windowManager.updateViewLayout(view, layoutParams)
            }
        }

        fun onTouchUp(event: MotionEvent, view: View, windowManager: WindowManager) {
            longPressStarted = false
            longPressRunnable?.let { view.removeCallbacks(it) }

            if (isMoving) {
                val layoutParams = view.layoutParams as WindowManager.LayoutParams
                val currentScreenWidth = screenWidth
                val currentScreenHeight = screenHeight
                isDockedToRight = (layoutParams.x + view.width / 2) >= currentScreenWidth / 2
                val targetX = if (isDockedToRight) (currentScreenWidth - view.width).coerceAtLeast(0) else 0
                dockedYRatio = ((layoutParams.y + view.height / 2f) / currentScreenHeight.coerceAtLeast(1)).coerceIn(0.05f, 0.95f)

                animateToEdge(layoutParams, view, windowManager, targetX)
                context.launch {
                    context.settingsStore.saveKey(KEY_NOTIFICATION_FLOATING_LOC_X, targetX)
                    context.settingsStore.saveKey(KEY_NOTIFICATION_FLOATING_LOC_Y, layoutParams.y)
                }
            } else {
                selectionHandler.selectedPackage?.let { handleLongPress(it) }
            }
        }

        private fun animateToEdge(
            layoutParams: WindowManager.LayoutParams,
            view: View,
            windowManager: WindowManager,
            targetX: Int
        ) {
            ObjectAnimator.ofInt(layoutParams.x, targetX).apply {
                addUpdateListener { animator ->
                    layoutParams.x = animator.animatedValue as Int
                    updateViewLayoutSafely(view, layoutParams)
                }
                duration = 200
                start()
            }
        }

        fun isLongPressed() = longPressStarted && !isMoving
    }

    private inner class SelectionHandler {
        private var startTime = 0L
        private var startX = 0f
        private var startY = 0f
        var selectedPackage: String? = null

        fun onTouchDown(event: MotionEvent, view: View) {
            startTime = System.currentTimeMillis()
            startX = event.rawX
            startY = event.rawY
            selectedPackage = findTouchedIcon(event)
        }

        fun onTouchMove(event: MotionEvent) {

        }

        fun onTouchUp(event: MotionEvent, view: View, dragHandler: DragHandler) {
            val dx = event.rawX - startX
            val dy = event.rawY - startY
            val overDis = hypot(dx, dy) > 50.dp2px
            if (overDis && abs(dx) > 40.dp2px) {
                pinnedMode()
            } else if (overDis) {
                if (selectedPackage != null) {
                    selectedPackage = null
                }
                handleSwipe()
            } else {
                selectedPackage?.let {
                    if (System.currentTimeMillis() - startTime < 200) handleClick(it)
                }
            }
        }

        private fun findTouchedIcon(event: MotionEvent): String? {
            val touchX = event.x
            val touchY = event.y

            for (i in 0 until binding.root.childCount) {
                val child = binding.root.getChildAt(i) as? MaterialCardView ?: continue
                val icon = child.findViewById<ImageFilterView>(R.id.icon) ?: continue

                val location = IntArray(2)
                icon.getLocationInWindow(location)

                if (touchX >= location[0] && touchX <= location[0] + icon.width &&
                    touchY >= location[1] && touchY <= location[1] + icon.height
                ) {
                    return currentNotifications.getOrNull(i)?.packageName
                }
            }
            return null
        }
    }

}
