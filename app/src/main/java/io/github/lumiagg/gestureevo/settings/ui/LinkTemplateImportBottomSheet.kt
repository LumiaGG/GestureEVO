package io.github.lumiagg.gestureevo.settings.ui

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleCoroutineScope
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.link.LinkConfigBackup
import io.github.lumiagg.gestureevo.link.LinkDispatchRule
import io.github.lumiagg.gestureevo.link.LinkGoRuleConverter
import io.github.lumiagg.gestureevo.link.LinkRuleRepository
import io.github.lumiagg.gestureevo.utils.ToastHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Link-specific availability and defaults; presentation belongs to the shared import sheet. */
class LinkTemplateImportBottomSheet(
    private val activity: AppCompatActivity,
    private val coroutineScope: LifecycleCoroutineScope,
    private val onImported: () -> Unit,
) {
    private data class Offer(
        val rule: LinkDispatchRule,
        val alreadyPresent: Boolean,
        val installed: Boolean,
    )

    fun show() {
        coroutineScope.launch {
            val offers = withContext(Dispatchers.IO) {
                val existingPatterns = LinkRuleRepository.loadRules(activity).map { it.pattern }.toSet()
                LinkGoRuleConverter.loadBuiltin(activity).map { rule ->
                    Offer(rule, rule.pattern in existingPatterns, isInstalled(rule.targetPackage))
                }
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (offers.none { it.installed && !it.alreadyPresent }) {
                ToastHelper.showInUIThread(activity, R.string.link_rule_import_none_installed)
                return@launch
            }
            showImportSelectionBottomSheet(
                activity = activity,
                title = activity.getString(R.string.link_rule_import_title),
                description = activity.getString(R.string.link_settings_templates_description),
                options = offers.map { offer ->
                    ImportOption(
                        value = offer.rule,
                        title = offer.rule.name.ifBlank { offer.rule.pattern },
                        subtitle = offer.rule.pattern,
                        packageName = offer.rule.targetPackage,
                        iconPath = offer.rule.iconPath.orEmpty(),
                        status = when {
                            offer.alreadyPresent -> activity.getString(R.string.link_rule_import_exists)
                            !offer.installed -> activity.getString(R.string.link_rule_import_missing)
                            else -> ""
                        },
                        initiallySelected = offer.installed && !offer.alreadyPresent,
                    )
                },
                onImport = ::importRules,
            )
        }
    }

    private fun importRules(rules: List<LinkDispatchRule>) {
        if (rules.isEmpty()) return
        coroutineScope.launch {
            val added = withContext(Dispatchers.IO) {
                LinkRuleRepository.importConfig(activity, LinkConfigBackup(rules = rules)).rulesAdded
            }
            ToastHelper.showInUIThread(activity, activity.getString(R.string.link_rule_import_done, added))
            onImported()
        }
    }

    private fun isInstalled(packageName: String): Boolean = runCatching {
        activity.packageManager.getPackageInfo(packageName, 0)
    }.isSuccess
}
