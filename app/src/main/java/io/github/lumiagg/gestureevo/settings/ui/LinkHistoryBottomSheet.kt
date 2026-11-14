package io.github.lumiagg.gestureevo.settings.ui

import android.app.Activity
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.drake.brv.utils.models
import com.drake.brv.utils.setup
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textview.MaterialTextView
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.BottomSheetLinkHistoryBinding
import io.github.lumiagg.gestureevo.link.LinkHistoryEntry
import io.github.lumiagg.gestureevo.link.LinkHistoryRepository
import io.github.lumiagg.gestureevo.link.LinkLauncher
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.app.appDisplayNameFromDatabase
import io.github.lumiagg.gestureevo.utils.icon.IconHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows where recent links went and lets the user open one again.
 *
 * Entries are newest first. Tapping a row re-dispatches the stored URL through the normal
 * routing path, so a rule change immediately affects what happens on re-open.
 */
class LinkHistoryBottomSheet(
    private val activity: Activity,
    private val coroutineScope: LifecycleCoroutineScope
) {

    private lateinit var dialog: BottomSheetDialog
    private lateinit var binding: BottomSheetLinkHistoryBinding
    private val entries = mutableListOf<LinkHistoryEntry>()
    private val appNameCache = mutableMapOf<String, String>()

    fun show() {
        val themedContext = DynamicColorUtils.dynamicThemedContext(activity)
        dialog = BottomSheetDialog(themedContext, R.style.ThemeOverlay_BottomSheetDialog_Scrollable)
        binding = BottomSheetLinkHistoryBinding.inflate(LayoutInflater.from(themedContext))
        dialog.setContentView(binding.root)
        dialog.setOnShowListener {
            DynamicColorUtils.applyBottomSheetSurface(it as BottomSheetDialog)
        }

        binding.title.text = activity.getString(R.string.link_history_title)
        binding.clearButton.setOnClickListener { confirmClear() }
        setupRecyclerView()
        dialog.show()
        reload()
    }

    private fun setupRecyclerView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(activity)
        binding.recyclerView.setup {
            addType<LinkHistoryEntry>(R.layout.item_link_history)
            onBind {
                val entry = getModel<LinkHistoryEntry>()
                findView<MaterialTextView>(R.id.urlText).text = entry.url
                findView<MaterialTextView>(R.id.metaText).text = metaLabel(entry)
                findView<ShapeableImageView>(R.id.appIcon).setImageDrawable(
                    runCatching { IconHelper.getIcon(activity, entry.targetPackage) }.getOrNull()
                )
                itemView.setOnClickListener { reopen(entry) }
            }
        }
    }

    private fun metaLabel(entry: LinkHistoryEntry): String {
        val time = DateUtils.formatDateTime(
            activity,
            entry.timestamp,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME
        )
        val target = when {
            !entry.success -> activity.getString(R.string.link_history_failed)
            entry.targetPackage.isEmpty() -> activity.getString(R.string.link_history_system)
            else -> appNameCache[entry.targetPackage] ?: entry.targetPackage
        }
        return "$target · $time"
    }

    private fun reload() {
        coroutineScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                LinkHistoryRepository.load(activity)
            }
            entries.clear()
            entries.addAll(loaded)
            binding.recyclerView.models = entries.toList()
            binding.emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
            preloadAppNames(loaded)
        }
    }

    private fun preloadAppNames(items: List<LinkHistoryEntry>) {
        coroutineScope.launch {
            items.map { it.targetPackage }.filter { it.isNotEmpty() }.distinct().forEach { pkg ->
                if (appNameCache.containsKey(pkg)) return@forEach
                val name = withContext(Dispatchers.IO) {
                    runCatching { appDisplayNameFromDatabase(activity, pkg) }.getOrNull()
                }.orEmpty()
                if (name.isNotEmpty()) appNameCache[pkg] = name
            }
            binding.recyclerView.models = entries.toList()
        }
    }

    private fun reopen(entry: LinkHistoryEntry) {
        coroutineScope.launch {
            LinkLauncher.openUrl(activity, entry.url)
            reload()
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.link_history_title)
            .setMessage(R.string.link_history_clear)
            .setPositiveButton(R.string.link_history_clear) { _, _ ->
                coroutineScope.launch {
                    withContext(Dispatchers.IO) { LinkHistoryRepository.clear(activity) }
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.link_history_cleared),
                        Toast.LENGTH_SHORT
                    ).show()
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
