package io.github.lumiagg.gestureevo

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.lumiagg.gestureevo.db.ExternalActionPermissionModel
import io.github.lumiagg.gestureevo.settings.ui.SettingsNavRow
import io.github.lumiagg.gestureevo.utils.ActionProxy
import io.github.lumiagg.gestureevo.utils.ExternalActionPermissionStore
import io.github.lumiagg.gestureevo.utils.GestureActionLink
import io.github.lumiagg.gestureevo.utils.ToastHelper
import kotlinx.coroutines.launch

class ExternalActionProxyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data
        if (uri == null) {
            showToastAndFinish(R.string.action_proxy_link_missing)
            return
        }

        when (uri.host) {
            "action" -> handleDirectActionLink(uri)
            else -> showToastAndFinish(R.string.action_proxy_link_missing)
        }
    }

    private fun handleDirectActionLink(uri: Uri) {
        val link = uri.toString()
        val callerPackage = resolveCallerPackage()
        if (callerPackage == packageName) {
            lifecycleScope.launch {
                executeAndFinish(link)
            }
            return
        }

        lifecycleScope.launch {
            if (!callerPackage.isNullOrBlank() &&
                ExternalActionPermissionStore.isAlwaysAllowed(callerPackage, link)
            ) {
                executeAndFinish(link)
                return@launch
            }

            showAuthorizationDialog(link, callerPackage)
        }
    }

    private fun showAuthorizationDialog(link: String, callerPackage: String?) {
        val callerName = resolveAppName(callerPackage)
        val canRememberPermission = !callerPackage.isNullOrBlank()
        val dialogMessage = buildDialogMessage(link, canRememberPermission)
        val dialogView = layoutInflater.inflate(R.layout.dialog_external_action_permission, null)

        dialogView.findViewById<TextView>(R.id.dialogTitle).text = getString(
            R.string.action_proxy_permission_inferred_caller_title,
            callerName
        )
        dialogView.findViewById<TextView>(R.id.dialogMessage).text = dialogMessage

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setOnCancelListener {
                finish()
            }
            .create()

        dialogView.findViewById<SettingsNavRow>(R.id.allowActionButton).apply {
            setTitle(getString(R.string.action_proxy_permission_allow_action))
            isEnabled = canRememberPermission
            setOnClickListener {
                rememberPermissionAndExecute(
                    dialog = dialog,
                    callerPackage = callerPackage,
                    link = link,
                    scopeType = ExternalActionPermissionModel.ScopeType.ACTION
                )
            }
        }

        dialogView.findViewById<SettingsNavRow>(R.id.allowLinkButton).apply {
            setTitle(getString(R.string.action_proxy_permission_allow_link))
            isEnabled = canRememberPermission
            setOnClickListener {
                rememberPermissionAndExecute(
                    dialog = dialog,
                    callerPackage = callerPackage,
                    link = link,
                    scopeType = ExternalActionPermissionModel.ScopeType.LINK
                )
            }
        }

        dialogView.findViewById<SettingsNavRow>(R.id.allowOnceButton).apply {
            setTitle(getString(R.string.action_proxy_permission_allow_once))
            setOnClickListener {
                dialog.dismiss()
                lifecycleScope.launch {
                    executeAndFinish(link)
                }
            }
        }

        dialogView.findViewById<MaterialButton>(R.id.cancelButton).apply {
            text = getString(R.string.data_backup_cancel)
            setOnClickListener {
                dialog.dismiss()
                finish()
            }
        }

        dialog.show()
    }

    private fun buildDialogMessage(link: String, canRememberPermission: Boolean): String {
        val actionLabel = GestureActionLink().apply { parseLink(link) }.actionType.showName(this)
        val baseMessage = getString(
            R.string.action_proxy_permission_message,
            actionLabel,
            link
        )
        val callerNotice = if (!canRememberPermission) {
            getString(R.string.action_proxy_permission_unknown_caller_once_only)
        } else {
            getString(R.string.action_proxy_permission_inferred_caller_notice)
        }
        return "$baseMessage\n\n$callerNotice"
    }

    private fun rememberPermissionAndExecute(
        dialog: AlertDialog,
        callerPackage: String?,
        link: String,
        scopeType: ExternalActionPermissionModel.ScopeType
    ) {
        lifecycleScope.launch {
            if (callerPackage.isNullOrBlank()) {
                ToastHelper.showInUIThread(
                    applicationContext,
                    getString(R.string.action_proxy_permission_unknown_caller_once_only)
                )
            } else {
                when (scopeType) {
                    ExternalActionPermissionModel.ScopeType.ACTION -> {
                        ExternalActionPermissionStore.allowAlwaysForAction(callerPackage, link)
                    }

                    ExternalActionPermissionModel.ScopeType.LINK -> {
                        ExternalActionPermissionStore.allowAlwaysForLink(callerPackage, link)
                    }
                }
                ToastHelper.showInUIThread(
                    applicationContext,
                    getString(R.string.action_proxy_permission_saved)
                )
            }
            dialog.dismiss()
            executeAndFinish(link)
        }
    }

    private suspend fun executeAndFinish(link: String) {
        ActionProxy(applicationContext, link).execute()
        finish()
    }

    private fun showToastAndFinish(messageRes: Int) {
        ToastHelper.showInUIThread(applicationContext, getString(messageRes))
        finish()
    }

    private fun resolveCallerPackage(): String? {
        callingPackage?.takeIf { it.isNotBlank() }?.let { return it }

        readReferrerUri()?.let { referrerUri ->
            if (referrerUri.scheme == "android-app") {
                referrerUri.host?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }

        intent.getStringExtra(Intent.EXTRA_REFERRER_NAME)
            ?.takeIf { it.startsWith("android-app://") }
            ?.removePrefix("android-app://")
            ?.substringBefore('/')
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return null
    }

    @Suppress("DEPRECATION")
    private fun readReferrerUri(): Uri? {
        return intent.getParcelableExtra(Intent.EXTRA_REFERRER) ?: referrer
    }

    private fun resolveAppName(packageName: String?): String {
        val resolvedPackage = packageName?.takeIf { it.isNotBlank() }
            ?: return getString(R.string.action_proxy_permission_unknown_caller)
        return try {
            val appInfo = packageManager.getApplicationInfo(resolvedPackage, 0)
            packageManager.getApplicationLabel(appInfo).toString().takeIf { it.isNotBlank() }
                ?.let { "$it ($resolvedPackage)" }
                ?: resolvedPackage
        } catch (_: Exception) {
            resolvedPackage
        }
    }
}
