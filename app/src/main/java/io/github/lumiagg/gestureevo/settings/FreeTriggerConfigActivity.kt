package io.github.lumiagg.gestureevo.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.graphics.Rect
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.FreeTriggerConfigBottomsheetBinding
import io.github.lumiagg.gestureevo.gesture.EventBusManager.produceFreeTriggerEvent
import io.github.lumiagg.gestureevo.gesture.FreeTriggerEvent
import io.github.lumiagg.gestureevo.gesture.freeTriggerBounds
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerConfig
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerDataManager
import io.github.lumiagg.gestureevo.gesture.config.GestureActionDataManager
import io.github.lumiagg.gestureevo.gesture.config.GestureActionItem
import io.github.lumiagg.gestureevo.gesture.ui.GestureType
import io.github.lumiagg.gestureevo.gesture.ui.GestureTypeEvent
import io.github.lumiagg.gestureevo.settings.ui.BlackListWithNoDBSelectAPPBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.GestureAngleConfig
import io.github.lumiagg.gestureevo.settings.ui.GestureAngleConfigBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.GestureAngleConfigCallback
import io.github.lumiagg.gestureevo.settings.ui.GestureAngleStorge
import io.github.lumiagg.gestureevo.settings.ui.GestureTimingConfigBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.GestureTimingConfigCallback
import io.github.lumiagg.gestureevo.settings.ui.GestureTypeSelectBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.GestureTypeSelectCallback
import io.github.lumiagg.gestureevo.settings.ui.OnQuadrantClick
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.ScreenUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import java.util.Locale

class FreeTriggerConfigActivity : AppCompatActivity() {
    private lateinit var binding: FreeTriggerConfigBottomsheetBinding
    private lateinit var trigger: FreeTriggerConfig
    private var pendingBatchEvents: List<GestureTypeEvent> = emptyList()
    private var deleted = false
    private lateinit var actionSelectionLauncher: ActivityResultLauncher<Intent>
    private var isInitializingViews = true
    private var pendingWidth = 0
    private var pendingHeight = 0
    private var savedWidth = 0
    private var savedHeight = 0
    private var sizeAdjustMode = SizeAdjustMode.SMALL
    private var ignoreSizeSliderChange = false
    private var isShowingSizeRiskDialog = false
    private var otherTriggers: List<FreeTriggerConfig> = emptyList()
    private var serviceEnabled = false
    private var isDraggingSlider = false

    private enum class SizeAdjustMode(val gain: Int) {
        SMALL(1),
        MEDIUM(2),
        LARGE(10)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColorUtils.captureColor(applicationContext, lifecycleScope)
        binding = FreeTriggerConfigBottomsheetBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()

        val triggerJson = savedInstanceState?.getString(EXTRA_TRIGGER_JSON)
            ?: intent.getStringExtra(EXTRA_TRIGGER_JSON)
        trigger = FreeTriggerConfig.toModel(triggerJson ?: "") ?: run {
            finish()
            return
        }

        registerSelectionLauncher()
        setupViews()
        isInitializingViews = false
        refreshPreview()
    }

    private fun applySystemBarInsets() {
        val initialPaddingTop = binding.root.paddingTop
        val initialPaddingBottom = binding.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                initialPaddingTop + systemBars.top,
                view.paddingRight,
                initialPaddingBottom + systemBars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::trigger.isInitialized) outState.putString(EXTRA_TRIGGER_JSON, trigger.toJson())
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (!::trigger.isInitialized) return
        serviceEnabled = evoAccessibilityServiceIsEnable(this)
        refreshPreview()
        lifecycleScope.launch {
            try {
                otherTriggers = FreeTriggerDataManager.readTriggers(applicationContext)
                    .filter { it.tag != trigger.tag }.map { it.copy() }
                refreshPreview()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                android.util.Log.w("FreeTriggerConfig", "Unable to load other trigger areas", failure)
            }
            if (!deleted && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                produceFreeTriggerEvent(FreeTriggerEvent(isConfig = true, configTrigger = trigger))
            }
        }
    }

    override fun onPause() {
        super.onPause()
        lifecycleScope.launch {
            produceFreeTriggerEvent(FreeTriggerEvent(isConfig = false))
        }
    }

    private fun createSliderTouchListener(onStop: (() -> Unit)? = null) = object : Slider.OnSliderTouchListener {
        override fun onStartTrackingTouch(slider: Slider) {
            isDraggingSlider = true
        }
        override fun onStopTrackingTouch(slider: Slider) {
            isDraggingSlider = false
            onStop?.invoke() ?: saveTriggerConfig(onlyShowThisView = true)
        }
    }

    private fun createSliderLabelFormatter() = LabelFormatter { value ->
        String.format(Locale.US, "%d", sliderValueToActual(value))
    }

    private fun setupViews() {
        pendingWidth = trigger.width
        pendingHeight = trigger.height
        savedWidth = trigger.width
        savedHeight = trigger.height
        sizeAdjustMode = resolveInitialSizeMode(pendingWidth, pendingHeight)

        with(binding) {
            nameInput.setText(trigger.name)

            portraitChip.isChecked = trigger.getState(FreeTriggerConfig.FLAG_PORTRAIT)
            landscapeChip.isChecked = trigger.getState(FreeTriggerConfig.FLAG_LANDSCAPE)
            blacklistChip.isChecked = trigger.getState(FreeTriggerConfig.FLAG_BLACKLIST)
            whitelistChip.isChecked = trigger.getState(FreeTriggerConfig.FLAG_WHITELIST)
            mirrorSwitch.isChecked = trigger.getState(FreeTriggerConfig.FLAG_MIRROR)
            belowKeyboardSwitch.isChecked = trigger.getState(FreeTriggerConfig.FLAG_BELOW_KEYBOARD)
            followTriggerPointSwitch.isChecked =
                trigger.getState(FreeTriggerConfig.FLAG_WHEEL_FOLLOW_TRIGGER_POINT)
            followTriggerPointSwitch.isEnabled = true
            feedbackSwitch.isChecked = trigger.getState(FreeTriggerConfig.FLAG_FEEDBACK)
            feedbackSwitch.isEnabled = true

            xPositionSlider.value = trigger.xRelativeValue
            xPositionSlider.addOnSliderTouchListener(createSliderTouchListener())
            yPositionSlider.value = trigger.yRelativeValue
            yPositionSlider.addOnSliderTouchListener(createSliderTouchListener())
            widthSlider.setLabelFormatter(createSliderLabelFormatter())
            widthSlider.addOnSliderTouchListener(createSliderTouchListener {
                commitSizeChangeIfNeeded()
            })
            heightSlider.setLabelFormatter(createSliderLabelFormatter())
            heightSlider.addOnSliderTouchListener(createSliderTouchListener {
                commitSizeChangeIfNeeded()
            })
            applySizeModeSelection(sizeAdjustMode, syncSliders = true)

            pieConfigQuadrant.setQuadrantState(trigger.getQuadrant())
            bindImmediateSaveListeners()

            actionButton.setOnClickListener {
                GestureTypeSelectBottomSheet(
                    activity = this@FreeTriggerConfigActivity,
                    coroutineScope = lifecycleScope,
                    triggerConfig = trigger,
                    callback = object : GestureTypeSelectCallback {
                        override fun onGestureEventSelected(gestureTypeEvent: GestureTypeEvent) {
                            pendingBatchEvents = emptyList()
                            startActionSelection(gestureTypeEvent)
                        }

                        override fun onBatchGestureEventsSelected(events: List<GestureTypeEvent>) {
                            if (events.isEmpty()) return
                            pendingBatchEvents = events
                            startActionSelection(events.first())
                        }
                    }
                ).show()
            }

            angleButton.setOnClickListener {
                GestureAngleConfigBottomSheet(
                    activity = this@FreeTriggerConfigActivity,
                    coroutineScope = lifecycleScope,
                    triggerConfig = trigger,
                    callback = object : GestureAngleConfigCallback {
                        override fun onAngleConfigChanged(configs: List<GestureAngleConfig>) {
                            val angleMap = mutableMapOf<GestureType, GestureAngleStorge>()
                            configs.forEach {
                                angleMap[it.gestureType] =
                                    GestureAngleStorge(s = it.startAngle, e = it.endAngle)
                            }
                            trigger.gestureAngleMap = angleMap
                            saveTriggerConfig(onlyShowThisView = true)
                        }
                    }
                ).show()
            }

            timeButton.setOnClickListener {
                GestureTimingConfigBottomSheet(
                    activity = this@FreeTriggerConfigActivity,
                    triggerConfig = trigger,
                    callback = object : GestureTimingConfigCallback {
                        override fun onTimingConfigChanged() {
                            saveTriggerConfig(onlyShowThisView = true)
                        }
                    }
                ).show()
            }

            manageListButton.setOnClickListener {
                if (trigger.getState(FreeTriggerConfig.FLAG_BLACKLIST)) {
                    showBlacklistSelection()
                } else {
                    showWhitelistSelection()
                }
            }

            deleteButton.setOnClickListener {
                deleted = true
                lifecycleScope.launch {
                    FreeTriggerDataManager.removeTrigger(this@FreeTriggerConfigActivity, trigger.tag)
                    produceFreeTriggerEvent(FreeTriggerEvent(isConfig = true))
                    setResult(RESULT_OK)
                    finish()
                }
            }

            cancelButton.setOnClickListener { finish() }

            pieConfigQuadrant.onQuadrantClick = object : OnQuadrantClick {
                override fun onQuadrantClick(quadrantClickedList: ArrayList<Boolean>) {
                    trigger.setQuadrant(quadrantClickedList)
                    saveTriggerConfig(onlyShowThisView = true)
                }
            }
        }
    }

    private fun bindImmediateSaveListeners() {
        with(binding) {
            nameInput.doAfterTextChanged {
                triggerImmediateSave()
            }
            portraitChip.setOnCheckedChangeListener { _, _ -> triggerImmediateSave() }
            landscapeChip.setOnCheckedChangeListener { _, _ -> triggerImmediateSave() }
            blacklistChip.setOnCheckedChangeListener { _, _ -> triggerImmediateSave() }
            whitelistChip.setOnCheckedChangeListener { _, _ -> triggerImmediateSave() }
            mirrorSwitch.setOnCheckedChangeListener { _, _ -> triggerImmediateSave() }
            belowKeyboardSwitch.setOnCheckedChangeListener { _, _ -> triggerImmediateSave() }
            followTriggerPointSwitch.setOnCheckedChangeListener { _, _ -> triggerImmediateSave() }
            feedbackSwitch.setOnCheckedChangeListener { _, _ -> triggerImmediateSave() }

            xPositionSlider.addOnChangeListener { _, _, fromUser ->
                if (fromUser) triggerImmediateSave()
            }
            yPositionSlider.addOnChangeListener { _, _, fromUser ->
                if (fromUser) triggerImmediateSave()
            }
            widthSlider.addOnChangeListener { slider, _, fromUser ->
                if (!fromUser || ignoreSizeSliderChange) return@addOnChangeListener
                pendingWidth = sliderValueToActual(slider.value)
                refreshPreview()
                if (!isDraggingSlider) commitSizeChangeIfNeeded()
            }
            heightSlider.addOnChangeListener { slider, _, fromUser ->
                if (!fromUser || ignoreSizeSliderChange) return@addOnChangeListener
                pendingHeight = sliderValueToActual(slider.value)
                refreshPreview()
                if (!isDraggingSlider) commitSizeChangeIfNeeded()
            }
            sizeModeChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
                if (isInitializingViews || checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
                val newMode = when (checkedIds.first()) {
                    R.id.sizeModeMediumChip -> SizeAdjustMode.MEDIUM
                    R.id.sizeModeLargeChip -> SizeAdjustMode.LARGE
                    else -> SizeAdjustMode.SMALL
                }
                if (newMode != sizeAdjustMode) {
                    applySizeModeSelection(newMode, syncSliders = true)
                }
            }
        }
    }

    private fun resolveInitialSizeMode(width: Int, height: Int): SizeAdjustMode {
        val maxValue = maxOf(width, height)
        return when {
            maxValue <= 100 -> SizeAdjustMode.SMALL
            maxValue <= 200 -> SizeAdjustMode.MEDIUM
            else -> SizeAdjustMode.LARGE
        }
    }

    private fun applySizeModeSelection(mode: SizeAdjustMode, syncSliders: Boolean) {
        sizeAdjustMode = mode
        binding.sizeModeHint.text = getString(R.string.free_trigger_size_mode_hint, mode.gain)
        when (mode) {
            SizeAdjustMode.SMALL -> binding.sizeModeSmallChip.isChecked = true
            SizeAdjustMode.MEDIUM -> binding.sizeModeMediumChip.isChecked = true
            SizeAdjustMode.LARGE -> binding.sizeModeLargeChip.isChecked = true
        }
        if (syncSliders) {
            syncSizeSlidersFromPending()
        }
    }

    private fun syncSizeSlidersFromPending() {
        ignoreSizeSliderChange = true
        binding.widthSlider.value = actualToSliderValue(pendingWidth)
        binding.heightSlider.value = actualToSliderValue(pendingHeight)
        ignoreSizeSliderChange = false
    }

    private fun sliderValueToActual(sliderValue: Float): Int {
        return sliderValue.toInt().coerceAtLeast(1) * sizeAdjustMode.gain
    }

    private fun actualToSliderValue(actualValue: Int): Float {
        return ceil(actualValue / sizeAdjustMode.gain.toFloat()).toInt()
            .coerceIn(1, 100)
            .toFloat()
    }

    private fun commitSizeChangeIfNeeded() {
        if (deleted || isInitializingViews) return
        val reasons = getSizeRiskReasons(pendingWidth, pendingHeight)
        if (reasons.isEmpty()) {
            savedWidth = pendingWidth
            savedHeight = pendingHeight
            saveTriggerConfig(onlyShowThisView = true)
            return
        }
        if (isShowingSizeRiskDialog) return
        isShowingSizeRiskDialog = true
        AlertDialog.Builder(this)
            .setTitle(R.string.free_trigger_size_risk_title)
            .setMessage(
                getString(
                    R.string.free_trigger_size_risk_message,
                    reasons.joinToString("、")
                )
            )
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                restoreSavedSize()
            }
            .setPositiveButton(R.string.free_trigger_size_risk_confirm) { _, _ ->
                savedWidth = pendingWidth
                savedHeight = pendingHeight
                saveTriggerConfig(onlyShowThisView = true)
            }
            .setOnDismissListener {
                // Back and outside-tap cancel the pending size just like Cancel.
                if (pendingWidth != savedWidth || pendingHeight != savedHeight) restoreSavedSize()
                isShowingSizeRiskDialog = false
            }
            .show()
    }

    private fun restoreSavedSize() {
        pendingWidth = savedWidth
        pendingHeight = savedHeight
        syncSizeSlidersFromPending()
        refreshPreview()
    }

    private fun getSizeRiskReasons(widthDp: Int, heightDp: Int): List<String> {
        val screenWidthPx = ScreenUtil.getScreenRealWidth(this, false)
        val screenHeightPx = ScreenUtil.getScreenRealHeight(this, false)
        val widthPx = (widthDp * resources.displayMetrics.density).toInt()
        val heightPx = (heightDp * resources.displayMetrics.density).toInt()

        val reasons = mutableListOf<String>()
        val widthThreshold = minOf((screenWidthPx * 0.35f).toInt(), (180 * resources.displayMetrics.density).toInt())
        val heightThreshold = minOf((screenHeightPx * 0.55f).toInt(), (320 * resources.displayMetrics.density).toInt())
        val areaThreshold = (screenWidthPx * screenHeightPx * 0.15f).toInt()

        if (widthPx > widthThreshold) {
            reasons.add(getString(R.string.free_trigger_size_risk_reason_width))
        }
        if (heightPx > heightThreshold) {
            reasons.add(getString(R.string.free_trigger_size_risk_reason_height))
        }
        if (widthPx * heightPx > areaThreshold) {
            reasons.add(getString(R.string.free_trigger_size_risk_reason_area))
        }
        return reasons
    }

    private fun triggerImmediateSave() {
        if (isInitializingViews || deleted) return
        saveTriggerConfig(onlyShowThisView = true)
    }

    private fun showBlacklistSelection() {
        BlackListWithNoDBSelectAPPBottomSheet(
            this,
            lifecycleScope,
            blackListSelectAPPCallBack = object :
                BlackListWithNoDBSelectAPPBottomSheet.BlackListSelectAPPCallBack {
                override fun selectCompleted(blackList: List<String>) {
                    trigger.blackList = blackList
                    saveTriggerConfig(onlyShowThisView = true)
                }
            },
            blackList = ArrayList(trigger.blackList),
        ).show()
    }

    private fun showWhitelistSelection() {
        BlackListWithNoDBSelectAPPBottomSheet(
            this,
            lifecycleScope,
            blackListSelectAPPCallBack = object :
                BlackListWithNoDBSelectAPPBottomSheet.BlackListSelectAPPCallBack {
                override fun selectCompleted(blackList: List<String>) {
                    trigger.whiteList = blackList
                    saveTriggerConfig(onlyShowThisView = true)
                }
            },
            blackList = ArrayList(trigger.whiteList),
            disableIcon = ContextCompat.getDrawable(
                this,
                R.drawable.baseline_check_24
            )
        ).show()
    }

    private fun saveTriggerConfig(onlyShowThisView: Boolean) {
        with(binding) {
            trigger.name = nameInput.text.toString()
            trigger.setState(FreeTriggerConfig.FLAG_PORTRAIT, portraitChip.isChecked)
            trigger.setState(FreeTriggerConfig.FLAG_LANDSCAPE, landscapeChip.isChecked)
            trigger.setState(FreeTriggerConfig.FLAG_BLACKLIST, blacklistChip.isChecked)
            trigger.setState(FreeTriggerConfig.FLAG_WHITELIST, whitelistChip.isChecked)
            trigger.xRelativeValue = xPositionSlider.value
            trigger.yRelativeValue = yPositionSlider.value
            // Unrelated edits must not persist an unconfirmed large size.
            trigger.width = savedWidth
            trigger.height = savedHeight
            trigger.setState(FreeTriggerConfig.FLAG_MIRROR, mirrorSwitch.isChecked)
            trigger.setState(FreeTriggerConfig.FLAG_BELOW_KEYBOARD, belowKeyboardSwitch.isChecked)
            trigger.setState(
                FreeTriggerConfig.FLAG_WHEEL_FOLLOW_TRIGGER_POINT,
                followTriggerPointSwitch.isChecked
            )
            trigger.setState(FreeTriggerConfig.FLAG_FEEDBACK, feedbackSwitch.isChecked)
            trigger.setQuadrant(pieConfigQuadrant.getQuadrantClickedList())
        }
        refreshPreview()

        lifecycleScope.launch {
            FreeTriggerDataManager.updateTrigger(this@FreeTriggerConfigActivity, trigger)
            if (onlyShowThisView) {
                produceFreeTriggerEvent(FreeTriggerEvent(isConfig = true, configTrigger = trigger))
            } else {
                produceFreeTriggerEvent(FreeTriggerEvent(isConfig = true))
            }
            setResult(RESULT_OK)
        }
    }

    private fun refreshPreview() {
        if (!::trigger.isInitialized || isInitializingViews) return
        val preview = FreeTriggerConfig().apply {
            tag = trigger.tag
            width = pendingWidth
            height = pendingHeight
            xRelativeValue = binding.xPositionSlider.value
            yRelativeValue = binding.yPositionSlider.value
            setState(FreeTriggerConfig.FLAG_MIRROR, binding.mirrorSwitch.isChecked)
        }
        binding.triggerPreview.bind(preview, otherTriggers)
        val orientation = when {
            binding.portraitChip.isChecked && binding.landscapeChip.isChecked -> getString(R.string.trigger_summary_both)
            binding.portraitChip.isChecked -> getString(R.string.free_trigger_orientation_portrait)
            binding.landscapeChip.isChecked -> getString(R.string.free_trigger_orientation_landscape)
            else -> getString(R.string.trigger_summary_none)
        }
        val white = binding.whitelistChip.isChecked && trigger.whiteList.isNotEmpty()
        val black = binding.blacklistChip.isChecked && trigger.blackList.isNotEmpty()
        val apps = when {
            white && black -> getString(R.string.trigger_summary_apps_combined, trigger.whiteList.size, trigger.blackList.size)
            white -> getString(R.string.trigger_summary_apps_white, trigger.whiteList.size)
            black -> getString(R.string.trigger_summary_apps_black, trigger.blackList.size)
            else -> getString(R.string.trigger_summary_apps_all)
        }
        binding.triggerGeometry.text = getString(R.string.trigger_summary_geometry, pendingWidth, pendingHeight,
            (preview.xRelativeValue * 100).toInt(), (preview.yRelativeValue * 100).toInt())
        binding.triggerSummary.text = listOfNotNull(
            getString(R.string.trigger_summary_orientation, orientation),
            apps,
            getString(if (binding.mirrorSwitch.isChecked) R.string.trigger_summary_mirror_on else R.string.trigger_summary_mirror_off),
            getString(R.string.trigger_summary_self),
            if (!trigger.enable) getString(R.string.trigger_summary_disabled) else null,
            getString(R.string.trigger_summary_saved)
        ).joinToString("\n")
        val warnings = mutableListOf<String>()
        if (!serviceEnabled) warnings += getString(R.string.trigger_warning_service)
        if (!binding.portraitChip.isChecked && !binding.landscapeChip.isChecked) {
            warnings += getString(R.string.trigger_warning_orientation)
        }
        if (pendingWidth != savedWidth || pendingHeight != savedHeight) {
            warnings += getString(R.string.trigger_warning_size_pending)
        }
        val risks = getSizeRiskReasons(pendingWidth, pendingHeight)
        if (risks.isNotEmpty()) warnings += getString(R.string.trigger_warning_size, risks.joinToString("、"))
        val bounds = listOfNotNull(freeTriggerBounds(this, preview),
            if (binding.mirrorSwitch.isChecked) freeTriggerBounds(this, preview, true) else null)
        if (bounds.size == 2 && Rect.intersects(bounds[0], bounds[1])) {
            warnings += getString(R.string.trigger_warning_mirror)
        }
        val overlapping = otherTriggers.filter { other ->
            other.enable && (
                (binding.portraitChip.isChecked && other.getState(FreeTriggerConfig.FLAG_PORTRAIT)) ||
                    (binding.landscapeChip.isChecked && other.getState(FreeTriggerConfig.FLAG_LANDSCAPE))
                ) && bounds.any { current ->
                Rect.intersects(current, freeTriggerBounds(this, other)) ||
                    (other.getState(FreeTriggerConfig.FLAG_MIRROR) && Rect.intersects(current, freeTriggerBounds(this, other, true)))
            }
        }
        if (overlapping.isNotEmpty()) {
            warnings += getString(R.string.trigger_warning_overlap, overlapping.joinToString("、") { it.name })
        }
        binding.triggerWarning.text = warnings.joinToString("\n")
        binding.triggerWarning.visibility = if (warnings.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun startActionSelection(gestureTypeEvent: GestureTypeEvent) {
        val intent = Intent(this, GestureAddActionActivity::class.java).apply {
            putExtra("triggerActionMode", true)
            putExtra("tag", trigger.tag)
            putExtra("expectItemTag", gestureTypeEvent.toActionKey())
            trigger.getGestureAction(gestureTypeEvent.toActionKey())?.let {
                putExtra("editMode", true)
                putExtra("item", it.toJson())
            }
        }
        actionSelectionLauncher.launch(intent)
    }

    private fun registerSelectionLauncher() {
        actionSelectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                lifecycleScope.launch {
                    trigger.updateGestureActionList(this@FreeTriggerConfigActivity)
                    if (pendingBatchEvents.isNotEmpty()) {
                        applyBatchSelection()
                        pendingBatchEvents = emptyList()
                    }
                    binding.actionButton.performClick()
                }
            }
        }
    }

    private suspend fun applyBatchSelection() {
        val uniqueEvents = pendingBatchEvents.distinctBy { it.toActionKey() }
        val primaryEvent = uniqueEvents.firstOrNull() ?: return
        val primaryKey = primaryEvent.toActionKey()
        val templateItem = trigger.getGestureAction(primaryKey) ?: return
        val templateJson = templateItem.toJson()
        withContext(Dispatchers.IO) {
            uniqueEvents.drop(1).forEach { event ->
                val copy = GestureActionItem.toModel(templateJson) ?: return@forEach
                copy.tag = event.toActionKey()
                GestureActionDataManager.addActionItem(this@FreeTriggerConfigActivity, trigger.tag, copy)
            }
        }
        trigger.updateGestureActionList(this)
    }

    companion object {
        private const val EXTRA_TRIGGER_JSON = "extra_trigger_json"

        fun createIntent(context: Context, trigger: FreeTriggerConfig): Intent {
            return Intent(context, FreeTriggerConfigActivity::class.java).apply {
                putExtra(EXTRA_TRIGGER_JSON, trigger.toJson())
            }
        }
    }
}
