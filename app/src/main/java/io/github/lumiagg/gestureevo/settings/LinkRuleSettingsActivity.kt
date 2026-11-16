package io.github.lumiagg.gestureevo.settings

import io.github.lumiagg.gestureevo.utils.ioResult
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActivityLinkRuleSettingsBinding
import io.github.lumiagg.gestureevo.databinding.ItemLinkRuleBinding
import io.github.lumiagg.gestureevo.link.LinkConfigTransfer
import io.github.lumiagg.gestureevo.link.LinkDispatchRule
import io.github.lumiagg.gestureevo.link.LinkRuleRepository
import io.github.lumiagg.gestureevo.link.LinkLauncher
import io.github.lumiagg.gestureevo.utils.dp2px
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import io.github.lumiagg.gestureevo.utils.VibratorUtils
import io.github.lumiagg.gestureevo.settings.ui.LinkExemptsBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.LinkExtractsBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.LinkTemplateImportBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.LinkRuleEditBottomSheet
import io.github.lumiagg.gestureevo.utils.app.appDisplayNameFromDatabase
import io.github.lumiagg.gestureevo.utils.icon.bindAppIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.lumiagg.gestureevo.utils.ToastHelper

/**
 * Full settings screen for link routing.
 *
 * Rules are listed in match order: the list is the priority, and dragging a row rewrites the
 * stored priority so the new order survives a restart. A search field filters by name, pattern
 * or target app without changing the underlying order.
 */
class LinkRuleSettingsActivity : AppCompatActivity() {

    companion object {
        const val KEY_LINK_RADAR_ENABLED = "key_link_radar_enabled"
        const val DEFAULT_LINK_RADAR_ENABLED = true
    }


    private lateinit var binding: ActivityLinkRuleSettingsBinding
    private lateinit var adapter: RuleAdapter
    private var actionMode: androidx.appcompat.view.ActionMode? = null
    private val allRules = mutableListOf<LinkDispatchRule>()
    private var filteredRules = listOf<LinkDispatchRule>()
    private var searchQuery = ""
    private val appNameCache = mutableMapOf<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLinkRuleSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupRulesList()
        setupEntries()
        setupRadarSwitch()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    /** 链接雷达开关：雷达运行时读取同一 key。 */
    private fun setupRadarSwitch() {
        lifecycleScope.launch {
            binding.radarSwitch.isChecked = settingsStore.readKey(
                KEY_LINK_RADAR_ENABLED,
                DEFAULT_LINK_RADAR_ENABLED
            )
        }
        binding.radarSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsStore.saveKey(KEY_LINK_RADAR_ENABLED, isChecked)
            }
        }
        lifecycleScope.launch {
            binding.smallWindowSwitch.isChecked = settingsStore.readKey(
                LinkLauncher.KEY_LINK_OPEN_IN_SMALL_WINDOW, false
            )
        }
        binding.smallWindowSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsStore.saveKey(LinkLauncher.KEY_LINK_OPEN_IN_SMALL_WINDOW, isChecked)
            }
        }
    }

    private fun setupRulesList() {
        adapter = RuleAdapter(
            iconScope = lifecycleScope,
            onClick = { rule ->
                if (adapter.selectionMode) adapter.toggleSelection(rule.id) else openEditor(rule)
            },
            onToggle = { rule, enabled -> persistRule(rule.copy(isEnabled = enabled)) },
            onEnterSelection = { rule -> startSelectionWith(rule) },
            onToggleSelection = { rule -> adapter.toggleSelection(rule.id) }
        )
        adapter.onSelectionCountChanged = { count ->
            actionMode?.title = getString(R.string.link_selection_title, count)
        }
        binding.rulesRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.rulesRecyclerView.adapter = adapter
        (binding.rulesRecyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        // Dragging is only meaningful on the unfiltered, non-selecting list, otherwise the new
        // order would be ambiguous.
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled(): Boolean =
                searchQuery.isBlank() && !adapter.selectionMode

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                adapter.move(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                persistOrder()
            }
        })
        touchHelper.attachToRecyclerView(binding.rulesRecyclerView)

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                // Filtering while selecting would hide rows the user marked for deletion.
                if (adapter.selectionMode) finishSelection()
                applyFilter()
            }
        })
    }

    /** Long press enters multi-select; the action mode hosts select-all and batch delete. */
    private fun startSelectionWith(firstRule: LinkDispatchRule) {
        // One controlled buzz instead of the system haptic plus the drag-start haptic.
        VibratorUtils.vibrator(this, false)
        adapter.enterSelection(firstRule.id)
        actionMode = startSupportActionMode(selectionModeCallback)
    }

    private fun finishSelection() {
        actionMode?.finish()
        actionMode = null
        adapter.clearSelection()
    }

    private val selectionModeCallback = object : androidx.appcompat.view.ActionMode.Callback {
        override fun onCreateActionMode(mode: androidx.appcompat.view.ActionMode, menu: android.view.Menu): Boolean {
            mode.menuInflater.inflate(R.menu.menu_link_rule_selection, menu)
            return true
        }

        override fun onPrepareActionMode(mode: androidx.appcompat.view.ActionMode, menu: android.view.Menu): Boolean = false

        override fun onActionItemClicked(mode: androidx.appcompat.view.ActionMode, item: android.view.MenuItem): Boolean {
            return when (item.itemId) {
                R.id.action_select_all -> {
                    adapter.selectAll()
                    true
                }

                R.id.action_delete_selected -> {
                    confirmBatchDelete(adapter.selectedRules())
                    true
                }

                else -> false
            }
        }

        override fun onDestroyActionMode(mode: androidx.appcompat.view.ActionMode) {
            adapter.clearSelection()
            actionMode = null
        }
    }

    private fun confirmBatchDelete(rules: List<LinkDispatchRule>) {
        if (rules.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.link_rule_delete)
            .setMessage(getString(R.string.link_selection_delete_confirm, rules.size))
            .setPositiveButton(R.string.link_rule_delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val ids = rules.map { it.id }.toSet()
                        LinkRuleRepository.updateRules(this@LinkRuleSettingsActivity) { current ->
                            current.filterNot { it.id in ids }
                        }
                    }
                    finishSelection()
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupEntries() {
        binding.addRuleRow.setOnClickListener { openEditor(null) }
        binding.templatesRow.setOnClickListener {
            LinkTemplateImportBottomSheet(this, lifecycleScope) { reload() }.show()
        }
        binding.exemptsRow.setOnClickListener {
            LinkExemptsBottomSheet(this, lifecycleScope).show()
        }
        binding.extractsRow.setOnClickListener {
            LinkExtractsBottomSheet(this, lifecycleScope).show()
        }
        binding.exportRow.setOnClickListener { exportConfig() }
        binding.importRow.setOnClickListener { importConfig() }
    }

    private fun reload() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                LinkRuleRepository.loadRules(this@LinkRuleSettingsActivity)
            }
            allRules.clear()
            allRules.addAll(loaded)
            appNameCache.clear()
            loaded.map { it.targetPackage }.filter { it.isNotEmpty() }.distinct().forEach { pkg ->
                val name = ioResult { appDisplayNameFromDatabase(this@LinkRuleSettingsActivity, pkg) }.getOrNull().orEmpty()
                if (name.isNotEmpty()) appNameCache[pkg] = name
            }
            applyFilter()
        }
    }

    private fun applyFilter() {
        filteredRules = if (searchQuery.isBlank()) {
            allRules.toList()
        } else {
            val query = searchQuery.trim()
            allRules.filter { rule ->
                rule.name.contains(query, ignoreCase = true) ||
                    rule.pattern.contains(query, ignoreCase = true) ||
                    appNameCache[rule.targetPackage]?.contains(query, ignoreCase = true) == true ||
                    rule.targetPackage.contains(query, ignoreCase = true)
            }
        }
        adapter.submit(filteredRules, appNameCache)
        binding.rulesEmptyView.visibility =
            if (filteredRules.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openEditor(rule: LinkDispatchRule?) {
        LinkRuleEditBottomSheet(
            activity = this,
            coroutineScope = lifecycleScope,
            original = rule,
            onSaved = { reload() }
        ).show()
    }


    private fun persistRule(rule: LinkDispatchRule) {
        // Update the row in place: a full reload re-binds every row and cancels the switch
        // animation, which read as lag.
        replaceInLists(rule)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                LinkRuleRepository.updateRules(this@LinkRuleSettingsActivity) { current ->
                    current.map { if (it.id == rule.id) it.copy(isEnabled = rule.isEnabled) else it }
                }
            }
        }
    }

    /** Replaces one rule in the master list and the visible adapter rows. */
    private fun replaceInLists(rule: LinkDispatchRule) {
        val index = allRules.indexOfFirst { it.id == rule.id }
        if (index >= 0) allRules[index] = rule
        val visibleIndex = filteredRules.indexOfFirst { it.id == rule.id }
        if (visibleIndex >= 0) {
            filteredRules = filteredRules.toMutableList().also { it[visibleIndex] = rule }
            adapter.updateItem(rule)
        }
    }

    /** Rewrites priority from the current visual order so a drag survives a restart. */
    private fun persistOrder() {
        if (searchQuery.isNotBlank()) return
        val order = adapter.currentRules().map { it.id }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                LinkRuleRepository.updateRules(this@LinkRuleSettingsActivity) { current ->
                    val indexed = current.associateBy { it.id }
                    (order.mapNotNull(indexed::get) + current.filterNot { it.id in order })
                        .mapIndexed { index, rule -> rule.copy(priority = index) }
                }
            }
            reload()
        }
    }

    private fun exportConfig() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { LinkConfigTransfer.exportToClipboard(this@LinkRuleSettingsActivity) }
            ToastHelper.showInUIThread(this@LinkRuleSettingsActivity, R.string.link_rule_export_done)
        }
    }

    private fun importConfig() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                LinkConfigTransfer.importFromClipboard(this@LinkRuleSettingsActivity)
            }
            val message = when {
                result.error == "clipboard_empty" -> getString(R.string.link_rule_import_empty)
                !result.isSuccess -> getString(R.string.link_rule_import_invalid)
                else -> getString(
                    R.string.link_rule_import_result,
                    result.rulesAdded,
                    result.exemptsAdded
                )
            }
            ToastHelper.showInUIThread(this@LinkRuleSettingsActivity, message)
            reload()
        }
    }

    /**
     * Rule row adapter.
     *
     * Kept local to this screen because it is the only place that needs drag support and hit
     * counters; the rest of the app only ever displays a rule name.
     *
     * Long press enters selection mode: rows toggle on tap, [selectAll] marks everything and
     * [selectedRules] hands the batch to the caller for deletion.
     */
    private class RuleAdapter(
        private val iconScope: CoroutineScope,
        private val onClick: (LinkDispatchRule) -> Unit,
        private val onToggle: (LinkDispatchRule, Boolean) -> Unit,
        private val onEnterSelection: (LinkDispatchRule) -> Unit,
        private val onToggleSelection: (LinkDispatchRule) -> Unit
    ) : RecyclerView.Adapter<RuleAdapter.Holder>() {

        private val items = mutableListOf<LinkDispatchRule>()
        private var names: Map<String, String> = emptyMap()

        var selectionMode = false
            private set
        private val selectedIds = mutableSetOf<String>()
        var onSelectionCountChanged: (Int) -> Unit = {}

        val selectionCount: Int get() = selectedIds.size

        fun enterSelection(firstId: String) {
            selectionMode = true
            selectedIds.clear()
            selectedIds.add(firstId)
            notifyDataSetChanged()
            onSelectionCountChanged(selectionCount)
        }

        fun toggleSelection(id: String) {
            if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
            notifyDataSetChanged()
            onSelectionCountChanged(selectionCount)
        }

        fun selectAll() {
            selectedIds.clear()
            selectedIds.addAll(items.map { it.id })
            notifyDataSetChanged()
            onSelectionCountChanged(selectionCount)
        }

        fun clearSelection() {
            if (!selectionMode && selectedIds.isEmpty()) return
            selectionMode = false
            selectedIds.clear()
            notifyDataSetChanged()
            onSelectionCountChanged(0)
        }

        fun selectedRules(): List<LinkDispatchRule> = items.filter { it.id in selectedIds }

        fun submit(rules: List<LinkDispatchRule>, appNames: Map<String, String>) {
            items.clear()
            items.addAll(rules)
            names = appNames.toMap()
            notifyDataSetChanged()
        }

        fun currentRules(): List<LinkDispatchRule> = items.toList()

        /** In-place update for a toggle: no full rebind, so the switch animation is smooth. */
        fun updateItem(rule: LinkDispatchRule) {
            val index = items.indexOfFirst { it.id == rule.id }
            if (index >= 0) {
                items[index] = rule
                notifyItemChanged(index)
            }
        }

        fun move(from: Int, to: Int) {
            if (from !in items.indices || to !in items.indices) return
            val moved = items.removeAt(from)
            items.add(to, moved)
            notifyItemMoved(from, to)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val rowBinding = ItemLinkRuleBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(rowBinding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(
                items[position],
                names,
                iconScope,
                selectionMode,
                items[position].id in selectedIds,
                onClick,
                onToggle,
                onEnterSelection,
                onToggleSelection
            )
        }

        override fun getItemCount(): Int = items.size

        class Holder(private val rowBinding: ItemLinkRuleBinding) :
            RecyclerView.ViewHolder(rowBinding.root) {

            fun bind(
                rule: LinkDispatchRule,
                appNames: Map<String, String>,
                iconScope: CoroutineScope,
                selectionMode: Boolean,
                selected: Boolean,
                onClick: (LinkDispatchRule) -> Unit,
                onToggle: (LinkDispatchRule, Boolean) -> Unit,
                onEnterSelection: (LinkDispatchRule) -> Unit,
                onToggleSelection: (LinkDispatchRule) -> Unit
            ) {
                val context = rowBinding.root.context
                rowBinding.ruleName.text = rule.name.ifBlank { rule.pattern }

                val appLabel = appNames[rule.targetPackage] ?: rule.targetPackage
                val hitLabel = if (rule.hitCount > 0) {
                    context.getString(R.string.link_rule_hit_stats, rule.hitCount)
                } else {
                    context.getString(R.string.link_rule_never_hit)
                }
                rowBinding.ruleDetail.text = "$appLabel · $hitLabel"
                rowBinding.ruleDetail.alpha = if (rule.isEnabled || selectionMode) 1f else 0.5f

                // A custom icon wins over the target app icon so a user can tell two rules for
                // the same app apart. The cache keeps scroll smooth with many rules.
                rowBinding.appIcon.bindAppIcon(iconScope, rule.targetPackage, rule.iconPath.orEmpty())

                // Selection mode swaps the enable switch for the selected highlight.
                rowBinding.enabledSwitch.visibility =
                    if (selectionMode) View.GONE else View.VISIBLE
                rowBinding.root.background = if (selected) {
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(
                            com.google.android.material.color.MaterialColors.getColor(
                                rowBinding.root,
                                com.google.android.material.R.attr.colorSecondaryContainer
                            )
                        )
                        cornerRadius = 12f.dp2px
                    }
                } else {
                    ContextCompat.getDrawable(context, R.drawable.bg_settings_tile)
                }

                // Rows opt out of the system long-press haptic: entering selection vibrates
                // exactly once from startSelectionWith() instead of a system buzz plus a
                // drag-start buzz.
                rowBinding.root.isHapticFeedbackEnabled = false
                rowBinding.enabledSwitch.setOnCheckedChangeListener(null)
                rowBinding.enabledSwitch.isChecked = rule.isEnabled
                rowBinding.enabledSwitch.setOnCheckedChangeListener { _, checked ->
                    onToggle(rule, checked)
                }
                if (selectionMode) {
                    rowBinding.root.setOnClickListener { onToggleSelection(rule) }
                    rowBinding.root.setOnLongClickListener(null)
                } else {
                    rowBinding.root.setOnClickListener { onClick(rule) }
                    // Long press enters selection mode; there is no per-row delete button.
                    rowBinding.root.setOnLongClickListener {
                        onEnterSelection(rule)
                        true
                    }
                }
            }
        }
    }
}
