package io.github.lumiagg.gestureevo.settings.guide

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textview.MaterialTextView
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.GuideGestureSetupBinding
import io.github.lumiagg.gestureevo.databinding.GuideWheelSetupBinding
import io.github.lumiagg.gestureevo.utils.CurrentPackageProvider
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.dp2px
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToInt

internal class QuickSetupController(
    private val activity: AppCompatActivity,
    private val gestureBinding: GuideGestureSetupBinding,
    private val wheelBinding: GuideWheelSetupBinding,
    savedInstanceState: Bundle?,
    private val onChanged: () -> Unit
) {
    private var config = QuickSetupConfig.default()
    private var savedConfig: QuickSetupConfig? = null
    private var loading = true
    private var updatingViews = false
    private val practiced = mutableSetOf<QuickGesture>()
    private val previewOwner = UUID.randomUUID().toString()
    private var previewActive = false
    private var previewFinished = false
    private var wheelMode = GuideWheelMode.HIDDEN
    private var wheelInputEnabled = true
    private var wheelActionDialog: Dialog? = null

    val hasUnsavedChanges: Boolean get() = !loading && config != savedConfig
    val isSaved: Boolean get() = !loading && savedConfig != null && !hasUnsavedChanges

    init {
        gestureBinding.positionSlider.valueTo = 90f
        gestureBinding.heightSlider.valueTo = 200f

        gestureBinding.sideGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked && !updatingViews) update(config.copy(side = when (id) {
                R.id.sideLeft -> QuickSetupSide.LEFT
                R.id.sideBoth -> QuickSetupSide.BOTH
                else -> QuickSetupSide.RIGHT
            }))
        }
        gestureBinding.positionSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) update(config.copy(verticalPercent = value.roundToInt()), rebuild = false)
        }
        gestureBinding.heightSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) update(config.copy(triggerHeightDp = value.roundToInt()), rebuild = false)
        }

        gestureBinding.positionSlider.setLabelFormatter { activity.getString(R.string.display_percent, it.roundToInt()) }
        gestureBinding.heightSlider.setLabelFormatter { activity.getString(R.string.quick_setup_dp, it.roundToInt()) }

        listOf(gestureBinding.positionSlider, gestureBinding.heightSlider)
            .forEach { slider ->
                slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) {
                        wheelMode = GuideWheelMode.PREVIEW
                        refreshPreview()
                    }
                    override fun onStopTrackingTouch(slider: Slider) {
                        wheelMode = GuideWheelMode.HIDDEN
                        refreshPreview()
                    }
                })
            }

        wheelBinding.resetDefaultsButton.setOnClickListener {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.quick_setup_reset)
                .setMessage(R.string.quick_setup_reset_message)
                .setPositiveButton(android.R.string.ok) { _, _ -> update(QuickSetupConfig.default()) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        gestureBinding.applyQuickSetupButton.setText(R.string.quick_practice_start)
        gestureBinding.applyQuickSetupButton.setIconResource(R.drawable.baseline_gesture_24)
        gestureBinding.applyQuickSetupButton.setOnClickListener { practiceSequence(0) }

        wheelBinding.addWheelItemButton.setOnClickListener {
            chooseWheelAction { selected ->
                if (config.wheelItems.size < QuickSetupConfig.MAX_WHEEL_ITEMS) {
                    update(config.copy(wheelItems = config.wheelItems + selected))
                }
            }
        }

        activity.lifecycleScope.launch {
            try {
                savedConfig = QuickSetupRepository.load(activity)
                config = savedInstanceState?.getString(KEY_DRAFT)?.let(QuickSetupConfig::fromJson)
                    ?: savedConfig ?: QuickSetupConfig.default()
                savedInstanceState?.getStringArrayList(KEY_PRACTICED)?.forEach { name ->
                    QuickGesture.entries.find { it.name == name }?.let(practiced::add)
                }
                loading = false
                render()
                onChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("QuickSetup", "Unable to load quick setup", e)
                wheelBinding.quickSetupMessage.setText(R.string.guide_load_failed)
            }
        }
        render()
    }

    fun saveState(outState: Bundle) {
        outState.putString(KEY_DRAFT, config.toJson())
        outState.putStringArrayList(KEY_PRACTICED, ArrayList(practiced.map { it.name }))
    }

    fun resumePreview() {
        previewFinished = false
        previewActive = true
        refreshPreview()
    }

    fun suspendPreview() {
        previewActive = false
        GuidePreviewSession.clear(previewOwner)
    }

    fun finishPreview() {
        previewFinished = true
        wheelActionDialog?.dismiss()
        suspendPreview()
    }

    fun refreshPreview() {
        if (loading || !previewActive || previewFinished) return
        val current = GuidePreviewSession.current.value
        GuidePreviewSession.show(
            previewOwner, config,
            current?.takeIf { it.owner == previewOwner }?.practice,
            current?.takeIf { it.owner == previewOwner }?.practiceId,
            current?.takeIf { it.owner == previewOwner }?.wheelSelection == true,
            wheelMode, wheelInputEnabled
        )
    }

    private fun update(value: QuickSetupConfig, rebuild: Boolean = true) {
        if (loading || config == value) return
        config = value
        practiced.clear()
        if (rebuild) render() else renderState()
        refreshPreview()
        onChanged()
    }

    private fun render() {
        updatingViews = true
        gestureBinding.sideGroup.check(when (config.side) {
            QuickSetupSide.LEFT -> R.id.sideLeft
            QuickSetupSide.BOTH -> R.id.sideBoth
            QuickSetupSide.RIGHT -> R.id.sideRight
        })
        gestureBinding.positionSlider.value = config.verticalPercent.toFloat()
        gestureBinding.heightSlider.value = config.triggerHeightDp.toFloat()
        updatingViews = false
        renderGestures()
        renderWheelItems()
        renderState()
        refreshPreview()
    }

    private fun renderState() {
        gestureBinding.positionValue.text = activity.getString(R.string.quick_setup_position_value, config.verticalPercent)
        gestureBinding.heightValue.text = activity.getString(R.string.quick_setup_dp, config.triggerHeightDp)
        wheelBinding.quickSetupMessage.setText(when {
            loading -> R.string.guide_loading
            hasUnsavedChanges -> R.string.quick_setup_unsaved
            else -> R.string.quick_setup_saved
        })
        gestureBinding.applyQuickSetupButton.isEnabled = !loading
        wheelBinding.addWheelItemButton.isEnabled = !loading && config.wheelItems.size < QuickSetupConfig.MAX_WHEEL_ITEMS
        wheelBinding.resetDefaultsButton.isEnabled = !loading
        gestureBinding.sideGroup.isEnabled = !loading
        for (index in 0 until gestureBinding.sideGroup.childCount) gestureBinding.sideGroup.getChildAt(index).isEnabled = !loading
        gestureBinding.positionSlider.isEnabled = !loading
        gestureBinding.heightSlider.isEnabled = !loading
    }

    private fun renderGestures() {
        gestureBinding.gestureRows.removeAllViews()
        QuickGesture.entries.forEach { gesture ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_settings_tile)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                isClickable = true
                isFocusable = true
                contentDescription = activity.getString(R.string.quick_practice_for, gesture.label(activity))
                setOnClickListener { practice(gesture) }
            }
            val demo = GuideGestureDemoView(activity).apply {
                show(gesture, config.side)
                contentDescription = activity.getString(R.string.quick_practice_for, gesture.label(activity))
                isClickable = true
                isFocusable = true
                setOnClickListener { practice(gesture) }
                TooltipCompat.setTooltipText(this, contentDescription)
            }
            row.addView(demo, LinearLayout.LayoutParams(dp(56), dp(88)))

            val text = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(4), dp(4), dp(4))
            }
            val action = config.gestures.getValue(gesture)
            text.addView(label(action.label(activity), title = true))
            text.addView(label(gesture.label(activity)))
            if (gesture in practiced) {
                text.addView(label(activity.getString(R.string.quick_practice_saved)).apply {
                    (this as? TextView)?.setTextColor(DynamicColorUtils.primaryColor)
                })
            }
            row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))

            // Action change button
            row.addView(iconButton(
                R.drawable.baseline_edit_24,
                activity.getString(R.string.quick_setup_choose_for, gesture.label(activity))
            ) {
                chooseAction(wheel = false, currentAction = action) { selected ->
                    update(config.copy(gestures = config.gestures + (gesture to selected)))
                }
            })

            // Practice / Test button
            row.addView(iconButton(
                R.drawable.baseline_gesture_24,
                activity.getString(R.string.quick_practice_for, gesture.label(activity))
            ) {
                practice(gesture)
            })

            gestureBinding.gestureRows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun renderWheelItems() {
        wheelBinding.wheelSampleView.setConfig(
            config = config,
            onEditItem = { index ->
                if (index in config.wheelItems.indices) {
                    chooseWheelAction(currentAction = config.wheelItems[index]) { action ->
                        val newItems = config.wheelItems.toMutableList()
                        newItems[index] = action
                        update(config.copy(wheelItems = newItems))
                    }
                }
            },
            onRemoveItem = { index ->
                removeWheelItem(index)
            }
        )

        wheelBinding.wheelItemsContainer.removeAllViews()
        config.wheelItems.forEachIndexed { index, action ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_settings_tile)
                setPadding(dp(12), dp(8), dp(8), dp(8))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    chooseWheelAction(currentAction = action) { selected ->
                        val newItems = config.wheelItems.toMutableList()
                        newItems[index] = selected
                        update(config.copy(wheelItems = newItems))
                    }
                }
            }

            // Index badge
            val idxView = TextView(activity).apply {
                text = "${index + 1}"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                setTextColor(DynamicColorUtils.primaryColor)
                setPadding(dp(4), 0, dp(8), 0)
            }
            row.addView(idxView)

            // Icon badge
            val iconBadge = ImageView(activity).apply {
                val size = dp(36)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = dp(10)
                }
                val pad = dp(6)
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(DynamicColorUtils.secondaryContainerColor)
                }
                setImageDrawable(action.icon(activity))
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (action.type != QuickActionType.OPEN_APP || action.packageName == null) {
                    imageTintList = ColorStateList.valueOf(DynamicColorUtils.primaryColor)
                }
            }
            row.addView(iconBadge)

            // Text
            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }
            textCol.addView(label(action.label(activity), title = true))
            textCol.addView(label(action.description(activity)))
            row.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))

            // Delete item button (only if > 2 items)
            if (config.wheelItems.size > 2) {
                row.addView(iconButton(
                    R.drawable.baseline_clear_24,
                    activity.getString(R.string.quick_setup_remove)
                ) {
                    removeWheelItem(index)
                })
            }

            wheelBinding.wheelItemsContainer.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        wheelBinding.addWheelItemButton.isVisible = config.wheelItems.size < QuickSetupConfig.MAX_WHEEL_ITEMS
    }

    private fun chooseWheelAction(currentAction: QuickAction? = null, selected: (QuickAction) -> Unit) {
        chooseAction(wheel = true, currentAction = currentAction, onFinished = {}, selected = {
            selected(it)
        })
    }

    private fun removeWheelItem(index: Int) {
        if (index !in config.wheelItems.indices) return
        if (config.wheelItems.size <= 2) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.quick_setup_minimum_items)
                .setMessage(R.string.quick_setup_minimum_items)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.action_view_item_menu_delete)
            .setMessage(config.wheelItems[index].label(activity))
            .setPositiveButton(R.string.action_view_item_menu_delete) { _, _ ->
                update(config.copy(wheelItems = config.wheelItems.filterIndexed { i, _ -> i != index }))
            }
            .setNegativeButton(android.R.string.cancel, null)
        wheelActionDialog = dialog.setOnDismissListener {
            wheelActionDialog = null
        }.show()
    }

    private fun chooseAction(
        wheel: Boolean,
        currentAction: QuickAction? = null,
        onFinished: () -> Unit = {},
        selected: (QuickAction) -> Unit
    ) {
        if (loading) return
        showGuideActionSelectBottomSheet(
            activity = activity,
            allowWheel = !wheel,
            allowNone = !wheel,
            currentAction = currentAction,
            onActionSelected = { chosen ->
                selected(chosen)
                onFinished()
            }
        )
    }

    private fun practiceSequence(index: Int) {
        val gestures = QuickGesture.entries.filter { config.gestures.getValue(it).type != QuickActionType.NONE }
        if (index >= gestures.size) return
        val next: (() -> Unit)? = if (index + 1 < gestures.size) ({ practiceSequence(index + 1) }) else null
        practice(gestures[index], next)
    }

    private fun practice(gesture: QuickGesture, next: (() -> Unit)? = null) {
        if (loading) return
        if (CurrentPackageProvider.accessibilityService() == null) {
            wheelBinding.quickSetupMessage.setText(R.string.quick_setup_preview_permission)
            onChanged()
            return
        }
        try {
            config.validate(Build.VERSION.SDK_INT)
        } catch (_: IllegalArgumentException) {
            wheelBinding.quickSetupMessage.setText(R.string.quick_setup_invalid)
            return
        }
        val draft = config
        showGuidePractice(activity, gesture, draft, previewOwner, onRecognized = {
            QuickSetupRepository.apply(activity, draft)
            savedConfig = draft
            practiced.add(gesture)
            render()
            onChanged()
        }, onNext = next)
    }

    private fun iconButton(icon: Int, description: String, click: () -> Unit): View =
        AppCompatImageButton(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setImageResource(icon)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            imageTintList = ColorStateList.valueOf(DynamicColorUtils.primaryColor)
            contentDescription = description
            TooltipCompat.setTooltipText(this, description)
            val background = android.util.TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true)
            setBackgroundResource(background.resourceId)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { click() }
        }

    private fun label(text: String, title: Boolean = false): View = MaterialTextView(activity).apply {
        this.text = text
        setTextAppearance(if (title) com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
            else com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        setTextColor(if (title) DynamicColorUtils.onSurfaceColor else DynamicColorUtils.onSurfaceVariantColor)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun dp(value: Int): Int = value.toFloat().dp2px.toInt()

    private companion object {
        const val KEY_DRAFT = "quick_setup_draft"
        const val KEY_PRACTICED = "quick_setup_practiced"
    }
}
