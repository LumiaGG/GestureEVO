package io.github.lumiagg.gestureevo.settings.ui

import io.github.lumiagg.gestureevo.utils.ioResult
import android.app.Activity
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import io.github.lumiagg.gestureevo.utils.ToastHelper
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.BottomSheetEditLinkRuleBinding
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.link.LinkDispatchRule
import io.github.lumiagg.gestureevo.link.LinkMatchType
import io.github.lumiagg.gestureevo.link.LinkResolveStrategy
import io.github.lumiagg.gestureevo.link.LinkRuleRepository
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.app.appDisplayNameFromDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Creates or edits a single dispatch rule.
 *
 * Validation happens before anything is written: a rule without a pattern or without a target
 * app would silently capture or drop links, so both are required.
 */
class LinkRuleEditBottomSheet(
    private val activity: Activity,
    private val coroutineScope: LifecycleCoroutineScope,
    private val original: LinkDispatchRule?,
    private val onSaved: () -> Unit
) {

    private lateinit var dialog: BottomSheetDialog
    private lateinit var binding: BottomSheetEditLinkRuleBinding
    private var selectedPackage: String = original?.targetPackage.orEmpty()
    private var selectedMatchType: LinkMatchType = original?.matchType ?: LinkMatchType.CONTAINS
    private var selectedStrategy: LinkResolveStrategy =
        original?.resolveStrategy ?: LinkResolveStrategy.NONE

    fun show() {
        val themedContext = DynamicColorUtils.dynamicThemedContext(activity)
        dialog = BottomSheetDialog(themedContext, R.style.ThemeOverlay_BottomSheetDialog_Scrollable)
        binding = BottomSheetEditLinkRuleBinding.inflate(LayoutInflater.from(themedContext))
        dialog.setContentView(binding.root)
        dialog.prepareLinkSettingsSheet(binding.root)

        binding.title.text = activity.getString(
            if (original == null) R.string.link_rule_new_title else R.string.link_rule_edit_title
        )
        bindMatchType()
        bindStrategy()
        bindFields()
        bindActions()
        dialog.show()
    }

    private fun bindMatchType() {
        val labels = listOf(
            activity.getString(R.string.link_rule_match_contains),
            activity.getString(R.string.link_rule_match_regex),
            activity.getString(R.string.link_rule_match_exact)
        )
        val values = listOf(
            LinkMatchType.CONTAINS,
            LinkMatchType.REGEX,
            LinkMatchType.EXACT
        )
        binding.matchTypeInput.setAdapter(
            ArrayAdapter(binding.root.context, android.R.layout.simple_dropdown_item_1line, labels)
        )
        val index = values.indexOf(selectedMatchType).coerceAtLeast(0)
        binding.matchTypeInput.setText(labels[index], false)
        binding.matchTypeInput.setOnItemClickListener { _, _, position, _ ->
            selectedMatchType = values[position]
        }
    }

    private fun bindStrategy() {
        val labels = listOf(
            activity.getString(R.string.link_rule_resolve_none),
            activity.getString(R.string.link_rule_resolve_direct),
            activity.getString(R.string.link_rule_resolve_redispatch)
        )
        val values = listOf(
            LinkResolveStrategy.NONE,
            LinkResolveStrategy.DIRECT,
            LinkResolveStrategy.REDISPATCH
        )
        binding.resolveStrategyInput.setAdapter(
            ArrayAdapter(binding.root.context, android.R.layout.simple_dropdown_item_1line, labels)
        )
        val index = values.indexOf(selectedStrategy).coerceAtLeast(0)
        binding.resolveStrategyInput.setText(labels[index], false)
        binding.resolveStrategyInput.setOnItemClickListener { _, _, position, _ ->
            selectedStrategy = values[position]
        }
        binding.resolveShortSwitch.isChecked = original?.resolveShortLink ?: false
        binding.resolveStrategyLayout.visibility = if (binding.resolveShortSwitch.isChecked) android.view.View.VISIBLE else android.view.View.GONE
        binding.resolveShortSwitch.setOnCheckedChangeListener { _, checked ->
            binding.resolveStrategyLayout.visibility = if (checked) android.view.View.VISIBLE else android.view.View.GONE
        }
        if (selectedStrategy != LinkResolveStrategy.NONE) {
            binding.resolveShortSwitch.isChecked = true
            binding.resolveStrategyLayout.visibility = android.view.View.VISIBLE
        }
    }

    private fun bindFields() {
        binding.nameInput.setText(original?.name.orEmpty())
        binding.patternInput.setText(original?.pattern.orEmpty())
        binding.templateInput.setText(original?.template.orEmpty())
        binding.excludeRecentsSwitch.isChecked = original?.excludeFromRecents ?: false
        binding.enabledSwitch.isChecked = original?.isEnabled ?: true
        updateTargetLabel(original?.targetPackage.orEmpty())
        binding.targetAppRow.setOnClickListener { pickApp() }

        binding.deleteButton.visibility = if (original == null) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun bindActions() {
        binding.saveButton.setOnClickListener { save() }
        binding.deleteButton.setOnClickListener { confirmDelete() }
    }

    private fun updateTargetLabel(pkg: String) {
        if (pkg.isEmpty()) {
            binding.targetAppValue.text = activity.getString(R.string.link_rule_target_unset)
            return
        }
        binding.targetAppValue.text = pkg
        coroutineScope.launch {
            val label = withContext(Dispatchers.IO) {
                ioResult { appDisplayNameFromDatabase(activity, pkg) }.getOrNull()
            }.orEmpty().ifEmpty { pkg }
            binding.targetAppValue.text = label
        }
    }

    private fun pickApp() {
        LinkHandlerSelectAPPBottomSheet(
            activity = activity,
            coroutineScope = coroutineScope,
            selectAPPCallBack = object : SelectAPPCallBack {
                override fun selectCompleted(model: APPModel) {
                    selectedPackage = model.pkgName
                    updateTargetLabel(selectedPackage)
                }
            }
        ).show()
    }

    private fun save() {
        val pattern = binding.patternInput.text?.toString()?.trim().orEmpty()
        binding.patternLayout.error = null
        if (pattern.isEmpty()) {
            binding.patternLayout.error = activity.getString(R.string.link_rule_pattern_required)
            binding.patternInput.requestFocus()
            return
        }
        if (selectedMatchType == LinkMatchType.REGEX) {
            val compiled = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }
            if (compiled.isFailure) {
                binding.patternLayout.error = activity.getString(R.string.link_rule_pattern_invalid)
                binding.patternInput.requestFocus()
                return
            }
        }
        if (selectedPackage.isEmpty() &&
            !(binding.resolveShortSwitch.isChecked && selectedStrategy == LinkResolveStrategy.REDISPATCH)) {
            ToastHelper.showInUIThread(activity, R.string.link_rule_target_required)
            return
        }

        val rule = (original ?: LinkDispatchRule()).copy(
            name = binding.nameInput.text?.toString()?.trim().orEmpty(),
            pattern = pattern,
            targetPackage = selectedPackage,
            component = original?.component?.takeIf { original.targetPackage == selectedPackage },
            matchType = selectedMatchType,
            template = binding.templateInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            isEnabled = binding.enabledSwitch.isChecked,
            resolveShortLink = binding.resolveShortSwitch.isChecked,
            resolveStrategy = if (binding.resolveShortSwitch.isChecked) {
                selectedStrategy
            } else {
                LinkResolveStrategy.NONE
            },
            excludeFromRecents = binding.excludeRecentsSwitch.isChecked
        )

        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                LinkRuleRepository.updateRules(activity) { stored ->
                    val current = stored.toMutableList()
                    val index = current.indexOfFirst { it.id == rule.id }
                    if (index >= 0) {
                        current[index] = rule.copy(hitCount = current[index].hitCount,
                            lastHitAt = current[index].lastHitAt, priority = current[index].priority)
                    } else {
                        // New rules go to the end so an existing order is never reshuffled.
                        current.add(rule.copy(priority = (current.maxOfOrNull { it.priority } ?: -1) + 1))
                    }
                    current
                }
            }
            dialog.dismiss()
            onSaved()
        }
    }

    private fun confirmDelete() {
        val rule = original ?: return
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.link_rule_delete)
            .setMessage(R.string.link_rule_description)
            .setPositiveButton(R.string.link_rule_delete) { _, _ ->
                coroutineScope.launch {
                    withContext(Dispatchers.IO) {
                        LinkRuleRepository.updateRules(activity) { it.filterNot { current -> current.id == rule.id } }
                    }
                    dialog.dismiss()
                    onSaved()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

}
