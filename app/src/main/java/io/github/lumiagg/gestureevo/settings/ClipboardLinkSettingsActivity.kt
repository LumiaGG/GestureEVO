package io.github.lumiagg.gestureevo.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.clipboard.ClipboardLinkConfig
import io.github.lumiagg.gestureevo.databinding.ActivityClipboardLinkSettingsBinding
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Settings for the capsule that offers apps to open a freshly copied link.
 *
 * Every change is written straight to the settings store, which the watcher and the capsule observe,
 * so nothing here needs a restart — the next copy already behaves differently.
 */
class ClipboardLinkSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClipboardLinkSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClipboardLinkSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupToolbar()
        setupRows()
        setupBackendRow()

        lifecycleScope.launch {
            ClipboardLinkConfig.observe(this@ClipboardLinkSettingsActivity).collect { render(it) }
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupBackendRow() {
        binding.backendRow.setOnClickListener {
            lifecycleScope.launch {
                val config = ClipboardLinkConfig.from(settingsStore.data.first())
                val values = listOf(
                    ClipboardLinkConfig.BACKEND_AUTO to getString(R.string.clipboard_link_backend_auto),
                    ClipboardLinkConfig.BACKEND_SHIZUKU to getString(R.string.clipboard_link_backend_shizuku),
                    ClipboardLinkConfig.BACKEND_ROOT to getString(R.string.clipboard_link_backend_root),
                )
                val checked = values.indexOfFirst { it.first == config.backend }.coerceAtLeast(0)
                MaterialAlertDialogBuilder(this@ClipboardLinkSettingsActivity)
                    .setTitle(R.string.clipboard_link_backend_row)
                    .setSingleChoiceItems(
                        values.map { it.second }.toTypedArray(), checked
                    ) { dialog, which ->
                        lifecycleScope.launch {
                            settingsStore.saveKey(ClipboardLinkConfig.KEY_BACKEND, values[which].first)
                        }
                        dialog.dismiss()
                    }
                    .show()
            }
        }
    }

    private fun renderBackend(config: ClipboardLinkConfig) {
        binding.backendStateText.setText(
            when (config.backend) {
                ClipboardLinkConfig.BACKEND_SHIZUKU -> R.string.clipboard_link_backend_shizuku
                ClipboardLinkConfig.BACKEND_ROOT -> R.string.clipboard_link_backend_root
                else -> R.string.clipboard_link_backend_auto
            }
        )
    }

    private fun setupRows() {
        binding.enabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch { settingsStore.saveKey(ClipboardLinkConfig.KEY_ENABLED, isChecked) }
        }
        binding.clearAfterOpenSwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsStore.saveKey(ClipboardLinkConfig.KEY_CLEAR_AFTER_OPEN, isChecked)
            }
        }
        binding.siteAppsOnlySwitch.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsStore.saveKey(ClipboardLinkConfig.KEY_SITE_APPS_ONLY, isChecked)
            }
        }
        binding.autoDismissRow.setOnClickListener { showAutoDismissDialog() }
        binding.positionRow.setOnClickListener { showPositionDialog() }
        binding.maxTargetsRow.setOnClickListener { showMaxTargetsDialog() }
    }

    private fun render(config: ClipboardLinkConfig) {
        if (binding.enabledSwitch.isChecked != config.enabled) {
            binding.enabledSwitch.isChecked = config.enabled
        }
        if (binding.siteAppsOnlySwitch.isChecked != config.siteAppsOnly) {
            binding.siteAppsOnlySwitch.isChecked = config.siteAppsOnly
        }
        renderBackend(config)
        if (binding.clearAfterOpenSwitch.isChecked != config.clearAfterOpen) {
            binding.clearAfterOpenSwitch.isChecked = config.clearAfterOpen
        }
        binding.selectedAutoDismissText.text = if (config.autoDismissMs <= 0L) {
            getString(R.string.clipboard_link_settings_auto_dismiss_never)
        } else {
            getString(
                R.string.clipboard_link_settings_auto_dismiss_seconds,
                (config.autoDismissMs / 1000L).toString()
            )
        }
        binding.selectedPositionText.setText(
            if (config.edge == ClipboardLinkConfig.EDGE_LEFT) R.string.clipboard_link_settings_position_left
            else R.string.clipboard_link_settings_position_right
        )
        binding.selectedMaxTargetsText.text = config.maxTargets.toString()

        // The remaining rows only describe a capsule that is allowed to appear at all.
        for (row in listOf(binding.autoDismissRow, binding.positionRow, binding.maxTargetsRow)) {
            row.isEnabled = config.enabled
            row.alpha = if (config.enabled) 1f else DISABLED_ALPHA
        }
        binding.siteAppsOnlyRow.isEnabled = config.enabled
        binding.siteAppsOnlyRow.alpha = if (config.enabled) 1f else DISABLED_ALPHA
        binding.clearAfterOpenRow.isEnabled = config.enabled
        binding.clearAfterOpenRow.alpha = if (config.enabled) 1f else DISABLED_ALPHA
    }

    private fun showAutoDismissDialog() {
        val values = ClipboardLinkConfig.AUTO_DISMISS_CHOICES
        val options = values.map { ms ->
            if (ms <= 0L) getString(R.string.clipboard_link_settings_auto_dismiss_never)
            else getString(
                R.string.clipboard_link_settings_auto_dismiss_seconds,
                (ms / 1000L).toString()
            )
        }.toTypedArray()
        lifecycleScope.launch {
            val current = ClipboardLinkConfig.from(settingsStore.data.first()).autoDismissMs
            val checked = values.indexOfFirst { it == current }.coerceAtLeast(0)
            MaterialAlertDialogBuilder(this@ClipboardLinkSettingsActivity)
                .setTitle(R.string.clipboard_link_settings_auto_dismiss)
                .setSingleChoiceItems(options, checked) { dialog, which ->
                    lifecycleScope.launch {
                        settingsStore.saveKey(
                            ClipboardLinkConfig.KEY_AUTO_DISMISS_MS,
                            values[which].toInt()
                        )
                    }
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun showPositionDialog() {
        lifecycleScope.launch {
            val config = ClipboardLinkConfig.from(settingsStore.data.first())
            MaterialAlertDialogBuilder(this@ClipboardLinkSettingsActivity)
                .setTitle(R.string.clipboard_link_settings_position)
                .setSingleChoiceItems(
                    arrayOf(
                        getString(R.string.clipboard_link_settings_position_right),
                        getString(R.string.clipboard_link_settings_position_left)
                    ),
                    if (config.edge == ClipboardLinkConfig.EDGE_LEFT) 1 else 0
                ) { dialog, which ->
                    lifecycleScope.launch {
                        settingsStore.saveKey(
                            ClipboardLinkConfig.KEY_CAPSULE_EDGE,
                            if (which == 1) ClipboardLinkConfig.EDGE_LEFT else ClipboardLinkConfig.EDGE_RIGHT
                        )
                    }
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun showMaxTargetsDialog() {
        val values = ClipboardLinkConfig.MAX_TARGET_CHOICES
        lifecycleScope.launch {
            val current = ClipboardLinkConfig.from(settingsStore.data.first()).maxTargets
            MaterialAlertDialogBuilder(this@ClipboardLinkSettingsActivity)
                .setTitle(R.string.clipboard_link_settings_max_targets)
                .setSingleChoiceItems(
                    values.map { it.toString() }.toTypedArray(),
                    values.indexOf(current).coerceAtLeast(0)
                ) { dialog, which ->
                    lifecycleScope.launch {
                        settingsStore.saveKey(
                            ClipboardLinkConfig.KEY_MAX_TARGETS,
                            values[which]
                        )
                    }
                    dialog.dismiss()
                }
                .show()
        }
    }

    private companion object {
        const val DISABLED_ALPHA = 0.38f
    }
}
