package io.github.lumiagg.gestureevo.utils

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.lumiagg.gestureevo.R

class NotificationPermissionPromptActivity : AppCompatActivity() {
    private var waitingForResult = false

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (NotificationPermissionUtils.canPostNotifications(this)) {
                finish()
            } else {
                openNotificationSettings()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (NotificationPermissionUtils.canPostNotifications(this)) {
            finish()
            return
        }
        showPrompt()
    }

    private fun showPrompt() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.notification_permission_disabled_title)
            .setMessage(R.string.notification_permission_disabled_message)
            .setNegativeButton(R.string.edit_bottomsheet_cancel, null)
            .setPositiveButton(R.string.notification_permission_disabled_action) { _, _ ->
                requestOrOpenNotificationSettings()
            }
            .setOnDismissListener {
                if (!waitingForResult) finish()
            }
            .show()
    }

    private fun requestOrOpenNotificationSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            waitingForResult = true
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings()
            finish()
        }
    }

    private fun openNotificationSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            })
            return
        }
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
            )
        }.onFailure {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            )
        }
    }
}
