package io.github.lumiagg.gestureevo.settings.ui

import android.app.Activity
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
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
import io.github.lumiagg.gestureevo.databinding.BottomSheetLinkExtractsBinding
import io.github.lumiagg.gestureevo.link.LinkExtractPattern
import io.github.lumiagg.gestureevo.link.LinkRuleRepository
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages the regexes used to find a link inside arbitrary text.
 *
 * Built-in patterns can be disabled but not deleted, because the fallback detector covers
 * common URLs and users would otherwise be able to remove every way of finding a link.
 */
class LinkExtractsBottomSheet(
    private val activity: Activity,
    private val coroutineScope: LifecycleCoroutineScope
) {

    private lateinit var dialog: BottomSheetDialog
    private lateinit var binding: BottomSheetLinkExtractsBinding
    private val patterns = mutableListOf<LinkExtractPattern>()

    fun show() {
        val themedContext = DynamicColorUtils.dynamicThemedContext(activity)
        dialog = BottomSheetDialog(themedContext, R.style.ThemeOverlay_BottomSheetDialog_Scrollable)
        binding = BottomSheetLinkExtractsBinding.inflate(LayoutInflater.from(themedContext))
        dialog.setContentView(binding.root)
        dialog.setOnShowListener {
            DynamicColorUtils.applyBottomSheetSurface(it as BottomSheetDialog)
        }

        binding.title.text = activity.getString(R.string.link_extract_title)
        binding.description.text = activity.getString(R.string.link_extract_description)
        binding.addButton.setOnClickListener { showEditor(null) }
        setupRecyclerView()
        dialog.show()
        reload()
    }

    private fun setupRecyclerView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(activity)
        binding.recyclerView.setup {
            addType<LinkExtractPattern>(R.layout.item_link_extract)
            onBind {
                val pattern = getModel<LinkExtractPattern>()
                val label = if (pattern.isBuiltin) {
                    "${pattern.name} · ${activity.getString(R.string.link_extract_builtin)}"
                } else {
                    pattern.name
                }
                findView<MaterialTextView>(R.id.nameText).text = label
                findView<MaterialTextView>(R.id.patternText).text = pattern.pattern

                findView<MaterialSwitch>(R.id.enabledSwitch).apply {
                    isChecked = pattern.isEnabled
                    setOnCheckedChangeListener { _, checked ->
                        persist(pattern.copy(isEnabled = checked))
                    }
                }

                findView<MaterialButton>(R.id.deleteButton).apply {
                    isEnabled = !pattern.isBuiltin
                    alpha = if (pattern.isBuiltin) 0.4f else 1f
                    setOnClickListener {
                        if (pattern.isBuiltin) {
                            toast(R.string.link_extract_builtin_locked)
                        } else {
                            confirmDelete(pattern)
                        }
                    }
                }

                itemView.setOnClickListener { showEditor(pattern) }
            }
        }
    }

    private fun reload() {
        coroutineScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                LinkRuleRepository.loadExtractPatterns(activity)
            }
            patterns.clear()
            patterns.addAll(loaded)
            binding.recyclerView.models = patterns.toList()
            binding.emptyView.visibility = if (patterns.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showEditor(original: LinkExtractPattern?) {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }
        val nameInput = EditText(activity).apply {
            hint = activity.getString(R.string.link_extract_name_hint)
            setText(original?.name.orEmpty())
        }
        val patternInput = EditText(activity).apply {
            hint = activity.getString(R.string.link_extract_pattern_hint)
            inputType = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(original?.pattern.orEmpty())
        }
        container.addView(nameInput)
        container.addView(patternInput)

        MaterialAlertDialogBuilder(activity)
            .setTitle(
                if (original == null) R.string.link_extract_add else R.string.link_extract_title
            )
            .setView(container)
            .setPositiveButton(R.string.link_rule_save) { _, _ ->
                val name = nameInput.text?.toString()?.trim().orEmpty()
                val pattern = patternInput.text?.toString()?.trim().orEmpty()
                when {
                    pattern.isEmpty() -> toast(R.string.link_rule_pattern_required)
                    runCatching { Regex(pattern) }.isFailure ->
                        toast(R.string.link_extract_invalid)

                    else -> {
                        val updated = (original ?: LinkExtractPattern()).copy(
                            name = name.ifEmpty { pattern.take(24) },
                            pattern = pattern
                        )
                        persist(updated)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun persist(pattern: LinkExtractPattern) {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                val current = LinkRuleRepository.loadExtractPatterns(activity).toMutableList()
                val index = current.indexOfFirst { it.id == pattern.id }
                if (index >= 0) current[index] = pattern else current.add(pattern)
                LinkRuleRepository.saveExtractPatterns(activity, current)
            }
            reload()
        }
    }

    private fun confirmDelete(pattern: LinkExtractPattern) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.link_extract_title)
            .setMessage(pattern.name)
            .setPositiveButton(R.string.link_open_action_delete) { _, _ ->
                coroutineScope.launch {
                    withContext(Dispatchers.IO) {
                        val current = LinkRuleRepository.loadExtractPatterns(activity)
                            .filterNot { it.id == pattern.id }
                        LinkRuleRepository.saveExtractPatterns(activity, current)
                    }
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(resId: Int) {
        Toast.makeText(activity, resId, Toast.LENGTH_SHORT).show()
    }
}
