package io.github.lumiagg.gestureevo.settings.guide

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.gesture.config.ActionConfig
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.gesture.config.GestureActionDataManager
import io.github.lumiagg.gestureevo.gesture.ui.RoundImageTextView
import io.github.lumiagg.gestureevo.gesture.ui.applyWheelStyle
import io.github.lumiagg.gestureevo.gesture.freeTriggerBounds
import androidx.core.widget.NestedScrollView
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.VibratorUtils
import io.github.lumiagg.gestureevo.utils.dp2px
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

internal fun showGuidePractice(
    activity: AppCompatActivity,
    gesture: QuickGesture,
    config: QuickSetupConfig,
    previewOwner: String,
    actionConfig: ActionConfig? = null,
    onRecognized: suspend (QuickGesture) -> Unit,
    onDismissed: (() -> Unit)? = null
) {
    showGuidePracticeSequence(
        activity = activity,
        gestures = listOf(gesture),
        initialIndex = 0,
        config = config,
        previewOwner = previewOwner,
        actionConfig = actionConfig,
        onRecognized = onRecognized,
        onDismissed = onDismissed
    )
}

internal fun showGuidePracticeSequence(
    activity: AppCompatActivity,
    gestures: List<QuickGesture>,
    initialIndex: Int = 0,
    config: QuickSetupConfig,
    previewOwner: String,
    actionConfig: ActionConfig? = null,
    onRecognized: suspend (QuickGesture) -> Unit,
    onDismissed: (() -> Unit)? = null
) {
    if (gestures.isEmpty()) return
    val context = DynamicColorUtils.dynamicThemedContext(activity)
    val dialog = Dialog(context, R.style.Theme_GestureEVO)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

    var currentIndex = initialIndex.coerceIn(0, gestures.size - 1)
    var currentGesture = gestures[currentIndex]
    var practiceId = UUID.randomUUID().toString()

    val root = FrameLayout(context).apply {
        setBackgroundColor(Color.argb(160, 0, 0, 0))
    }

    // --- Center Instruction & Demo Card ---
    val card = MaterialCardView(context).apply {
        radius = 24f.dp2px
        cardElevation = 8f.dp2px
        setCardBackgroundColor(DynamicColorUtils.surfaceContainerColor)
        strokeWidth = 0
    }
    val cardParams = FrameLayout.LayoutParams(
        340f.dp2px.toInt(),
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER
    ).apply {
        setMargins(20f.dp2px.toInt(), 20f.dp2px.toInt(), 20f.dp2px.toInt(), 20f.dp2px.toInt())
    }
    root.addView(card, cardParams)

    val cardContent = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val pad = 20f.dp2px.toInt()
        setPadding(pad, pad, pad, pad)
    }
    val cardScroll = NestedScrollView(context).apply {
        addView(cardContent, FrameLayout.LayoutParams(-1, -2))
    }
    card.addView(cardScroll, FrameLayout.LayoutParams(-1, -1))

    // Top Header: Step/Dialog title + Close button
    val headerLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    val badgeView = TextView(context).apply {
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        setTextColor(DynamicColorUtils.primaryColor)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f.dp2px
            setColor(DynamicColorUtils.secondaryContainerColor)
        }
        val padH = 10f.dp2px.toInt()
        val padV = 4f.dp2px.toInt()
        setPadding(padH, padV, padH, padV)
    }
    headerLayout.addView(badgeView, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        1f
    ))

    val closeButton = AppCompatImageButton(context).apply {
        setImageResource(R.drawable.baseline_clear_24)
        imageTintList = ColorStateList.valueOf(DynamicColorUtils.onSurfaceVariantColor)
        val background = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true)
        setBackgroundResource(background.resourceId)
        val size = 36f.dp2px.toInt()
        layoutParams = LinearLayout.LayoutParams(size, size)
        contentDescription = context.getString(android.R.string.cancel)
        setOnClickListener { dialog.dismiss() }
    }
    headerLayout.addView(closeButton)
    cardContent.addView(headerLayout)

    // Gesture Title
    val titleView = TextView(context).apply {
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)
        setTextColor(DynamicColorUtils.onSurfaceColor)
        gravity = Gravity.CENTER
        setPadding(0, 10f.dp2px.toInt(), 0, 4f.dp2px.toInt())
    }
    cardContent.addView(titleView)

    // Animated Demo
    val demoView = GuideGestureDemoView(context).apply {
        val w = 100f.dp2px.toInt()
        val h = 150f.dp2px.toInt()
        layoutParams = LinearLayout.LayoutParams(w, h).apply {
            setMargins(0, 6f.dp2px.toInt(), 0, 8f.dp2px.toInt())
        }
    }
    cardContent.addView(demoView)

    // Assigned Action Badge
    val actionBadge = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 14f.dp2px
            setColor(DynamicColorUtils.surfaceContainerHighColor)
        }
        val padH = 12f.dp2px.toInt()
        val padV = 6f.dp2px.toInt()
        setPadding(padH, padV, padH, padV)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 10f.dp2px.toInt()
        }
    }
    val actionIcon = RoundImageTextView(context).apply {
        val size = 20f.dp2px.toInt()
        layoutParams = LinearLayout.LayoutParams(size, size).apply {
            marginEnd = 6f.dp2px.toInt()
        }
        applyWheelStyle(ActionConfig(), size / 55f.dp2px)
    }
    actionBadge.addView(actionIcon)

    val actionText = TextView(context).apply {
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        setTextColor(DynamicColorUtils.onSurfaceColor)
    }
    actionBadge.addView(actionText)
    cardContent.addView(actionBadge)

    // Instruction Text
    val instructionView = TextView(context).apply {
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        setTextColor(DynamicColorUtils.onSurfaceVariantColor)
        gravity = Gravity.CENTER
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    cardContent.addView(instructionView)

    // Status Banner
    val statusBanner = TextView(context).apply {
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        setTextColor(DynamicColorUtils.onSurfaceVariantColor)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 10f.dp2px
            setColor(DynamicColorUtils.surfaceContainerHighColor)
        }
        val padH = 12f.dp2px.toInt()
        val padV = 8f.dp2px.toInt()
        setPadding(padH, padV, padH, padV)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 12f.dp2px.toInt()
            bottomMargin = 12f.dp2px.toInt()
        }
    }
    cardContent.addView(statusBanner)

    // Controls Layout (Buttons)
    val buttonLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    val skipButton = MaterialButton(
        context,
        null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle
    ).apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = 8f.dp2px.toInt()
        }
    }
    buttonLayout.addView(skipButton)

    val nextButton = MaterialButton(context).apply {
        isVisible = false
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }
    buttonLayout.addView(nextButton)
    cardContent.addView(buttonLayout)

    // --- Floating Edge Pointer Hint ---
    val isLeftEdge = config.side == QuickSetupSide.LEFT
    val pointerPill = TextView(context).apply {
        text = if (isLeftEdge) {
            context.getString(R.string.quick_practice_pointer_hint_left)
        } else {
            context.getString(R.string.quick_practice_pointer_hint_right)
        }
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        setTextColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnPrimary, Color.WHITE))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f.dp2px
            setColor(DynamicColorUtils.primaryColor)
        }
        val padH = 14f.dp2px.toInt()
        val padV = 8f.dp2px.toInt()
        setPadding(padH, padV, padH, padV)
        elevation = 12f.dp2px
    }

    val pointerGravity = if (isLeftEdge) Gravity.LEFT or Gravity.TOP else Gravity.RIGHT or Gravity.TOP
    val pointerParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        pointerGravity
    ).apply {
        val marginSide = 24f.dp2px.toInt()
        if (isLeftEdge) leftMargin = marginSide else rightMargin = marginSide
        topMargin = 0
    }
    root.addView(pointerPill, pointerParams)

    val previewTrigger = buildQuickSetupTriggers(config, context.getString(R.string.guide_title))
        .first { it.tag == if (isLeftEdge) QuickSetupRepository.PREVIEW_LEFT_TRIGGER_TAG else QuickSetupRepository.PREVIEW_RIGHT_TRIGGER_TAG }
    val rootLocation = IntArray(2)
    fun updateLayout() {
        if (root.width <= 0 || root.height <= 0) return
        val availableWidth = (root.width - root.paddingLeft - root.paddingRight - 40f.dp2px).toInt().coerceAtLeast(1)
        val cardWidth = minOf(340f.dp2px.toInt(), availableWidth)
        cardContent.measure(View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val availableHeight = (root.height - root.paddingTop - root.paddingBottom - 40f.dp2px).toInt().coerceAtLeast(1)
        val cardHeight = minOf(cardContent.measuredHeight, availableHeight).coerceAtLeast(1)
        val params = card.layoutParams
        if (params.width != cardWidth || params.height != cardHeight) {
            params.width = cardWidth
            params.height = cardHeight
            card.layoutParams = params
        }
        root.getLocationOnScreen(rootLocation)
        val bounds = freeTriggerBounds(activity, previewTrigger, config.side == QuickSetupSide.BOTH && !isLeftEdge)
        val top = guideHintTop(bounds.centerY() - rootLocation[1] - root.paddingTop,
            pointerPill.height, root.height - root.paddingTop - root.paddingBottom)
        val hintParams = pointerPill.layoutParams as FrameLayout.LayoutParams
        if (hintParams.topMargin != top) {
            hintParams.topMargin = top
            pointerPill.layoutParams = hintParams
        }
        if (pointerPill.maxWidth != availableWidth) pointerPill.maxWidth = availableWidth
    }
    root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateLayout() }
    val bounceDistance = 10f.dp2px
    val bounceAnimator = ObjectAnimator.ofFloat(
        pointerPill,
        "translationX",
        0f,
        if (isLeftEdge) -bounceDistance else bounceDistance,
        0f
    ).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
    }

    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }

    var recognized = false
    var saving = false
    var disposed = false
    var awaitingWheel = false
    var saveJob: Job? = null
    var transitionJob: Job? = null
    var wheelLoadJob: Job? = null
    var practiceConfig = config
    var practiceActionConfig = actionConfig
    var wheelLoading = false

    fun publishPractice() {
        if (!disposed && !wheelLoading && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) GuidePreviewSession.show(previewOwner, practiceConfig, currentGesture,
            practiceId, wheelSelection = awaitingWheel, actionConfig = practiceActionConfig)
    }

    fun restorePreview() {
        if (GuidePreviewSession.current.value?.let { it.owner == previewOwner && it.practiceId == practiceId } == true) {
            GuidePreviewSession.show(previewOwner, config, actionConfig = actionConfig)
        }
    }

    fun displayGesture(index: Int) {
        transitionJob?.cancel()
        wheelLoadJob?.cancel()
        currentIndex = index
        currentGesture = gestures[currentIndex]
        recognized = false
        saving = false
        awaitingWheel = false
        practiceId = UUID.randomUUID().toString()
        practiceConfig = config
        practiceActionConfig = actionConfig
        wheelLoading = false

        val isLast = currentIndex + 1 >= gestures.size
        badgeView.text = if (gestures.size > 1) {
            "${context.getString(R.string.quick_practice_dialog_title)} (${currentIndex + 1}/${gestures.size})"
        } else {
            context.getString(R.string.quick_practice_dialog_title)
        }

        titleView.text = currentGesture.label(context)
        demoView.show(currentGesture, config.side)

        val targetAction = config.gestures[currentGesture]
        if (targetAction != null) {
            actionBadge.isVisible = true
            actionIcon.bindGuideAction(targetAction)
            actionText.text = context.getString(R.string.quick_practice_trigger_hint, targetAction.label(context))
        } else {
            actionBadge.isVisible = false
        }

        instructionView.text = currentGesture.instruction(context)
        statusBanner.text = context.getString(R.string.quick_practice_status_waiting)
        (statusBanner.background as? GradientDrawable)?.setColor(DynamicColorUtils.surfaceContainerHighColor)
        statusBanner.setTextColor(DynamicColorUtils.onSurfaceVariantColor)

        skipButton.text = context.getString(if (!isLast) R.string.quick_practice_skip else R.string.quick_practice_exit)
        skipButton.isVisible = true
        skipButton.isEnabled = true
        closeButton.isEnabled = true
        dialog.setCancelable(true)

        nextButton.isVisible = false
        nextButton.text = context.getString(if (!isLast) R.string.quick_practice_next else R.string.quick_practice_done)

        pointerPill.isVisible = true
        if (!bounceAnimator.isRunning) {
            bounceAnimator.start()
        }

        val targetWheelTag = targetAction?.takeIf { it.guideActionType == GestureActionType.SHOW_ACTION_VIEW }
            ?.firstStageLink?.let { parseActionLink(it).second["actionItemsTag"] } ?: QuickSetupRepository.WHEEL_TAG
        if (targetWheelTag == QuickSetupRepository.WHEEL_TAG) {
            publishPractice()
        } else {
            wheelLoading = true
            pointerPill.isVisible = false
            bounceAnimator.cancel()
            statusBanner.setText(R.string.guide_loading)
            GuidePreviewSession.show(previewOwner, config, actionConfig = actionConfig)
            val expectedId = practiceId
            wheelLoadJob = activity.lifecycleScope.launch {
                try {
                    val items = QuickSetupRepository.readWheelItems(activity, targetWheelTag)
                    val appearance = GestureActionDataManager.readActionConfig(activity, targetWheelTag, false)
                    if (disposed || practiceId != expectedId) return@launch
                    practiceConfig = config.copy(wheelItems = items)
                    practiceActionConfig = appearance
                    wheelLoading = false
                    statusBanner.setText(R.string.quick_practice_status_waiting)
                    pointerPill.isVisible = true
                    bounceAnimator.start()
                    publishPractice()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("GuidePractice", "Unable to load the selected wheel", e)
                    if (!disposed && practiceId == expectedId) statusBanner.setText(R.string.guide_load_failed)
                }
            }
        }
    }

    skipButton.setOnClickListener {
        transitionJob?.cancel()
        if (currentIndex + 1 < gestures.size) {
            displayGesture(currentIndex + 1)
        } else {
            dialog.dismiss()
        }
    }

    nextButton.setOnClickListener {
        transitionJob?.cancel()
        if (currentIndex + 1 < gestures.size) {
            displayGesture(currentIndex + 1)
        } else {
            dialog.dismiss()
        }
    }

    fun save() {
        if (saving || disposed) return
        saving = true
        skipButton.isEnabled = false
        closeButton.isEnabled = false
        dialog.setCancelable(false)
        statusBanner.text = context.getString(R.string.quick_setup_saving)

        saveJob = activity.lifecycleScope.launch {
            try {
                onRecognized(currentGesture)
                if (disposed) return@launch

                VibratorUtils.vibrator(activity, true)
                bounceAnimator.cancel()
                pointerPill.isVisible = false

                statusBanner.text = context.getString(R.string.quick_practice_status_success)
                (statusBanner.background as? GradientDrawable)?.setColor(DynamicColorUtils.secondaryContainerColor)
                statusBanner.setTextColor(DynamicColorUtils.primaryColor)

                val hasNext = currentIndex + 1 < gestures.size
                nextButton.text = context.getString(if (hasNext) R.string.quick_practice_next else R.string.quick_practice_done)
                nextButton.isVisible = true
                skipButton.isVisible = false
                restorePreview()

                transitionJob?.cancel()
                transitionJob = launch {
                    delay(800)
                    if (!disposed && dialog.isShowing) {
                        if (hasNext) {
                            displayGesture(currentIndex + 1)
                        } else {
                            dialog.dismiss()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("GuidePractice", "Unable to apply practiced configuration", e)
                if (!disposed) {
                    statusBanner.text = context.getString(R.string.quick_setup_save_failed)
                    recognized = false
                    awaitingWheel = false
                    practiceId = UUID.randomUUID().toString()
                    publishPractice()
                }
            } finally {
                saving = false
                if (!disposed) {
                    skipButton.isEnabled = true
                    closeButton.isEnabled = true
                    dialog.setCancelable(true)
                }
            }
        }
    }

    val gestureJob = activity.lifecycleScope.launch {
        GuidePreviewSession.gestures.collect { result ->
            if (disposed || recognized || result.owner != previewOwner || result.practiceId != practiceId) return@collect
            if (config.gestures[currentGesture]?.guideActionType == GestureActionType.SHOW_ACTION_VIEW && result.wheelItemIndex == null) {
                if (!awaitingWheel) {
                    awaitingWheel = true
                    statusBanner.text = context.getString(R.string.quick_practice_choose_wheel)
                    publishPractice()
                }
                return@collect
            }
            recognized = true
            save()
        }
    }

    val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            if (!disposed && !recognized) {
                publishPractice()
                if (pointerPill.isVisible && !bounceAnimator.isRunning) bounceAnimator.start()
            }
        }
        override fun onPause(owner: LifecycleOwner) { bounceAnimator.cancel() }
        override fun onDestroy(owner: LifecycleOwner) { dialog.dismiss() }
    }
    activity.lifecycle.addObserver(lifecycleObserver)

    dialog.setOnDismissListener {
        disposed = true
        bounceAnimator.cancel()
        gestureJob.cancel()
        saveJob?.cancel()
        transitionJob?.cancel()
        wheelLoadJob?.cancel()
        restorePreview()
        activity.lifecycle.removeObserver(lifecycleObserver)
        onDismissed?.invoke()
    }

    dialog.setContentView(root)
    displayGesture(currentIndex)
    dialog.show()
    dialog.window?.let { window ->
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }
    ViewCompat.requestApplyInsets(root)
}
