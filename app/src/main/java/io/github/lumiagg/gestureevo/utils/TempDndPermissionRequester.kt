package io.github.lumiagg.gestureevo.utils

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.lumiagg.gestureevo.R

class TempDndPermissionRequester(
    private val activity: ComponentActivity
) {
    private val requestNotificationPermissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                NotificationPermissionUtils.showDisabledPromptIfNeeded(activity)
            }
        }

    private val dndSettingsLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    fun ensurePermissionWithPrompt(onContinue: () -> Unit) {
        if (hasDndPolicyAccess() && NotificationPermissionUtils.canPostNotifications(activity)) {
            onContinue()
            return
        }

        if (hasDndPolicyAccess()) {
            NotificationPermissionUtils.showDisabledPromptIfNeeded(activity)
            onContinue()
            return
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.temp_dnd_permission_notice_title)
            .setMessage(R.string.temp_dnd_permission_notice_message)
            .setPositiveButton(R.string.temp_dnd_permission_notice_authorize) { _, _ ->
                requestPermissions()
                onContinue()
            }
            .setNegativeButton(R.string.temp_dnd_permission_notice_later) { _, _ ->
                onContinue()
            }
            .setNeutralButton(R.string.edit_bottomsheet_cancel, null)
            .show()
    }

    private fun requestPermissions() {
        if (!NotificationPermissionUtils.canPostNotifications(activity)) {
            requestNotificationPermission()
        }
        if (!hasDndPolicyAccess()) {
            dndSettingsLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            NotificationPermissionUtils.showDisabledPromptIfNeeded(activity)
        }
    }

    private fun hasDndPolicyAccess(): Boolean {
        return TempDndManager.hasUsableDndPolicyAccess(activity)
    }
}
