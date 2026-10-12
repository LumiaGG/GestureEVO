package io.github.lumiagg.gestureevo.gesture.ui

import android.graphics.Rect
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import io.github.lumiagg.gestureevo.ui.ActionTextMenuCard
import io.github.lumiagg.gestureevo.ui.ActionTextMenuItem
import io.github.lumiagg.gestureevo.utils.dp2px

internal class ActionItemMenuOverlay(
    private val hostView: ConstraintLayout,
    private val onMenuShown: (() -> Unit)? = null,
    private val onMenuHidden: (() -> Unit)? = null
) {
    private val menuBounds = Rect()
    private val menuCard = ActionTextMenuCard(hostView.context).apply {
        visibility = View.GONE
        isClickable = true
        isFocusable = true
        alpha = 0f
    }

    init {
        hostView.addView(
            menuCard,
            ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    val isShowing: Boolean
        get() = menuCard.isVisible

    fun show(anchorView: View, actions: List<ActionTextMenuItem>) {
        if (actions.isEmpty()) {
            hide()
            return
        }
        menuCard.animate().cancel()
        menuCard.setActions(actions, dismiss = ::hide)
        onMenuShown?.invoke()
        menuCard.visibility = View.INVISIBLE
        keepMenuOnTop()
        menuCard.doOnLayout {
            positionNextToAnchor(anchorView)
            keepMenuOnTop()
            menuCard.alpha = 0f
            menuCard.visibility = View.VISIBLE
            menuCard.animate().alpha(1f).setDuration(150).start()
        }
        menuCard.requestLayout()
    }

    fun refreshTheme() {
        menuCard.refreshTheme()
        keepMenuOnTop()
    }

    fun hide() {
        if (!menuCard.isVisible && menuCard.visibility != View.INVISIBLE) return
        menuCard.animate().cancel()
        if (menuCard.visibility != View.VISIBLE) {
            finishHide()
            return
        }
        menuCard.animate()
            .alpha(0f)
            .setDuration(150)
            .withEndAction { finishHide() }
            .start()
    }

    private fun finishHide() {
        menuCard.visibility = View.GONE
        menuCard.alpha = 0f
        onMenuHidden?.invoke()
    }

    fun dismissIfOutside(x: Float, y: Float): Boolean {
        if (!isShowing) return false
        if (contains(x, y)) return true
        hide()
        return false
    }

    private fun contains(x: Float, y: Float): Boolean {
        menuBounds.set(
            menuCard.x.toInt(),
            menuCard.y.toInt(),
            (menuCard.x + menuCard.width).toInt(),
            (menuCard.y + menuCard.height).toInt()
        )
        return menuBounds.contains(x.toInt(), y.toInt())
    }

    private fun keepMenuOnTop() {
        val parent = hostView
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (child.tag === menuCard) {
                child.elevation = 999f
                child.translationZ = 999f
                parent.bringChildToFront(child)
            }
        }
        menuCard.elevation = 1000f
        menuCard.translationZ = 1000f
        parent.bringChildToFront(menuCard)
    }

    private fun positionNextToAnchor(anchorView: View) {
        val margin = 12.dp2px
        val menuWidth = menuCard.measuredWidth.toFloat()
        val menuHeight = menuCard.measuredHeight.toFloat()
        val preferRight = anchorView.x + anchorView.width / 2f < hostView.width / 2f

        var targetX = if (preferRight) {
            anchorView.x + anchorView.width + margin
        } else {
            anchorView.x - menuWidth - margin
        }
        var targetY = anchorView.y + (anchorView.height - menuHeight) / 2f

        targetX = targetX.coerceIn(8.dp2px, (hostView.width - menuWidth - 8.dp2px).coerceAtLeast(8.dp2px))
        targetY = targetY.coerceIn(8.dp2px, (hostView.height - menuHeight - 8.dp2px).coerceAtLeast(8.dp2px))

        menuCard.x = targetX
        menuCard.y = targetY
    }

}
