package io.github.lumiagg.gestureevo.settings.guide

import android.content.res.ColorStateList
import android.app.Dialog
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.GuideQuickSetupBinding
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.settings.ui.DefaultSelectAPPBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.SelectAPPCallBack
import io.github.lumiagg.gestureevo.utils.CurrentPackageProvider
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.app.syncAppCatalog
import io.github.lumiagg.gestureevo.utils.dp2px
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt
import java.util.UUID

internal class QuickSetupController(
    private val activity: AppCompatActivity,
    private val binding: GuideQuickSetupBinding,
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
    private var wheelEditor: Dialog? = null
    private var wheelEditorSession = 0
    private var wheelPickerJob: Job? = null
    private var wheelPicker: DefaultSelectAPPBottomSheet? = null
    private var wheelActionDialog: Dialog? = null
    val hasUnsavedChanges: Boolean get() = !loading && config != savedConfig
    val isSaved: Boolean get() = !loading && savedConfig != null && !hasUnsavedChanges

    init {
        binding.positionSlider.valueTo = 90f
        binding.heightSlider.valueTo = 200f
        binding.sideGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked && !updatingViews) update(config.copy(side = when (id) {
                R.id.sideLeft -> QuickSetupSide.LEFT
                R.id.sideBoth -> QuickSetupSide.BOTH
                else -> QuickSetupSide.RIGHT
            }))
        }
        binding.positionSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) update(config.copy(verticalPercent = value.roundToInt()), rebuild = false)
        }
        binding.heightSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) update(config.copy(triggerHeightDp = value.roundToInt()), rebuild = false)
        }
        binding.wheelInsetSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) update(config.copy(wheelOffsetXDp = value.roundToInt()), rebuild = false)
        }
        binding.wheelRadiusSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) update(config.copy(wheelRadiusDp = value.roundToInt()), rebuild = false)
        }
        binding.positionSlider.setLabelFormatter { activity.getString(R.string.display_percent, it.roundToInt()) }
        binding.heightSlider.setLabelFormatter { activity.getString(R.string.quick_setup_dp, it.roundToInt()) }
        binding.wheelInsetSlider.setLabelFormatter { activity.getString(R.string.quick_setup_dp, it.roundToInt()) }
        binding.wheelRadiusSlider.setLabelFormatter { activity.getString(R.string.quick_setup_dp, it.roundToInt()) }
        listOf(binding.positionSlider, binding.heightSlider, binding.wheelInsetSlider, binding.wheelRadiusSlider)
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
        binding.resetDefaultsButton.setOnClickListener {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.quick_setup_reset)
                .setMessage(R.string.quick_setup_reset_message)
                .setPositiveButton(android.R.string.ok) { _, _ -> update(QuickSetupConfig.default()) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        binding.applyQuickSetupButton.setText(R.string.quick_practice_start)
        binding.applyQuickSetupButton.setIconResource(R.drawable.baseline_gesture_24)
        binding.applyQuickSetupButton.setOnClickListener { practiceSequence(0) }
        binding.editWheelButton.setOnClickListener { editWheel() }
        activity.lifecycleScope.launch {
            GuidePreviewSession.wheelEdits.collect { edit ->
                if (edit.owner != previewOwner || edit.config != config || wheelEditor?.isShowing != true) return@collect
                when (edit.action) {
                    GuideWheelEditAction.EDIT -> editWheelItem(edit.itemIndex)
                    GuideWheelEditAction.REMOVE -> removeWheelItem(edit.itemIndex)
                    GuideWheelEditAction.ADD -> chooseWheelAction { update(config.copy(wheelItems = config.wheelItems + it)) }
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
                binding.quickSetupMessage.setText(R.string.guide_load_failed)
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
        wheelEditor?.dismiss()
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
        binding.sideGroup.check(when (config.side) {
            QuickSetupSide.LEFT -> R.id.sideLeft
            QuickSetupSide.BOTH -> R.id.sideBoth
            QuickSetupSide.RIGHT -> R.id.sideRight
        })
        binding.positionSlider.value = config.verticalPercent.toFloat()
        binding.heightSlider.value = config.triggerHeightDp.toFloat()
        binding.wheelInsetSlider.value = config.wheelOffsetXDp.toFloat()
        binding.wheelRadiusSlider.value = config.wheelRadiusDp.toFloat()
        updatingViews = false
        renderGestures()
        renderState()
        refreshPreview()
    }

    private fun renderState() {
        binding.positionValue.text = activity.getString(R.string.quick_setup_position_value, config.verticalPercent)
        binding.heightValue.text = activity.getString(R.string.quick_setup_dp, config.triggerHeightDp)
        binding.wheelInsetValue.text = activity.getString(R.string.quick_setup_dp, config.wheelOffsetXDp)
        binding.wheelRadiusValue.text = activity.getString(R.string.quick_setup_dp, config.wheelRadiusDp)
        binding.quickSetupMessage.setText(when {
            loading -> R.string.guide_loading
            hasUnsavedChanges -> R.string.quick_setup_unsaved
            else -> R.string.quick_setup_saved
        })
        binding.applyQuickSetupButton.isEnabled = !loading
        binding.editWheelButton.isEnabled = !loading
        binding.resetDefaultsButton.isEnabled = !loading
        binding.sideGroup.isEnabled = !loading
        for (index in 0 until binding.sideGroup.childCount) binding.sideGroup.getChildAt(index).isEnabled = !loading
        binding.positionSlider.isEnabled = !loading
        binding.heightSlider.isEnabled = !loading
        binding.wheelInsetSlider.isEnabled = !loading
        binding.wheelRadiusSlider.isEnabled = !loading
    }

    private fun renderGestures() {
        binding.gestureRows.removeAllViews()
        QuickGesture.entries.forEach { gesture ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_settings_tile)
                setPadding(dp(8), dp(8), dp(4), dp(8))
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
            row.addView(demo, LinearLayout.LayoutParams(dp(64), dp(104)))
            val text = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(4), dp(8))
            }
            val action = config.gestures.getValue(gesture)
            text.addView(label(action.label(activity), title = true))
            text.addView(label(gesture.label(activity)))
            if (gesture in practiced) text.addView(label(activity.getString(R.string.quick_practice_saved)))
            row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(iconButton(R.drawable.baseline_edit_24,
                activity.getString(R.string.quick_setup_choose_for, gesture.label(activity))) {
                chooseAction(wheel = false) { selected ->
                    update(config.copy(gestures = config.gestures + (gesture to selected)))
                }
            })
            binding.gestureRows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun editWheel() {
        if (loading || wheelEditor?.isShowing == true) return
        if (CurrentPackageProvider.accessibilityService() == null) {
            binding.quickSetupMessage.setText(R.string.quick_setup_preview_permission)
            return
        }
        wheelPickerJob?.cancel()
        wheelPickerJob = null
        val session = ++wheelEditorSession
        wheelMode = GuideWheelMode.EDIT
        wheelEditor = showGuideWheelEditor(activity) {
            if (session != wheelEditorSession) return@showGuideWheelEditor
            wheelPickerJob?.cancel()
            wheelPickerJob = null
            wheelEditorSession++
            wheelPicker?.dismiss()
            wheelPicker = null
            wheelActionDialog?.dismiss()
            wheelActionDialog = null
            wheelMode = GuideWheelMode.HIDDEN
            wheelInputEnabled = true
            wheelEditor = null
            refreshPreview()
        }
        refreshPreview()
    }

    private fun editWheelItem(index: Int) {
        if (wheelEditor?.isShowing != true || index !in config.wheelItems.indices) return
        chooseWheelAction { action ->
            if (wheelEditor?.isShowing != true || index !in config.wheelItems.indices) return@chooseWheelAction
            update(config.copy(wheelItems = config.wheelItems.toMutableList().apply { this[index] = action }))
        }
    }

    private fun chooseWheelAction(selected: (QuickAction) -> Unit) {
        if (wheelEditor?.isShowing != true) return
        val session = wheelEditorSession
        val draft = config
        wheelInputEnabled = false
        refreshPreview()
        chooseAction(wheel = true, onFinished = {
            if (session != wheelEditorSession) return@chooseAction
            wheelInputEnabled = true
            refreshPreview()
        }, selected = {
            if (session == wheelEditorSession && config == draft && wheelEditor?.isShowing == true) selected(it)
        })
    }

    private fun removeWheelItem(index: Int) {
        if (wheelEditor?.isShowing != true || index !in config.wheelItems.indices) return
        val session = wheelEditorSession
        val draft = config
        wheelInputEnabled = false
        refreshPreview()
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.action_view_item_menu_delete)
            .setMessage(if (config.wheelItems.size > 2) config.wheelItems[index].label(activity)
                else activity.getString(R.string.quick_setup_minimum_items))
            .setNegativeButton(android.R.string.cancel, null)
        if (config.wheelItems.size > 2) dialog.setPositiveButton(R.string.action_view_item_menu_delete) { _, _ ->
            if (session == wheelEditorSession && config == draft && wheelEditor?.isShowing == true) {
                update(config.copy(wheelItems = config.wheelItems.filterIndexed { i, _ -> i != index }))
            }
        }
        wheelActionDialog = dialog.setOnDismissListener {
            if (session != wheelEditorSession) return@setOnDismissListener
            wheelActionDialog = null
            wheelInputEnabled = true
            refreshPreview()
        }.show()
    }

    private fun chooseAction(wheel: Boolean, onFinished: () -> Unit = {}, selected: (QuickAction) -> Unit) {
        if (loading) return
        val editorSession = wheelEditorSession
        val types = QuickActionType.entries.filter {
            (!wheel || it !in listOf(QuickActionType.NONE, QuickActionType.WHEEL)) &&
                (it != QuickActionType.SCREENSHOT || Build.VERSION.SDK_INT >= 28)
        }
        val labels = types.map { QuickAction(it).label(activity) }.toTypedArray()
        var selectingApp = false
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.quick_setup_choose_action)
            .setItems(labels) { _, index ->
                val type = types[index]
                if (type != QuickActionType.OPEN_APP) selected(QuickAction(type))
                else {
                    selectingApp = true
                    val job = activity.lifecycleScope.launch {
                        try {
                            syncAppCatalog(activity.applicationContext)
                            if (wheel && (editorSession != wheelEditorSession || wheelEditor?.isShowing != true)) return@launch
                            val picker = DefaultSelectAPPBottomSheet(activity, activity.lifecycleScope, object : SelectAPPCallBack {
                                override fun selectCompleted(model: APPModel) {
                                    if (wheel && (editorSession != wheelEditorSession || wheelEditor?.isShowing != true)) return
                                    selected(QuickAction(QuickActionType.OPEN_APP, model.pkgName, model.name, model.userId))
                                }
                            }, onDismiss = {
                                if (!wheel || editorSession == wheelEditorSession) {
                                    if (wheel) wheelPicker = null
                                    onFinished()
                                }
                            })
                            if (wheel) wheelPicker = picker
                            picker.show()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            android.util.Log.w("QuickSetup", "Unable to load app picker", e)
                            binding.quickSetupMessage.setText(R.string.guide_load_failed)
                            onFinished()
                        } finally {
                            if (wheel && editorSession == wheelEditorSession) wheelPickerJob = null
                        }
                    }
                    if (wheel) wheelPickerJob = job
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener {
                if (!wheel || editorSession == wheelEditorSession) {
                    if (wheel) wheelActionDialog = null
                    if (!selectingApp) onFinished()
                }
            }
            .show()
        if (wheel) wheelActionDialog = dialog
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
            binding.quickSetupMessage.setText(R.string.quick_setup_preview_permission)
            onChanged()
            return
        }
        try {
            config.validate(Build.VERSION.SDK_INT)
        } catch (_: IllegalArgumentException) {
            binding.quickSetupMessage.setText(R.string.quick_setup_invalid)
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
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setImageResource(icon)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            imageTintList = ColorStateList.valueOf(DynamicColorUtils.primaryColor)
            contentDescription = description
            TooltipCompat.setTooltipText(this, description)
            val background = android.util.TypedValue()
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true)
            setBackgroundResource(background.resourceId)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { click() }
        }

    private fun label(text: String, title: Boolean = false): View = MaterialTextView(activity).apply {
        this.text = text
        setTextAppearance(if (title) com.google.android.material.R.style.TextAppearance_Material3_TitleSmall
            else com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        setTextColor(if (title) DynamicColorUtils.onSurfaceColor else DynamicColorUtils.onSurfaceVariantColor)
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun dp(value: Int): Int = value.toFloat().dp2px.toInt()

    private companion object {
        const val KEY_DRAFT = "quick_setup_draft"
        const val KEY_PRACTICED = "quick_setup_practiced"
    }
}
