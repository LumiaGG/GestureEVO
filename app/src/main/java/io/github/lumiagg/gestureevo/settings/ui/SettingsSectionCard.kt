package io.github.lumiagg.gestureevo.settings.ui

import android.content.Context
import android.content.res.ColorStateList
import android.os.Parcel
import android.os.Parcelable
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionSet
import android.transition.TransitionManager
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.withStyledAttributes
import androidx.core.view.isGone
import com.google.android.material.card.MaterialCardView
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.dp2px

class SettingsSectionCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : MaterialCardView(context, attrs, defStyleAttr) {
    private val titleView: TextView
    private val summaryView: TextView
    private val expandIcon: ImageView
    private val contentContainer: LinearLayout
    private var internalAdd = false
    private var expanded = true
    private var expandable = true
    private val expandInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    init {
        radius = resources.getDimension(R.dimen.settings_card_corner_radius)
        strokeWidth = 0
        cardElevation = 0f
        maxCardElevation = 0f
        elevation = 0f
        translationZ = 0f
        stateListAnimator = null
        useCompatPadding = false
        preventCornerOverlap = false
        setCardBackgroundColor(DynamicColorUtils.surfaceContainerColor)
        isFocusable = true

        var title: CharSequence = ""
        var summary: CharSequence = ""
        context.withStyledAttributes(attrs, R.styleable.SettingsSectionCard) {
            title = getText(R.styleable.SettingsSectionCard_settingsCardTitle) ?: ""
            summary = getText(R.styleable.SettingsSectionCard_settingsCardSummary) ?: ""
            expanded = getBoolean(R.styleable.SettingsSectionCard_settingsCardExpanded, true)
            expandable = getBoolean(R.styleable.SettingsSectionCard_settingsCardExpandable, true)
        }

        val root = LinearLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            orientation = LinearLayout.VERTICAL
        }

        val header = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 56f.dp2px.toInt()
            orientation = LinearLayout.HORIZONTAL
            setPadding(16f.dp2px.toInt(), 12f.dp2px.toInt(), 16f.dp2px.toInt(), 12f.dp2px.toInt())
            isClickable = true
            isFocusable = true
            foreground = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let {
                val drawable = it.getDrawable(0)
                it.recycle()
                drawable
            }
            setOnClickListener {
                if (expandable) {
                    toggleExpanded()
                } else {
                    this@SettingsSectionCard.performClick()
                }
            }
        }

        val titleColumn = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            orientation = LinearLayout.VERTICAL
        }

        titleView = TextView(context).apply {
            text = title
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setTextColor(DynamicColorUtils.onSurfaceColor)
        }
        summaryView = TextView(context).apply {
            text = summary
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(DynamicColorUtils.onSurfaceVariantColor)
            isGone = summary.isBlank()
        }
        titleColumn.addView(titleView)
        titleColumn.addView(summaryView)

        expandIcon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(24f.dp2px.toInt(), 24f.dp2px.toInt()).apply {
                marginStart = 12f.dp2px.toInt()
            }
            setImageResource(R.drawable.ic_chevron_right)
            contentDescription = null
            imageTintList = ColorStateList.valueOf(DynamicColorUtils.onSurfaceVariantColor)
        }

        contentContainer = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
        }

        header.addView(titleColumn)
        header.addView(expandIcon)
        root.addView(header)
        root.addView(contentContainer)

        internalAdd = true
        super.addView(root)
        internalAdd = false
        expandIcon.isGone = !expandable
        applyExpandedState(animate = false)
    }

    fun setExpanded(expanded: Boolean) {
        if (!expandable) return
        if (this.expanded == expanded) return
        this.expanded = expanded
        applyExpandedState(animate = true)
    }

    fun isExpanded(): Boolean = expanded

    fun setTitle(title: CharSequence) {
        titleView.text = title
    }

    fun setSummary(summary: CharSequence?) {
        summaryView.text = summary ?: ""
        summaryView.isGone = summary.isNullOrBlank()
    }

    private fun toggleExpanded() {
        if (!expandable) return
        setExpanded(!expanded)
    }

    private fun applyExpandedState(animate: Boolean) {
        if (!expandable) {
            contentContainer.isGone = !expanded
            expandIcon.rotation = 0f
            return
        }

        val targetRotation = if (expanded) 90f else 0f
        val transitionRoot = findTransitionRoot()
        val shouldAnimate = animate && isLaidOut && transitionRoot.isLaidOut

        expandIcon.animate().cancel()
        if (shouldAnimate) {
            TransitionManager.beginDelayedTransition(transitionRoot, createExpandTransition())
            expandIcon.animate()
                .rotation(targetRotation)
                .setDuration(EXPAND_ICON_DURATION)
                .setInterpolator(expandInterpolator)
                .start()
        } else {
            expandIcon.rotation = targetRotation
        }

        contentContainer.isGone = !expanded
    }

    private fun findTransitionRoot(): ViewGroup {
        val directParent = parent as? ViewGroup
        return if (directParent == null || directParent is androidx.recyclerview.widget.RecyclerView) {
            this
        } else {
            directParent
        }
    }

    private fun createExpandTransition(): TransitionSet {
        return TransitionSet().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            duration = EXPAND_DURATION
            interpolator = expandInterpolator
            addTransition(ChangeBounds())
            addTransition(Fade(Fade.IN or Fade.OUT).apply { duration = CONTENT_FADE_DURATION })
        }
    }

    override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams) {
        if (internalAdd) {
            super.addView(child, index, params)
        } else {
            contentContainer.addView(child, index, params)
            child.post { hideDuplicatedHeaderText(child) }
        }
    }

    private fun hideDuplicatedHeaderText(root: View) {
        val duplicatedTexts = linkedSetOf(
            titleView.text?.toString().orEmpty(),
            summaryView.text?.toString().orEmpty()
        ).filterTo(LinkedHashSet()) { it.isNotBlank() }
        if (duplicatedTexts.isEmpty()) return

        fun visit(view: View) {
            if (duplicatedTexts.isEmpty()) return
            // Only remove legacy duplicate headings, never labels inside interactive controls.
            if (view is SettingsNavRow || view is SettingsSwitchRow ||
                view is android.widget.Button || view is android.widget.CompoundButton) return
            if (view is TextView) {
                val text = view.text?.toString().orEmpty()
                if (duplicatedTexts.remove(text)) {
                    view.visibility = GONE
                    return
                }
            }
            if (view is ViewGroup) {
                for (childIndex in 0 until view.childCount) {
                    visit(view.getChildAt(childIndex))
                    if (duplicatedTexts.isEmpty()) return
                }
            }
        }

        visit(root)
    }

    override fun onSaveInstanceState(): Parcelable {
        return SavedState(super.onSaveInstanceState()).also { it.expanded = expanded }
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is SavedState) {
            super.onRestoreInstanceState(state.superState)
            expanded = state.expanded
            applyExpandedState(animate = false)
        } else {
            super.onRestoreInstanceState(state)
        }
    }

    private class SavedState : BaseSavedState {
        var expanded: Boolean = true

        constructor(superState: Parcelable?) : super(superState)

        private constructor(source: Parcel) : super(source) {
            expanded = source.readInt() == 1
        }

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeInt(if (expanded) 1 else 0)
        }

        companion object CREATOR : Parcelable.Creator<SavedState> {
            override fun createFromParcel(source: Parcel): SavedState = SavedState(source)
            override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
        }
    }

    private companion object {
        const val EXPAND_DURATION = 220L
        const val CONTENT_FADE_DURATION = 120L
        const val EXPAND_ICON_DURATION = 180L
    }
}
