package io.github.lumiagg.gestureevo.settings.guide

import android.app.Dialog
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textview.MaterialTextView
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.settings.GestureAddActionActivity
import io.github.lumiagg.gestureevo.databinding.GuideGestureSetupBinding
import io.github.lumiagg.gestureevo.databinding.GuideWheelSetupBinding
import io.github.lumiagg.gestureevo.gesture.config.ActionConfig
import io.github.lumiagg.gestureevo.gesture.config.GestureActionItem
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
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
    private var baseActionConfig = ActionConfig()
    private var savedJson: String? = null
    private var draftJson = config.toJson()
    private var draftContentJson = config.contentJson()
    private var saving = false
    private var saveFailed = false
    private var loadFailed = false
    private val gestureRowStates = mutableMapOf<QuickGesture, String>()
    private val gestureRowViews = mutableMapOf<QuickGesture, View>()
    private var loading = true
    private var updatingViews = false
    private val practiced = mutableSetOf<QuickGesture>()
    private val previewOwner = UUID.randomUUID().toString()
    private var previewActive = false
    private var previewFinished = false
    private var wheelMode = GuideWheelMode.HIDDEN
    private var wheelActionDialog: Dialog? = null
    private var editorPending = savedInstanceState?.getBoolean(KEY_EDITOR_PENDING) ?: false
    private var editorGesture = savedInstanceState?.getString(KEY_EDITOR_GESTURE)
    private var editorWheelTag = savedInstanceState?.getString(KEY_EDITOR_WHEEL_TAG)
    private var editorResult = savedInstanceState?.getString(KEY_EDITOR_RESULT)
    private val actionEditor = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && editorPending) {
            editorResult = result.data?.getStringExtra(GestureAddActionActivity.EXTRA_ACTION_ITEM)
            if (editorResult != null) applyEditorResult() else clearEditorTarget()
        } else clearEditorTarget()
    }

    val hasUnsavedChanges: Boolean get() = !loading && draftContentJson != savedJson
    val isSaving: Boolean get() = saving
    val isSaved: Boolean get() = !loading && savedJson != null && !hasUnsavedChanges && !saving
    val selectedTriggerTag: String? get() = config.baseTrigger?.tag

    init {
        gestureBinding.positionSlider.valueFrom = 0f
        gestureBinding.positionSlider.valueTo = 100f
        gestureBinding.positionSlider.stepSize = 1f

        gestureBinding.heightSlider.valueFrom = 40f
        gestureBinding.heightSlider.valueTo = 200f
        gestureBinding.heightSlider.stepSize = 1f

        gestureBinding.sideGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked && !updatingViews) update(config.copy(side = when (id) {
                R.id.sideLeft -> QuickSetupSide.LEFT
                R.id.sideBoth -> QuickSetupSide.BOTH
                else -> QuickSetupSide.RIGHT
            }))
        }
        gestureBinding.positionSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val newConfig = config.copy(verticalPercent = value.roundToInt())
                update(newConfig, rebuild = false)
                wheelBinding.wheelSampleView.updateTriggerConfig(newConfig)
            }
        }
        gestureBinding.heightSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val newConfig = config.copy(triggerHeightDp = value.roundToInt())
                update(newConfig, rebuild = false)
                wheelBinding.wheelSampleView.updateTriggerConfig(newConfig)
            }
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
                .setPositiveButton(android.R.string.ok) { _, _ -> update(QuickSetupConfig.default().copy(baseTrigger = config.baseTrigger, editedGestures = QuickGesture.entries.toSet())) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        gestureBinding.applyQuickSetupButton.setText(R.string.quick_setup_save_button)
        gestureBinding.applyQuickSetupButton.setIconResource(R.drawable.baseline_save_24)
        gestureBinding.applyQuickSetupButton.setOnClickListener { if (loadFailed) loadConfiguration(null) else saveConfig() }

        gestureBinding.practiceQuickSetupButton.setText(R.string.quick_setup_practice_button)
        gestureBinding.practiceQuickSetupButton.setIconResource(R.drawable.baseline_gesture_24)
        gestureBinding.practiceQuickSetupButton.setOnClickListener { practiceSequence() }

        loadConfiguration(savedInstanceState)
        render()
    }

    private fun loadConfiguration(savedInstanceState: Bundle?) {
        loadFailed = false
        loading = true
        renderState()
        activity.lifecycleScope.launch {
            try {
                val loaded = QuickSetupRepository.load(activity)
                baseActionConfig = QuickSetupRepository.readWheelConfig(activity)
                savedJson = loaded.takeIf { it.hasPersistedConfiguration }?.contentJson()
                config = savedInstanceState?.getString(KEY_DRAFT)?.let {
                    runCatching { QuickSetupConfig.fromJson(it) }.getOrNull()
                } ?: loaded
                if (config.contentJson() == loaded.contentJson()) config = loaded
                draftJson = config.toJson()
                draftContentJson = config.contentJson()
                savedInstanceState?.getStringArrayList(KEY_PRACTICED)?.forEach { name ->
                    QuickGesture.entries.find { it.name == name }?.let(practiced::add)
                }
                loading = false
                applyEditorResult()
                wheelBinding.wheelSampleView.setActionConfig(baseActionConfig)
                render()
                onChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("QuickSetup", "Unable to load quick setup", e)
                loadFailed = true
                renderState()
                onChanged()
            }
        }
    }
    fun saveState(outState: Bundle) {
        if (!loading) outState.putString(KEY_DRAFT, draftJson)
        outState.putStringArrayList(KEY_PRACTICED, ArrayList(practiced.map { it.name }))
        outState.putBoolean(KEY_EDITOR_PENDING, editorPending)
        outState.putString(KEY_EDITOR_GESTURE, editorGesture)
        outState.putString(KEY_EDITOR_WHEEL_TAG, editorWheelTag)
        outState.putString(KEY_EDITOR_RESULT, editorResult)
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
        GuidePreviewSession.refresh(previewOwner, config, wheelMode, baseActionConfig)
    }

    private fun update(value: QuickSetupConfig, rebuild: Boolean = true) {
        if (loading || saving || config == value) return
        val previous = config
        config = value
        draftJson = config.toJson()
        draftContentJson = config.contentJson()
        saveFailed = false
        if (previous.side != config.side || previous.verticalPercent != config.verticalPercent || previous.triggerHeightDp != config.triggerHeightDp) {
            practiced.clear()
        } else {
            val wheelChanged = GestureActionItem.toJsonList(previous.wheelItems) != GestureActionItem.toJsonList(config.wheelItems)
            practiced.removeAll { gesture ->
                previous.gestures.getValue(gesture).toJson(false) != config.gestures.getValue(gesture).toJson(false) ||
                    (wheelChanged && config.gestures.getValue(gesture).guideActionType == GestureActionType.SHOW_ACTION_VIEW)
            }
        }
        if (!rebuild) renderGestures()
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
        gestureBinding.positionSlider.setSafeValue(config.verticalPercent.toFloat())
        gestureBinding.heightSlider.valueFrom = minOf(40f, config.triggerHeightDp.toFloat())
        gestureBinding.heightSlider.valueTo = maxOf(200f, config.triggerHeightDp.toFloat())
        gestureBinding.heightSlider.setSafeValue(config.triggerHeightDp.toFloat())
        updatingViews = false
        renderGestures()
        renderWheelItems()
        renderState()
        refreshPreview()
    }

    private fun Slider.setSafeValue(target: Float) {
        val clamped = target.coerceIn(valueFrom, valueTo)
        val step = stepSize
        val safeValue = if (step > 0f) {
            val steps = ((clamped - valueFrom) / step).roundToInt()
            (valueFrom + steps * step).coerceIn(valueFrom, valueTo)
        } else {
            clamped
        }
        value = safeValue
    }

    private fun renderState() {
        gestureBinding.positionValue.text = activity.getString(R.string.quick_setup_position_value, config.verticalPercent)
        gestureBinding.heightValue.text = activity.getString(R.string.quick_setup_dp, config.triggerHeightDp)
        wheelBinding.quickSetupMessage.setText(when {
            loadFailed -> R.string.guide_load_failed
            loading -> R.string.guide_loading
            saving -> R.string.quick_setup_saving
            saveFailed -> R.string.quick_setup_save_failed
            hasUnsavedChanges -> R.string.quick_setup_unsaved
            else -> R.string.quick_setup_saved
        })
        gestureBinding.applyQuickSetupButton.isEnabled = loadFailed || (!loading && !saving)
        gestureBinding.applyQuickSetupButton.setText(if (loadFailed) R.string.guide_retry else R.string.quick_setup_save_button)
        wheelBinding.wheelSampleView.isEnabled = !loading && !saving
        gestureBinding.practiceQuickSetupButton.isEnabled = !loading && !saving
        wheelBinding.resetDefaultsButton.isEnabled = !loading && !saving
        gestureBinding.sideGroup.isEnabled = !loading && !saving
        for (index in 0 until gestureBinding.sideGroup.childCount) gestureBinding.sideGroup.getChildAt(index).isEnabled = !loading && !saving
        gestureBinding.positionSlider.isEnabled = !loading && !saving
        gestureBinding.heightSlider.isEnabled = !loading && !saving
    }

    private fun renderGestures() {

        QuickGesture.entries.forEachIndexed { index, gesture ->
            val rowState = config.gestures.getValue(gesture).toJson(false) + config.side.name + (gesture in practiced)
            if (gestureRowStates[gesture] == rowState) return@forEachIndexed
            gestureRowStates[gesture] = rowState
            gestureRowViews.remove(gesture)?.let { gestureBinding.gestureRows.removeView(it) }
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
                chooseAction(wheel = false, currentAction = action, gesture = gesture) { selected ->
                    update(config.withGesture(gesture, selected))
                }
            })

            // Practice / Test button
            row.addView(iconButton(
                R.drawable.baseline_gesture_24,
                activity.getString(R.string.quick_practice_for, gesture.label(activity))
            ) {
                practice(gesture)
            })

            gestureRowViews[gesture] = row
            gestureBinding.gestureRows.addView(row, index, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun renderWheelItems() {
        wheelBinding.wheelSampleView.setConfig(
            config = config,
            onEditItem = { index ->
                if (index in config.wheelItems.indices) {
                    chooseWheelAction(currentAction = config.wheelItems[index]) { action ->
                        val newItems = config.wheelItems.toMutableList()
                        newItems[index] = action.copy(tag = newItems[index].tag)
                        update(config.copy(wheelItems = newItems))
                    }
                }
            },
            onRemoveItem = { index ->
                removeWheelItem(index)
            },
            onAddItem = {
                chooseWheelAction { selected ->
                    update(config.copy(wheelItems = config.wheelItems + selected))
                }
            },
            onReorderItems = { newItems ->
                update(config.copy(wheelItems = newItems))
            }
        )
    }

    private fun chooseWheelAction(currentAction: GestureActionItem? = null, selected: (GestureActionItem) -> Unit) {
        chooseAction(wheel = true, currentAction = currentAction, selected = selected)
    }

    private fun removeWheelItem(index: Int) {
        if (index !in config.wheelItems.indices) return
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
        currentAction: GestureActionItem? = null,
        gesture: QuickGesture? = null,
        selected: (GestureActionItem) -> Unit
    ) {
        if (loading || saving) return
        showGuideActionSelectBottomSheet(
            activity = activity,
            allowWheel = !wheel,
            allowNone = !wheel,
            currentAction = currentAction,
            onActionSelected = { chosen ->
                selected(currentAction?.let { replaceGuideAction(it, chosen) } ?: chosen)
            },
            onMoreSettings = {
                editorPending = true
                editorGesture = gesture?.name
                editorWheelTag = currentAction?.tag
                editorResult = null
                actionEditor.launch(Intent(activity, GestureAddActionActivity::class.java).apply {
                    putExtra(GestureAddActionActivity.EXTRA_RETURN_ACTION_ITEM, true)
                    putExtra("editMode", true)
                    putExtra("triggerActionMode", gesture != null)
                    if (wheel) putExtra(GestureAddActionActivity.EXTRA_ICON_WHEEL_STYLE, baseActionConfig.toJson(false))
                    putExtra(GestureAddActionActivity.EXTRA_ACTION_ITEM, (currentAction ?: GestureActionItem()).toJson(false))
                })
            }
        )
    }

    private fun applyEditorResult() {
        if (loading || saving || !editorPending) return
        val json = editorResult ?: return
        val action = runCatching { GestureActionItem.toModel(json) }.getOrNull()
        val gesture = QuickGesture.entries.find { it.name == editorGesture }
        val wheelTag = editorWheelTag
        clearEditorTarget()
        if (action == null) return
        when {
            gesture != null -> update(config.withGesture(gesture, action, preserveOtherPhases = true))
            wheelTag == null -> update(config.copy(wheelItems = config.wheelItems + action))
            else -> {
                val index = config.wheelItems.indexOfFirst { it.tag == wheelTag }
                if (index >= 0) update(config.copy(wheelItems = config.wheelItems.toMutableList().apply {
                    set(index, action.copy(tag = wheelTag))
                }))
            }
        }
    }

    private fun clearEditorTarget() {
        editorPending = false
        editorGesture = null
        editorWheelTag = null
        editorResult = null
    }

    fun saveConfig() {
        if (loading || saving) return
        activity.lifecycleScope.launch {
            if (save()) {
                android.widget.Toast.makeText(activity, R.string.quick_setup_saved_toast, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    suspend fun save(): Boolean = saveDraft(config.snapshot())

    private suspend fun saveDraft(draft: QuickSetupConfig): Boolean {
        if (loading || saving) return false
        if (savedJson == draft.contentJson()) return true
        saving = true
        saveFailed = false
        renderState()
        onChanged()
        return try {
            draft.validate(Build.VERSION.SDK_INT)
            config = QuickSetupRepository.apply(activity, draft)
            draftJson = config.toJson()
            draftContentJson = config.contentJson()
            savedJson = draftContentJson
            render()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("QuickSetup", "Failed to save quick setup", e)
            saveFailed = true
            false
        } finally {
            saving = false
            renderState()
            onChanged()
        }
    }
    private fun practiceSequence() {
        if (loading || saving) return
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
        val gestures = QuickGesture.entries.filter { !config.gestures.getValue(it).isUnassigned }
        if (gestures.isEmpty()) return
        val draft = config.snapshot()
        showGuidePracticeSequence(
            activity = activity,
            gestures = gestures,
            initialIndex = 0,
            config = draft,
            previewOwner = previewOwner,
            actionConfig = baseActionConfig,
            onRecognized = { gesture ->
                check(saveDraft(draft)) { "Unable to save practiced configuration" }
                practiced.add(gesture)
                render()
                onChanged()
            }
        )
    }

    private fun practice(gesture: QuickGesture) {
        if (loading || saving) return
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
        val draft = config.snapshot()
        showGuidePractice(activity, gesture, draft, previewOwner, baseActionConfig, onRecognized = {
            check(saveDraft(draft)) { "Unable to save practiced configuration" }
            practiced.add(gesture)
            render()
            onChanged()
        })
    }

    private fun iconButton(icon: Int, description: String, click: () -> Unit): View =
        AppCompatImageButton(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setImageResource(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
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
        const val KEY_EDITOR_PENDING = "quick_setup_editor_pending"
        const val KEY_EDITOR_GESTURE = "quick_setup_editor_gesture"
        const val KEY_EDITOR_WHEEL_TAG = "quick_setup_editor_wheel_tag"
        const val KEY_EDITOR_RESULT = "quick_setup_editor_result"
        const val KEY_DRAFT = "quick_setup_runtime_draft"
        const val KEY_PRACTICED = "quick_setup_practiced"
    }
}
