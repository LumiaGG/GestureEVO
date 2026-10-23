package io.github.lumiagg.gestureevo.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActivityInspireSettingsBinding
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.gesture.EventBusManager.produceKeyboardEvent
import io.github.lumiagg.gestureevo.gesture.KeyboardEvent
import io.github.lumiagg.gestureevo.quick.ui.KeyboardType
import io.github.lumiagg.gestureevo.settings.ui.BlackListSelectAPPBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.DefaultLinkOpenManager
import io.github.lumiagg.gestureevo.settings.ui.DefaultSelectAPPBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.NotificationDisplayTimeBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.PointerTouchAreaOffsetBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.SelectAPPCallBack
import io.github.lumiagg.gestureevo.settings.ui.showEditTextBottomSheet
import io.github.lumiagg.gestureevo.utils.ToastHelper
import io.github.lumiagg.gestureevo.utils.app.appDisplayName
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class InspireSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityInspireSettingsBinding

    private val notSelection: String
        get() = getString(R.string.inspire_settings_current_not_selected)
    private val clearSelection: String
        get() = getString(R.string.inspire_settings_clear_selection)
    private val evoImageEditorLabel: String
        get() = getString(R.string.inspire_settings_use_evo_image_editor)
    private val evoSearchLabel: String
        get() = getString(R.string.inspire_settings_use_evo_search)

    companion object {
        // Preference keys
        const val KEY_OCR_PRIORITY = "inspire_ocr_priority"
        const val KEY_INSPIRE_DOUBLE_CHECK = "key_inspire_double_check"
        const val KEY_INSPIRE_SELECTION_VIBRATE = "key_inspire_selection_vibrate"
        const val KEY_INSPIRE_DELAY_REMOVE_IMAGE = "key_inspire_delay_remove_image"
        const val KEY_INSPIRE_SHOW_ENGINE = "key_inspire_show_engine"
        const val KEY_DEFAULT_IMAGE_TEXT_OVERLAY = "key_default_image_text_overlay"
        const val KEY_INSPIRE_STRONG_COPY = "key_inspire_strong_copy"
        const val KEY_KEYBOARD_TYPE = "keyboard_type"
        const val KEY_TRANSLATION_APP = "key_translation_app"

        const val KEY_IMAGE_APP = "key_image_app"
        const val KEY_DEFAULT_SHARE_IMAGE_APP = "key_default_share_image_app"
        const val KEY_SEARCH_APP = "key_search_app"
        const val KEY_SEARCH_LINK = "key_search_link"
        const val KEY_SHOW_NOTIFICATION_ICON = "key_show_notification_icon"
        const val KEY_NOTIFICATION_IMAGE_DISPLAY_SECONDS =
            "key_notification_image_display_seconds"
        const val KEY_SHOW_POINTER_TRAIL = "key_show_pointer_trail"
        const val KEY_ALWAYS_SHOW_POINTER_TOUCH_AREA = "key_always_show_pointer_touch_area"
        const val KEY_POINTER_TOUCH_AREA_OFFSET_DP = "key_pointer_touch_area_offset_dp"
        const val KEY_ENABLE_POINTER_DRAG_MODE = "key_enable_pointer_drag_mode"
        const val KEY_PIN_POOL_DISPLAY_MODE = "key_pin_pool_display_mode"
        const val KEY_PIN_POOL_LOCAL_RULE_EXTRACTION = "key_pin_pool_local_rule_extraction"
        const val DEFAULT_PIN_POOL_LOCAL_RULE_EXTRACTION = false
        const val KEY_PIN_POOL_SAVE_SCREEN_CONTENT_IMAGES =
            "key_pin_pool_save_screen_content_images"
        const val KEY_PIN_POOL_AUTO_COPY_IMAGE_TO_CLIPBOARD =
            "key_pin_pool_auto_copy_image_to_clipboard"
        const val DEFAULT_PIN_POOL_AUTO_COPY_IMAGE_TO_CLIPBOARD = false
        const val KEY_PIN_POOL_AUTO_DELETE_DAYS = "key_pin_pool_auto_delete_days"
        const val PIN_POOL_AUTO_DELETE_DAYS_MAX = 30
        const val PIN_POOL_DISPLAY_MODE_FULLSCREEN = "fullscreen"
        const val PIN_POOL_DISPLAY_MODE_LEFT_SIDEBAR = "left_sidebar"
        const val PIN_POOL_DISPLAY_MODE_RIGHT_SIDEBAR = "right_sidebar"
        const val PIN_POOL_DISPLAY_MODE_FOLLOW_TRIGGER = "follow_trigger"
        const val PIN_POOL_DISPLAY_MODE_LEGACY_SIDEBAR = "sidebar"
        const val KEY_DEFAULT_SHARE_BEHAVIOR = "key_default_share_behavior"
        const val DEFAULT_NOTIFICATION_IMAGE_DISPLAY_SECONDS = 6
//        const val KEY_POINTER_ICON_PATH = "key_pointer_icon_PATH"
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInspireSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()

        lifecycleScope.launch {
            initializeViews()
            setupListeners()
            SettingsSearchNavigator.focusAnchorIfNeeded(binding.root, intent)
        }
    }

    private fun setupToolbar() {
        // Setup back button
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private suspend fun initializeViews() {
        // Initialize OCR-priority switch state
        val ocrPriority = settingsStore.readKey(KEY_OCR_PRIORITY, true)
        binding.ocrPrioritySwitch.isChecked = ocrPriority

        // Double confirmation
        binding.doubleCheckSwitch.isChecked = settingsStore.readKey(KEY_INSPIRE_DOUBLE_CHECK, false)

        // Vibrate on selection enter
        binding.selectionVibrateSwitch.isChecked =
            settingsStore.readKey(KEY_INSPIRE_SELECTION_VIBRATE, true)

        // Delayed deletion
        binding.delayRemoveImageSwitch.isChecked =
            settingsStore.readKey(KEY_INSPIRE_DELAY_REMOVE_IMAGE, false)

        // Display search engines
        binding.showEngineSwitch.isChecked = settingsStore.readKey(KEY_INSPIRE_SHOW_ENGINE, false)
        binding.defaultImageTextOverlaySwitch.isChecked =
            settingsStore.readKey(KEY_DEFAULT_IMAGE_TEXT_OVERLAY, false)
        binding.strongCopySwitch.isChecked = settingsStore.readKey(KEY_INSPIRE_STRONG_COPY, false)
        // Default share behavior
        val defaultShareBehavior = settingsStore.readKey(KEY_DEFAULT_SHARE_BEHAVIOR, "tokenization")
        updateDefaultShareBehaviorText(defaultShareBehavior)

        // Notification icon
        binding.notificationIconSwitch.isChecked =
            settingsStore.readKey(KEY_SHOW_NOTIFICATION_ICON, false)
        updateNotificationDisplayTimeText(
            settingsStore.readKey(
                KEY_NOTIFICATION_IMAGE_DISPLAY_SECONDS,
                DEFAULT_NOTIFICATION_IMAGE_DISPLAY_SECONDS
            )
        )

        // Pointer trail
        binding.pointerTrailSwitch.isChecked =
            settingsStore.readKey(KEY_SHOW_POINTER_TRAIL, false)

        binding.pointerTouchAreaSwitch.isChecked =
            settingsStore.readKey(KEY_ALWAYS_SHOW_POINTER_TOUCH_AREA, false)
        updatePointerTouchAreaOffsetText(settingsStore.readKey(KEY_POINTER_TOUCH_AREA_OFFSET_DP, 0))

        binding.pointerDragModeSwitch.isChecked =
            settingsStore.readKey(KEY_ENABLE_POINTER_DRAG_MODE, false)

        val pinPoolDisplayMode =
            settingsStore.readKey(KEY_PIN_POOL_DISPLAY_MODE, PIN_POOL_DISPLAY_MODE_RIGHT_SIDEBAR)
        updatePinPoolModeText(pinPoolDisplayMode)
        binding.pinPoolLocalRuleExtractionSwitch.isChecked = settingsStore.readKey(
            KEY_PIN_POOL_LOCAL_RULE_EXTRACTION,
            DEFAULT_PIN_POOL_LOCAL_RULE_EXTRACTION
        )
        val autoSaveEnabled = settingsStore.readKey(
            KEY_PIN_POOL_SAVE_SCREEN_CONTENT_IMAGES,
            true
        )
        binding.pinPoolSaveScreenContentSwitch.isChecked = autoSaveEnabled
        binding.pinPoolAutoCopyClipboardSwitch.isChecked = settingsStore.readKey(
            KEY_PIN_POOL_AUTO_COPY_IMAGE_TO_CLIPBOARD,
            DEFAULT_PIN_POOL_AUTO_COPY_IMAGE_TO_CLIPBOARD
        )
        updatePinPoolAutoDeleteVisibility(autoSaveEnabled)
        updatePinPoolAutoDeleteText(
            settingsStore.readKey(KEY_PIN_POOL_AUTO_DELETE_DAYS, 0)
        )

        // Initialize keyboard type selection state
        val keyboardType = settingsStore.readKey(KEY_KEYBOARD_TYPE, KeyboardType.DOUBLE_KEY.name)
        updateKeyboardTypeText(KeyboardType.valueOfSafe(keyboardType))

        // Translation app
        val translationApp = settingsStore.readKey(KEY_TRANSLATION_APP, "")
        updateSelectionRow(
            textView = binding.selectedTranslationAppText,
            clearButton = binding.clearTranslationAppButton,
            value = if (translationApp.isEmpty()) notSelection
            else appDisplayName(applicationContext, translationApp) ?: notSelection
        )

        // Image app
        val imageApp = settingsStore.readKey(KEY_IMAGE_APP, "")
        updateImageSelectionText(
            value = if (imageApp.isEmpty()) evoImageEditorLabel
            else appDisplayName(applicationContext, imageApp) ?: evoImageEditorLabel
        )

        // Default image sharing app
        val defaultShareImageApp = settingsStore.readKey(KEY_DEFAULT_SHARE_IMAGE_APP, "")
        updateSelectionRow(
            textView = binding.selectedDefaultShareImageAppText,
            clearButton = binding.clearDefaultShareImageAppButton,
            value = if (defaultShareImageApp.isEmpty()) notSelection
            else appDisplayName(applicationContext, defaultShareImageApp) ?: notSelection
        )

        // Search app
        val searchLink = settingsStore.readKey(KEY_SEARCH_LINK, "")
        if (searchLink.isEmpty()) {
            val searchApp = settingsStore.readKey(KEY_SEARCH_APP, "")
            updateSearchSelectionText(
                value =
                if (searchApp.isEmpty()) evoSearchLabel
                else appDisplayName(applicationContext, searchApp) ?: evoSearchLabel
            )
        } else {
            updateSearchSelectionText(searchLink)
        }

        binding.linkOpenRow.setOnClickListener {
            lifecycleScope.launch {
                val linkOpenManager =
                    DefaultLinkOpenManager(this@InspireSettingsActivity, lifecycleScope)
                linkOpenManager.showDefaultLinkOpenSettings()
            }
        }
    }

    private fun setupListeners() {
        // OCR-priority switch listener
        binding.ocrPrioritySwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_OCR_PRIORITY, isChecked)
                updateOcrPriorityState(isChecked)
            }
        }

        // Double confirmation
        binding.doubleCheckSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_INSPIRE_DOUBLE_CHECK, isChecked)
            }
        }

        // Vibrate on selection enter
        binding.selectionVibrateSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_INSPIRE_SELECTION_VIBRATE, isChecked)
            }
        }

        // Delayed deletion
        binding.delayRemoveImageSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_INSPIRE_DELAY_REMOVE_IMAGE, isChecked)
            }
        }

        // Display search engines
        binding.showEngineSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_INSPIRE_SHOW_ENGINE, isChecked)
            }
        }

        binding.defaultImageTextOverlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_DEFAULT_IMAGE_TEXT_OVERLAY, isChecked)
            }
        }

        binding.strongCopySwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_INSPIRE_STRONG_COPY, isChecked)
            }
        }

        binding.defaultShareBehaviorRow.setOnClickListener {
            showDefaultShareBehaviorDialog()
        }

        binding.keyboardTypeRow.setOnClickListener {
            showKeyboardTypeDialog()
        }

        // Notification app whitelist button click listener
        binding.notificationListenerWhiteListButton.setOnClickListener {
            BlackListSelectAPPBottomSheet(
                this,
                lifecycleScope,
                object : SelectAPPCallBack {
                    override fun selectCompleted(model: APPModel) {}
                },
                blackListFlag = APPModel.FLAG_NOTIFICATION_APPS_ENABLE,
                disableIcon = ContextCompat.getDrawable(
                    applicationContext,
                    R.drawable.baseline_check_24
                )
            ).show()
        }

        // Notification icon blacklist button click listener
        binding.notificationIconBlackListButton.setOnClickListener {
            BlackListSelectAPPBottomSheet(
                this,
                lifecycleScope,
                object : SelectAPPCallBack {
                    override fun selectCompleted(model: APPModel) {}
                },
                blackListFlag = APPModel.FLAG_NOTIFICATION_ICON_DISABLE,
            ).show()
        }

        binding.notificationDisplayTimeRow.setOnClickListener {
            showNotificationDisplayTimeBottomSheet()
        }

        // Notification permission check button
        binding.notificationPermissionButton.setOnClickListener {
            val intent: Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }

        binding.notificationIconSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_SHOW_NOTIFICATION_ICON, isChecked)
            }
        }

        // Translation app selection
        binding.translationAppRow.setOnClickListener {
            openAppSelector(
                key = KEY_TRANSLATION_APP,
                textView = binding.selectedTranslationAppText,
                clearButton = binding.clearTranslationAppButton
            )
        }
        binding.clearTranslationAppButton.setOnClickListener {
            clearSingleAppSelection(
                textView = binding.selectedTranslationAppText,
                key = KEY_TRANSLATION_APP,
                clearButton = binding.clearTranslationAppButton
            )
        }

        // Image app selection
        binding.imageAppRow.setOnClickListener { showImageAppConfigDialog() }
        binding.clearImageAppButton.setOnClickListener {
            selectEvoImageEditor()
        }

        // Default image sharing app selection
        binding.defaultShareImageAppRow.setOnClickListener {
            openAppSelector(
                key = KEY_DEFAULT_SHARE_IMAGE_APP,
                textView = binding.selectedDefaultShareImageAppText,
                clearButton = binding.clearDefaultShareImageAppButton
            )
        }
        binding.clearDefaultShareImageAppButton.setOnClickListener {
            clearSingleAppSelection(
                textView = binding.selectedDefaultShareImageAppText,
                key = KEY_DEFAULT_SHARE_IMAGE_APP,
                clearButton = binding.clearDefaultShareImageAppButton
            )
        }

        binding.searchAppRow.setOnClickListener { showSearchConfigDialog() }
        binding.clearSearchAppButton.setOnClickListener {
            selectEvoSearch()
        }

        // Pointer
        binding.pointerTrailSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_SHOW_POINTER_TRAIL, isChecked)
            }
        }

        binding.pointerTouchAreaSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_ALWAYS_SHOW_POINTER_TOUCH_AREA, isChecked)
            }
        }

        binding.pointerTouchAreaOffsetRow.setOnClickListener {
            showPointerTouchAreaOffsetBottomSheet()
        }

        binding.pointerDragModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_ENABLE_POINTER_DRAG_MODE, isChecked)
            }
        }

        binding.pinPoolModeRow.setOnClickListener {
            showPinPoolModeDialog()
        }

        binding.pinPoolLocalRuleExtractionSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_PIN_POOL_LOCAL_RULE_EXTRACTION, isChecked)
            }
        }

        binding.pinPoolSaveScreenContentSwitch.setOnCheckedChangeListener { _, isChecked ->
            updatePinPoolAutoDeleteVisibility(isChecked)
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_PIN_POOL_SAVE_SCREEN_CONTENT_IMAGES, isChecked)
            }
        }

        binding.pinPoolAutoDeleteRow.setOnClickListener {
            showPinPoolAutoDeleteBottomSheet()
        }

        binding.pinPoolAutoCopyClipboardSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_PIN_POOL_AUTO_COPY_IMAGE_TO_CLIPBOARD, isChecked)
            }
        }
    }

    private fun updateOcrPriorityState(enabled: Boolean) {
        // Additional logic when OCR-priority state changes
        // Such as updating UI state or triggering related functions
    }

    private fun clearSingleAppSelection(textView: TextView, key: String, clearButton: View) {
        lifecycleScope.launch {
            updateSelectionRow(textView, clearButton, notSelection)
            settingsStore.saveKey(key, "")
            ToastHelper.showInUIThread(applicationContext, clearSelection)
        }
    }

    private fun openAppSelector(key: String, textView: TextView, clearButton: View) {
        DefaultSelectAPPBottomSheet(this, lifecycleScope, object : SelectAPPCallBack {
            override fun selectCompleted(model: APPModel) {
                updateSelectionRow(textView, clearButton, model.name)
                lifecycleScope.launch {
                    settingsStore.saveKey(key, model.pkgName)
                }
            }
        }).show()
    }

    private fun showSearchConfigDialog() {
        val options = arrayOf(
            getString(R.string.inspire_settings_use_evo_search),
            getString(R.string.inspire_settings_select_app),
            getString(R.string.inspire_settings_custom_link)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.inspire_settings_search_app_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> selectEvoSearch()
                    1 -> openSearchAppSelector()
                    2 -> showSearchLinkEditor()
                }
            }
            .show()
    }

    private fun showImageAppConfigDialog() {
        val options = arrayOf(
            getString(R.string.inspire_settings_use_evo_image_editor),
            getString(R.string.inspire_settings_select_app)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.inspire_settings_image_app_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> selectEvoImageEditor()
                    1 -> openImageAppSelector()
                }
            }
            .show()
    }

    private fun selectEvoImageEditor() {
        lifecycleScope.launch {
            updateImageSelectionText(evoImageEditorLabel)
            settingsStore.saveKey(KEY_IMAGE_APP, "")
        }
    }

    private fun openImageAppSelector() {
        DefaultSelectAPPBottomSheet(this, lifecycleScope, object : SelectAPPCallBack {
            override fun selectCompleted(model: APPModel) {
                updateImageSelectionText(model.name)
                lifecycleScope.launch {
                    settingsStore.saveKey(KEY_IMAGE_APP, model.pkgName)
                }
            }
        }).show()
    }

    private fun selectEvoSearch() {
        lifecycleScope.launch {
            updateSearchSelectionText(evoSearchLabel)
            settingsStore.saveKey(KEY_SEARCH_LINK, "")
            settingsStore.saveKey(KEY_SEARCH_APP, "")
        }
    }

    private fun openSearchAppSelector() {
        DefaultSelectAPPBottomSheet(this, lifecycleScope, object : SelectAPPCallBack {
            override fun selectCompleted(model: APPModel) {
                updateSearchSelectionText(model.name)
                lifecycleScope.launch {
                    settingsStore.saveKey(KEY_SEARCH_APP, model.pkgName)
                    settingsStore.saveKey(KEY_SEARCH_LINK, "")
                }
            }
        }).show()
    }

    private fun showSearchLinkEditor() {
        lifecycleScope.launch {
            val searchLink = settingsStore.readKey(KEY_SEARCH_LINK, "")
            showEditTextBottomSheet(
                activity = this@InspireSettingsActivity,
                coroutineScope = lifecycleScope,
                title = getString(R.string.inspire_settings_enter_link),
                hint = getString(R.string.inspire_settings_enter_link),
                defaultText = searchLink,
                onConfirm = { inputText ->
                    if (inputText.isNotEmpty()) {
                        updateSearchSelectionText(inputText)
                        lifecycleScope.launch {
                            settingsStore.saveKey(KEY_SEARCH_LINK, inputText)
                            settingsStore.saveKey(KEY_SEARCH_APP, "")
                        }
                    }
                }
            )
        }
    }

    private fun updateSelectedAppText(textView: TextView, value: String) {
        textView.text = value
    }

    private fun updateDefaultShareBehaviorText(value: String) {
        binding.selectedDefaultShareBehaviorText.text = when (value) {
            "pinpool" -> getString(R.string.inspire_settings_share_to_pinpool)
            else -> getString(R.string.inspire_settings_share_to_tokenization)
        }
    }

    private fun updateKeyboardTypeText(type: KeyboardType) {
        binding.selectedKeyboardTypeText.text = when (type) {
            KeyboardType.T9 -> getString(R.string.inspire_settings_keyboard_type_t9)
            KeyboardType.DOUBLE_KEY, KeyboardType.FULL_KEY ->
                getString(R.string.inspire_settings_keyboard_type_double_key)
        }
    }

    private fun updatePinPoolModeText(value: String) {
        binding.selectedPinPoolModeText.text = when (value) {
            PIN_POOL_DISPLAY_MODE_FULLSCREEN -> getString(R.string.inspire_settings_pin_pool_mode_fullscreen)
            PIN_POOL_DISPLAY_MODE_LEFT_SIDEBAR -> getString(R.string.inspire_settings_pin_pool_mode_left_sidebar)
            PIN_POOL_DISPLAY_MODE_FOLLOW_TRIGGER -> getString(R.string.inspire_settings_pin_pool_mode_follow_trigger)
            else -> getString(R.string.inspire_settings_pin_pool_mode_right_sidebar)
        }
    }

    private fun showPinPoolModeDialog() {
        val options = arrayOf(
            getString(R.string.inspire_settings_pin_pool_mode_fullscreen),
            getString(R.string.inspire_settings_pin_pool_mode_left_sidebar),
            getString(R.string.inspire_settings_pin_pool_mode_right_sidebar),
            getString(R.string.inspire_settings_pin_pool_mode_follow_trigger),
        )
        lifecycleScope.launch {
            val current = settingsStore.readKey(
                KEY_PIN_POOL_DISPLAY_MODE,
                PIN_POOL_DISPLAY_MODE_RIGHT_SIDEBAR
            )
            val checkedItem = when (current) {
                PIN_POOL_DISPLAY_MODE_FULLSCREEN -> 0
                PIN_POOL_DISPLAY_MODE_LEFT_SIDEBAR -> 1
                PIN_POOL_DISPLAY_MODE_FOLLOW_TRIGGER -> 3
                else -> 2
            }
            MaterialAlertDialogBuilder(this@InspireSettingsActivity)
                .setTitle(R.string.inspire_settings_pin_pool_mode_title)
                .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                    val value = when (which) {
                        0 -> PIN_POOL_DISPLAY_MODE_FULLSCREEN
                        1 -> PIN_POOL_DISPLAY_MODE_LEFT_SIDEBAR
                        3 -> PIN_POOL_DISPLAY_MODE_FOLLOW_TRIGGER
                        else -> PIN_POOL_DISPLAY_MODE_RIGHT_SIDEBAR
                    }
                    lifecycleScope.launch {
                        settingsStore.saveKey(KEY_PIN_POOL_DISPLAY_MODE, value)
                        updatePinPoolModeText(value)
                    }
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun showDefaultShareBehaviorDialog() {
        val options = arrayOf(
            getString(R.string.inspire_settings_share_to_tokenization),
            getString(R.string.inspire_settings_share_to_pinpool)
        )
        lifecycleScope.launch {
            val current = settingsStore.readKey(KEY_DEFAULT_SHARE_BEHAVIOR, "tokenization")
            val checkedItem = if (current == "pinpool") 1 else 0
            MaterialAlertDialogBuilder(this@InspireSettingsActivity)
                .setTitle(R.string.inspire_settings_default_share_behavior_title)
                .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                    val value = if (which == 1) "pinpool" else "tokenization"
                    lifecycleScope.launch {
                        settingsStore.saveKey(KEY_DEFAULT_SHARE_BEHAVIOR, value)
                        updateDefaultShareBehaviorText(value)
                    }
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun showKeyboardTypeDialog() {
        val options = arrayOf(
            getString(R.string.inspire_settings_keyboard_type_double_key),
            getString(R.string.inspire_settings_keyboard_type_t9)
        )
        lifecycleScope.launch {
            val current = KeyboardType.valueOfSafe(
                settingsStore.readKey(KEY_KEYBOARD_TYPE, KeyboardType.DOUBLE_KEY.name)
            )
            val checkedItem = if (current == KeyboardType.T9) 1 else 0
            MaterialAlertDialogBuilder(this@InspireSettingsActivity)
                .setTitle(R.string.inspire_settings_keyboard_type_title)
                .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                    val value = if (which == 1) KeyboardType.T9 else KeyboardType.DOUBLE_KEY
                    lifecycleScope.launch {
                        settingsStore.saveKey(KEY_KEYBOARD_TYPE, value.name)
                        updateKeyboardTypeText(value)
                        produceKeyboardEvent(KeyboardEvent(show = true, reloadKeyboard = true))
                    }
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun updateSelectionRow(textView: TextView, clearButton: View, value: String) {
        updateSelectedAppText(textView, value)
        clearButton.visibility = if (value == notSelection) View.GONE else View.VISIBLE
    }

    private fun updateSearchSelectionText(value: String) {
        updateSelectedAppText(binding.selectedSearchAppText, value)
        binding.clearSearchAppButton.visibility = if (
            value == evoSearchLabel
        ) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    private fun updateImageSelectionText(value: String) {
        updateSelectedAppText(binding.selectedImageAppText, value)
        binding.clearImageAppButton.visibility = if (
            value == evoImageEditorLabel
        ) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    private fun showNotificationDisplayTimeBottomSheet() {
        lifecycleScope.launch {
            NotificationDisplayTimeBottomSheet(
                activity = this@InspireSettingsActivity,
                initialSeconds = settingsStore.readKey(
                    KEY_NOTIFICATION_IMAGE_DISPLAY_SECONDS,
                    DEFAULT_NOTIFICATION_IMAGE_DISPLAY_SECONDS
                )
            ) { selectedSeconds ->
                lifecycleScope.launch {
                    settingsStore.saveKey(KEY_NOTIFICATION_IMAGE_DISPLAY_SECONDS, selectedSeconds)
                    updateNotificationDisplayTimeText(selectedSeconds)
                }
            }.show()
        }
    }

    private fun updateNotificationDisplayTimeText(seconds: Int) {
        binding.selectedNotificationDisplayTimeText.text = formatNotificationDisplayTime(seconds)
    }

    private fun showPointerTouchAreaOffsetBottomSheet() {
        lifecycleScope.launch {
            PointerTouchAreaOffsetBottomSheet(
                activity = this@InspireSettingsActivity,
                initialOffsetDp = settingsStore.readKey(KEY_POINTER_TOUCH_AREA_OFFSET_DP, 0)
            ) { selectedOffsetDp ->
                lifecycleScope.launch {
                    settingsStore.saveKey(KEY_POINTER_TOUCH_AREA_OFFSET_DP, selectedOffsetDp)
                    updatePointerTouchAreaOffsetText(selectedOffsetDp)
                }
            }.show()
        }
    }

    private fun updatePointerTouchAreaOffsetText(offsetDp: Int) {
        binding.selectedPointerTouchAreaOffsetText.text = getString(
            R.string.inspire_settings_pointer_touch_area_offset_dp,
            offsetDp.coerceIn(-100, 100)
        )
    }

    private fun updatePinPoolAutoDeleteVisibility(autoSaveEnabled: Boolean) {
        binding.pinPoolAutoDeleteGroup.visibility =
            if (autoSaveEnabled) View.VISIBLE else View.GONE
    }

    private fun showPinPoolAutoDeleteBottomSheet() {
        lifecycleScope.launch {
            NotificationDisplayTimeBottomSheet(
                activity = this@InspireSettingsActivity,
                initialSeconds = settingsStore.readKey(KEY_PIN_POOL_AUTO_DELETE_DAYS, 0),
                titleRes = R.string.inspire_settings_pin_pool_auto_delete_title,
                maxValue = PIN_POOL_AUTO_DELETE_DAYS_MAX,
                persistLabelRes = R.string.inspire_settings_pin_pool_auto_delete_off_short,
                valueFormatRes = R.string.inspire_settings_pin_pool_auto_delete_days,
            ) { selectedDays ->
                lifecycleScope.launch {
                    settingsStore.saveKey(KEY_PIN_POOL_AUTO_DELETE_DAYS, selectedDays)
                    updatePinPoolAutoDeleteText(selectedDays)
                }
            }.show()
        }
    }

    private fun updatePinPoolAutoDeleteText(days: Int) {
        binding.selectedPinPoolAutoDeleteText.text = formatPinPoolAutoDelete(days)
    }

    private fun formatPinPoolAutoDelete(days: Int): String {
        return if (days <= 0) {
            getString(R.string.inspire_settings_pin_pool_auto_delete_off)
        } else {
            getString(R.string.inspire_settings_pin_pool_auto_delete_days, days)
        }
    }

    private fun formatNotificationDisplayTime(seconds: Int): String {
        return if (seconds <= 0) {
            getString(R.string.inspire_settings_notification_display_time_persist)
        } else {
            getString(R.string.inspire_settings_notification_display_time_seconds, seconds)
        }
    }
}
