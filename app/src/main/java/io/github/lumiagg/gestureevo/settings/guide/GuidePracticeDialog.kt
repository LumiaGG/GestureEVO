package io.github.lumiagg.gestureevo.settings.guide

import android.app.Dialog
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.textview.MaterialTextView
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.dp2px
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
        setBackgroundColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, 0))
    }
    val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val panelGravity = if (config.verticalPercent < 50) Gravity.BOTTOM else Gravity.TOP
    root.addView(panel, FrameLayout.LayoutParams(-1, -2, panelGravity))
    val prompt = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24f.dp2px.toInt(), 20f.dp2px.toInt(), 24f.dp2px.toInt(), 16f.dp2px.toInt())
        setBackgroundColor(DynamicColorUtils.surfaceContainerColor)
    }
    panel.addView(prompt, LinearLayout.LayoutParams(-1, -2))
    val title = MaterialTextView(context).apply {
        text = config.gestures.getValue(gesture).label(context)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
        setTextColor(DynamicColorUtils.onSurfaceColor)
    }
    prompt.addView(title, LinearLayout.LayoutParams(-1, -2))
    val message = MaterialTextView(context).apply {
        text = gesture.instruction(context)
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        setTextColor(DynamicColorUtils.onSurfaceVariantColor)
        setPadding(0, 8f.dp2px.toInt(), 0, 0)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    prompt.addView(message, LinearLayout.LayoutParams(-1, -2))
    val controls = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(24f.dp2px.toInt(), 12f.dp2px.toInt(), 24f.dp2px.toInt(), 16f.dp2px.toInt())
    }
    panel.addView(controls, LinearLayout.LayoutParams(-1, -2))
    val next = MaterialButton(context).apply {
        setText(if (onNext != null) R.string.quick_practice_next else R.string.quick_practice_done)
        isVisible = false
        setOnClickListener { dialog.dismiss(); onNext?.invoke() }
    }
    controls.addView(next, LinearLayout.LayoutParams(-1, -2))
    val close = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        setText(android.R.string.cancel)
        setOnClickListener { dialog.dismiss() }
    }
    controls.addView(close, LinearLayout.LayoutParams(-1, -2))
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
        close.isEnabled = false
        dialog.setCancelable(false)
        message.setText(R.string.quick_setup_saving)
        saveJob = activity.lifecycleScope.launch {
            try {
                onRecognized()
                if (disposed) return@launch
                title.setText(R.string.quick_practice_saved)
                message.setText(R.string.quick_practice_applied)
                next.isVisible = true
                close.isVisible = false
                restorePreview()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("GuidePractice", "Unable to apply practiced configuration", e)
                if (!disposed) {
                    message.setText(R.string.quick_setup_save_failed)
                    recognized = false
                    awaitingWheel = false
                    practiceId = UUID.randomUUID().toString()
                    GuidePreviewSession.show(previewOwner, config, gesture, practiceId)
                }
            } finally {
                saving = false
                if (!disposed) {
                    close.isEnabled = true
                    dialog.setCancelable(true)
                }
            }
        }
    }
    val gestureJob = activity.lifecycleScope.launch {
        GuidePreviewSession.gestures.collect { result ->
            if (disposed || recognized || result.owner != previewOwner || result.practiceId != practiceId) return@collect
            if (config.gestures.getValue(gesture).type == QuickActionType.WHEEL && result.wheelItemIndex == null) {
                if (!awaitingWheel) {
                    awaitingWheel = true
                    message.setText(R.string.quick_practice_choose_wheel)
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
