package io.github.lumiagg.gestureevo.pin

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.view.DragEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.google.android.material.R as MaterialR
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.PinDragTargetsBinding
import io.github.lumiagg.gestureevo.drop.DragActionPreview
import io.github.lumiagg.gestureevo.drop.DropPreferences.EXTERNAL_DROP_POSITION_LEFT
import io.github.lumiagg.gestureevo.drop.DropPreferences.EXTERNAL_DROP_POSITION_RIGHT
import io.github.lumiagg.gestureevo.drop.styleDropTarget
import io.github.lumiagg.gestureevo.floating.BaseFloatingWindow
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal class PinDragTargets(
    context: Context,
    private val scope: CoroutineScope,
    position: String,
    windowType: Int,
    title: String,
    onCancel: () -> Unit = {},
    onRepin: (() -> Unit)? = null,
    onEnded: (accepted: Boolean) -> Unit,
) : BaseFloatingWindow(context, scope, "PinDragTargets") {
    private val binding = PinDragTargetsBinding.inflate(
        LayoutInflater.from(DynamicColorUtils.dynamicThemedContext(context))
    )
    private var highlighted: MaterialCardView? = null
    private var themeJob: Job? = null
    private val actionPreview = DragActionPreview(
        context = context,
        scope = scope,
        themedContext = DynamicColorUtils.dynamicThemedContext(context),
        position = position,
        insetSource = binding.root,
        title = "$title:Preview",
        windowType = windowType,
    )

    // Left and right match the physical screen edges named in the setting.
    @SuppressLint("RtlHardcoded")
    private val params = WindowManager.LayoutParams().apply {
        configureAccessibilityOverlay(
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            width = WindowManager.LayoutParams.WRAP_CONTENT,
            height = WindowManager.LayoutParams.WRAP_CONTENT,
            gravity = when (position) {
                EXTERNAL_DROP_POSITION_LEFT -> Gravity.LEFT or Gravity.CENTER_VERTICAL
                EXTERNAL_DROP_POSITION_RIGHT -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
                else -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            },
            allowDisplayCutout = false,
            title = title,
        )
        type = windowType
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setFitInsetsTypes(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            setFitInsetsIgnoringVisibility(true)
        }
    }

    init {
        val sidebar = position == EXTERNAL_DROP_POSITION_LEFT || position == EXTERNAL_DROP_POSITION_RIGHT
        binding.tools.orientation = if (sidebar) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        binding.cancelIcon.isVisible = sidebar
        binding.cancelLabel.isVisible = !sidebar
        binding.pinIcon.isVisible = sidebar
        binding.pinLabel.isVisible = !sidebar
        binding.pinTarget.isVisible = onRepin != null
        binding.pinTarget.updateLayoutParams<LinearLayout.LayoutParams> {
            val gap = (4 * context.resources.displayMetrics.density).roundToInt()
            if (sidebar) topMargin = gap else marginStart = gap
        }
        bindTarget(binding.cancelTarget, R.drawable.baseline_clear_24, onCancel, onEnded)
        if (onRepin != null) bindTarget(binding.pinTarget, R.drawable.baseline_push_pin_24, onRepin)
    }

    fun show(): Boolean {
        refreshTheme()
        if (!attachViewSafely(binding.root, params, shouldRetry = { false })) {
            hide()
            return false
        }
        actionPreview.attach { isAttachRequested(binding.root) }
        themeJob = scope.launch(Dispatchers.Main.immediate) {
            DynamicColorUtils.themeRevision.collect { refreshTheme() }
        }
        return true
    }

    fun hide() {
        themeJob?.cancel()
        themeJob = null
        highlighted = null
        actionPreview.detach()
        detachViewSafely(binding.root)
    }

    fun refreshTheme() {
        val onSurface = DynamicColorUtils.onSurfaceColor.takeIf { it != 0 }
            ?: MaterialColors.getColor(binding.panel, MaterialR.attr.colorOnSurface)
        val surface = DynamicColorUtils.surfaceContainerColor.takeIf { it != 0 }
            ?: MaterialColors.getColor(binding.panel, MaterialR.attr.colorSurfaceContainer)
        binding.panel.setCardBackgroundColor(surface)
        binding.panel.strokeColor = ColorUtils.setAlphaComponent(onSurface, 48)
        binding.cancelLabel.setTextColor(onSurface)
        binding.pinLabel.setTextColor(onSurface)
        val iconTint = ColorStateList.valueOf(onSurface)
        binding.cancelIcon.imageTintList = iconTint
        binding.pinIcon.imageTintList = iconTint
        styleDropTarget(binding.cancelTarget, highlighted === binding.cancelTarget)
        styleDropTarget(binding.pinTarget, highlighted === binding.pinTarget)
    }

    private fun bindTarget(
        card: MaterialCardView,
        icon: Int,
        onDrop: () -> Unit,
        onEnded: (Boolean) -> Unit = {},
    ) {
        card.setOnDragListener { _, event ->
            if (!isAttachRequested(binding.root)) return@setOnDragListener false
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED, DragEvent.ACTION_DRAG_LOCATION -> true
                DragEvent.ACTION_DRAG_ENTERED -> {
                    highlighted = card
                    styleDropTarget(card, true)
                    actionPreview.updatePosition()
                    actionPreview.show { it.setImageWithBackplate(icon) }
                    true
                }
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DROP, DragEvent.ACTION_DRAG_ENDED -> {
                    if (highlighted === card) {
                        highlighted = null
                        styleDropTarget(card, false)
                        actionPreview.hide()
                    }
                    when (event.action) {
                        DragEvent.ACTION_DROP -> onDrop()
                        DragEvent.ACTION_DRAG_ENDED -> onEnded(event.result)
                    }
                    true
                }
                else -> false
            }
        }
    }
}
