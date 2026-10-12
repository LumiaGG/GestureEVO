package io.github.lumiagg.gestureevo.settings

import FreeFormUtils.getHeight
import FreeFormUtils.getLeft
import FreeFormUtils.getTop
import FreeFormUtils.getWidth
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActivityFreeFormSettingsBinding
import io.github.lumiagg.gestureevo.settings.ui.ResizableRectangleView
import io.github.lumiagg.gestureevo.utils.ScreenUtil
import io.github.lumiagg.gestureevo.utils.dp2px
import io.github.lumiagg.gestureevo.utils.px2dp
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.removeKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class FreeFormSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFreeFormSettingsBinding
    private lateinit var resizableRectangle: ResizableRectangleView
    private val _orientation = MutableStateFlow(Configuration.ORIENTATION_PORTRAIT)
    private val orientation: StateFlow<Int> = _orientation

    // StateFlow tracking whether size adjustment should be disabled
    private val _shouldDisableSizeAdjustment = MutableStateFlow(false)

    companion object {
        private const val MODE_SUNSHINE = -3

        const val KEY_ORIENTATION = "current_orientation"
        const val KEY_USE_MI_WINDOW = "use_mi_window"
        const val KEY_FREEFORM_CODE = "freeform_code"
        const val KEY_FREEFORM_FOLLOW_TRIGGER_SIDE = "freeform_follow_trigger_side"

        // Base preference keys
        const val KEY_WIDTH = "freeform_width_dp"
        const val KEY_HEIGHT = "freeform_height_dp"
        const val KEY_LEFT_MARGIN = "freeform_left_margin_dp"
        const val KEY_TOP_MARGIN = "freeform_top_margin_dp"
        const val KEY_EDGE_MARGIN = "freeform_edge_margin_dp"

        fun getSettingsKey(baseKey: String, orientation: Int): String {
            val suffix =
                if (orientation == Configuration.ORIENTATION_PORTRAIT) "_portrait" else "_landscape"
            return "${baseKey}${suffix}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFreeFormSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enableFullScreen()

        // Initialize resizable rectangle view
        resizableRectangle = binding.resizableRectangle
        // Restore orientation state
        savedInstanceState?.let {
            val savedOrientation = it.getInt(KEY_ORIENTATION, Configuration.ORIENTATION_PORTRAIT)
            _orientation.value = savedOrientation
            setScreenOrientation(savedOrientation)
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        lifecycleScope.launch {
            initializeViews()
            setupListeners()
            observeSettings()
            observeSizeAdjustmentState()
            SettingsSearchNavigator.focusAnchorIfNeeded(binding.root, intent)
        }
    }

    private fun enableFullScreen() {
        when {
            // Android 11+ (API 30+): Use WindowInsetsController
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                val controller = window.insetsController
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }

            // Android 10 and earlier
            else -> {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_ORIENTATION, _orientation.value)
    }

    private fun setScreenOrientation(orientation: Int) {
        requestedOrientation = if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private suspend fun initializeViews() {
        // Initialize orientation buttons
        if (_orientation.value == Configuration.ORIENTATION_PORTRAIT) {
            binding.directionButtons.check(binding.directionButtonPortrait.id)
        } else {
            binding.directionButtons.check(binding.directionButtonLandscape.id)
        }

        // Initialize dropdown menu
        setupDropdownMenu()

        // Initialize rectangle position
        updateResizableRectangle()

        binding.followTriggerSideSwitch.isChecked =
            settingsStore.readKey(KEY_FREEFORM_FOLLOW_TRIGGER_SIDE, false)

        // Update disabled state
        updateSizeAdjustmentDisabledState()
    }

    private suspend fun setupDropdownMenu() {
        val items = arrayOf(
            getString(R.string.freeform_mode_default),
            getString(R.string.freeform_mode_1),
            getString(R.string.freeform_mode_2_oppo),
            getString(R.string.freeform_mode_3_hw),
            getString(R.string.freeform_mode_4_vivo_share),
            getString(R.string.freeform_mode_5_flyme),
            getString(R.string.freeform_mode_6_nubia),
            getString(R.string.freeform_mode_7_sunshine)
        )
        val itemsCode = arrayOf(-1, 5, 100, 102, 106, 11, -2, MODE_SUNSHINE)
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, items)
        (binding.dropdownMenu.editText as? AutoCompleteTextView)?.apply {
            setAdapter(adapter)

            // Restore saved selection
            val oldCode = if (settingsStore.readKey(KEY_USE_MI_WINDOW, false)) {
                MODE_SUNSHINE
            } else {
                settingsStore.readKey(KEY_FREEFORM_CODE, -1)
            }
            val selectedIndex = itemsCode.indexOf(oldCode).takeIf { it >= 0 } ?: 0
            setText(items[selectedIndex], false)

            setOnItemClickListener { _, _, position, _ ->
                lifecycleScope.launch {
                    val selectedCode = itemsCode[position]
                    settingsStore.edit { preferences ->
                        // Preserve the existing Sunshine setting and keep its UI-only code
                        // out of Android's native windowing mode configuration.
                        preferences[booleanPreferencesKey(KEY_USE_MI_WINDOW)] = selectedCode == MODE_SUNSHINE
                        if (selectedCode != MODE_SUNSHINE) {
                            preferences[intPreferencesKey(KEY_FREEFORM_CODE)] = selectedCode
                        }
                    }
                    // Update disabled state
                    updateSizeAdjustmentDisabledState()
                }
            }
        }
    }

    private fun setupListeners() {
        // Orientation button group listener
        binding.directionButtons.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    binding.directionButtonPortrait.id -> {
                        _orientation.value = Configuration.ORIENTATION_PORTRAIT
                        setScreenOrientation(Configuration.ORIENTATION_PORTRAIT)
                    }

                    binding.directionButtonLandscape.id -> {
                        _orientation.value = Configuration.ORIENTATION_LANDSCAPE
                        setScreenOrientation(Configuration.ORIENTATION_LANDSCAPE)
                    }
                }
            }
        }

        binding.followTriggerSideSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_FREEFORM_FOLLOW_TRIGGER_SIDE, isChecked)
            }
        }

        binding.enableSizeChange.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !_shouldDisableSizeAdjustment.value) {
                binding.resizableRectangle.visibility = View.VISIBLE
            } else {
                binding.resizableRectangle.visibility = View.INVISIBLE
            }
        }

        binding.resetSizeButton.setOnLongClickListener {
            lifecycleScope.launch {
                val orientationVal = _orientation.value
                settingsStore.removeKey<Int>(getSettingsKey(KEY_TOP_MARGIN, orientationVal))
                settingsStore.removeKey<Float>(getSettingsKey(KEY_TOP_MARGIN, orientationVal))
                settingsStore.removeKey<Int>(getSettingsKey(KEY_LEFT_MARGIN, orientationVal))
                settingsStore.removeKey<Float>(getSettingsKey(KEY_LEFT_MARGIN, orientationVal))
                settingsStore.removeKey<Int>(getSettingsKey(KEY_WIDTH, orientationVal))
                settingsStore.removeKey<Float>(getSettingsKey(KEY_WIDTH, orientationVal))
                settingsStore.removeKey<Int>(getSettingsKey(KEY_HEIGHT, orientationVal))
                settingsStore.removeKey<Float>(getSettingsKey(KEY_HEIGHT, orientationVal))
                settingsStore.removeKey<Int>(getSettingsKey(KEY_EDGE_MARGIN, orientationVal))
                settingsStore.removeKey<Float>(getSettingsKey(KEY_EDGE_MARGIN, orientationVal))

                val left = getLeft(_orientation.value, settingsStore).dp2px
                val top = getTop(_orientation.value, settingsStore).dp2px
                val w = getWidth(_orientation.value, settingsStore).dp2px
                val h = getHeight(_orientation.value, settingsStore).dp2px
                val rectF = RectF(left, top, left + w, top + h)
                // Update size information display
                resizableRectangle.setRectangle(rectF)
                updateSizeInfoText(rectF)
                resizableRectangle.visibility = View.VISIBLE
            }
            return@setOnLongClickListener true
        }

        // Rectangle changes listener
        resizableRectangle.setOnRectangleChangedListener(object :
            ResizableRectangleView.OnRectangleChangedListener {
            override fun onRectangleChanged(rect: RectF) {
                lifecycleScope.launch {
                    val currentScreenWidth = resizableRectangle.width.takeIf { it > 0 }
                        ?: ScreenUtil.getScreenWidth(this@FreeFormSettingsActivity)
                    val leftMargin = rect.left
                    val rightMargin = (currentScreenWidth - rect.right).coerceAtLeast(0f)
                    val edgeMargin = minOf(leftMargin, rightMargin).px2dp

                    // Save settings in real time
                    settingsStore.saveKey(
                        getSettingsKey(KEY_WIDTH, _orientation.value),
                        rect.width().px2dp
                    )
                    settingsStore.saveKey(
                        getSettingsKey(KEY_HEIGHT, _orientation.value),
                        rect.height().px2dp
                    )
                    settingsStore.saveKey(
                        getSettingsKey(KEY_LEFT_MARGIN, _orientation.value),
                        rect.left.px2dp
                    )
                    settingsStore.saveKey(
                        getSettingsKey(KEY_TOP_MARGIN, _orientation.value),
                        rect.top.px2dp
                    )
                    settingsStore.saveKey(
                        getSettingsKey(KEY_EDGE_MARGIN, _orientation.value),
                        edgeMargin
                    )

                    // Update size information display
                    updateSizeInfoText(rect)
                }
            }

            override fun onHideButtonClicked() {
                binding.resizableRectangle.visibility = View.INVISIBLE
                binding.enableSizeChange.isChecked = false
            }

        })
    }

    private suspend fun updateResizableRectangle() {
        val left = getLeft(_orientation.value, settingsStore).dp2px
        val top = getTop(_orientation.value, settingsStore).dp2px
        val width = getWidth(_orientation.value, settingsStore).dp2px
        val height = getHeight(_orientation.value, settingsStore).dp2px

        val rectF = RectF(
            left,
            top,
            (left + width),
            (top + height)
        )
        resizableRectangle.setRectangle(rectF)
        updateSizeInfoText(rectF)
        resizableRectangle.visibility = View.INVISIBLE
    }

    private fun updateSizeInfoText(rect: RectF) {
        binding.sizeInfoText.text = getString(
            R.string.freeform_size_info,
            rect.width().toInt(),
            rect.height().toInt(),
            rect.left.toInt(),
            rect.top.toInt()
        )
    }

    /**
     * Updates disabled state for size adjustment.
     */
    private suspend fun updateSizeAdjustmentDisabledState() {
        val freeformCode = settingsStore.readKey(KEY_FREEFORM_CODE, -1)
        val useMiWindow = settingsStore.readKey(KEY_USE_MI_WINDOW, false)

        // Shared freeform and Nubia/RedMagic protocols do not support ActivityOptions size parameters
        val shouldDisable = (freeformCode == 106) || (freeformCode == -2) || useMiWindow
        _shouldDisableSizeAdjustment.value = shouldDisable

        // Update UI state
        binding.enableSizeChange.isEnabled = !shouldDisable
        binding.followTriggerSideSwitch.isEnabled = !shouldDisable

        if (shouldDisable) {
            // If disabled, turn off switch and hide rectangle view
            binding.enableSizeChange.isChecked = false
            binding.resizableRectangle.visibility = View.INVISIBLE

            // Optional disabled hint
            binding.enableSizeChange.alpha = 0.5f
            binding.followTriggerSideSwitch.alpha = 0.5f
        } else {
            binding.enableSizeChange.alpha = 1.0f
            binding.followTriggerSideSwitch.alpha = 1.0f
        }
    }

    /**
     * Observes disabled state for size adjustment.
     */
    private fun observeSizeAdjustmentState() {
        lifecycleScope.launch {
            _shouldDisableSizeAdjustment.collect { shouldDisable ->
                binding.enableSizeChange.isEnabled = !shouldDisable
                binding.followTriggerSideSwitch.isEnabled = !shouldDisable

                if (shouldDisable) {
                    binding.enableSizeChange.isChecked = false
                    binding.resizableRectangle.visibility = View.INVISIBLE
                    binding.enableSizeChange.alpha = 0.5f
                    binding.followTriggerSideSwitch.alpha = 0.5f
                } else {
                    binding.enableSizeChange.alpha = 1.0f
                    binding.followTriggerSideSwitch.alpha = 1.0f
                }
            }
        }
    }

    private fun observeSettings() {
        lifecycleScope.launch {
            orientation.collect { orientationValue ->
                if (orientationValue == Configuration.ORIENTATION_PORTRAIT) {
                    binding.directionButtons.check(binding.directionButtonPortrait.id)
                } else {
                    binding.directionButtons.check(binding.directionButtonLandscape.id)
                }
                initializeViews()
            }
        }
    }
}
