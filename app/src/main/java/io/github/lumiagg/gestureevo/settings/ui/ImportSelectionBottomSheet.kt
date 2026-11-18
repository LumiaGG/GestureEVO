package io.github.lumiagg.gestureevo.settings.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.BottomSheetImportSelectionBinding
import io.github.lumiagg.gestureevo.databinding.ItemImportSelectionBinding
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.icon.bindAppIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

internal fun <T> showImportSelectionBottomSheet(
    activity: AppCompatActivity,
    title: CharSequence,
    description: CharSequence,
    options: List<ImportOption<T>>,
    onSelectionChanged: (T, Boolean) -> Unit = { _, _ -> },
    onCancelled: () -> Unit = {},
    onImport: (List<T>) -> Unit,
) {
    val context = DynamicColorUtils.dynamicThemedContext(activity)
    val scope = CoroutineScope(activity.lifecycleScope.coroutineContext +
        SupervisorJob(activity.lifecycleScope.coroutineContext[Job]))
    val binding = BottomSheetImportSelectionBinding.inflate(LayoutInflater.from(context))
    val dialog = BottomSheetDialog(context, R.style.ThemeOverlay_BottomSheetDialog_Scrollable)
    val selection = ImportSelection(options)
    binding.title.text = title
    binding.description.text = description
    binding.description.isVisible = description.isNotEmpty()
    binding.emptyView.isVisible = options.isEmpty()

    fun updateSummary() {
        binding.selectionSummary.text = context.resources.getQuantityString(
            R.plurals.import_selection_count, selection.count, selection.count)
        binding.importButton.isEnabled = selection.count > 0
        binding.selectAllButton.isEnabled = selection.options.isNotEmpty()
        binding.selectAllButton.setText(if (selection.allSelected) R.string.import_selection_clear_all else R.string.import_selection_select_all)
    }

    val adapter = object : RecyclerView.Adapter<ImportHolder>() {
        override fun getItemCount() = selection.options.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ImportHolder(
            ItemImportSelectionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: ImportHolder, position: Int) {
            val option = selection.options[position]
            holder.binding.apply {
                itemTitle.text = option.title
                itemSubtitle.text = option.subtitle
                itemSubtitle.isVisible = option.subtitle.isNotEmpty()
                itemStatus.text = option.status
                itemStatus.isVisible = option.status.isNotEmpty()
                appIcon.bindAppIcon(scope, option.packageName, option.iconPath)
                selectedCheck.isChecked = selection.isSelected(position)
                root.isActivated = selection.isSelected(position)
                ViewCompat.setStateDescription(root, context.getString(
                    if (selection.isSelected(position)) R.string.import_selection_selected else R.string.import_selection_not_selected))
                root.setOnClickListener {
                    val selected = selection.toggle(position)
                    onSelectionChanged(option.value, selected)
                    notifyItemChanged(position)
                    updateSummary()
                }
            }
        }

        override fun onViewRecycled(holder: ImportHolder) {
            holder.binding.appIcon.bindAppIcon(scope, "")
            holder.binding.root.setOnClickListener(null)
            super.onViewRecycled(holder)
        }
    }
    binding.recyclerView.layoutManager = LinearLayoutManager(context)
    binding.recyclerView.adapter = adapter
    binding.selectAllButton.setOnClickListener {
        val selected = !selection.allSelected
        selection.setAll(selected).forEach { index ->
            onSelectionChanged(selection.options[index].value, selected)
            adapter.notifyItemChanged(index)
        }
        updateSummary()
    }
    binding.importButton.setOnClickListener {
        if (!dialog.isShowing) return@setOnClickListener
        val snapshot = selection.values
        if (snapshot.isNotEmpty()) {
            binding.importButton.isEnabled = false
            // The accepted import belongs to the caller; dismissing the preview cancels only UI work.
            dialog.dismiss()
            onImport(snapshot)
        }
    }
    val observer = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) = dialog.dismiss()
    }
    activity.lifecycle.addObserver(observer)
    dialog.setOnCancelListener { onCancelled() }
    dialog.setOnDismissListener {
        scope.cancel()
        binding.recyclerView.adapter = null
        activity.lifecycle.removeObserver(observer)
    }
    dialog.setContentView(binding.root)
    dialog.prepareSettingsSheet(binding.root)
    updateSummary()
    dialog.show()
}

private class ImportHolder(val binding: ItemImportSelectionBinding) : RecyclerView.ViewHolder(binding.root)
