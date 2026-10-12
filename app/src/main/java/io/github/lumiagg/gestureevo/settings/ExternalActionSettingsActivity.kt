package io.github.lumiagg.gestureevo.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActivityExternalActionSettingsBinding
import io.github.lumiagg.gestureevo.db.ExternalActionPermissionModel
import io.github.lumiagg.gestureevo.utils.ClipboardUtils
import io.github.lumiagg.gestureevo.utils.ExternalActionPermissionStore
import io.github.lumiagg.gestureevo.utils.GestureActionLink
import kotlinx.coroutines.launch

class ExternalActionSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityExternalActionSettingsBinding

    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult

        val link = result.data?.getStringExtra(GestureAddActionActivity.EXTRA_EXTERNAL_ACTION_LINK)
            ?.takeIf { it.isNotBlank() }
            ?: return@registerForActivityResult

        ClipboardUtils.copyText(this, link)
        Toast.makeText(
            this,
            getString(R.string.external_action_link_copied),
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExternalActionSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupToolbar()
        setupListeners()
        SettingsSearchNavigator.focusAnchorIfNeeded(binding.root, intent)
    }

    override fun onResume() {
        super.onResume()
        refreshRecords()
    }

    private fun setupToolbar() {
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupListeners() {
        binding.newRecordButton.setOnClickListener {
            launchEditor()
        }
        binding.openExternalWheelButton.setOnClickListener {
            startActivity(
                Intent(this, ActionViewSettingsActivity::class.java).apply {
                    putExtra(ActionViewSettingsActivity.EXTRA_SCROLL_TO_EXTERNAL_CALL, true)
                }
            )
        }
    }

    private fun launchEditor() {
        val intent = Intent(this, GestureAddActionActivity::class.java).apply {
            putExtra(GestureAddActionActivity.EXTRA_EXTERNAL_ACTION_MODE, true)
        }
        editorLauncher.launch(intent)
    }

    private fun refreshRecords() {
        lifecycleScope.launch {
            val records = ExternalActionPermissionStore.getAllPermissions()
            binding.recordsContainer.removeAllViews()
            binding.emptyHistoryText.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE

            records.forEach { record ->
                val itemView = layoutInflater.inflate(
                    R.layout.item_external_permission_record,
                    binding.recordsContainer,
                    false
                )

//                itemView.findViewById<TextView>(R.id.recordTitle).text = buildRecordTitle(record)
                itemView.findViewById<TextView>(R.id.recordTitle).isGone = true
                itemView.findViewById<TextView>(R.id.recordId).text = buildRecordSummary(record)
                itemView.findViewById<TextView>(R.id.recordLink).text = record.link

                itemView.findViewById<MaterialButton>(R.id.copyRecordButton).apply {
                    setOnClickListener {
                        ClipboardUtils.copyText(this@ExternalActionSettingsActivity, record.link)
                        Toast.makeText(
                            this@ExternalActionSettingsActivity,
                            getString(R.string.external_action_link_copied),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                itemView.findViewById<View>(R.id.recordLink).setOnClickListener {
                    ClipboardUtils.copyText(this@ExternalActionSettingsActivity, record.link)
                    Toast.makeText(
                        this@ExternalActionSettingsActivity,
                        getString(R.string.external_action_link_copied),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                itemView.findViewById<View>(R.id.deleteRecordButton).setOnClickListener {
                    confirmDeleteRecord(record)
                }
                binding.recordsContainer.addView(itemView)
            }
        }
    }

    private fun buildRecordTitle(record: ExternalActionPermissionModel): String {
        val packageName = record.callerPackage.takeIf { it.isNotBlank() }
            ?: return getString(R.string.action_proxy_permission_unknown_caller)
        return resolveAppName(packageName)
    }

    private fun buildRecordSummary(record: ExternalActionPermissionModel): String {
        val actionName = GestureActionLink()
            .apply { parseLink(record.link) }
            .actionType
            .showName(this)
        val scopeLabel = when (record.scopeType) {
            ExternalActionPermissionModel.ScopeType.ACTION.name -> {
                getString(R.string.external_action_permission_scope_action)
            }

            else -> {
                getString(R.string.external_action_permission_scope_link)
            }
        }
        return getString(
            R.string.external_action_permission_record_summary,
            scopeLabel,
            actionName
        )
    }

    private fun resolveAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val appLabel = packageManager.getApplicationLabel(appInfo).toString()
            if (appLabel.isBlank()) packageName else "$appLabel ($packageName)"
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    private fun confirmDeleteRecord(record: ExternalActionPermissionModel) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.external_action_delete_confirm_title)
            .setMessage(R.string.external_action_delete_confirm_message)
            .setPositiveButton(R.string.external_action_delete_button) { _, _ ->
                lifecycleScope.launch {
                    ExternalActionPermissionStore.deletePermission(record.id)
                    refreshRecords()
                    Toast.makeText(
                        this@ExternalActionSettingsActivity,
                        getString(R.string.external_action_deleted),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(R.string.data_backup_cancel, null)
            .show()
    }
}
