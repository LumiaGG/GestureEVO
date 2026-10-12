package io.github.lumiagg.gestureevo.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.transition.ChangeBounds
import android.transition.ChangeClipBounds
import android.transition.ChangeTransform
import android.transition.Transition
import android.transition.TransitionSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.view.children
import androidx.core.widget.NestedScrollView
import io.github.lumiagg.gestureevo.settings.ui.SettingsSectionCard

object SettingsSearchNavigator {
    const val EXTRA_TARGET_VIEW_ID = "settings_search_target_view_id"
    const val EXTRA_TARGET_PREFERENCE_KEY = "settings_search_target_preference_key"

    fun buildIntent(context: Context, entry: SettingsSearchEntry): Intent {
        return Intent(context, entry.targetActivity).apply {
            if (entry.targetViewId != View.NO_ID) {
                putExtra(EXTRA_TARGET_VIEW_ID, entry.targetViewId)
            }
            entry.targetPreferenceKey?.let {
                putExtra(EXTRA_TARGET_PREFERENCE_KEY, it)
            }
            if (entry.targetActivity == SettingsActivity::class.java) {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }
    }

    fun focusAnchorIfNeeded(root: View, intent: Intent?) {
        val targetViewId = intent?.getIntExtra(EXTRA_TARGET_VIEW_ID, View.NO_ID) ?: View.NO_ID
        if (targetViewId == View.NO_ID) return

        intent?.removeExtra(EXTRA_TARGET_VIEW_ID)
        root.post {
            val targetView = root.findViewById<View>(targetViewId) ?: return@post
            (targetView as? SettingsSectionCard)?.setExpanded(true)
            val scrollView = findNestedScrollView(root)
            if (scrollView != null) {
                val rect = Rect()
                targetView.getDrawingRect(rect)
                scrollView.offsetDescendantRectToMyCoords(targetView, rect)
                val topInset = (root.resources.displayMetrics.density * 16).toInt()
                scrollView.smoothScrollTo(0, (rect.top - topInset).coerceAtLeast(0))
            }
            targetView.requestFocus()
            pulseHighlight(targetView)
        }
    }

    fun buildSharedElementTransition(activity: Activity): Transition? {
        return TransitionSet().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            duration = 260L
            interpolator = AccelerateDecelerateInterpolator()
            addTransition(ChangeBounds())
            addTransition(ChangeTransform())
            addTransition(ChangeClipBounds())
        }
    }

    private fun pulseHighlight(targetView: View) {
        targetView.animate().cancel()
        targetView.alpha = 0.68f
        targetView.animate()
            .alpha(1f)
            .setDuration(650L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun findNestedScrollView(root: View): NestedScrollView? {
        if (root is NestedScrollView) return root
        if (root is ViewGroup) {
            root.children.forEach { child ->
                val nestedScrollView = findNestedScrollView(child)
                if (nestedScrollView != null) return nestedScrollView
            }
        }
        return null
    }
}
