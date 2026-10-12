package io.github.lumiagg.gestureevo.settings

import android.os.Bundle
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActivityActionViewSettingsBinding
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.gesture.ActionViewEvent
import io.github.lumiagg.gestureevo.gesture.EventBusManager
import io.github.lumiagg.gestureevo.gesture.config.ActionConfig
import io.github.lumiagg.gestureevo.gesture.config.ActionItemsTag
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerConfig
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerDataManager
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.gesture.config.GestureActionDataManager
import io.github.lumiagg.gestureevo.gesture.config.GestureActionDataManager.buildTag
import io.github.lumiagg.gestureevo.gesture.ui.ActionView
import io.github.lumiagg.gestureevo.settings.ui.BlackListSelectAPPBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.SelectAPPCallBack
import io.github.lumiagg.gestureevo.settings.ui.showFreeTriggerSelectBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.showActionConfigBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.showEditTextBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.showPieGroupSelectBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.triggerDisplayName
import io.github.lumiagg.gestureevo.utils.ClipboardUtils
import io.github.lumiagg.gestureevo.utils.ToastHelper
import io.github.lumiagg.gestureevo.utils.uriBuilder
import io.github.lumiagg.gestureevo.utils.app.syncAppCatalog
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ActionViewSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityActionViewSettingsBinding

    private var itemListTag = ActionConfig.DEFAULT_TAG
    private val pieGroups = mutableListOf<ActionItemsTag>()
    private var previewTriggerTag = ""
    private var previewMirror = false
    private var externalCallActionItemsTag = ""
    private var externalCallTriggerTag = ""
    private var externalCallMirror = false

    companion object {
        const val KEY_MAIN_SHOW_ANIM = "key_main_show_anim"
        const val KEY_MAIN_SHOW_VIBRATE = "key_main_show_vibrate"
        const val KEY_DISABLE_ALL_WHEEL_VIBRATE = "key_disable_all_wheel_vibrate"
        const val KEY_SELECTED_HINT_ICON = "key_selected_hint_icon"
        const val KEY_PREVIEW_TRIGGER_TAG = "key_preview_trigger_tag"
        const val KEY_PREVIEW_MIRROR = "key_preview_mirror"
        const val KEY_EXTERNAL_CALL_MIRROR = "key_external_call_mirror"
        const val KEY_HIDE_ACTION_VIEW_NOTICE = "key_hide_action_view_notice"
        const val EXTRA_SCROLL_TO_EXTERNAL_CALL = "extra_scroll_to_external_call"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityActionViewSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        lifecycleScope.launch {
            initializeViews()
            checkProVersion()
            setupListeners()
            if (intent.getBooleanExtra(EXTRA_SCROLL_TO_EXTERNAL_CALL, false)) {
                focusExternalCallCard()
            } else {
                SettingsSearchNavigator.focusAnchorIfNeeded(binding.root, intent)
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            syncAppCatalog(applicationContext)
        }
    }

    override fun onPause() {
        super.onPause()
        EventBusManager.produceActionViewEvent(
            lifecycleScope, ActionViewEvent(
                show = false,
                workMode = ActionView.WorkMode.ACTIVE
            )
        )
    }

    private suspend fun initializeViews() {
        previewTriggerTag = settingsStore.readKey(KEY_PREVIEW_TRIGGER_TAG, "")
        previewMirror = settingsStore.readKey(KEY_PREVIEW_MIRROR, false)
        externalCallMirror = settingsStore.readKey(KEY_EXTERNAL_CALL_MIRROR, false)
        binding.mainViewShowAnimSwitch.isChecked = settingsStore.readKey(KEY_MAIN_SHOW_ANIM, true)
        binding.mainViewShowVibrateSwitch.isChecked =
            settingsStore.readKey(KEY_MAIN_SHOW_VIBRATE, true)
        binding.disableAllWheelVibrateSwitch.isChecked =
            settingsStore.readKey(KEY_DISABLE_ALL_WHEEL_VIBRATE, false)
        binding.selectedHintIconSwitch.isChecked =
            settingsStore.readKey(KEY_SELECTED_HINT_ICON, true)
        binding.debugNotice.visibility = if (settingsStore.readKey(KEY_HIDE_ACTION_VIEW_NOTICE, false)) {
            View.GONE
        } else {
            View.VISIBLE
        }
        setupPieGroupTabs()
        ensurePreviewTriggerSelection()
        ensureExternalCallSelection()
        refreshPreviewTriggerSummary()
        refreshExternalCallSummary()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnClickListener { showCurrentPieGroupSelector() }
        binding.toolbarLayout.setOnClickListener { showCurrentPieGroupSelector() }
        updateToolbarTitle(null)
    }

    private fun checkProVersion() {
        binding.addPieGroupButton.isEnabled = true
        binding.managePieGroupButton.isEnabled = true
        binding.selectPieGroupButton.isEnabled = true
        binding.mainViewShowAnimSwitch.isEnabled = true
        binding.mainViewShowVibrateSwitch.isEnabled = true
        binding.disableAllWheelVibrateSwitch.isEnabled = true
        binding.selectedHintIconSwitch.isEnabled = true
    }

    private fun setupListeners() {
        binding.selectPieGroupButton.setOnClickListener { showCurrentPieGroupSelector() }

        binding.addPieGroupButton.setOnClickListener {
            showAddPieGroupDialog()
        }

        binding.managePieGroupButton.setOnClickListener {
            pieGroups.find { it.tag == itemListTag }?.let(::showManagePieGroupDialog)
        }

        binding.closeDebugNoticeButton.setOnClickListener {
            binding.debugNotice.visibility = View.GONE
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_HIDE_ACTION_VIEW_NOTICE, true)
            }
        }

        binding.pieContentConfigEntry.setOnClickListener {
            if (!evoAccessibilityServiceIsEnable(applicationContext)) {
                Toast.makeText(
                    applicationContext,
                    getString(R.string.action_view_accessibility_required),
                    Toast.LENGTH_SHORT
                )
                    .show()
            }
            lifecycleScope.launch {
                EventBusManager.produceActionViewEvent(
                    this, ActionViewEvent(
                        show = true,
                        workMode = ActionView.WorkMode.CONFIG_MAIN,
                        mainItemListTag = itemListTag,
                        triggerConfig = getPreviewTrigger(),
                        isMirror = previewMirror
                    )
                )
            }
        }

        // Style button click listener
        binding.pieStyleConfigEntry.setOnClickListener {
            if (!evoAccessibilityServiceIsEnable(applicationContext)) {
                Toast.makeText(
                    applicationContext,
                    getString(R.string.action_view_accessibility_required),
                    Toast.LENGTH_SHORT
                )
                    .show()
            }
            showActionConfigBottomSheet(this, itemListTag, false, lifecycleScope, {
                lifecycleScope.launch {
                    EventBusManager.produceActionViewEvent(
                        this, ActionViewEvent(
                            show = true,
                            workMode = ActionView.WorkMode.CONFIG_MAIN,
                            mainItemListTag = itemListTag,
                            triggerConfig = getPreviewTrigger(),
                            isMirror = previewMirror
                        )
                    )
                }
            })
        }

        binding.pieSubStyleConfigEntry.setOnClickListener {
            if (!evoAccessibilityServiceIsEnable(applicationContext)) {
                Toast.makeText(
                    applicationContext,
                    getString(R.string.action_view_accessibility_required),
                    Toast.LENGTH_SHORT
                ).show()
            }
            showActionConfigBottomSheet(this, itemListTag, true, lifecycleScope, {
                lifecycleScope.launch {
                    EventBusManager.produceActionViewEvent(
                        this, ActionViewEvent(
                            show = true,
                            workMode = ActionView.WorkMode.CONFIG_SUB,
                            mainItemListTag = itemListTag,
                            triggerConfig = getPreviewTrigger(),
                            isMirror = previewMirror
                        )
                    )
                }
            })
        }

        // Recent apps blacklist button click listener
        binding.pieRecentBlackListButton.setOnClickListener {
            BlackListSelectAPPBottomSheet(
                this,
                lifecycleScope,
                object : SelectAPPCallBack {
                    override fun selectCompleted(model: APPModel) {}
                },
                blackListFlag = APPModel.FLAG_RECENT_APPS_DISABLE
            ).show()
        }

        // Quadrant button click listener
        val showPreviewTriggerSelect = {
            showFreeTriggerSelectBottomSheet(
                activity = this@ActionViewSettingsActivity,
                coroutineScope = lifecycleScope,
                title = getString(R.string.action_view_preview_trigger_select_title),
                subtitle = getString(R.string.action_view_preview_trigger_select_subtitle),
                currentSelectedTag = previewTriggerTag,
                currentSelectedMirror = previewMirror
            ) { selectedTriggerTag, selectedMirror ->
                lifecycleScope.launch {
                    previewTriggerTag = selectedTriggerTag
                    previewMirror = selectedMirror
                    settingsStore.saveKey(KEY_PREVIEW_TRIGGER_TAG, selectedTriggerTag)
                    settingsStore.saveKey(KEY_PREVIEW_MIRROR, selectedMirror)
                    refreshPreviewTriggerSummary()
                }
            }
        }

        binding.previewTriggerButton.setOnClickListener { showPreviewTriggerSelect() }

        binding.previewPieButton.setOnClickListener {
            lifecycleScope.launch {
                val trigger = getPreviewTrigger()
                if (trigger == null) {
                    ToastHelper.showInUIThread(
                        applicationContext,
                        getString(R.string.action_view_no_trigger_selected)
                    )
                    return@launch
                }
                EventBusManager.produceActionViewEvent(
                    lifecycleScope, ActionViewEvent(
                        show = true,
                        workMode = ActionView.WorkMode.ACTIVE,
                        mainItemListTag = itemListTag,
                        triggerConfig = trigger,
                        isMirror = previewMirror
                    )
                )
            }
        }

        binding.mainViewShowAnimSwitch.setOnCheckedChangeListener { view, isCheck ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_MAIN_SHOW_ANIM, isCheck)
            }
        }
        binding.mainViewShowVibrateSwitch.setOnCheckedChangeListener { view, isCheck ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_MAIN_SHOW_VIBRATE, isCheck)
            }
        }
        binding.disableAllWheelVibrateSwitch.setOnCheckedChangeListener { view, isCheck ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_DISABLE_ALL_WHEEL_VIBRATE, isCheck)
            }
        }

        binding.selectedHintIconSwitch.setOnCheckedChangeListener { view, isCheck ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_SELECTED_HINT_ICON, isCheck)
            }
        }

        binding.externalCallConfigButton.setOnClickListener {
            lifecycleScope.launch {
                showPieGroupSelectBottomSheet(
                    activity = this@ActionViewSettingsActivity,
                    coroutineScope = lifecycleScope,
                    title = getString(R.string.external_call_group_select_title),
                    subtitle = getString(R.string.external_call_group_select_subtitle),
                    currentSelectedTag = externalCallActionItemsTag
                ) { selectedTag ->
                    lifecycleScope.launch {
                        externalCallActionItemsTag = selectedTag
                        showFreeTriggerSelectBottomSheet(
                            activity = this@ActionViewSettingsActivity,
                            coroutineScope = lifecycleScope,
                            currentSelectedTag = externalCallTriggerTag,
                            currentSelectedMirror = externalCallMirror
                        ) { selectedTriggerTag, selectedMirror ->
                            lifecycleScope.launch {
                                externalCallTriggerTag = selectedTriggerTag
                                externalCallMirror = selectedMirror
                                settingsStore.saveKey(KEY_EXTERNAL_CALL_MIRROR, selectedMirror)
                                refreshExternalCallSummary()
                            }
                        }
                    }
                }
            }
        }

        binding.externalCallCopyButton.setOnClickListener {
            lifecycleScope.launch {
                val link = buildExternalCallLink()
                ClipboardUtils.copyText(this@ActionViewSettingsActivity, link)
                ToastHelper.showInUIThread(
                    this@ActionViewSettingsActivity,
                    getString(R.string.external_call_link_copied)
                )
            }
        }
    }

    private fun showCurrentPieGroupSelector() {
        if (pieGroups.isEmpty()) return
        lifecycleScope.launch {
            showPieGroupSelectBottomSheet(
                activity = this@ActionViewSettingsActivity,
                coroutineScope = lifecycleScope,
                title = getString(R.string.action_view_settings_current_group_title),
                subtitle = getString(R.string.action_view_settings_groups_description),
                currentSelectedTag = itemListTag
            ) { selectedTag ->
                itemListTag = selectedTag
                updateCurrentPieGroupSummary()
            }
        }
    }

    private suspend fun setupPieGroupTabs() {
        pieGroups.clear()
        pieGroups.addAll(GestureActionDataManager.readActionListTags(applicationContext))

        val hasGroups = pieGroups.isNotEmpty()
        binding.selectPieGroupButton.isEnabled = hasGroups
        binding.selectPieGroupButton.alpha = if (hasGroups) 1f else 0.5f
        binding.managePieGroupButton.isEnabled = hasGroups
        binding.managePieGroupButton.alpha = if (hasGroups) 1f else 0.5f

        if (hasGroups && pieGroups.none { it.tag == itemListTag }) {
            itemListTag = pieGroups.first().tag
        }
        updateCurrentPieGroupSummary()
    }

    private fun updateCurrentPieGroupSummary() {
        val currentGroupName = pieGroups.find { it.tag == itemListTag }?.name
        val currentGroupText = currentGroupName
            ?: getString(R.string.action_view_settings_current_group_not_selected)
        binding.currentPieGroupName.text = currentGroupText
        updateToolbarTitle(currentGroupName)
    }

    private fun updateToolbarTitle(currentGroupName: String?) {
        val title = currentGroupName?.let {
            getString(R.string.action_view_settings_title_with_group, it)
        } ?: getString(R.string.action_view_settings_title)
        binding.toolbar.title = title
        binding.toolbarLayout.title = title
    }

    private suspend fun ensureExternalCallSelection() {
        if (pieGroups.isNotEmpty() && pieGroups.none { it.tag == externalCallActionItemsTag }) {
            externalCallActionItemsTag = pieGroups.first().tag
        }

        val triggers = FreeTriggerDataManager.readTriggers(applicationContext)
        if (triggers.isNotEmpty() && triggers.none { it.tag == externalCallTriggerTag }) {
            externalCallTriggerTag = triggers.first().tag
            externalCallMirror = false
            settingsStore.saveKey(KEY_EXTERNAL_CALL_MIRROR, false)
        }
        if (externalCallMirror && triggers.none {
                it.tag == externalCallTriggerTag && it.getState(FreeTriggerConfig.FLAG_MIRROR)
            }) {
            externalCallMirror = false
            settingsStore.saveKey(KEY_EXTERNAL_CALL_MIRROR, false)
        }
    }

    private suspend fun ensurePreviewTriggerSelection() {
        val triggers = FreeTriggerDataManager.readTriggers(applicationContext)
        if (triggers.isNotEmpty() && triggers.none { it.tag == previewTriggerTag }) {
            previewTriggerTag = triggers.first().tag
            previewMirror = false
            settingsStore.saveKey(KEY_PREVIEW_TRIGGER_TAG, previewTriggerTag)
        }
        if (previewMirror && triggers.none {
                it.tag == previewTriggerTag && it.getState(FreeTriggerConfig.FLAG_MIRROR)
            }) {
            previewMirror = false
            settingsStore.saveKey(KEY_PREVIEW_MIRROR, false)
        }
    }

    private suspend fun refreshPreviewTriggerSummary() {
        ensurePreviewTriggerSelection()
        val trigger = FreeTriggerDataManager.getTrigger(applicationContext, previewTriggerTag)
        val triggerName = trigger?.let { triggerDisplayName(this, it, previewMirror) }
            ?: getString(R.string.action_view_no_trigger_selected)
        binding.previewTriggerName.text = triggerName
    }

    private suspend fun getPreviewTrigger(): FreeTriggerConfig? {
        ensurePreviewTriggerSelection()
        return FreeTriggerDataManager.getTrigger(applicationContext, previewTriggerTag)
    }

    private suspend fun refreshExternalCallSummary() {
        ensureExternalCallSelection()
        val groupName = pieGroups.find { it.tag == externalCallActionItemsTag }?.name
            ?: getString(R.string.external_call_not_selected)
        val triggerName = FreeTriggerDataManager.getTrigger(applicationContext, externalCallTriggerTag)
            ?.let { triggerDisplayName(this, it, externalCallMirror) }
            ?: getString(R.string.external_call_not_selected)
        binding.externalCallSummary.text = getString(
            R.string.external_call_summary_format,
            groupName,
            triggerName
        )
        binding.externalCallLink.text =
            buildExternalCallLink(externalCallActionItemsTag, externalCallTriggerTag, externalCallMirror)
    }

    private suspend fun buildExternalCallLink(): String {
        ensureExternalCallSelection()
        return buildExternalCallLink(externalCallActionItemsTag, externalCallTriggerTag, externalCallMirror)
    }

    private suspend fun buildExternalCallLink(
        actionItemsTag: String,
        triggerTag: String,
        isMirror: Boolean
    ): String {
        return buildExternalCallTargetLink(actionItemsTag, triggerTag, isMirror)
    }

    private fun buildExternalCallTargetLink(
        actionItemsTag: String,
        triggerTag: String,
        isMirror: Boolean
    ): String {
        return uriBuilder(GestureActionType.SHOW_ACTION_VIEW)
            .apply {
                if (actionItemsTag.isNotBlank()) {
                    appendQueryParameter("actionItemsTag", actionItemsTag)
                }
                if (triggerTag.isNotBlank()) {
                    appendQueryParameter("triggerTag", triggerTag)
                }
                if (isMirror) {
                    appendQueryParameter("isMirror", true.toString())
                }
            }
            .build()
            .toString()
    }

    private fun showAddPieGroupDialog() {
        showEditTextBottomSheet(
            activity = this,
            coroutineScope = lifecycleScope,
            title = getString(R.string.action_view_add_group_title),
            hint = getString(R.string.action_view_group_name_hint),
            onConfirm = { newName ->
                lifecycleScope.launch {
                    val newTag = buildTag()
                    GestureActionDataManager.addActionItemsTag(
                        applicationContext,
                        ActionItemsTag().apply {
                            tag = newTag
                            name = newName
                        })
                    itemListTag = newTag
                    setupPieGroupTabs()
                    ensureExternalCallSelection()
                    refreshExternalCallSummary()
                }
            },
        )
    }

    private fun showManagePieGroupDialog(group: ActionItemsTag) {
        showEditTextBottomSheet(
            activity = this,
            coroutineScope = lifecycleScope,
            title = getString(R.string.action_view_edit_group_title),
            hint = getString(R.string.action_view_group_name_hint),
            defaultText = group.name,
            onConfirm = { newName ->
                lifecycleScope.launch {
                    GestureActionDataManager.updateActionItemsTag(
                        applicationContext,
                        group.tag,
                        newName
                    )
                    setupPieGroupTabs()
                    refreshExternalCallSummary()
                }
            },
            showDeleteButton = true,
            deleteConfirmationMessage =
                getString(R.string.action_view_delete_group_confirm, group.name),
            onDelete = {
                lifecycleScope.launch {
                    val wasCurrentGroup = itemListTag == group.tag
                    GestureActionDataManager.removeActionItemsTag(
                        applicationContext,
                        group.tag
                    )
                    if (wasCurrentGroup) {
                        itemListTag = ""
                    }
                    setupPieGroupTabs()
                    ensureExternalCallSelection()
                    refreshExternalCallSummary()
                }
            }
        )
    }

    private fun focusExternalCallCard() {
        binding.contentScrollView.post {
            val targetY = (binding.externalCallCard.top - resources.displayMetrics.density.times(16))
                .toInt()
                .coerceAtLeast(0)
            binding.contentScrollView.smoothScrollTo(0, targetY)
            binding.externalCallCard.requestFocus()
            binding.externalCallCard.animate().cancel()
            binding.externalCallCard.alpha = 0.65f
            binding.externalCallCard.animate()
                .alpha(1f)
                .setDuration(650L)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
    }

}
