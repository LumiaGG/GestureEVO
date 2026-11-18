package io.github.lumiagg.gestureevo.settings.ui

import android.os.UserManager
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.AppSelectBottomsheetBinding
import io.github.lumiagg.gestureevo.databinding.ItemDynamicShortcutBinding
import io.github.lumiagg.gestureevo.db.DynamicShortcutModel
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.dp2px
import io.github.lumiagg.gestureevo.utils.icon.bindAppIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun showDynamicShortcutSelectBottomSheet(
    activity: AppCompatActivity,
    records: List<DynamicShortcutModel>,
    onSelected: (DynamicShortcutModel) -> Unit,
) {
    val context = DynamicColorUtils.dynamicThemedContext(activity)
    val dialog = BottomSheetDialog(context, R.style.ThemeOverlay_BottomSheetDialog_Scrollable)
    val binding = AppSelectBottomsheetBinding.inflate(LayoutInflater.from(context))
    val scope = CoroutineScope(activity.lifecycleScope.coroutineContext +
        SupervisorJob(activity.lifecycleScope.coroutineContext[Job]))
    val currentUser = activity.getSystemService(UserManager::class.java)
        .getSerialNumberForUser(android.os.Process.myUserHandle())
    val adapter = ShortcutPickerAdapter(scope, currentUser) { record ->
        dialog.dismiss()
        onSelected(record)
    }
    binding.selectionTitle.setText(R.string.shortcut_source_dynamic)
    binding.selectionCount.isVisible = true
    binding.searchInput.setHint(R.string.dynamic_shortcuts_search)
    binding.filterChipGroup.isVisible = false
    binding.appList.layoutManager = LinearLayoutManager(context)
    val horizontalPadding = 16.dp2px.toInt()
    binding.appList.setPaddingRelative(horizontalPadding, binding.appList.paddingTop,
        horizontalPadding, binding.appList.paddingBottom)
    binding.appList.adapter = adapter
    binding.emptyAppList.setText(R.string.dynamic_shortcuts_no_match)
    var searchJob: Job? = null
    fun showRecords(visible: List<DynamicShortcutModel>) {
        adapter.submitList(visible)
        binding.selectionCount.text = context.resources.getQuantityString(
            R.plurals.dynamic_shortcuts_picker_count, visible.size, visible.size)
        binding.emptyAppList.isVisible = visible.isEmpty()
    }
    showRecords(records)
    binding.searchInput.doAfterTextChanged { text ->
        val query = text?.toString()?.trim().orEmpty()
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(150)
            val visible = withContext(Dispatchers.Default) {
                records.filter { query.isEmpty() || it.label.contains(query, true) ||
                    it.appLabel.contains(query, true) || it.packageName.contains(query, true) }
            }
            showRecords(visible)
        }
    }
    val observer = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) = dialog.dismiss()
    }
    activity.lifecycle.addObserver(observer)
    dialog.setContentView(binding.root)
    dialog.setOnDismissListener {
        scope.cancel()
        binding.appList.adapter = null
        activity.lifecycle.removeObserver(observer)
    }
    dialog.setOnShowListener {
        DynamicColorUtils.applyBottomSheetSurface(dialog)
        dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            sheet.layoutParams.height = activity.window.decorView.height
            BottomSheetBehavior.from(sheet).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
                isHideable = true
            }
        }
    }
    dialog.show()
}

private class ShortcutPickerAdapter(
    private val scope: CoroutineScope,
    private val currentUser: Long,
    private val onSelected: (DynamicShortcutModel) -> Unit,
) : ListAdapter<DynamicShortcutModel, ShortcutPickerAdapter.Holder>(
    object : DiffUtil.ItemCallback<DynamicShortcutModel>() {
        override fun areItemsTheSame(old: DynamicShortcutModel, new: DynamicShortcutModel) =
            old.packageName == new.packageName && old.shortcutId == new.shortcutId && old.userSerial == new.userSerial
        override fun areContentsTheSame(old: DynamicShortcutModel, new: DynamicShortcutModel) = old == new
    },
) {
    class Holder(val binding: ItemDynamicShortcutBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemDynamicShortcutBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val record = getItem(position)
        holder.binding.apply {
            shortcutLabel.text = record.label
            shortcutApp.text = if (record.userSerial == currentUser) record.appLabel else
                root.context.getString(R.string.dynamic_shortcuts_profile_label, record.appLabel, record.userSerial)
            appIcon.bindAppIcon(scope, record.packageName)
            root.setOnClickListener { onSelected(record) }
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.binding.appIcon.bindAppIcon(scope, "")
        super.onViewRecycled(holder)
    }
}
