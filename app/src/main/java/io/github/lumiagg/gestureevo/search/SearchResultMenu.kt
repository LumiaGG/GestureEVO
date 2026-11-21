package io.github.lumiagg.gestureevo.search

import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.widget.ConstraintLayout
import io.github.lumiagg.gestureevo.ui.ActionTextMenuCard
import io.github.lumiagg.gestureevo.ui.ActionTextMenuItem
import io.github.lumiagg.gestureevo.utils.dp2px

/** A menu in the search window so opening it leaves the editable view and IME connected. */
internal class SearchResultMenu(
    private val activity: ComponentActivity,
    private val host: ConstraintLayout,
    cardBlurEnabled: Boolean,
    backgroundBlurEnabled: Boolean,
) : FrameLayout(activity) {
    private val margin = 8.dp2px.toInt()
    private val anchorLocation = IntArray(2)
    private val menuLocation = IntArray(2)
    private var anchor: View? = null
    private var observer: ViewTreeObserver? = null
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = dismiss()
    }
    private val positionListener = ViewTreeObserver.OnPreDrawListener {
        positionMenu()
        true
    }
    private val card = ActionTextMenuCard(activity).apply {
        this.cardBlurEnabled = cardBlurEnabled
        this.backgroundBlurEnabled = backgroundBlurEnabled
        emphasizeGlassSurface = true
        // The scroll viewport would cut a soft shadow off at a rectangular edge.
        showGlassShadow = false
        clipToOutline = true
        isClickable = true
        isFocusable = false
    }
    private val menu = ScrollView(activity).apply {
        isFocusable = false
        isVerticalScrollBarEnabled = false
        addView(card, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    init {
        setPadding(margin, margin, margin, margin)
        setOnClickListener { dismiss() }
        isFocusable = false
        addView(menu, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun show(anchorView: View, actions: List<ActionTextMenuItem>) {
        dismiss()
        if (actions.isEmpty() || !anchorView.isAttachedToWindow || !host.isAttachedToWindow) return
        anchor = anchorView
        card.setActions(actions, dismiss = ::dismiss)
        menu.scrollTo(0, 0)
        host.addView(this, ConstraintLayout.LayoutParams(0, 0).apply {
            leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
            rightToRight = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        })
        menu.alpha = 0f
        menu.animate().alpha(1f).setDuration(150L).start()
    }

    fun dismiss() {
        anchor = null
        menu.animate().cancel()
        if (parent === host) host.removeView(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        activity.onBackPressedDispatcher.addCallback(activity, backCallback)
        observer = viewTreeObserver.also { it.addOnPreDrawListener(positionListener) }
    }

    override fun onDetachedFromWindow() {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(positionListener)
        observer = null
        backCallback.remove()
        anchor = null
        menu.animate().cancel()
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) dismiss()
    }

    private fun positionMenu() {
        val currentAnchor = anchor ?: return
        if (!currentAnchor.isAttachedToWindow || !currentAnchor.isShown) {
            dismiss()
            return
        }
        currentAnchor.getLocationInWindow(anchorLocation)
        getLocationInWindow(menuLocation)
        val anchorRight = anchorLocation[0] - menuLocation[0] + currentAnchor.width
        val anchorCenterY = anchorLocation[1] - menuLocation[1] + currentAnchor.height / 2
        val maxX = (width - paddingRight - menu.width).coerceAtLeast(paddingLeft)
        val maxY = (height - paddingBottom - menu.height).coerceAtLeast(paddingTop)
        menu.x = (anchorRight - menu.width).coerceIn(paddingLeft, maxX).toFloat()
        menu.y = (anchorCenterY - menu.height / 2).coerceIn(paddingTop, maxY).toFloat()
    }
}
