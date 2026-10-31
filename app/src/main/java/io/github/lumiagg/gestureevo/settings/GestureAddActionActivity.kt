package io.github.lumiagg.gestureevo.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.createBitmap
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActivityGestureAddActionBinding
import io.github.lumiagg.gestureevo.databinding.ActivityGestureAddActionConfigBinding
import io.github.lumiagg.gestureevo.databinding.BottomSheetAccActionSelectBinding
import io.github.lumiagg.gestureevo.databinding.BottomSheetClipboardActionSelectBinding
import io.github.lumiagg.gestureevo.databinding.BottomSheetIconSelectBinding
import io.github.lumiagg.gestureevo.databinding.BottomSheetShortcutSourceSelectBinding
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.gesture.ActionViewEvent
import io.github.lumiagg.gestureevo.gesture.EventBusManager
import io.github.lumiagg.gestureevo.gesture.config.ActionConfig
import io.github.lumiagg.gestureevo.gesture.config.GestureActionDataManager
import io.github.lumiagg.gestureevo.gesture.config.GestureActionDataManager.buildTag
import io.github.lumiagg.gestureevo.gesture.config.GestureActionItem
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.gesture.config.IconType
import io.github.lumiagg.gestureevo.gesture.ui.ActionView
import io.github.lumiagg.gestureevo.gesture.ui.RoundImageTextView
import io.github.lumiagg.gestureevo.gesture.ui.gestureActionIcon
import io.github.lumiagg.gestureevo.settings.ui.ActivityModel
import io.github.lumiagg.gestureevo.settings.ui.DefaultSelectAPPBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.LocalUserShortcutSheet
import io.github.lumiagg.gestureevo.settings.ui.SelectAPPCallBack
import io.github.lumiagg.gestureevo.settings.ui.SelectActivityBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.SelectActivityCallBack
import io.github.lumiagg.gestureevo.settings.ui.SelectShortcutCallback
import io.github.lumiagg.gestureevo.settings.ui.ShortcutModel
import io.github.lumiagg.gestureevo.settings.ui.ShortcutSelectAPPBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.showActionSelectBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.showEditTextBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.showPieGroupSelectBottomSheet
import io.github.lumiagg.gestureevo.utils.GestureActionLink
import io.github.lumiagg.gestureevo.utils.MultiUserAPP
import io.github.lumiagg.gestureevo.utils.NotificationPermissionUtils
import io.github.lumiagg.gestureevo.utils.TempDndPermissionRequester
import io.github.lumiagg.gestureevo.utils.app.syncAppCatalog
import io.github.lumiagg.gestureevo.utils.icon.IconFileUtils
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import io.github.lumiagg.gestureevo.utils.icon.IconImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ConfigButtonsState(var enable: Boolean = false)
class GestureAddActionActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGestureAddActionBinding
    private var itemListTag = ""
    private var sourceActionView = ActionView.ActionViewType.MAIN
    private var editMode = false
    private var externalActionMode = false
    private var returnActionItemMode = false
    private var savingAction = false
    private var actionItem = GestureActionItem()
    private val firstActionLink = GestureActionLink()
    private val secondActionLink = GestureActionLink()
    private val firstButtonsState = ConfigButtonsState()
    private val secondButtonsState = ConfigButtonsState()
    private val iconFileUtils = IconFileUtils(this)
    private var pendingShortcutLink: GestureActionLink? = null
    private var lastSelectedConfigBinding: ActivityGestureAddActionConfigBinding? = null
    private var selectTriggerActionMode = false
    private var expectItemTag: String? = null
    private lateinit var tempDndPermissionRequester: TempDndPermissionRequester

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGestureAddActionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        tempDndPermissionRequester = TempDndPermissionRequester(this)
        initView()
        initData(savedInstanceState)
        savedInstanceState?.getString(STATE_SHORTCUT_LINK)?.let {
            pendingShortcutLink = parseValidLink(it)
            lastSelectedConfigBinding = if (savedInstanceState.getBoolean(STATE_SHORTCUT_LONG)) binding.longAction else binding.shortAction
        }
        firstButtonsState.enable = true
        secondButtonsState.enable = true
        if (!externalActionMode) gestureActionIcon(this, binding.actionIcon, actionItem)
        binding.subWheelButton.visibility = if (externalActionMode || selectTriggerActionMode) View.GONE else View.VISIBLE
        binding.subWheelButton.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.gesture_add_action_sub_wheel)
                .setItems(arrayOf(getString(R.string.gesture_add_action_sub_wheel_choose), getString(R.string.gesture_add_action_sub_wheel_clear))) { _, index ->
                    if (index == 1) {
                        actionItem.subActionListTag = null
                    } else {
                        showPieGroupSelectBottomSheet(this, lifecycleScope,
                            currentSelectedTag = actionItem.subActionListTag.orEmpty(),
                            onSelect = { actionItem.subActionListTag = it; renderSubWheelButton() })
                    }
                    renderSubWheelButton()
                }.show()
        }
        renderSubWheelButton()
        binding.toolbar.setTitle(if (editMode) R.string.action_editor_edit else R.string.action_editor_title)
        if (!externalActionMode && !selectTriggerActionMode) {
            binding.toolbar.menu.add(when {
                editMode -> R.string.action_placeholder_save
                returnActionItemMode -> R.string.action_placeholder_single
                else -> R.string.action_placeholder_add
            })
                .setOnMenuItemClickListener {
                    if (editMode || returnActionItemMode) saveAction(true) else batchSavePlaceHolder(5)
                    true
                }
        }
        if (selectTriggerActionMode) {
            initSelectTriggerActionView()
        } else if (externalActionMode) {
            initExternalActionView()
        }
        renderConfig(binding.shortAction)
        renderConfig(binding.longAction)
    }

    private fun initView() {
        binding.longAction.actionTitle.text = getString(R.string.gesture_add_action_long_press)
        binding.shortAction.actionTimeout.text = getString(R.string.gesture_add_action_touch_execute)
        binding.fab.setOnClickListener { saveAction(false) }
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.changeIcon.setOnClickListener { selectImage(binding.actionIcon) }
        binding.actionIcon.setOnClickListener {
            selectImage(it as RoundImageTextView)
        }
        binding.actionIcon.setImage(R.drawable.baseline_image_24)
        binding.btnSyncDown.setOnClickListener {
            syncLink(binding.shortAction, binding.longAction)
        }
        binding.btnSyncUp.setOnClickListener {
            syncLink(binding.longAction, binding.shortAction)
        }
        setConfigButtons(binding.shortAction)
        setConfigButtons(binding.longAction)
    }

    private fun initSelectTriggerActionView() {
        binding.longAction.root.visibility = View.GONE
        binding.shortAction.actionTitle.visibility = View.GONE
        binding.syncButtonsLayout.visibility = View.GONE
        binding.iconSettings.visibility = View.GONE
        // Must add timeout parameter when fixed gesture selection is used
        binding.shortAction.actionTimeout.isChecked = true
        binding.shortAction.actionTimeout.isEnabled = false
        binding.fab.setOnClickListener {
            val resultIntent = Intent()
            if (!returnActionItemMode) setResult(RESULT_OK, resultIntent)
            saveAction(false)
        }
    }

    private fun initExternalActionView() {
        binding.iconSettings.visibility = View.GONE
        binding.longAction.root.visibility = View.GONE
        binding.syncButtonsLayout.visibility = View.GONE
        binding.shortAction.actionTitle.text = getString(R.string.external_action_config_title)
        binding.fab.text = getString(R.string.external_action_save_button)
        binding.fab.setOnClickListener {
            saveExternalAction()
        }
    }

    private fun setConfigButtons(configBinding: ActivityGestureAddActionConfigBinding) {
        configBinding.actionSelect.setOnClickListener {
            showActionPicker(configBinding)
        }

        configBinding.executionToggle.setOnClickListener {
            configBinding.actionExternButtons.isVisible = !configBinding.actionExternButtons.isVisible
        }
        configBinding.advancedToggle.setOnClickListener {
            configBinding.actionLinkLayout.isVisible = !configBinding.actionLinkLayout.isVisible
        }
        configBinding.configureAction.setOnClickListener {
            val link = parseValidLink(configBinding.actionLink.text.toString()) ?: return@setOnClickListener
            selectAction(configBinding, link.actionType)
        }
        configBinding.actionLinkLayout.setEndIconOnClickListener {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                ClipData.newPlainText(getString(R.string.action_raw_link), configBinding.actionLink.text.toString()))
            Snackbar.make(binding.root, R.string.action_link_copied, Snackbar.LENGTH_SHORT).show()
        }
        configBinding.actionLink.doAfterTextChanged {
            if (configBinding.actionLink.hasFocus()) {
                parseValidLink(it.toString())?.let { link ->
                    configBinding.getButtonsState().enable = false
                    configBinding.getActionLink().parseLink(link.buildLink())
                    configBinding.actionFreeform.isChecked = link.isFreeform
                    configBinding.actionNewWindow.isChecked = link.isNewWindow
                    configBinding.actionDualOpen.isChecked = link.isDualOpen
                    configBinding.actionTimeout.isChecked = link.isTimeout
                    configBinding.getButtonsState().enable = true
                }
            }
            renderConfig(configBinding)
        }
        configBinding.actionExternButtons.setOnCheckedStateChangeListener { group, checkedIds ->
            if (!configBinding.getButtonsState().enable) return@setOnCheckedStateChangeListener
            val actionLink = configBinding.getActionLink()
            if (!configBinding.actionLink.text.isNullOrBlank() && parseValidLink(configBinding.actionLink.text.toString()) == null) return@setOnCheckedStateChangeListener
            parseValidLink(configBinding.actionLink.text.toString())?.let { actionLink.parseLink(it.buildLink()) }
            actionLink.isFreeform = configBinding.actionFreeform.isChecked
            actionLink.isNewWindow = configBinding.actionNewWindow.isChecked
            actionLink.isTimeout = configBinding.actionTimeout.isChecked
            if (configBinding.actionDualOpen.isChecked && actionLink.pkg != null) {
                actionLink.userId =
                    MultiUserAPP(applicationContext).getSerialNumberForUser(actionLink.pkg!!)
            } else {
                actionLink.userId = 0
            }
            applyLink(configBinding)
        }
    }

    private fun initData(savedInstanceState: Bundle?) {
        returnActionItemMode = intent.getBooleanExtra(EXTRA_RETURN_ACTION_ITEM, false)
        externalActionMode = intent.getBooleanExtra(EXTRA_EXTERNAL_ACTION_MODE, false)
        if (externalActionMode) {
            intent.getStringExtra(EXTRA_EXTERNAL_ACTION_LINK)?.let {
                fillActionConfig(binding.shortAction, it)
            } ?: run {
                binding.shortAction.getButtonsState().enable = true
            }
            return
        }

        itemListTag = intent.getStringExtra("tag").toString()
        sourceActionView = ActionView.ActionViewType.valueOf(
            intent.getStringExtra("source") ?: ActionView.ActionViewType.MAIN.name
        )
        editMode = intent.getBooleanExtra("editMode", false)
        selectTriggerActionMode = intent.getBooleanExtra("triggerActionMode", false)
        expectItemTag = intent.getStringExtra("expectItemTag")
        if (editMode || savedInstanceState?.containsKey(EXTRA_ACTION_ITEM) == true) {
            GestureActionItem.toModel(savedInstanceState?.getString(EXTRA_ACTION_ITEM) ?: intent.getStringExtra(EXTRA_ACTION_ITEM)!!)?.let {
                actionItem = it
                actionItem.firstStageLink?.let {
                    fillActionConfig(binding.shortAction, actionItem.firstStageLink!!)
                }
                actionItem.secondStageLink?.let {
                    fillActionConfig(binding.longAction, actionItem.secondStageLink!!)
                }
            }
        } else {
            binding.shortAction.getButtonsState().enable = true
            binding.longAction.getButtonsState().enable = true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val draft = actionItem.copy().apply {
            firstStageLink = binding.shortAction.actionLink.text.toString()
            secondStageLink = binding.longAction.actionLink.text.toString()
        }
        outState.putString(EXTRA_ACTION_ITEM, draft.toJson(false))
        outState.putString(STATE_SHORTCUT_LINK, pendingShortcutLink?.buildLink())
        outState.putBoolean(STATE_SHORTCUT_LONG, lastSelectedConfigBinding == binding.longAction)
        super.onSaveInstanceState(outState)
    }

    private fun renderSubWheelButton() {
        binding.subWheelButton.setText(if (actionItem.subActionListTag.isNullOrBlank())
            R.string.gesture_add_action_sub_wheel else R.string.gesture_add_action_sub_wheel_linked)
    }

    private fun fillActionConfig(
        configBinding: ActivityGestureAddActionConfigBinding, link: String
    ) {
        configBinding.getButtonsState().enable = false
        val actionLink = configBinding.getActionLink()
        actionLink.parseLink(link)
        if (configBinding == binding.shortAction) actionItem.firstStageLink = link
        else actionItem.secondStageLink = link
        configBinding.actionSelect.text = actionLink.actionType.showName(this)
        configBinding.actionFreeform.isChecked = actionLink.isFreeform
        configBinding.actionNewWindow.isChecked = actionLink.isNewWindow
        configBinding.actionDualOpen.isChecked = actionLink.isDualOpen
        configBinding.actionTimeout.isChecked = actionLink.isTimeout
        configBinding.getButtonsState().enable = true
        if (configBinding == binding.shortAction && actionItem.iconType == IconType.OTHER) {
            binding.actionIcon.setImage(R.drawable.baseline_gesture_24)
            gestureActionIcon(this, binding.actionIcon, actionItem)
        }
        applyLink(configBinding)
    }

    private fun showActionPicker(configBinding: ActivityGestureAddActionConfigBinding) {
        showActionSelectBottomSheet(
            activity = this,
            current = parseValidLink(configBinding.actionLink.text.toString()) ?: GestureActionLink(),
            allowWheel = !externalActionMode && intent.getBooleanExtra(EXTRA_ALLOW_WHEEL, true),
            allowNone = !externalActionMode && intent.getBooleanExtra(EXTRA_ALLOW_NONE, true),
            allowClickThrough = !externalActionMode
        ) { choice ->
            if (choice.parameters != null) {
                val link = GestureActionLink().apply {
                    actionType = choice.type
                    choice.parameters.forEach { (key, value) -> addParams(key, value) }
                    isTimeout = selectTriggerActionMode || parseValidLink(configBinding.actionLink.text.toString())?.isTimeout == true
                }
                commitAction(configBinding, link)
            } else {
                selectAction(configBinding, choice.type)
            }
        }
    }

    private fun parseValidLink(text: String): GestureActionLink? = runCatching {
        val uri = Uri.parse(text)
        require(uri.scheme == "gesture-evo" && uri.authority == "action")
        require(GestureActionType.entries.any { it.name == uri.lastPathSegment })
        GestureActionLink().parseLink(text)
    }.getOrNull()

    private fun renderConfig(configBinding: ActivityGestureAddActionConfigBinding) {
        val raw = configBinding.actionLink.text.toString()
        val link = parseValidLink(raw)
        configBinding.actionLinkLayout.error = if (raw.isNotBlank() && link == null) getString(R.string.action_link_invalid) else null
        configBinding.actionSelect.text = link?.actionType?.showName(this) ?: getString(R.string.gesture_add_action_config_select_event)
        val params = link?.queryList?.toMap().orEmpty()
        val detail = when (link?.actionType) {
            GestureActionType.OPEN_APP, GestureActionType.FREEZE_APP -> params["pkg"]?.let { pkg ->
                runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            }
            GestureActionType.ACC_ACTION -> mapOf(
                "1" to R.string.acc_action_select_back, "2" to R.string.acc_action_select_home,
                "3" to R.string.acc_action_select_recents, "4" to R.string.acc_action_select_notifications,
                "5" to R.string.acc_action_select_quick_settings, "6" to R.string.acc_action_select_power_dialog,
                "8" to R.string.acc_action_select_lock_screen, "9" to R.string.acc_action_select_screenshot
            )[params["accAction"]]?.let(::getString)
            GestureActionType.TEMP_DND -> params["minutes"]?.let { getString(R.string.action_minutes_summary, it) }
            GestureActionType.PAUSE_GESTURE -> params["seconds"]?.let { getString(R.string.action_seconds_summary, it) }
            GestureActionType.SHOW_ACTION_VIEW -> getString(R.string.quick_setup_action_wheel_desc)
            GestureActionType.OPEN_ACTIVITY -> params["activityName"]
            GestureActionType.OTHER_LINK, GestureActionType.OTHER_SHORTCUT -> params["uri"]
            GestureActionType.CLIPBOARD_ACTION -> mapOf(
                "pin" to R.string.clipboard_action_select_pin, "search" to R.string.clipboard_action_select_search,
                "translate" to R.string.clipboard_action_select_translate,
                "inspire" to R.string.clipboard_action_select_share_inspire, "saveImage" to R.string.clipboard_action_select_save_image
            )[params["clipAction"]]?.let(::getString)
            GestureActionType.OPEN_SHELL -> params["shell"]
            else -> null
        }
        val options = listOfNotNull(
            getString(R.string.gesture_add_action_config_freeform).takeIf { link?.isFreeform == true },
            getString(R.string.gesture_add_action_config_new_window).takeIf { link?.isNewWindow == true },
            getString(R.string.gesture_add_action_config_dual_open).takeIf { link?.isDualOpen == true },
            getString(if (configBinding == binding.shortAction) R.string.gesture_add_action_touch_execute else R.string.gesture_add_action_config_timeout_execute)
                .takeIf { link?.isTimeout == true }
        )
        configBinding.actionSummary.text = (listOfNotNull(detail) + options).joinToString(" · ")
            .ifBlank { link?.actionType?.showName(this) ?: getString(R.string.action_not_selected) }
        val appOptions = link?.actionType in setOf(GestureActionType.OPEN_APP, GestureActionType.OPEN_ACTIVITY, GestureActionType.OTHER_SHORTCUT)
        configBinding.actionFreeform.isVisible = appOptions || link?.isFreeform == true
        configBinding.actionNewWindow.isVisible = appOptions || link?.isNewWindow == true
        configBinding.actionDualOpen.isVisible = (appOptions && params["pkg"] != null) || link?.isDualOpen == true
        configBinding.configureAction.isVisible = link?.actionType in CONFIGURABLE_ACTIONS
        configBinding.executionToggle.isVisible = link != null
        if (link == null) configBinding.actionExternButtons.isVisible = false
    }

    private fun commitAction(configBinding: ActivityGestureAddActionConfigBinding, link: GestureActionLink) {
        if (configBinding == binding.shortAction && actionItem.iconType == IconType.OTHER) actionItem.name = null
        fillActionConfig(configBinding, link.buildLink())
        if (configBinding == binding.shortAction && !selectTriggerActionMode && !externalActionMode &&
            binding.longAction.actionLink.text.isNullOrEmpty()) {
            fillActionConfig(binding.longAction, link.buildLink())
        }
    }

    private val selectImageLauncher = registerForActivityResult(IconEditorContract()) { path ->
        if (path != null) lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { IconImageIO.read(path) }
            if (bitmap != null) {
                iconFileUtils.setImgBitmap(bitmap)
                iconFileUtils.imgPath = path
                actionItem.iconType = IconType.URI
                actionItem.icon = path
                binding.actionIcon.setImage(bitmap)
            } else {
                Toast.makeText(this@GestureAddActionActivity, R.string.icon_editor_load_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun selectGalleryIcon() {
        lifecycleScope.launch {
            val style = if (selectTriggerActionMode) null else {
                intent.getStringExtra(EXTRA_ICON_WHEEL_STYLE) ?: run {
                    val tag = itemListTag.takeUnless { it.isBlank() || it == "null" } ?: ActionConfig.DEFAULT_TAG
                    val main = GestureActionDataManager.readActionConfig(this@GestureAddActionActivity, tag, false)
                    val config = if (sourceActionView == ActionView.ActionViewType.SUB) {
                        GestureActionDataManager.readActionConfig(this@GestureAddActionActivity, tag, true).apply {
                            if (subActionFollowConfig) {
                                itemDiameter = main.itemDiameter * 0.8f
                                iconPadding = main.iconPadding * 0.8f
                                iconCornerRadius = main.iconCornerRadius * 0.8f
                            }
                        }
                    } else main
                    config.toJson(false)
                }
            }
            selectImageLauncher.launch(IconEditorRequest(wheelStyle = style))
        }
    }

    private fun updateAndSaveUriImage(img: Drawable) {
        actionItem.iconType = IconType.URI
        binding.actionIcon.setImage(img)
        img.let { drawable ->
            val bitmap =
                createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            iconFileUtils.setImgBitmap(bitmap)
        }
    }

    private fun selectImage(view: RoundImageTextView) {
        val bottomSheet = BottomSheetDialog(
            this, R.style.ThemeOverlay_BottomSheetDialog_Scrollable
        )
        val bottomSheetView = BottomSheetIconSelectBinding.inflate(layoutInflater).root

        bottomSheetView.findViewById<View>(R.id.select_gallery)
            .setOnClickListener {
                bottomSheet.dismiss()
                selectGalleryIcon()
            }

        bottomSheetView.findViewById<View>(R.id.select_app_icon)
            .setOnClickListener {
                bottomSheet.dismiss()
                DefaultSelectAPPBottomSheet(this, lifecycleScope, object : SelectAPPCallBack {
                    override fun selectCompleted(model: APPModel) {
                        IconHelper.getIcon(applicationContext, model.pkgName)?.let { drawable ->
                            updateAndSaveUriImage(drawable)
                        }
                    }
                }).show()
            }

        bottomSheetView.findViewById<View>(R.id.select_text_icon)
            .setOnClickListener {
                bottomSheet.dismiss()
                showEditTextBottomSheet(
                    activity = this,
                    coroutineScope = lifecycleScope,
                    title = getString(R.string.gesture_add_action_text_icon_title),
                    hint = getString(R.string.gesture_add_action_text_icon_hint),
                    onConfirm = { inputText ->
                        actionItem.name = inputText.trim()
                        actionItem.iconType = IconType.TEXT
                        binding.actionIcon.setText(actionItem.name ?: "")
                    }
                )
            }

        bottomSheet.setContentView(bottomSheetView)
        bottomSheet.show()
    }

    private val shortcutPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.let { data ->
                val shortcutIntent =
                    data.getParcelableExtra<Intent>("android.intent.extra.shortcut.INTENT")
                val shortcutName = data.getStringExtra("android.intent.extra.shortcut.NAME")
                val shortcutIcon =
                    data.getParcelableExtra<Bitmap>("android.intent.extra.shortcut.ICON")

                val shortcutUri = shortcutIntent?.toUri(Intent.URI_INTENT_SCHEME)

                lastSelectedConfigBinding?.let { configBinding ->
                    shortcutUri?.let { uri ->
                        val actionLink = pendingShortcutLink ?: return@registerForActivityResult
                        actionLink.addParams("uri", uri)
                        commitAction(configBinding, actionLink)
                        shortcutIcon?.takeIf { configBinding == binding.shortAction }?.let { icon ->
                            actionItem.iconType = IconType.URI
                            iconFileUtils.setImgBitmap(icon)
                            binding.actionIcon.setImage(icon)
                        }
                    }
                }
            }
        }
    }

    private fun selectAccAction(callback: (Int, Drawable?) -> Unit) {
        val bottomSheet = BottomSheetDialog(
            this, R.style.ThemeOverlay_BottomSheetDialog_Scrollable
        )
        val binding = BottomSheetAccActionSelectBinding.inflate(layoutInflater)

        binding.root.touchables.forEach { view ->
            val actionId = (view.tag as? String)?.toIntOrNull() ?: return@forEach
            view.setOnClickListener {
                callback(actionId, null)
                bottomSheet.dismiss()
            }
        }

        bottomSheet.setContentView(binding.root)
        bottomSheet.show()
    }

    private fun selectClipboardAction(callback: (String, Drawable?) -> Unit) {
        val bottomSheet = BottomSheetDialog(
            this, R.style.ThemeOverlay_BottomSheetDialog_Scrollable
        )
        val binding = BottomSheetClipboardActionSelectBinding.inflate(layoutInflater)

        binding.root.touchables.forEach { view ->
            val actionId = view.tag as? String ?: return@forEach
            view.setOnClickListener {
                callback(actionId, null)
                bottomSheet.dismiss()
            }
        }

        bottomSheet.setContentView(binding.root)
        bottomSheet.show()
    }

    private fun selectAction(
        configBinding: ActivityGestureAddActionConfigBinding, type: GestureActionType
    ) {
        lastSelectedConfigBinding = configBinding
        val actionLink = parseValidLink(configBinding.actionLink.text.toString()) ?: GestureActionLink()
        if (selectTriggerActionMode) actionLink.isTimeout = true
        actionLink.actionType = type
        val oldQueryList: ArrayList<Pair<String, String>> = ArrayList()
        oldQueryList.addAll(actionLink.queryList)
        actionLink.queryList.clear()

        when (type) {
            GestureActionType.OPEN_APP, GestureActionType.FREEZE_APP -> {
                lifecycleScope.launch {
                    try {
                        syncAppCatalog(applicationContext)
                        if (isFinishing || isDestroyed) return@launch
                        DefaultSelectAPPBottomSheet(this@GestureAddActionActivity, lifecycleScope, object : SelectAPPCallBack {
                            override fun selectCompleted(model: APPModel) {
                                actionLink.addParams("pkg", model.pkgName)
                                actionLink.userId = if (actionLink.isDualOpen && model.userId == 0)
                                    MultiUserAPP(applicationContext).getSerialNumberForUser(model.pkgName)
                                else model.userId.toLong()
                                commitAction(configBinding, actionLink)
                            }
                        }).show()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Unable to load applications", e)
                        Toast.makeText(this@GestureAddActionActivity, R.string.guide_load_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }

            GestureActionType.OPEN_ACTIVITY -> {
                SelectActivityBottomSheet(this, lifecycleScope, object : SelectActivityCallBack {
                    override fun selectCompleted(model: ActivityModel) {
                        val intent = Intent().apply {
                            component = ComponentName(model.packageName, model.activityName)
                        }
                        // Convert Intent to URI
                        val uri = intent.toUri(Intent.URI_INTENT_SCHEME)
                        actionLink.addParams("uri", uri)
                        actionLink.addParams("pkg", model.packageName)
                        actionLink.addParams("activityName", model.activityName)
                        actionLink.addParams("isExported", model.isExported.toString())
                        commitAction(configBinding, actionLink)
                        if (configBinding == binding.shortAction && actionItem.iconType == IconType.OTHER) {
                            binding.actionIcon.setImage(IconHelper.getIcon(applicationContext, model.packageName))
                        }
                    }
                }).show()
            }

            GestureActionType.OTHER_LINK -> {
                showEditTextBottomSheet(
                    activity = this,
                    coroutineScope = lifecycleScope,
                    title = getString(R.string.gesture_add_action_link_title),
                    hint = getString(R.string.gesture_add_action_link_hint),
                    defaultText = oldQueryList.find { it.first == "uri" }?.second,
                    onConfirm = { inputText ->
                        if (inputText.isNotEmpty()) {
                            actionLink.addParams("uri", inputText)
                            commitAction(configBinding, actionLink)
                        }
                    }
                )
            }

            GestureActionType.OPEN_SHELL -> {
                showEditTextBottomSheet(
                    activity = this,
                    coroutineScope = lifecycleScope,
                    title = getString(R.string.gesture_add_action_shell_title),
                    hint = getString(R.string.gesture_add_action_shell_hint),
                    defaultText = oldQueryList.find { it.first == "shell" }?.second,
                    onConfirm = { inputText ->
                        if (inputText.isNotEmpty()) {
                            actionLink.addParams("shell", inputText)
                            commitAction(configBinding, actionLink)
                        }
                    }
                )
            }

            GestureActionType.TEMP_DND -> {
                selectTempDndAction(configBinding, actionLink, oldQueryList)
            }

            GestureActionType.PAUSE_GESTURE -> {
                showPauseGestureSecondsInput(configBinding, actionLink, oldQueryList)
            }

            GestureActionType.OTHER_SHORTCUT -> {
                selectOtherShortcut(configBinding, actionLink)
            }

            GestureActionType.SHOW_ACTION_VIEW -> {
                val defaultTag = intent.getStringExtra(EXTRA_DEFAULT_WHEEL_TAG)
                if (defaultTag != null) {
                    actionLink.addParams("actionItemsTag", defaultTag)
                    commitAction(configBinding, actionLink)
                    return
                }
                showPieGroupSelectBottomSheet(
                    activity = this,
                    coroutineScope = lifecycleScope,
                    currentSelectedTag = itemListTag,
                    onSelect = { selectedTag ->
                        actionLink.addParams("actionItemsTag", selectedTag)
                        commitAction(configBinding, actionLink)
                    }
                )
            }

            GestureActionType.ACC_ACTION -> {
                selectAccAction { actionId, icon ->
                    actionLink.addParams("accAction", actionId.toString())
                    commitAction(configBinding, actionLink)
                }
            }

            GestureActionType.CLIPBOARD_ACTION -> {
                selectClipboardAction { actionId, icon ->
                    actionLink.addParams("clipAction", actionId)
                    commitAction(configBinding, actionLink)
                }
            }

            else -> {
                commitAction(configBinding, actionLink)
            }
        }
    }

    private fun selectTempDndAction(
        configBinding: ActivityGestureAddActionConfigBinding,
        actionLink: GestureActionLink,
        oldQueryList: ArrayList<Pair<String, String>>
    ) {
        val showMinutesInput = {
            showEditTextBottomSheet(
                activity = this,
                coroutineScope = lifecycleScope,
                title = getString(R.string.gesture_add_action_temp_dnd_minutes_title),
                hint = getString(R.string.gesture_add_action_temp_dnd_minutes_hint),
                defaultText = oldQueryList.find { it.first == "minutes" }?.second ?: "15",
                onConfirm = { inputText ->
                    val minutes = inputText.trim().toIntOrNull()
                    if (minutes == null || minutes <= 0) {
                        Toast.makeText(
                            this,
                            getString(R.string.gesture_add_action_temp_dnd_minutes_invalid),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        actionLink.addParams("minutes", minutes.toString())
                        commitAction(configBinding, actionLink)
                    }
                }
            )
        }

        tempDndPermissionRequester.ensurePermissionWithPrompt {
            showMinutesInput()
        }
    }

    private fun showPauseGestureSecondsInput(
        configBinding: ActivityGestureAddActionConfigBinding,
        actionLink: GestureActionLink,
        oldQueryList: ArrayList<Pair<String, String>>
    ) {
        val showSecondsInput = {
            showEditTextBottomSheet(
                activity = this,
                coroutineScope = lifecycleScope,
                title = getString(R.string.gesture_add_action_pause_seconds_title),
                hint = getString(R.string.gesture_add_action_pause_seconds_hint),
                defaultText = oldQueryList.find { it.first == "seconds" }?.second ?: "3",
                onConfirm = { inputText ->
                    val seconds = inputText.trim().toIntOrNull()
                    if (seconds == null || seconds <= 0) {
                        Toast.makeText(
                            this,
                            getString(R.string.gesture_add_action_pause_seconds_invalid),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        actionLink.addParams("seconds", seconds.toString())
                        commitAction(configBinding, actionLink)
                    }
                }
            )
        }
        // The countdown notification is the only handle on a paused trigger, so ask for
        // notification access while the action is being configured.
        if (NotificationPermissionUtils.canPostNotifications(this)) {
            showSecondsInput()
        } else {
            tempDndPermissionRequester.ensureNotificationPermissionWithPrompt(showSecondsInput)
        }
    }

    private fun selectOtherShortcut(configBinding: ActivityGestureAddActionConfigBinding, actionLink: GestureActionLink) {
        val bottomSheet = BottomSheetDialog(
            this, R.style.ThemeOverlay_BottomSheetDialog_Scrollable
        )
        val bottomSheetView = BottomSheetShortcutSourceSelectBinding.inflate(layoutInflater).root

        bottomSheetView.findViewById<View>(R.id.selectLocalShortcut)
            .setOnClickListener {
                bottomSheet.dismiss()
                ShortcutSelectAPPBottomSheet(this, lifecycleScope, object : SelectAPPCallBack {
                    override fun selectCompleted(model: APPModel) {
                        val shortcutIntent = Intent("android.intent.action.CREATE_SHORTCUT")
                        shortcutIntent.setPackage(model.pkgName)
                        val activities = packageManager.queryIntentActivities(shortcutIntent, 0)
                        if (activities.isNotEmpty()) {
                            pendingShortcutLink = actionLink
                            shortcutPickerLauncher.launch(shortcutIntent)
                        } else {
                            Toast.makeText(
                                this@GestureAddActionActivity,
                                getString(R.string.gesture_add_action_shortcut_create_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }).show()
            }

        bottomSheetView.findViewById<View>(R.id.selectOnlineShortcut)
            .setOnClickListener {
                bottomSheet.dismiss()
                LocalUserShortcutSheet(this, lifecycleScope, object : SelectShortcutCallback {
                    override fun selectCompleted(model: ShortcutModel) {
                        actionLink.addParams("uri", model.scheme)
                        actionLink.addParams("pkg", model.packageName)
                        commitAction(configBinding, actionLink)
                    }
                }).show()
            }

        bottomSheet.setContentView(bottomSheetView)
        bottomSheet.show()
    }

    private fun applyLink(configBinding: ActivityGestureAddActionConfigBinding) {
        configBinding.actionLink.setText(configBinding.getActionLink().buildLink())
    }

    private fun syncLink(
        sourceConfig: ActivityGestureAddActionConfigBinding,
        targetConfig: ActivityGestureAddActionConfigBinding
    ) {
        val previous = targetConfig.actionLink.text.toString()
        if (parseValidLink(sourceConfig.actionLink.text.toString()) == null) return
        targetConfig.getButtonsState().enable = false
        fillActionConfig(targetConfig, sourceConfig.actionLink.text.toString())
        Snackbar.make(binding.root, R.string.action_config_copied, Snackbar.LENGTH_LONG)
            .setAction(R.string.action_undo) {
                targetConfig.getActionLink().apply {
                    actionType = GestureActionType.NONE
                    queryList.clear()
                    isFreeform = false
                    isNewWindow = false
                    isTimeout = false
                    userId = 0
                }
                if (parseValidLink(previous) == null) {
                    fillActionConfig(targetConfig, GestureActionLink().buildLink())
                    targetConfig.actionLink.setText(previous)
                } else fillActionConfig(targetConfig, previous)
            }.show()
    }


    private fun batchSavePlaceHolder(total: Int) {
        if (savingAction) return
        savingAction = true
        binding.fab.isEnabled = false
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repeat(total) {
                        val item = GestureActionItem().apply { iconType = IconType.INVISIBLE }
                        GestureActionDataManager.addActionItem(applicationContext, itemListTag, item)
                    }
                }
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Unable to save placeholders", e)
                Toast.makeText(this@GestureAddActionActivity, R.string.gesture_add_action_save_failed, Toast.LENGTH_LONG).show()
            } finally {
                savingAction = false
                binding.fab.isEnabled = true
            }
        }
    }

    private fun validateDraft(): Boolean {
        val configs = if (externalActionMode || selectTriggerActionMode) listOf(binding.shortAction)
            else listOf(binding.shortAction, binding.longAction)
        for (config in configs) {
            val raw = config.actionLink.text.toString()
            if (raw.isBlank() && (config == binding.longAction ||
                    (!externalActionMode && !selectTriggerActionMode &&
                        (!actionItem.subActionListTag.isNullOrBlank() || actionItem.iconType == IconType.INVISIBLE ||
                            !binding.longAction.actionLink.text.isNullOrBlank())))) continue
            if (parseValidLink(raw) == null) {
                config.actionLinkLayout.isVisible = raw.isNotBlank()
                config.actionLinkLayout.error = getString(if (raw.isBlank()) R.string.external_action_select_required else R.string.action_link_invalid)
                Snackbar.make(binding.root, if (raw.isBlank()) R.string.external_action_select_required else R.string.action_link_invalid, Snackbar.LENGTH_LONG).show()
                return false
            }
        }
        return true
    }

    private fun saveExternalAction() {
        if (!validateDraft()) return
        val link = binding.shortAction.actionLink.text?.toString().orEmpty()
        if (link.isEmpty()) {
            Toast.makeText(
                this,
                getString(R.string.external_action_select_required),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_EXTERNAL_ACTION_LINK, link)
            }
        )
        finish()
    }

    private fun saveAction(isPlaceHolder: Boolean) {
        if (savingAction || (!isPlaceHolder && !validateDraft())) return
        val savedItem = actionItem.copy().apply {
            firstStageLink = binding.shortAction.actionLink.text.toString()
            secondStageLink = binding.longAction.actionLink.text.toString()
            if (isPlaceHolder) iconType = IconType.INVISIBLE
        }
        savingAction = true
        binding.fab.isEnabled = false
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (iconFileUtils.saveIcon()) savedItem.icon = iconFileUtils.imgPath
                    if (!returnActionItemMode) {
                        if (editMode) {
                            GestureActionDataManager.updateActionItem(applicationContext, itemListTag, savedItem)
                        } else {
                            savedItem.tag = expectItemTag ?: buildTag()
                            GestureActionDataManager.addActionItem(applicationContext, itemListTag, savedItem)
                        }
                    }
                }
                if (returnActionItemMode) {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION_ITEM, savedItem.toJson(false)))
                }
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Unable to save action", e)
                Toast.makeText(this@GestureAddActionActivity, R.string.gesture_add_action_save_failed, Toast.LENGTH_LONG).show()
            } finally {
                savingAction = false
                binding.fab.isEnabled = true
            }
        }
    }

    fun ActivityGestureAddActionConfigBinding.getActionLink(): GestureActionLink {
        return if (this == binding.shortAction) firstActionLink else secondActionLink
    }

    fun ActivityGestureAddActionConfigBinding.getButtonsState(): ConfigButtonsState {
        return if (this == binding.shortAction) firstButtonsState else secondButtonsState
    }

    override fun finish() {
        super.finish()
        if (selectTriggerActionMode || externalActionMode || returnActionItemMode) return
        if (sourceActionView == ActionView.ActionViewType.MAIN) {
            EventBusManager.produceActionViewEvent(
                lifecycleScope, ActionViewEvent(
                    show = true,
                    workMode = ActionView.WorkMode.CONFIG_MAIN,
                    mainItemListTag = itemListTag
                )
            )
        } else {
            EventBusManager.produceActionViewEvent(
                lifecycleScope, ActionViewEvent(
                    show = true,
                    workMode = ActionView.WorkMode.CONFIG_SUB,
                    subItemListTag = itemListTag
                )
            )
        }
    }


    companion object {
        private val CONFIGURABLE_ACTIONS = setOf(
            GestureActionType.OPEN_APP, GestureActionType.FREEZE_APP, GestureActionType.OPEN_ACTIVITY,
            GestureActionType.OTHER_SHORTCUT, GestureActionType.OTHER_LINK, GestureActionType.OPEN_SHELL,
            GestureActionType.CLIPBOARD_ACTION, GestureActionType.TEMP_DND, GestureActionType.PAUSE_GESTURE,
            GestureActionType.SHOW_ACTION_VIEW, GestureActionType.ACC_ACTION
        )
        private const val STATE_SHORTCUT_LINK = "pending_shortcut_link"
        private const val STATE_SHORTCUT_LONG = "pending_shortcut_long"
        const val EXTRA_ALLOW_WHEEL = "allow_wheel"
        const val EXTRA_ALLOW_NONE = "allow_none"
        const val EXTRA_DEFAULT_WHEEL_TAG = "default_wheel_tag"
        const val TAG = "GestureAddActionActivity"
        const val EXTRA_EXTERNAL_ACTION_MODE = "extra_external_action_mode"
        const val EXTRA_EXTERNAL_ACTION_LINK = "extra_external_action_link"
        const val EXTRA_RETURN_ACTION_ITEM = "extra_return_action_item"
        const val EXTRA_ACTION_ITEM = "item"
        const val EXTRA_ICON_WHEEL_STYLE = "icon_wheel_style"
    }
}
