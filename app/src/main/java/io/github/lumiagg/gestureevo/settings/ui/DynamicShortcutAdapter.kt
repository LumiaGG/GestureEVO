package io.github.lumiagg.gestureevo.settings.ui

import android.content.Context
import android.os.UserManager
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ItemDynamicShortcutBinding
import io.github.lumiagg.gestureevo.db.DynamicShortcutModel
import io.github.lumiagg.gestureevo.utils.icon.bindAppIcon
import kotlinx.coroutines.CoroutineScope

/** Shared presentation for the imported list and shortcut picker. */
internal class DynamicShortcutAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onClick: (DynamicShortcutModel) -> Unit,
    private val onLongClick: ((DynamicShortcutModel) -> Unit)? = null,
) : ListAdapter<DynamicShortcutModel, DynamicShortcutAdapter.Holder>(
    object : DiffUtil.ItemCallback<DynamicShortcutModel>() {
        override fun areItemsTheSame(old: DynamicShortcutModel, new: DynamicShortcutModel) =
            old.packageName == new.packageName && old.shortcutId == new.shortcutId && old.userSerial == new.userSerial
        override fun areContentsTheSame(old: DynamicShortcutModel, new: DynamicShortcutModel) = old == new
    },
) {
    private val currentUser = context.getSystemService(UserManager::class.java)
        .getSerialNumberForUser(android.os.Process.myUserHandle())

    class Holder(val binding: ItemDynamicShortcutBinding) : RecyclerView.ViewHolder(binding.root)

    fun displayLabel(record: DynamicShortcutModel) = "${record.label}\n${appLabel(record)}"

    private fun appLabel(record: DynamicShortcutModel) = if (record.userSerial == currentUser) record.appLabel else
        context.getString(R.string.dynamic_shortcuts_profile_label, record.appLabel, record.userSerial)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemDynamicShortcutBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val record = getItem(position)
        holder.binding.apply {
            shortcutLabel.text = record.label
            shortcutApp.text = appLabel(record)
            appIcon.bindAppIcon(scope, record.packageName)
            root.setOnClickListener { onClick(record) }
            if (onLongClick != null) root.setOnLongClickListener { onLongClick.invoke(record); true }
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.binding.appIcon.bindAppIcon(scope, "")
        holder.binding.root.setOnClickListener(null)
        holder.binding.root.setOnLongClickListener(null)
        super.onViewRecycled(holder)
    }
}
