package io.github.lumiagg.gestureevo.ui

import androidx.core.graphics.drawable.toDrawable

import androidx.core.view.isGone

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.GlassEffectUtils
import io.github.lumiagg.gestureevo.utils.dp2px

data class ActionTextMenuItem(
    val label: CharSequence,
    val destructive: Boolean = false,
    val icon: Drawable? = null,
    val onClick: () -> Unit
)

class ActionTextMenuCard(context: Context) : MaterialCardView(context) {
    private val menuMinWidth = 70.dp2px.toInt()
    private var themedContext = DynamicColorUtils.dynamicThemedContext(context)
    private var menuActions = emptyList<ActionTextMenuItem>()
    var cardBlurEnabled = false
    var backgroundBlurEnabled = false

    private val menuContainer = EqualWidthMenuContainer(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        minimumWidth = menuMinWidth
        setPadding(0, 4.dp2px.toInt(), 0, 4.dp2px.toInt())
    }

    init {
        radius = 12.dp2px
        cardElevation = 0f
        minimumWidth = menuMinWidth
        addView(menuContainer)
        refreshTheme()
    }

    fun setActions(actions: List<ActionTextMenuItem>, dismiss: () -> Unit) {
        menuActions = actions
        menuContainer.removeAllViews()
        actions.forEach { action ->
            menuContainer.addView(buildActionView(action, dismiss))
        }
        refreshTheme()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshTheme()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshTheme(newConfig)
    }

    fun refreshTheme(config: Configuration? = null) {
        val source = config?.let { context.createConfigurationContext(it) } ?: context
        themedContext = DynamicColorUtils.dynamicThemedContext(source)
        val surface = DynamicColorUtils.surfaceContainerHighColor.takeIf { it != 0 }
            ?: resolveThemeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh)
        val onSurface = DynamicColorUtils.onSurfaceColor.takeIf { it != 0 }
            ?: resolveThemeColor(com.google.android.material.R.attr.colorOnSurface)
        val isGlass = cardBlurEnabled || backgroundBlurEnabled
        GlassEffectUtils.applyCardStyle(
            card = this,
            color = surface,
            glassEnabled = isGlass,
            clearForeground = true,
            blurRegion = isGlass,
            backgroundBlurEnabled = false,
            overlayOnContent = true,
        )
        GlassEffectUtils.applyFixedGlassChrome(this)
        menuActions.forEachIndexed { index, action ->
            val row = menuContainer.getChildAt(index) ?: return@forEachIndexed
            rowLabel(row)?.setTextColor(
                if (action.destructive) resolveThemeColor(androidx.appcompat.R.attr.colorError)
                else onSurface
            )
            row.background = ContextCompat.getDrawable(themedContext, resolveSelectableBackground())
        }
    }

    private fun resolveThemeColor(attr: Int): Int = MaterialColors.getColor(themedContext, attr, 0)

    private fun buildActionView(action: ActionTextMenuItem, dismiss: () -> Unit): View {
        val onSelect = View.OnClickListener {
            dismiss()
            action.onClick()
        }
        val icon = action.icon
        if (icon == null) {
            return TextView(themedContext).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    44.dp2px.toInt()
                )
                text = action.label
                gravity = Gravity.CENTER
                minWidth = menuMinWidth
                setPadding(16.dp2px.toInt(), 0, 16.dp2px.toInt(), 0)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setSingleLine()
                setOnClickListener(onSelect)
            }
        }
        val label = TextView(themedContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            text = action.label
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        return LinearLayout(themedContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                44.dp2px.toInt()
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumWidth = menuMinWidth
            setPadding(16.dp2px.toInt(), 0, 16.dp2px.toInt(), 0)
            addView(ImageView(themedContext).apply {
                layoutParams = LinearLayout.LayoutParams(20.dp2px.toInt(), 20.dp2px.toInt()).apply {
                    marginEnd = 10.dp2px.toInt()
                }
                setImageDrawable(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            })
            addView(label)
            setOnClickListener(onSelect)
        }
    }

    private fun rowLabel(row: View): TextView? {
        if (row is TextView) return row
        val group = row as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            if (child is TextView) return child
        }
        return null
    }

    private fun resolveSelectableBackground(): Int {
        val typedValue = TypedValue()
        themedContext.theme.resolveAttribute(
            android.R.attr.selectableItemBackground,
            typedValue,
            true
        )
        return typedValue.resourceId
    }

}

private class EqualWidthMenuContainer(context: Context) : LinearLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val availableChildWidth = measuredWidth - paddingLeft - paddingRight
        if (availableChildWidth <= 0) return

        var needsSecondPass = false
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility != View.GONE && child.measuredWidth != availableChildWidth) {
                needsSecondPass = true
                break
            }
        }
        if (!needsSecondPass) return

        val childWidthSpec = MeasureSpec.makeMeasureSpec(availableChildWidth, MeasureSpec.EXACTLY)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.isGone) continue
            val childHeightSpec = MeasureSpec.makeMeasureSpec(child.measuredHeight, MeasureSpec.EXACTLY)
            child.measure(childWidthSpec, childHeightSpec)
        }
    }
}

class ActionTextMenuPopup(
    private val context: Context,
    private val hostView: View,
    private val minWidth: Int = 70.dp2px.toInt(),
    private val cardBlurEnabled: Boolean = false,
    private val backgroundBlurEnabled: Boolean = false,
) {
    private var popupWindow: PopupWindow? = null
    private var activeMenuCard: ActionTextMenuCard? = null
    private var activeOverlay: FrameLayout? = null
    private var imeBottomAtShow = Int.MIN_VALUE
    private var windowFocusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null
    private var backCallback: OnBackPressedCallback? = null
    private var isShown = false

    fun show(anchorView: View, actions: List<ActionTextMenuItem>) {
        if (actions.isEmpty()) {
            dismiss()
            return
        }
        val parentView = hostView.rootView ?: hostView
        val targetToken = parentView.windowToken ?: anchorView.windowToken
        if (targetToken == null) {
            return
        }
        dismissImmediate()

        val menuCard = ActionTextMenuCard(context).apply {
            cardBlurEnabled = this@ActionTextMenuPopup.cardBlurEnabled
            backgroundBlurEnabled = this@ActionTextMenuPopup.backgroundBlurEnabled
            isClickable = true
            isFocusable = false
        }
        menuCard.setActions(actions) { dismiss() }

        menuCard.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val cardWidth = menuCard.measuredWidth.coerceAtLeast(minWidth)
        val cardHeight = menuCard.measuredHeight

        val anchorLocation = IntArray(2)
        anchorView.getLocationOnScreen(anchorLocation)

        val hostLocation = IntArray(2)
        parentView.getLocationOnScreen(hostLocation)

        val visibleFrame = Rect()
        hostView.getWindowVisibleDisplayFrame(visibleFrame)
        if (visibleFrame.width() <= 0 || visibleFrame.height() <= 0) {
            visibleFrame.set(
                0,
                0,
                context.resources.displayMetrics.widthPixels,
                context.resources.displayMetrics.heightPixels
            )
        }

        val margin = 8.dp2px.toInt()
        val minX = visibleFrame.left + margin - hostLocation[0]
        val maxX = (visibleFrame.right - margin - cardWidth - hostLocation[0]).coerceAtLeast(minX)
        val anchorRight = anchorLocation[0] - hostLocation[0] + anchorView.width
        val cardX = (anchorRight - cardWidth).coerceIn(minX, maxX)

        val minY = visibleFrame.top + margin - hostLocation[1]
        val maxY = (visibleFrame.bottom - margin - cardHeight - hostLocation[1]).coerceAtLeast(minY)
        val anchorCenterY = anchorLocation[1] - hostLocation[1] + anchorView.height / 2
        val cardY = (anchorCenterY - cardHeight / 2).coerceIn(minY, maxY)

        val overlay = FrameLayout(context).apply {
            isClickable = true
            isFocusable = false
            clipChildren = false
            clipToPadding = false
            setOnClickListener { dismiss() }
            addView(
                menuCard,
                FrameLayout.LayoutParams(cardWidth, cardHeight).apply {
                    leftMargin = cardX
                    topMargin = cardY
                }
            )
        }

        GlassEffectUtils.applyFixedGlassChrome(menuCard)

        menuCard.alpha = 0.18f
        menuCard.scaleX = 0.95f
        menuCard.scaleY = 0.95f
        menuCard.pivotX = (cardWidth - margin).toFloat().coerceIn(0f, cardWidth.toFloat())
        menuCard.pivotY = (cardHeight / 2f).coerceIn(0f, cardHeight.toFloat())

        val popup = PopupWindow(
            overlay,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            false
        ).apply {
            isOutsideTouchable = true
            isFocusable = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setIsClippedToScreen(false)
            }
            elevation = 0f
            setOnDismissListener {
                dismissImmediate()
            }
        }

        popupWindow = popup
        activeMenuCard = menuCard
        activeOverlay = overlay

        attachDismissGuards(overlay)

        val shown = runCatching {
            popup.showAtLocation(parentView, Gravity.NO_GRAVITY, hostLocation[0], hostLocation[1])
            true
        }.getOrDefault(false)

        if (!shown) {
            dismissImmediate()
            return
        }

        isShown = true

        menuCard.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(MENU_FADE_MS)
            .start()
    }

    fun dismiss() {
        val popup = popupWindow ?: return
        val card = activeMenuCard
        val overlay = activeOverlay
        isShown = false
        popupWindow = null
        activeMenuCard = null
        activeOverlay = null
        overlay?.let { detachDismissGuards(it) }

        if (card != null && card.isAttachedToWindow) {
            card.animate().cancel()
            card.animate()
                .alpha(0f)
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(MENU_FADE_MS)
                .withEndAction {
                    runCatching { popup.dismiss() }
                }
                .start()
        } else {
            runCatching { popup.dismiss() }
        }
    }

    private fun dismissImmediate() {
        isShown = false
        val popup = popupWindow
        popupWindow = null
        activeMenuCard?.animate()?.cancel()
        activeMenuCard = null
        val overlay = activeOverlay
        activeOverlay = null
        overlay?.let { detachDismissGuards(it) }
        runCatching { popup?.dismiss() }
    }

    private fun attachDismissGuards(overlay: FrameLayout) {
        imeBottomAtShow = Int.MIN_VALUE
        ViewCompat.setOnApplyWindowInsetsListener(overlay) { _, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            if (imeBottomAtShow == Int.MIN_VALUE) {
                imeBottomAtShow = imeBottom
            } else if (isShown && imeBottom != imeBottomAtShow) {
                dismiss()
            }
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            overlay,
            object : WindowInsetsAnimationCompat.Callback(
                WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
            ) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    if (isShown && animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                        dismiss()
                    }
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat = insets
            }
        )
        ViewCompat.requestApplyInsets(overlay)

        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (!hasFocus) dismiss()
        }
        overlay.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        windowFocusListener = focusListener

        val activity = (context as? ComponentActivity)
            ?: (hostView.context as? ComponentActivity)
        if (activity != null) {
            val callback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    dismiss()
                }
            }
            activity.onBackPressedDispatcher.addCallback(callback)
            backCallback = callback
        }
    }

    private fun detachDismissGuards(overlay: FrameLayout) {
        ViewCompat.setOnApplyWindowInsetsListener(overlay, null)
        ViewCompat.setWindowInsetsAnimationCallback(overlay, null)
        windowFocusListener?.let { listener ->
            if (overlay.viewTreeObserver.isAlive) {
                overlay.viewTreeObserver.removeOnWindowFocusChangeListener(listener)
            }
        }
        windowFocusListener = null
        backCallback?.remove()
        backCallback = null
        imeBottomAtShow = Int.MIN_VALUE
    }

    private companion object {
        const val MENU_FADE_MS = 150L
    }
}
