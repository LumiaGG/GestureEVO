package io.github.lumiagg.gestureevo.settings

import io.github.lumiagg.gestureevo.databinding.BottomSheetAppIconOverrideBinding
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActivityAppearanceSettingsBinding
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.settings.ui.DefaultSelectAPPBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.SelectAPPCallBack
import io.github.lumiagg.gestureevo.gesture.ui.GestureFluidAppearance
import io.github.lumiagg.gestureevo.gesture.ui.GestureFluidConfig
import io.github.lumiagg.gestureevo.settings.ui.SettingsSwitchRow
import io.github.lumiagg.gestureevo.settings.ui.showColorPickerDialog
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.ToastHelper
import io.github.lumiagg.gestureevo.utils.app.appDisplayName
import io.github.lumiagg.gestureevo.utils.icon.IconFileUtils
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import io.github.lumiagg.gestureevo.utils.icon.IconPackRepository
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppearanceSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppearanceSettingsBinding
    private val overrideIconFileUtils by lazy { IconFileUtils(this) }
    private var pendingOverridePackage: APPModel? = null

    companion object {
        const val KEY_APP_LANGUAGE = "key_app_language"
        const val KEY_ICON_PACK = "key_icon_pack"
        const val KEY_ICON_IMAGE_ROUND_PERCENT = "key_global_icon_image_round_percent"
        const val KEY_APP_ICON_OVERRIDES = "key_app_icon_overrides"
        const val KEY_WHEEL_BACKGROUND_COLOR = "key_wheel_background_color"
        const val KEY_WHEEL_BACKGROUND_BLUR = "key_wheel_background_blur"
        const val KEY_SEARCH_BACKGROUND_COLOR = "key_search_background_color"
        const val KEY_SEARCH_BACKGROUND_BLUR = "key_search_background_blur"
        const val KEY_SEARCH_CARD_BACKGROUND_BLUR = "key_search_card_background_blur"
        const val KEY_KEYBOARD_BACKGROUND_BLUR = "key_keyboard_background_blur"
        const val KEY_KEYBOARD_CARD_BACKGROUND_BLUR = "key_keyboard_card_background_blur"
        const val KEY_PIN_POOL_BACKGROUND_BLUR = "key_pin_pool_background_blur"
        const val KEY_PIN_POOL_CARD_BACKGROUND_BLUR = "key_pin_pool_card_background_blur"
        const val KEY_SCREEN_CONTENT_BACKGROUND_BLUR = "key_screen_content_background_blur"
        const val KEY_SCREEN_CONTENT_CARD_BACKGROUND_BLUR = "key_screen_content_card_background_blur"
        const val KEY_DYNAMIC_COLOR_SCHEME = "key_dynamic_color_scheme"
        const val KEY_POINTER_COLOR = "key_pointer_color"
        const val KEY_GESTURE_FLUID_COLOR = "key_gesture_fluid_color"
        const val KEY_GESTURE_FLUID_STYLE = "key_gesture_fluid_style"
        const val KEY_GESTURE_FLUID_SIZE = "key_gesture_fluid_size"
        const val KEY_GESTURE_FLUID_STROKE = "key_gesture_fluid_stroke"
        const val KEY_GESTURE_FLUID_STROKE_COLOR = "key_gesture_fluid_stroke_color"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppearanceSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        lifecycleScope.launch {
            initializeViews()
            setupListeners()
            SettingsSearchNavigator.focusAnchorIfNeeded(binding.root, intent)
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.toolbar.title = getString(R.string.appearance_settings_title)
    }

    private suspend fun initializeViews() {
        setupDynamicColorSchemeDropdown()
        val savedPercent = settingsStore.readKey(KEY_ICON_IMAGE_ROUND_PERCENT, 0f)
        binding.iconImageRoundPercentSlider.value = savedPercent
        binding.iconImageRoundPercentValue.text = getString(R.string.display_percent, (savedPercent * 100).toInt())
        binding.wheelBackgroundBlurSwitch.isChecked =
            settingsStore.readKey(KEY_WHEEL_BACKGROUND_BLUR, false)
        val searchBackgroundBlurEnabled = settingsStore.readKey(KEY_SEARCH_BACKGROUND_BLUR, false)
        binding.searchBackgroundBlurSwitch.isChecked = searchBackgroundBlurEnabled
        binding.searchCardBackgroundBlurSwitch.isChecked =
            settingsStore.readKey(KEY_SEARCH_CARD_BACKGROUND_BLUR, false)
        val keyboardBackgroundBlurEnabled = settingsStore.readKey(KEY_KEYBOARD_BACKGROUND_BLUR, false)
        binding.keyboardBackgroundBlurSwitch.isChecked = keyboardBackgroundBlurEnabled
        binding.keyboardCardBackgroundBlurSwitch.isChecked =
            settingsStore.readKey(KEY_KEYBOARD_CARD_BACKGROUND_BLUR, false)
        val pinPoolBackgroundBlurEnabled = settingsStore.readKey(KEY_PIN_POOL_BACKGROUND_BLUR, false)
        binding.pinPoolBackgroundBlurSwitch.isChecked = pinPoolBackgroundBlurEnabled
        binding.pinPoolCardBackgroundBlurSwitch.isChecked =
            settingsStore.readKey(KEY_PIN_POOL_CARD_BACKGROUND_BLUR, false)
        val screenContentBackgroundBlurEnabled =
            settingsStore.readKey(KEY_SCREEN_CONTENT_BACKGROUND_BLUR, false)
        binding.screenContentBackgroundBlurSwitch.isChecked = screenContentBackgroundBlurEnabled
        binding.screenContentCardBackgroundBlurSwitch.isChecked =
            settingsStore.readKey(KEY_SCREEN_CONTENT_CARD_BACKGROUND_BLUR, false)
        setupIconPackDropdown()
        setupLanguageDropdown()
        setupFluidFeedbackViews()
        refreshColorSwatches()
    }

    private suspend fun refreshColorSwatches() {
        binding.wheelColorBtn.setSwatchColor(resolveWheelBackgroundColor())
        binding.searchColorBtn.setSwatchColor(resolveSearchBackgroundColor())
        val pointer = settingsStore.readKey(KEY_POINTER_COLOR, 0)
        binding.pointerColorBtn.setSwatchColor(
            if (pointer == 0) DynamicColorUtils.primaryColor else pointer
        )
        val fluid = settingsStore.readKey(KEY_GESTURE_FLUID_COLOR, 0)
        binding.fluidColorBtn.setSwatchColor(
            if (fluid == 0) DynamicColorUtils.secondaryContainerColor else fluid
        )
        val stroke = settingsStore.readKey(KEY_GESTURE_FLUID_STROKE_COLOR, 0)
        binding.fluidStrokeColorBtn.setSwatchColor(
            when {
                stroke != 0 -> stroke
                fluid != 0 -> fluid
                else -> DynamicColorUtils.secondaryContainerColor
            }
        )
    }

    private fun setupListeners() {
        binding.iconImageRoundPercentSlider.addOnChangeListener { _, value, _ ->
            binding.iconImageRoundPercentValue.text = getString(R.string.display_percent, (value * 100).toInt())
        }
        binding.iconImageRoundPercentSlider.addOnSliderTouchListener(object :
            Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}

            override fun onStopTrackingTouch(slider: Slider) {
                lifecycleScope.launch {
                    settingsStore.saveKey(KEY_ICON_IMAGE_ROUND_PERCENT, slider.value)
                    IconHelper.iconImageRoundPercent = slider.value
                }
            }
        })


        binding.wheelColorBtn.setOnClickListener {
            lifecycleScope.launch {
                val initialColor = resolveWheelBackgroundColor()
                showColorPickerDialog(
                    activity = this@AppearanceSettingsActivity,
                    initialColor = initialColor,
                    enableAlpha = true,
                    onColorSelected = { color ->
                        lifecycleScope.launch {
                            settingsStore.saveKey(KEY_WHEEL_BACKGROUND_COLOR, color)
                            refreshColorSwatches()
                        }
                    },
                    onColorPreview = { }
                )
            }
        }
        binding.wheelColorBtn.setOnLongClickListener {
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_WHEEL_BACKGROUND_COLOR, 0)
                refreshColorSwatches()
            }
            true
        }

        binding.searchColorBtn.setOnClickListener {
            lifecycleScope.launch {
                val initialColor = resolveSearchBackgroundColor()
                showColorPickerDialog(
                    activity = this@AppearanceSettingsActivity,
                    initialColor = initialColor,
                    enableAlpha = true,
                    onColorSelected = { color ->
                        lifecycleScope.launch {
                            settingsStore.saveKey(KEY_SEARCH_BACKGROUND_COLOR, color)
                            refreshColorSwatches()
                        }
                    },
                    onColorPreview = { }
                )
            }
        }
        binding.searchColorBtn.setOnLongClickListener {
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_SEARCH_BACKGROUND_COLOR, 0)
                refreshColorSwatches()
            }
            true
        }

        binding.wheelBackgroundBlurSwitch.setOnCheckedChangeListener { _, isCheck ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_WHEEL_BACKGROUND_BLUR, isCheck)
            }
        }

        binding.searchBackgroundBlurSwitch.setOnCheckedChangeListener { _, isCheck ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_SEARCH_BACKGROUND_BLUR, isCheck)
            }
        }

        binding.searchCardBackgroundBlurSwitch.setOnCheckedChangeListener { _, isCheck ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_SEARCH_CARD_BACKGROUND_BLUR, isCheck)
            }
        }

        binding.keyboardBackgroundBlurSwitch.setOnCheckedChangeListener { _, isCheck ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_KEYBOARD_BACKGROUND_BLUR, isCheck)
            }
        }

        binding.keyboardCardBackgroundBlurSwitch.setOnCheckedChangeListener { _, isCheck ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_KEYBOARD_CARD_BACKGROUND_BLUR, isCheck)
            }
        }

        binding.pinPoolBackgroundBlurSwitch.setOnCheckedChangeListener { _, isCheck ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_PIN_POOL_BACKGROUND_BLUR, isCheck)
            }
        }

        binding.pinPoolCardBackgroundBlurSwitch.setOnCheckedChangeListener { _, isCheck ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_PIN_POOL_CARD_BACKGROUND_BLUR, isCheck)
            }
        }

        binding.screenContentBackgroundBlurSwitch.setOnCheckedChangeListener { _, isCheck ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_SCREEN_CONTENT_BACKGROUND_BLUR, isCheck)
            }
        }

        binding.screenContentCardBackgroundBlurSwitch.setOnCheckedChangeListener { _, isCheck ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_SCREEN_CONTENT_CARD_BACKGROUND_BLUR, isCheck)
            }
        }

        binding.appIconOverrideButton.setOnClickListener {
            showAppIconOverrideFlow()
        }

        binding.pointerColorBtn.setOnClickListener {
            lifecycleScope.launch {
                val savedColor = settingsStore.readKey(KEY_POINTER_COLOR, 0)
                val initialColor =
                    if (savedColor == 0) DynamicColorUtils.primaryColor else savedColor
                showColorPickerDialog(
                    activity = this@AppearanceSettingsActivity,
                    initialColor = initialColor,
                    enableAlpha = true,
                    onColorSelected = { color ->
                        lifecycleScope.launch {
                            settingsStore.saveKey(KEY_POINTER_COLOR, color)
                            refreshColorSwatches()
                        }
                    },
                    onColorPreview = { /* Real-time preview (optional) */ }
                )
            }
        }

        binding.pointerColorBtn.setOnLongClickListener {
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_POINTER_COLOR, 0)
                refreshColorSwatches()
            }
            true
        }

        binding.fluidColorBtn.setOnClickListener {
            lifecycleScope.launch {
                val savedColor = settingsStore.readKey(KEY_GESTURE_FLUID_COLOR, 0)
                val initialColor =
                    if (savedColor == 0) DynamicColorUtils.secondaryContainerColor else savedColor
                showColorPickerDialog(
                    activity = this@AppearanceSettingsActivity,
                    initialColor = initialColor,
                    enableAlpha = true,
                    onColorSelected = { color ->
                        lifecycleScope.launch {
                            settingsStore.saveKey(KEY_GESTURE_FLUID_COLOR, color)
                            GestureFluidAppearance.refresh(applicationContext)
                            refreshColorSwatches()
                        }
                    },
                    onColorPreview = { }
                )
            }
        }
        binding.fluidColorBtn.setOnLongClickListener {
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_GESTURE_FLUID_COLOR, 0)
                GestureFluidAppearance.refresh(applicationContext)
                refreshColorSwatches()
            }
            true
        }

        binding.fluidSizeSlider.addOnChangeListener { _, value, _ ->
            binding.fluidSizeValue.text = getString(R.string.display_percent, (value * 100).toInt())
        }
        binding.fluidSizeSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}

            override fun onStopTrackingTouch(slider: Slider) {
                lifecycleScope.launch {
                    settingsStore.saveKey(KEY_GESTURE_FLUID_SIZE, slider.value)
                    GestureFluidAppearance.refresh(applicationContext)
                }
            }
        })

        binding.fluidStrokeSlider.addOnChangeListener { _, value, _ ->
            binding.fluidStrokeValue.text = formatFluidStroke(value)
        }
        binding.fluidStrokeSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}

            override fun onStopTrackingTouch(slider: Slider) {
                lifecycleScope.launch {
                    settingsStore.saveKey(KEY_GESTURE_FLUID_STROKE, slider.value)
                    GestureFluidAppearance.refresh(applicationContext)
                }
            }
        })

        binding.fluidStrokeColorBtn.setOnClickListener {
            lifecycleScope.launch {
                val savedColor = settingsStore.readKey(KEY_GESTURE_FLUID_STROKE_COLOR, 0)
                val fluidColor = settingsStore.readKey(KEY_GESTURE_FLUID_COLOR, 0)
                val initialColor = when {
                    savedColor != 0 -> savedColor
                    fluidColor != 0 -> fluidColor
                    else -> DynamicColorUtils.secondaryContainerColor
                }
                showColorPickerDialog(
                    activity = this@AppearanceSettingsActivity,
                    initialColor = initialColor,
                    enableAlpha = true,
                    onColorSelected = { color ->
                        lifecycleScope.launch {
                            settingsStore.saveKey(KEY_GESTURE_FLUID_STROKE_COLOR, color)
                            GestureFluidAppearance.refresh(applicationContext)
                            refreshColorSwatches()
                        }
                    },
                    onColorPreview = { }
                )
            }
        }
        binding.fluidStrokeColorBtn.setOnLongClickListener {
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_GESTURE_FLUID_STROKE_COLOR, 0)
                GestureFluidAppearance.refresh(applicationContext)
                refreshColorSwatches()
            }
            true
        }
    }

    private suspend fun resolveWheelBackgroundColor(): Int {
        val savedColor = settingsStore.readKey(KEY_WHEEL_BACKGROUND_COLOR, 0)
        if (savedColor != 0) return savedColor
        DynamicColorUtils.refreshSurfaceColors(applicationContext)
        return DynamicColorUtils.surfaceContainerHighestColor
    }

    private suspend fun resolveSearchBackgroundColor(): Int {
        val savedColor = settingsStore.readKey(KEY_SEARCH_BACKGROUND_COLOR, 0)
        if (savedColor != 0) return savedColor
        DynamicColorUtils.refreshSurfaceColors(applicationContext)
        return DynamicColorUtils.surfaceContainerHighestColor
    }

    private suspend fun setupIconPackDropdown() {
        val itemsPkg = IconPackRepository(applicationContext).availablePackageNames().toMutableList()
        val itemsName = ArrayList(itemsPkg.map { appDisplayName(applicationContext, it) })
        itemsPkg.add("")
        itemsName.add(getString(R.string.appearance_settings_icon_pack_none))
        val adapter =
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, itemsName)
        binding.iconPackAutoComplete.apply {
            setAdapter(adapter)
            val oldPack = settingsStore.readKey(KEY_ICON_PACK, "")
            if (oldPack.isNotEmpty()) {
                setText(appDisplayName(applicationContext, oldPack), false)
            }

            setOnItemClickListener { _, _, position, _ ->
                lifecycleScope.launch {
                    settingsStore.saveKey(KEY_ICON_PACK, itemsPkg[position])
                    IconHelper.initIconConfig(applicationContext, itemsPkg[position])
                }
            }
        }
    }

    private suspend fun setupFluidFeedbackViews() {
        val size = settingsStore.readKey(KEY_GESTURE_FLUID_SIZE, 1f)
            .coerceIn(GestureFluidAppearance.SIZE_MIN, GestureFluidAppearance.SIZE_MAX)
        binding.fluidSizeSlider.value = size
        binding.fluidSizeValue.text = getString(R.string.display_percent, (size * 100).toInt())

        val stroke = settingsStore.readKey(KEY_GESTURE_FLUID_STROKE, 0f)
            .coerceIn(GestureFluidAppearance.STROKE_MIN, GestureFluidAppearance.STROKE_MAX)
        binding.fluidStrokeSlider.value = stroke
        binding.fluidStrokeValue.text = formatFluidStroke(stroke)

        val labels = listOf(
            getString(R.string.appearance_settings_fluid_style_soft),
            getString(R.string.appearance_settings_fluid_style_sharp),
            getString(R.string.appearance_settings_fluid_style_wide),
        )
        val keys = listOf(
            GestureFluidConfig.Style.CLASSIC.key,
            GestureFluidConfig.Style.CAPSULE.key,
            GestureFluidConfig.Style.ARC.key,
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels)
        binding.fluidStyleAutoComplete.apply {
            setAdapter(adapter)
            val savedKey = GestureFluidConfig.Style.fromKey(
                settingsStore.readKey(
                    KEY_GESTURE_FLUID_STYLE,
                    GestureFluidConfig.Style.CLASSIC.key
                )
            ).key
            val index = keys.indexOf(savedKey).takeIf { it >= 0 } ?: 0
            setText(labels[index], false)
            setOnItemClickListener { _, _, position, _ ->
                lifecycleScope.launch {
                    settingsStore.saveKey(KEY_GESTURE_FLUID_STYLE, keys[position])
                    GestureFluidAppearance.refresh(applicationContext)
                }
            }
        }
        GestureFluidAppearance.refresh(applicationContext)
    }

    private fun formatFluidStroke(value: Float): String {
        return if (value <= 0f) {
            getString(R.string.appearance_settings_fluid_stroke_off)
        } else {
            getString(R.string.appearance_settings_fluid_stroke_value, value)
        }
    }

    private suspend fun setupLanguageDropdown() {
        val labels = listOf(
            getString(R.string.appearance_settings_language_follow_system),
            getString(R.string.appearance_settings_language_chinese),
            getString(R.string.appearance_settings_language_english)
        )
        val tags = listOf("", "zh-CN", "en")
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels)
        binding.languageAutoComplete.apply {
            setAdapter(adapter)
            val savedTag = settingsStore.readKey(KEY_APP_LANGUAGE, "")
            val index = tags.indexOf(savedTag).takeIf { it >= 0 } ?: 0
            setText(labels[index], false)
            setOnItemClickListener { _, _, position, _ ->
                val tag = tags[position]
                lifecycleScope.launch {
                    settingsStore.saveKey(KEY_APP_LANGUAGE, tag)
                    val locales = if (tag.isEmpty()) {
                        LocaleListCompat.getEmptyLocaleList()
                    } else {
                        LocaleListCompat.forLanguageTags(tag)
                    }
                    AppCompatDelegate.setApplicationLocales(locales)
                }
            }
        }
    }

    private suspend fun setupDynamicColorSchemeDropdown() {
        val labels = mutableListOf(
            getString(R.string.appearance_settings_color_scheme_system),
            getString(R.string.appearance_settings_color_scheme_forest),
            getString(R.string.appearance_settings_color_scheme_coral),
            getString(R.string.appearance_settings_color_scheme_ocean),
            getString(R.string.appearance_settings_color_scheme_sunset),
            getString(R.string.appearance_settings_color_scheme_midnight),
            getString(R.string.appearance_settings_color_scheme_pink)
        )
        val keys = mutableListOf("system", "forest", "coral", "ocean", "sunset", "midnight", "pink")
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels)
        binding.dynamicColorSchemeAutoComplete.apply {
            setAdapter(adapter)
            val savedKey = settingsStore.readKey(KEY_DYNAMIC_COLOR_SCHEME, keys.first())
            val index = keys.indexOf(savedKey).takeIf { it >= 0 } ?: 0
            setText(labels[index], false)
            setOnItemClickListener { _, _, position, _ ->
                lifecycleScope.launch {
                    val key = keys.getOrElse(position) { keys.first() }
                    settingsStore.saveKey(KEY_DYNAMIC_COLOR_SCHEME, key)
                    DynamicColorUtils.updateDynamicColorScheme(applicationContext, key)
                    ToastHelper.showInUIThread(
                        applicationContext,
                        R.string.appearance_settings_color_scheme_restart_hint
                    )
                }
            }
        }
    }

    private fun showAppIconOverrideFlow() {
        DefaultSelectAPPBottomSheet(this, lifecycleScope, object : SelectAPPCallBack {
            override fun selectCompleted(model: APPModel) {
                pendingOverridePackage = model
                showAppIconOverrideOptions(model)
            }
        }).show()
    }

    private fun showAppIconOverrideOptions(model: APPModel) {
        val bottomSheet =
            BottomSheetDialog(this, R.style.ThemeOverlay_BottomSheetDialog_Scrollable)
        val bottomSheetView = BottomSheetAppIconOverrideBinding.inflate(layoutInflater).root

        bottomSheetView.findViewById<TextView>(R.id.target_app_name).text = model.name
        bottomSheetView.findViewById<TextView>(R.id.target_app_pkg).text = model.pkgName

        bottomSheetView.findViewById<View>(R.id.select_gallery)
            .setOnClickListener {
                bottomSheet.dismiss()
                selectOverrideImageLauncher.launch("image/*")
            }

        bottomSheetView.findViewById<View>(R.id.select_app_icon)
            .setOnClickListener {
                bottomSheet.dismiss()
                DefaultSelectAPPBottomSheet(this, lifecycleScope, object : SelectAPPCallBack {
                    override fun selectCompleted(source: APPModel) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val drawable =
                                IconHelper.getIcon(applicationContext, source.pkgName)
                            if (drawable == null) {
                                withContext(Dispatchers.Main) {
                                    ToastHelper.showInUIThread(
                                        applicationContext,
                                        R.string.appearance_settings_icon_override_failed
                                    )
                                }
                                return@launch
                            }
                            overrideIconFileUtils.clear()
                            overrideIconFileUtils.setImgDrawable(drawable)
                            if (overrideIconFileUtils.saveIcon()) {
                                IconHelper.setAppIconOverride(
                                    applicationContext,
                                    model.pkgName,
                                    overrideIconFileUtils.imgPath
                                )
                                withContext(Dispatchers.Main) {
                                    ToastHelper.showInUIThread(
                                        applicationContext,
                                        R.string.appearance_settings_icon_override_saved
                                    )
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    ToastHelper.showInUIThread(
                                        applicationContext,
                                        R.string.appearance_settings_icon_override_failed
                                    )
                                }
                            }
                        }
                    }
                }).show()
            }

        bottomSheetView.findViewById<View>(R.id.clear_override)
            .setOnClickListener {
                bottomSheet.dismiss()
                lifecycleScope.launch {
                    IconHelper.setAppIconOverride(applicationContext, model.pkgName, null)
                    ToastHelper.showInUIThread(
                        applicationContext,
                        R.string.appearance_settings_icon_override_cleared
                    )
                }
            }

        bottomSheet.setContentView(bottomSheetView)
        bottomSheet.show()
    }

    private val selectOverrideImageLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        val target = pendingOverridePackage ?: return@registerForActivityResult
        uri?.let { selectedUri ->
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    overrideIconFileUtils.clear()
                    overrideIconFileUtils.setGalleryIcon(selectedUri)
                    if (overrideIconFileUtils.saveIcon()) {
                        IconHelper.setAppIconOverride(
                            applicationContext,
                            target.pkgName,
                            overrideIconFileUtils.imgPath
                        )
                        withContext(Dispatchers.Main) {
                            ToastHelper.showInUIThread(
                                applicationContext,
                                R.string.appearance_settings_icon_override_saved
                            )
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            ToastHelper.showInUIThread(
                                applicationContext,
                                R.string.appearance_settings_icon_override_failed
                            )
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        ToastHelper.showInUIThread(
                            applicationContext,
                            R.string.appearance_settings_icon_override_failed
                        )
                    }
                }
                pendingOverridePackage = null
            }
        } ?: run { pendingOverridePackage = null }
    }
}
