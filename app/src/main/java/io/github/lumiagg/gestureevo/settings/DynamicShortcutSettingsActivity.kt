package io.github.lumiagg.gestureevo.settings

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActivityDynamicShortcutSettingsBinding
import io.github.lumiagg.gestureevo.db.DynamicShortcutModel
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.gesture.ui.RoundImageTextView
import io.github.lumiagg.gestureevo.utils.ActionProxy
import io.github.lumiagg.gestureevo.utils.ClipboardUtils
import io.github.lumiagg.gestureevo.utils.GestureActionLink
import io.github.lumiagg.gestureevo.utils.ToastHelper
import io.github.lumiagg.gestureevo.utils.icon.bindAppIcon
import io.github.lumiagg.gestureevo.utils.shortcuts.DynamicShortcuts
import kotlinx.coroutines.launch

class DynamicShortcutSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDynamicShortcutSettingsBinding
    private lateinit var model: DynamicShortcutViewModel
    private var selectionDialog: AlertDialog? = null
    private var shownCandidates: List<DynamicShortcutModel>? = null
    private val adapter = ImportedAdapter()
    private val currentUserSerial by lazy {
        getSystemService(UserManager::class.java).getSerialNumberForUser(android.os.Process.myUserHandle())
    }
    private val homePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updatePermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[DynamicShortcutViewModel::class.java]
        binding = ActivityDynamicShortcutSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.importedList.layoutManager = LinearLayoutManager(this)
        binding.importedList.adapter = adapter
        binding.grantHome.setOnClickListener { requestHome() }
        binding.restoreHome.setOnClickListener { openHomeSettings() }
        binding.queryShortcuts.setOnClickListener { model.query() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    binding.progress.isVisible = state.busy
                    binding.status.text = state.message
                    binding.status.isVisible = state.message.isNotEmpty()
                    binding.emptyList.isVisible = !state.busy && state.imported.isEmpty()
                    adapter.submitList(state.imported)
                    binding.importedCard.setSummary(resources.getQuantityString(
                        R.plurals.dynamic_shortcuts_list_summary, state.imported.size, state.imported.size))
                    updatePermission()
                    val candidates = state.candidates
                    if (candidates != null && shownCandidates !== candidates) showSelection(candidates)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from Appearance can change icons without changing the shortcut records.
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
        updatePermission()
        model.refresh()
    }

    private fun updatePermission() {
        val supported = Build.VERSION.SDK_INT >= 25
        val allowed = supported && DynamicShortcuts.hasPermission(this)
        binding.permissionStatus.setText(when {
            !supported -> R.string.dynamic_shortcuts_unsupported
            allowed -> R.string.dynamic_shortcuts_permission_ready
            else -> R.string.dynamic_shortcuts_permission_needed
        })
        binding.grantHome.isEnabled = supported && !allowed && !model.state.value.busy
        binding.queryShortcuts.isEnabled = allowed && !model.state.value.busy
        binding.restoreHome.isEnabled = !model.state.value.busy
    }

    private fun requestHome() {
        val intent = if (Build.VERSION.SDK_INT >= 29) {
            getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_HOME)
        } else {
            Intent(Settings.ACTION_HOME_SETTINGS)
        }
        launchHomePicker(intent)
    }

    private fun openHomeSettings() = launchHomePicker(Intent(Settings.ACTION_HOME_SETTINGS))

    private fun launchHomePicker(intent: Intent) {
        try {
            homePicker.launch(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            ToastHelper.showInUIThread(this, getString(R.string.dynamic_shortcuts_home_unavailable))
        }
    }

    private fun showSelection(records: List<DynamicShortcutModel>) {
        shownCandidates = records
        val labels = records.map { displayLabel(it) }.toTypedArray()
        val checked = records.map { it in model.selected }.toBooleanArray()
        selectionDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dynamic_shortcuts_select)
            .setMultiChoiceItems(labels, checked) { _, index, enabled ->
                if (enabled) model.selected.add(records[index]) else model.selected.remove(records[index])
            }
            .setNeutralButton(R.string.dynamic_shortcuts_select_all, null)
            .setPositiveButton(R.string.dynamic_shortcuts_import) { _, _ -> model.importSelected() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> model.dismissCandidates() }
            .setOnCancelListener { model.dismissCandidates() }
            .create().also { dialog ->
                dialog.show()
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    model.selected.addAll(records)
                    records.indices.forEach { dialog.listView.setItemChecked(it, true) }
                }
            }
    }

    private fun displayLabel(record: DynamicShortcutModel) = "${record.label}\n${appLabel(record)}"

    private fun appLabel(record: DynamicShortcutModel): String = if (record.userSerial == currentUserSerial) {
        record.appLabel
    } else {
        getString(R.string.dynamic_shortcuts_profile_label, record.appLabel, record.userSerial)
    }

    override fun onDestroy() {
        selectionDialog?.dismiss()
        super.onDestroy()
    }

    private inner class ImportedAdapter : ListAdapter<DynamicShortcutModel, ImportedHolder>(
        object : DiffUtil.ItemCallback<DynamicShortcutModel>() {
            override fun areItemsTheSame(old: DynamicShortcutModel, new: DynamicShortcutModel) =
                old.packageName == new.packageName && old.shortcutId == new.shortcutId && old.userSerial == new.userSerial
            override fun areContentsTheSame(old: DynamicShortcutModel, new: DynamicShortcutModel) = old == new
        },
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ImportedHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_dynamic_shortcut, parent, false))

        override fun onBindViewHolder(holder: ImportedHolder, position: Int) {
            val record = getItem(position)
            holder.title.text = record.label
            holder.subtitle.text = appLabel(record)
            holder.icon.bindAppIcon(lifecycleScope, record.packageName)
            holder.itemView.setOnClickListener {
                lifecycleScope.launch { ActionProxy(this@DynamicShortcutSettingsActivity, actionLink(record)).execute() }
            }
            holder.itemView.setOnLongClickListener {
                ClipboardUtils.copyText(this@DynamicShortcutSettingsActivity, actionLink(record))
                ToastHelper.showInUIThread(this@DynamicShortcutSettingsActivity, getString(R.string.dynamic_shortcuts_link_copied))
                true
            }
        }
    }

    private class ImportedHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.shortcutLabel)
        val subtitle: TextView = view.findViewById(R.id.shortcutApp)
        val icon: RoundImageTextView = view.findViewById(R.id.appIcon)
    }

    companion object {
        fun actionLink(record: DynamicShortcutModel): String = GestureActionLink(
            actionType = GestureActionType.OTHER_SHORTCUT,
        ).apply {
            addParams("uri", DynamicShortcuts.uri(record))
            addParams("pkg", record.packageName)
        }.buildLink()
    }
}
