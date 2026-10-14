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
import android.widget.ImageView
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
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import io.github.lumiagg.gestureevo.R
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
    onRecognized: suspend () -> Unit,
    onNext: (() -> Unit)? = null
) {
    val context = DynamicColorUtils.dynamicThemedContext(activity)
    val dialog = Dialog(context, R.style.Theme_GestureEVO)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
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
    card.addView(cardContent)

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
        text = context.getString(R.string.quick_practice_dialog_title)
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
        text = gesture.label(context)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall)
        setTextColor(DynamicColorUtils.onSurfaceColor)
        gravity = Gravity.CENTER
        setPadding(0, 10f.dp2px.toInt(), 0, 4f.dp2px.toInt())
    }
    cardContent.addView(titleView)

    // Animated Demo
    val demoView = GuideGestureDemoView(context).apply {
        show(gesture, config.side)
        val w = 100f.dp2px.toInt()
        val h = 150f.dp2px.toInt()
        layoutParams = LinearLayout.LayoutParams(w, h).apply {
            setMargins(0, 6f.dp2px.toInt(), 0, 8f.dp2px.toInt())
        }
    }
    cardContent.addView(demoView)

    // Assigned Action Badge
    val targetAction = config.gestures[gesture]
    if (targetAction != null) {
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
        val actionIcon = ImageView(context).apply {
            val size = 20f.dp2px.toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = 6f.dp2px.toInt()
            }
            setImageDrawable(targetAction.icon(context))
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (targetAction.type != QuickActionType.OPEN_APP || targetAction.packageName == null) {
                imageTintList = ColorStateList.valueOf(DynamicColorUtils.primaryColor)
            }
        }
        actionBadge.addView(actionIcon)

        val actionText = TextView(context).apply {
            text = context.getString(R.string.quick_practice_trigger_hint, targetAction.label(context))
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
            setTextColor(DynamicColorUtils.onSurfaceColor)
        }
        actionBadge.addView(actionText)
        cardContent.addView(actionBadge)
    }

    // Instruction Text
    val instructionView = TextView(context).apply {
        text = gesture.instruction(context)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        setTextColor(DynamicColorUtils.onSurfaceVariantColor)
        gravity = Gravity.CENTER
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    cardContent.addView(instructionView)

    // Status Banner
    val statusBanner = TextView(context).apply {
        text = context.getString(R.string.quick_practice_status_waiting)
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
        text = context.getString(if (onNext != null) R.string.quick_practice_skip else R.string.quick_practice_exit)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = 8f.dp2px.toInt()
        }
        setOnClickListener {
            dialog.dismiss()
            onNext?.invoke()
        }
    }
    buttonLayout.addView(skipButton)

    val nextButton = MaterialButton(context).apply {
        text = context.getString(if (onNext != null) R.string.quick_practice_next else R.string.quick_practice_done)
        isVisible = false
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener {
            dialog.dismiss()
            onNext?.invoke()
        }
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

    val pointerGravity = if (isLeftEdge) Gravity.START or Gravity.TOP else Gravity.END or Gravity.TOP
    val pointerParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        pointerGravity
    ).apply {
        val marginSide = 24f.dp2px.toInt()
        if (isLeftEdge) leftMargin = marginSide else rightMargin = marginSide
        topMargin = (activity.resources.displayMetrics.heightPixels * (config.verticalPercent / 100f)).toInt() - 20f.dp2px.toInt()
    }
    root.addView(pointerPill, pointerParams)

    // Animated bouncing for the pointer
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
        start()
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

    fun restorePreview() {
        if (GuidePreviewSession.current.value?.let { it.owner == previewOwner && it.practiceId == practiceId } == true) {
            GuidePreviewSession.show(previewOwner, config)
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
                onRecognized()
                if (disposed) return@launch

                VibratorUtils.vibrator(activity, true)
                bounceAnimator.cancel()
                pointerPill.isVisible = false

                statusBanner.text = context.getString(R.string.quick_practice_status_success)
                (statusBanner.background as? GradientDrawable)?.setColor(DynamicColorUtils.secondaryContainerColor)
                statusBanner.setTextColor(DynamicColorUtils.primaryColor)

                nextButton.isVisible = true
                skipButton.isVisible = false
                restorePreview()

                if (onNext != null) {
                    delay(800)
                    if (!disposed && dialog.isShowing) {
                        dialog.dismiss()
                        onNext.invoke()
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
                    GuidePreviewSession.show(previewOwner, config, gesture, practiceId)
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
            if (config.gestures[gesture]?.type == QuickActionType.WHEEL && result.wheelItemIndex == null) {
                if (!awaitingWheel) {
                    awaitingWheel = true
                    statusBanner.text = context.getString(R.string.quick_practice_choose_wheel)
                    GuidePreviewSession.show(previewOwner, config, gesture, practiceId, wheelSelection = true)
                }
                return@collect
            }
            recognized = true
            save()
        }
    }

    val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            if (!disposed && !recognized) {
                GuidePreviewSession.show(previewOwner, config, gesture, practiceId, awaitingWheel)
            }
        }
        override fun onDestroy(owner: LifecycleOwner) { dialog.dismiss() }
    }
    activity.lifecycle.addObserver(lifecycleObserver)

    dialog.setOnDismissListener {
        disposed = true
        bounceAnimator.cancel()
        gestureJob.cancel()
        saveJob?.cancel()
        restorePreview()
        activity.lifecycle.removeObserver(lifecycleObserver)
    }

    dialog.setContentView(root)
    dialog.show()
    dialog.window?.let { window ->
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }
    ViewCompat.requestApplyInsets(root)
    GuidePreviewSession.show(previewOwner, config, gesture, practiceId)
}
