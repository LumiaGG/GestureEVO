package io.github.lumiagg.gestureevo.settings.ui

import android.app.Activity
import io.github.lumiagg.gestureevo.utils.ToastHelper
import androidx.lifecycle.LifecycleCoroutineScope
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.github.lumiagg.gestureevo.databinding.BottomSheetLinkTemplatesBinding
import io.github.lumiagg.gestureevo.databinding.ItemLinkTemplateBinding
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.icon.bindAppIcon
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.link.LinkDispatchRule
import io.github.lumiagg.gestureevo.link.LinkGoRuleConverter
import io.github.lumiagg.gestureevo.link.LinkRuleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Offers ready-made rules for apps that are actually installed.
 *
 * Uses the bundled LinkGo rule set. Missing apps and existing rules are marked and left
 * unchecked; the user can explicitly select them before importing.
 */
class LinkTemplateImportBottomSheet(
    private val activity: Activity,
    private val coroutineScope: LifecycleCoroutineScope,
    private val onImported: () -> Unit
) {

    /** One offerable entry: a rule plus a note when it is already configured. */
    private data class Offer(
        val label: String,
        val rule: LinkDispatchRule,
        val alreadyPresent: Boolean,
        val installed: Boolean
    )

    fun show() {
        coroutineScope.launch {
            val existingPatterns = withContext(Dispatchers.IO) {
                LinkRuleRepository.loadRules(activity).map { it.pattern }.toSet()
            }

            val offers = withContext(Dispatchers.IO) {
                LinkGoRuleConverter.loadBuiltin(activity).map { rule ->
                    Offer(
                        label = rule.name.ifBlank { rule.pattern },
                        rule = rule,
                        alreadyPresent = rule.pattern in existingPatterns,
                        installed = isInstalled(rule.targetPackage)
                    )
                }
            }

            if (offers.none { it.installed && !it.alreadyPresent }) {
                ToastHelper.showInUIThread(activity, R.string.link_rule_import_none_installed)
                return@launch
            }

            val themedContext = DynamicColorUtils.dynamicThemedContext(activity)
            val binding = BottomSheetLinkTemplatesBinding.inflate(LayoutInflater.from(themedContext))
            val dialog = BottomSheetDialog(themedContext, R.style.ThemeOverlay_BottomSheetDialog_Scrollable)
            dialog.setContentView(binding.root)
            dialog.prepareLinkSettingsSheet(binding.root)
            val selected = BooleanArray(offers.size) { offers[it].installed && !offers[it].alreadyPresent }
            fun updateSummary() {
                val count = selected.count { it }
                binding.selectionSummary.text = activity.getString(R.string.link_selection_title, count)
                binding.importButton.isEnabled = count > 0
            }
            binding.recyclerView.layoutManager = LinearLayoutManager(themedContext)
            binding.recyclerView.adapter = object : RecyclerView.Adapter<TemplateHolder>() {
                override fun getItemCount(): Int = offers.size
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TemplateHolder =
                    TemplateHolder(ItemLinkTemplateBinding.inflate(LayoutInflater.from(parent.context), parent, false))

                override fun onBindViewHolder(holder: TemplateHolder, position: Int) {
                    val offer = offers[position]
                    with(holder.binding) {
                        templateName.text = offer.label
                        templatePattern.text = offer.rule.pattern
                        templateStatus.text = when {
                            offer.alreadyPresent -> activity.getString(R.string.link_rule_import_exists)
                            !offer.installed -> activity.getString(R.string.link_rule_import_missing)
                            else -> ""
                        }
                        templateStatus.visibility = if (templateStatus.text.isEmpty()) View.GONE else View.VISIBLE
                        appIcon.bindAppIcon(coroutineScope, offer.rule.targetPackage, offer.rule.iconPath.orEmpty())
                        selectedCheck.isChecked = selected[position]
                        root.isActivated = selected[position]
                        ViewCompat.setStateDescription(root, activity.getString(
                            if (selected[position]) R.string.link_rule_selected else R.string.link_rule_not_selected
                        ))
                        root.setOnClickListener {
                            selected[position] = !selected[position]
                            notifyItemChanged(position)
                            updateSummary()
                        }
                    }
                }
            }
            binding.importButton.setOnClickListener {
                importRules(offers.filterIndexed { index, _ -> selected[index] }.map { it.rule })
                dialog.dismiss()
            }
            updateSummary()
            dialog.show()
        }
    }

    private class TemplateHolder(val binding: ItemLinkTemplateBinding) : RecyclerView.ViewHolder(binding.root)

    private fun importRules(rules: List<LinkDispatchRule>) {
        if (rules.isEmpty()) return
        coroutineScope.launch {
            val added = withContext(Dispatchers.IO) {
                LinkRuleRepository.importConfig(activity,
                    io.github.lumiagg.gestureevo.link.LinkConfigBackup(rules = rules)).rulesAdded
            }
            ToastHelper.showInUIThread(
                activity,
                activity.getString(R.string.link_rule_import_done, added)
            )
            onImported()
        }
    }

    private fun isInstalled(packageName: String): Boolean = runCatching {
        activity.packageManager.getPackageInfo(packageName, 0)
    }.isSuccess
}
