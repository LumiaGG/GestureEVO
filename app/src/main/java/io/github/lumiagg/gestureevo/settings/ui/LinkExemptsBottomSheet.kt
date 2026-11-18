package io.github.lumiagg.gestureevo.settings.ui

import io.github.lumiagg.gestureevo.utils.ioResult
import android.app.Activity
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import io.github.lumiagg.gestureevo.utils.ToastHelper
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.drake.brv.utils.models
import com.drake.brv.utils.setup
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textview.MaterialTextView
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.BottomSheetLinkExemptsBinding
import io.github.lumiagg.gestureevo.link.LinkExemptDomain
import io.github.lumiagg.gestureevo.link.LinkRuleRepository
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.app.appDisplayNameFromDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * Manages the domains that must never be intercepted.
 *
 * An exemption can be limited to one source app, which is how a user keeps a banking app's own
 * links out of the rules while still redirecting the same domain from elsewhere.
 */
class LinkExemptsBottomSheet(
    private val activity: Activity,
    private val coroutineScope: LifecycleCoroutineScope
) {

    private lateinit var dialog: BottomSheetDialog
    private lateinit var binding: BottomSheetLinkExemptsBinding
    private val exempts = mutableListOf<LinkExemptDomain>()
    private val appNameCache = mutableMapOf<String, String>()

    fun show() {
        val themedContext = DynamicColorUtils.dynamicThemedContext(activity)
        dialog = BottomSheetDialog(themedContext, R.style.ThemeOverlay_BottomSheetDialog_Scrollable)
        binding = BottomSheetLinkExemptsBinding.inflate(LayoutInflater.from(themedContext))
        dialog.setContentView(binding.root)
        dialog.prepareSettingsSheet(binding.root)

        binding.title.text = activity.getString(R.string.link_rule_exempt_title)
        binding.description.text = activity.getString(R.string.link_rule_exempt_description)
        binding.addButton.setOnClickListener { showAddDialog() }
        setupRecyclerView()
        dialog.show()
        reload()
    }

    private fun setupRecyclerView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(activity)
        binding.recyclerView.setup {
            addType<LinkExemptDomain>(R.layout.item_link_exempt)
            onBind {
                val exempt = getModel<LinkExemptDomain>()
                findView<MaterialTextView>(R.id.domainText).text = exempt.domain
                findView<MaterialTextView>(R.id.sourceText).text = sourceLabel(exempt.sourcePackage)

                findView<MaterialSwitch>(R.id.enabledSwitch).apply {
                    setOnCheckedChangeListener(null)
                    isChecked = exempt.isEnabled
                    setOnCheckedChangeListener { _, checked ->
                        updateExempt(exempt.copy(isEnabled = checked))
                    }
                }

                findView<MaterialButton>(R.id.deleteButton).setOnClickListener {
                    confirmDelete(exempt)
                }
            }
        }
    }

    private fun sourceLabel(pkg: String): String {
        if (pkg.isEmpty()) return activity.getString(R.string.link_rule_exempt_all_apps)
        return appNameCache[pkg] ?: pkg
    }

    private fun reload() {
        coroutineScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                LinkRuleRepository.loadExemptDomains(activity)
            }
            exempts.clear()
            exempts.addAll(loaded)
            binding.recyclerView.models = exempts.toList()
            binding.emptyView.visibility = if (exempts.isEmpty()) View.VISIBLE else View.GONE
            preloadAppNames(loaded)
        }
    }

    private fun preloadAppNames(items: List<LinkExemptDomain>) {
        coroutineScope.launch {
            items.map { it.sourcePackage }.filter { it.isNotEmpty() }.distinct().forEach { pkg ->
                if (appNameCache.containsKey(pkg)) return@forEach
                val name = withContext(Dispatchers.IO) {
                    ioResult { appDisplayNameFromDatabase(activity, pkg) }.getOrNull()
                }.orEmpty()
                if (name.isNotEmpty()) appNameCache[pkg] = name
            }
            binding.recyclerView.models = exempts.toList()
        }
    }

    private fun showAddDialog() {
        val themedContext = DynamicColorUtils.dynamicThemedContext(activity)
        val form = LinkSettingsForm(themedContext)
        val domainField = form.field(R.string.link_rule_exempt_domain_hint)
        domainField.editText?.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val editor = MaterialAlertDialogBuilder(themedContext)
            .setTitle(R.string.link_rule_exempt_add)
            .setView(form)
            .setPositiveButton(R.string.link_rule_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        editor.setOnShowListener {
            editor.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val domain = normalizeDomain(domainField.editText?.text?.toString()?.trim().orEmpty())
                val error = when {
                    domain == null -> R.string.link_rule_exempt_invalid
                    exempts.any { it.domain == domain && it.sourcePackage.isEmpty() } -> R.string.link_rule_exempt_duplicate
                    else -> null
                }
                domainField.error = error?.let(activity::getString)
                if (error != null || domain == null) {
                    domainField.editText?.requestFocus()
                    return@setOnClickListener
                }
                addExempt(LinkExemptDomain(domain = domain, sourcePackage = ""))
                editor.dismiss()
            }
        }
        editor.show()
    }

    /** Accepts a bare domain or a full URL and reduces it to the host. */
    private fun normalizeDomain(raw: String): String? {
        if (raw.isEmpty()) return null
        val candidate = when {
            raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true) -> raw

            raw.startsWith("//") -> "https:$raw"
            else -> "https://$raw"
        }
        val host = runCatching { URL(candidate).host }.getOrNull()?.lowercase() ?: return null
        val cleaned = host.removePrefix("www.").trim('.')
        if (!cleaned.contains('.') || cleaned.contains(' ')) return null
        return cleaned
    }

    private fun addExempt(exempt: LinkExemptDomain) {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                LinkRuleRepository.updateExemptDomains(activity) { it + exempt }
            }
            reload()
        }
    }

    private fun updateExempt(exempt: LinkExemptDomain) {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                LinkRuleRepository.updateExemptDomains(activity) { current ->
                    current.map { if (it.id == exempt.id) exempt else it }
                }
            }
            reload()
        }
    }

    private fun confirmDelete(exempt: LinkExemptDomain) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.link_rule_exempt_title)
            .setMessage(exempt.domain)
            .setPositiveButton(R.string.link_open_action_delete) { _, _ ->
                coroutineScope.launch {
                    withContext(Dispatchers.IO) {
                        LinkRuleRepository.updateExemptDomains(activity) { it.filterNot { current -> current.id == exempt.id } }
                    }
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

}
