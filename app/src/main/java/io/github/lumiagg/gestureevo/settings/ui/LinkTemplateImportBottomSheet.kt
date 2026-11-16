package io.github.lumiagg.gestureevo.settings.ui

import android.app.Activity
import io.github.lumiagg.gestureevo.utils.ToastHelper
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

            val labels = offers.map { offer ->
                val marker = when {
                    offer.alreadyPresent ->
                        " · ${activity.getString(R.string.link_rule_import_exists)}"

                    !offer.installed ->
                        " · ${activity.getString(R.string.link_rule_import_missing)}"

                    else -> ""
                }
                "${offer.label}$marker"
            }.toTypedArray()

            val selected = BooleanArray(offers.size) { index ->
                offers[index].installed && !offers[index].alreadyPresent
            }

            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.link_rule_import_title)
                .setMultiChoiceItems(labels, selected) { _, which, checked ->
                    selected[which] = checked
                }
                .setPositiveButton(R.string.link_rule_import_confirm) { _, _ ->
                    importRules(offers.filterIndexed { index, _ -> selected[index] }.map { it.rule })
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

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
