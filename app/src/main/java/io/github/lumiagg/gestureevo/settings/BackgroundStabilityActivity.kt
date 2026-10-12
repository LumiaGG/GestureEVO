package io.github.lumiagg.gestureevo.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActivityBackgroundStabilityBinding
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.launch

class BackgroundStabilityActivity : AppCompatActivity() {

    companion object {
        const val HIDE_TASK = "key_hide_task"
    }

    private lateinit var binding: ActivityBackgroundStabilityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityBackgroundStabilityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.adbCommandText.text = getString(R.string.background_stability_adb_command, packageName)

        setupToolbar()
        setupButtons()
        SettingsSearchNavigator.focusAnchorIfNeeded(binding.root, intent)
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupButtons() {
        binding.powerSettingsButton.setOnClickListener {
            openBatterySettings()
        }


        binding.autoStartButton.setOnClickListener {
            openAutoStartSettings()
        }

        binding.copyAdbButton.setOnClickListener {
            copyAdbCommand()
        }

        lifecycleScope.launch {
            binding.hideTaskButton.isChecked = settingsStore.readKey(HIDE_TASK, true)
        }
        binding.hideTaskButton.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                settingsStore.saveKey(HIDE_TASK, isChecked)
            }
        }
    }

    private fun openBatterySettings() {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            showErrorDialog(
                getString(R.string.background_battery_open_failed_title),
                getString(R.string.background_battery_open_failed_message)
            )
        }
    }

    private fun showLockTaskInfo() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.background_lock_task_title)
            .setMessage(R.string.background_lock_task_message)
            .setPositiveButton(R.string.background_lock_task_ack) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun openAutoStartSettings() {
        try {
            startActivity(backgroundAutoStartIntent(this))
        } catch (e: Exception) {
            showErrorDialog(
                getString(R.string.background_autostart_open_failed_title),
                getString(R.string.background_autostart_open_failed_message)
            )
        }
    }

    private fun copyAdbCommand() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(
            getString(R.string.background_adb_clipboard_label),
            getString(R.string.background_adb_command, packageName)
        )
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.background_adb_copied), Toast.LENGTH_SHORT).show()
    }

    private fun showErrorDialog(title: String, message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.background_dialog_ok, null)
            .show()
    }
}

internal fun backgroundAutoStartIntent(context: Context): Intent {
    val manufacturer = android.os.Build.MANUFACTURER.lowercase(java.util.Locale.ROOT)
    val component = when {
        manufacturer.contains("xiaomi") -> android.content.ComponentName(
            "com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"
        )
        manufacturer.contains("oppo") -> android.content.ComponentName(
            "com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"
        )
        manufacturer.contains("vivo") -> android.content.ComponentName(
            "com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
        )
        else -> null
    }
    return if (component != null) Intent().setComponent(component)
    else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
}
