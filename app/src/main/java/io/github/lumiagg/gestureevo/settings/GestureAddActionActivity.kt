package io.github.lumiagg.gestureevo.settings

import io.github.lumiagg.gestureevo.databinding.BottomSheetShortcutSourceSelectBinding

import io.github.lumiagg.gestureevo.databinding.BottomSheetIconSelectBinding

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.graphics.createBitmap
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActivityGestureAddActionBinding
import io.github.lumiagg.gestureevo.databinding.ActivityGestureAddActionConfigBinding
import io.github.lumiagg.gestureevo.databinding.BottomSheetAccActionSelectBinding
import io.github.lumiagg.gestureevo.databinding.BottomSheetClipboardActionSelectBinding
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.gesture.ActionViewEvent
import io.github.lumiagg.gestureevo.gesture.EventBusManager
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
import io.github.lumiagg.gestureevo.settings.ui.showEditTextBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.showPieGroupSelectBottomSheet
import io.github.lumiagg.gestureevo.utils.GestureActionLink
import io.github.lumiagg.gestureevo.utils.MultiUserAPP
import io.github.lumiagg.gestureevo.utils.TempDndPermissionRequester
import io.github.lumiagg.gestureevo.utils.icon.IconFileUtils
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
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
    private var actionItem = GestureActionItem()
    private val firstActionLink = GestureActionLink()
    private val secondActionLink = GestureActionLink()
    private val firstButtonsState = ConfigButtonsState()
    private val secondButtonsState = ConfigButtonsState()
    private val iconFileUtils = IconFileUtils(this)
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
        initData()
        if (selectTriggerActionMode) {
            initSelectTriggerActionView()
        } else if (externalActionMode) {
            initExternalActionView()
        }
    }

    private fun initView() {
        binding.longAction.actionTitle.text = getString(R.string.gesture_add_action_long_press)
        binding.shortAction.actionTimeout.text = getString(R.string.gesture_add_action_touch_execute)
        binding.fab.setOnClickListener { saveAction(false) }
        binding.fabPlaceholder.setOnClickListener {
            if (editMode) {
                saveAction(true)
            } else {
                batchSavePlaceHolder(5)
            }
        }
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
        binding.longAction.root.visibility = View.INVISIBLE
        binding.shortAction.actionTitle.visibility = View.GONE
        binding.syncButtonsLayout.visibility = View.INVISIBLE
        binding.fabPlaceholder.visibility = View.INVISIBLE
        // Must add timeout parameter when fixed gesture selection is used
        binding.shortAction.actionTimeout.isChecked = true
        binding.shortAction.actionTimeout.isEnabled = false
        binding.fab.setOnClickListener {
            val resultIntent = Intent()
            setResult(RESULT_OK, resultIntent)
            saveAction(false)
        }
    }

    private fun initExternalActionView() {
        binding.actionIcon.visibility = View.GONE
        binding.longAction.root.visibility = View.GONE
        binding.syncButtonsLayout.visibility = View.GONE
        binding.fabPlaceholder.visibility = View.GONE
        binding.shortAction.actionTitle.text = getString(R.string.external_action_config_title)
        binding.fab.text = getString(R.string.external_action_save_button)
        binding.fab.setOnClickListener {
            saveExternalAction()
        }
    }

    private fun setConfigButtons(configBinding: ActivityGestureAddActionConfigBinding) {
        configBinding.actionSelect.setOnClickListener {
            initActionPopupMenu(configBinding).show()
        }

        configBinding.actionExternButtons.setOnCheckedStateChangeListener { group, checkedIds ->
            if (!configBinding.getButtonsState().enable) return@setOnCheckedStateChangeListener
            val actionLink = configBinding.getActionLink()
            actionLink.isFreeform = configBinding.actionFreeform.isChecked
            actionLink.isNewWindow = configBinding.actionNewWindow.isChecked
            actionLink.isTimeout = configBinding.actionTimeout.isChecked
            if (configBinding.actionDualOpen.isChecked && actionLink.pkg != null) {
                actionLink.userId =
                    MultiUserAPP(applicationContext).getSerialNumberForUser(actionLink.pkg!!)
            } else {
                actionLink.userId = 0
            }
            applyLink(configBinding, needSyncToLongAction = false)
        }
    }

    private fun initData() {
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
        if (editMode) {
            GestureActionItem.toModel(intent.getStringExtra("item")!!)?.let {
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

    private fun fillActionConfig(
        configBinding: ActivityGestureAddActionConfigBinding, link: String
    ) {
        val actionLink = configBinding.getActionLink()
        actionLink.parseLink(link)
        configBinding.actionSelect.text = actionLink.actionType.showName(this)
        configBinding.actionFreeform.isChecked = actionLink.isFreeform
        configBinding.actionNewWindow.isChecked = actionLink.isNewWindow
        configBinding.actionDualOpen.isChecked = actionLink.isDualOpen
        configBinding.actionTimeout.isChecked = actionLink.isTimeout
        configBinding.getButtonsState().enable = true
        if (configBinding == binding.shortAction) {
            gestureActionIcon(this, binding.actionIcon, actionItem)
        }
        applyLink(configBinding, needSyncToLongAction = false)
    }

    private fun initActionPopupMenu(configBinding: ActivityGestureAddActionConfigBinding): ListPopupWindow {
        val listPopupWindow = ListPopupWindow(
            this, null, android.R.attr.listPopupWindowStyle
        )
        val adapter: ArrayAdapter<CharSequence?> = ArrayAdapter(
            this,
            R.layout.tirgger_config_relate_content_popup_item,
        )

        val typeList = ArrayList<GestureActionType>().apply {
            GestureActionType.entries.forEach { type ->
                if (
                    externalActionMode &&
                    (type == GestureActionType.NONE ||
                        type == GestureActionType.CLICK_THROUGH ||
                        type == GestureActionType.SHOW_ACTION_VIEW)
                ) {
                    return@forEach
                }
                add(type)
            }
        }

        typeList.forEach {
            adapter.add(it.showName(this))
        }
        listPopupWindow.setAdapter(adapter)
        listPopupWindow.anchorView = configBinding.actionSelect
        listPopupWindow.setOnItemClickListener { _: AdapterView<*>?, _: View?, position: Int, _: Long ->
            listPopupWindow.dismiss()
            val type = typeList[position]
            configBinding.actionSelect.text = type.showName(this)
            selectAction(configBinding, type)
        }

        return listPopupWindow
    }

    private val selectImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            binding.actionIcon.let { view ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        actionItem.iconType = IconType.URI
                        iconFileUtils.setGalleryIcon(selectedUri)
                        withContext(Dispatchers.Main) {
                            iconFileUtils.getImgBitmap()?.let { bitmap ->
                                view.setImage(bitmap)
                            } ?: {
                                Toast.makeText(
                                    this@GestureAddActionActivity,
                                    getString(R.string.gesture_add_action_uri_failed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@GestureAddActionActivity,
                                getString(
                                    R.string.gesture_add_action_image_failed,
                                    e.localizedMessage ?: ""
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        } ?: let {
            lifecycleScope.launch(Dispatchers.Main) {
                Toast.makeText(
                    this@GestureAddActionActivity,
                    getString(R.string.gesture_add_action_uri_missing),
                    Toast.LENGTH_SHORT
                ).show()
            }
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
                selectImageLauncher.launch("image/*")
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
                        val actionLink = configBinding.getActionLink()
                        actionLink.addParams("uri", uri)
                        applyLink(configBinding)
                        shortcutIcon?.let { icon ->
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
        val actionLink = configBinding.getActionLink()
        actionLink.actionType = type
        val oldQueryList: ArrayList<Pair<String, String>> = ArrayList()
        oldQueryList.addAll(actionLink.queryList)
        actionLink.queryList.clear()

        when (type) {
            GestureActionType.OPEN_APP, GestureActionType.FREEZE_APP -> {
                DefaultSelectAPPBottomSheet(this, lifecycleScope, object : SelectAPPCallBack {
                    override fun selectCompleted(model: APPModel) {
                        actionLink.addParams("pkg", model.pkgName)
                        applyLink(configBinding)
                        if (configBinding == binding.shortAction && actionItem.iconType == IconType.OTHER) {
                            actionItem.iconType = IconType.OTHER
                            binding.actionIcon.setImage(
                                IconHelper.getIcon(applicationContext, model.pkgName)
                            )
                        }
                    }
                }).show()
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
                        applyLink(configBinding)
                        actionItem.iconType = IconType.OTHER
                        binding.actionIcon.setImage(
                            IconHelper.getIcon(applicationContext, model.packageName)
                        )
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
                            applyLink(configBinding)
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
                            applyLink(configBinding)
                        }
                    }
                )
            }

            GestureActionType.TEMP_DND -> {
                selectTempDndAction(configBinding, actionLink, oldQueryList)
            }

            GestureActionType.OTHER_SHORTCUT -> {
                selectOtherShortcut(configBinding)
            }

            GestureActionType.SHOW_ACTION_VIEW -> {
                showPieGroupSelectBottomSheet(
                    activity = this,
                    coroutineScope = lifecycleScope,
                    currentSelectedTag = itemListTag,
                    onSelect = { selectedTag ->
                        actionLink.addParams("actionItemsTag", selectedTag)
                        applyLink(configBinding)
                    }
                )
            }

            GestureActionType.ACC_ACTION -> {
                selectAccAction { actionId, icon ->
                    val actionLink = configBinding.getActionLink()
                    actionLink.addParams("accAction", actionId.toString())
                    applyLink(configBinding)
                }
            }

            GestureActionType.CLIPBOARD_ACTION -> {
                selectClipboardAction { actionId, icon ->
                    val actionLink = configBinding.getActionLink()
                    actionLink.addParams("clipAction", actionId)
                    applyLink(configBinding)
                }
            }

            else -> {
                applyLink(configBinding)
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
                        applyLink(configBinding)
                    }
                }
            )
        }

        tempDndPermissionRequester.ensurePermissionWithPrompt {
            showMinutesInput()
        }
    }

    private fun selectOtherShortcut(configBinding: ActivityGestureAddActionConfigBinding) {
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
                        val actionLink = configBinding.getActionLink()
                        actionLink.addParams("uri", model.scheme)
                        actionLink.addParams("pkg", model.packageName)
                        applyLink(configBinding)
                    }
                }).show()
            }

        bottomSheet.setContentView(bottomSheetView)
        bottomSheet.show()
    }

    private fun applyLink(
        configBinding: ActivityGestureAddActionConfigBinding, needSyncToLongAction: Boolean = true
    ) {
        val actionLink = configBinding.getActionLink()
        configBinding.actionLink.setText(actionLink.buildLink())

        if (
            needSyncToLongAction &&
            !externalActionMode &&
            configBinding == binding.shortAction &&
            binding.longAction.actionLink.text.isNullOrEmpty()
        ) {
            fillActionConfig(binding.longAction, firstActionLink.buildLink())
        }
    }

    private fun syncLink(
        sourceConfig: ActivityGestureAddActionConfigBinding,
        targetConfig: ActivityGestureAddActionConfigBinding
    ) {
        targetConfig.getButtonsState().enable = false
        fillActionConfig(targetConfig, sourceConfig.actionLink.text.toString())
    }


    private fun batchSavePlaceHolder(total: Int) = lifecycleScope.launch(Dispatchers.IO) {
        for (i in 1..total) {
            val item = GestureActionItem()
            item.iconType = IconType.INVISIBLE
            item.tag = buildTag()
            GestureActionDataManager.addActionItem(
                applicationContext, itemListTag, item
            )
        }

        finish()
    }

    private fun saveExternalAction() {
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

    private fun saveAction(isPlaceHolder: Boolean) = lifecycleScope.launch(Dispatchers.IO) {
        actionItem.firstStageLink = binding.shortAction.actionLink.text.toString()
        actionItem.secondStageLink = binding.longAction.actionLink.text.toString()
        if (iconFileUtils.saveIcon()) {
            actionItem.icon = iconFileUtils.imgPath
        }

        if (isPlaceHolder) actionItem.iconType = IconType.INVISIBLE

        Log.d(TAG, "saveAction: tag $itemListTag")

        if (editMode) {
            GestureActionDataManager.updateActionItem(
                applicationContext, itemListTag, actionItem
            )
        } else {
            Log.d(TAG, "saveAction: expectItemTag:$expectItemTag")
            actionItem.tag = expectItemTag ?: buildTag()
            GestureActionDataManager.addActionItem(
                applicationContext, itemListTag, actionItem
            )
        }

        finish()
    }

    fun ActivityGestureAddActionConfigBinding.getActionLink(): GestureActionLink {
        return if (this == binding.shortAction) firstActionLink else secondActionLink
    }

    fun ActivityGestureAddActionConfigBinding.getButtonsState(): ConfigButtonsState {
        return if (this == binding.shortAction) firstButtonsState else secondButtonsState
    }

    override fun finish() {
        super.finish()
        if (selectTriggerActionMode || externalActionMode) return
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
        const val TAG = "GestureAddActionActivity"
        const val EXTRA_EXTERNAL_ACTION_MODE = "extra_external_action_mode"
        const val EXTRA_EXTERNAL_ACTION_LINK = "extra_external_action_link"
    }
}
