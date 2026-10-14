package io.github.lumiagg.gestureevo.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActivitySettingsGuideBinding
import io.github.lumiagg.gestureevo.search.QuickSearchActivity
import io.github.lumiagg.gestureevo.settings.guide.QuickSetupController
import io.github.lumiagg.gestureevo.settings.guide.SettingsGuideState
import io.github.lumiagg.gestureevo.settings.guide.SettingsGuideStatus
import io.github.lumiagg.gestureevo.settings.guide.SettingsGuideStatusReader
import io.github.lumiagg.gestureevo.utils.NotificationPermissionPromptActivity
import io.github.lumiagg.gestureevo.utils.NotificationPermissionUtils
import io.github.lumiagg.gestureevo.utils.CurrentPackageProvider
import io.github.lumiagg.gestureevo.utils.actionStore
import io.github.lumiagg.gestureevo.utils.gestureStore
import io.github.lumiagg.gestureevo.utils.app.AShizuku
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.saveKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class SettingsGuideActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsGuideBinding
    private var leaving = false
    private var currentStatus: SettingsGuideStatus? = null
    private lateinit var quickSetup: QuickSetupController
    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) lifecycleScope.launch {
            settingsStore.saveKey(SearchEngineSettingsActivity.KEY_SEARCH_CONTACT, true)
            refreshOptionalPermissions()
        }
        else if (!shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS)) {
            openSystemSettings(appDetailsIntent())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySettingsGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupInsets()
        quickSetup = QuickSetupController(
            this,
            binding.gestureSetup,
            binding.wheelSetup,
            savedInstanceState
        ) {
            currentStatus?.let(::renderStatus)
        }
        setupNavigation()
        setupActions()
        SettingsSearchNavigator.focusAnchorIfNeeded(binding.root, intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                combine(gestureStore.data, actionStore.data, settingsStore.data,
                    CurrentPackageProvider.serviceConnectedFlow) { _, _, _, _ -> Unit }
                    .retryWhen { failure, attempt ->
                        if (failure is CancellationException) throw failure
                        Log.w(TAG, "Unable to observe guide settings", failure)
                        currentStatus = null
                        binding.finishButton.isEnabled = false
                        binding.statusMessage.setText(R.string.guide_load_failed)
                        delay(((attempt + 1).coerceAtMost(10) * 1_000L))
                        true
                    }
                    .conflate().collect { refreshStatus() }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        quickSetup.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        quickSetup.suspendPreview()
        super.onPause()
        binding.finishButton.isEnabled = false
    }

    override fun onResume() {
        super.onResume()
        if (!leaving) quickSetup.resumePreview()
    }

    override fun onStop() {
        quickSetup.suspendPreview()
        super.onStop()
    }

    override fun onDestroy() {
        quickSetup.finishPreview()
        super.onDestroy()
    }

    private suspend fun refreshStatus() {
        try {
            val current = SettingsGuideStatusReader.read(this)
            currentStatus = current
            quickSetup.refreshPreview()
            renderStatus(current)
            refreshOptionalPermissions()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read guide status", e)
            currentStatus = null
            binding.finishButton.isEnabled = false
            binding.progressText.setText(R.string.guide_load_failed)
            binding.statusMessage.setText(R.string.guide_load_failed)
        }
    }

    private fun renderStatus(current: SettingsGuideStatus) {
        val serviceReady = current.accessibilityEnabled && current.serviceConnected
        val triggerReady = quickSetup.selectedTriggerTag in current.enabledTriggerTags
        val actionReady = quickSetup.selectedTriggerTag in current.configuredTriggerTags
        val ready = serviceReady && triggerReady && actionReady
        val progress = listOf(serviceReady, triggerReady, actionReady).count { it }
        binding.progressText.text = getString(R.string.guide_progress, progress, 3)
        binding.guideProgress.progress = progress
        binding.accessibilityStatus.setText(when {
            serviceReady -> R.string.guide_accessibility_running
            current.accessibilityEnabled -> R.string.guide_accessibility_connecting
            else -> R.string.guide_accessibility_off
        })
        binding.batteryStatus.setText(
            if (current.batteryExempt) R.string.guide_battery_allowed else R.string.guide_battery_restricted
        )
        binding.triggerStatus.setText(
            if (triggerReady) R.string.guide_trigger_ready else R.string.guide_trigger_missing
        )
        binding.actionStatus.setText(
            if (actionReady) R.string.guide_action_ready else R.string.guide_action_missing
        )
        binding.statusMessage.setText(when {
            !quickSetup.isSaved -> R.string.quick_setup_unsaved
            !serviceReady -> R.string.quick_setup_permission_pending
            !ready -> R.string.guide_incomplete
            else -> R.string.quick_setup_ready
        })
        binding.finishButton.isEnabled = ready && quickSetup.isSaved && !leaving
    }

    private fun setupInsets() {
        binding.root.fitsSystemWindows = false
        binding.toolbarLayout.fitsSystemWindows = false
        val appBarHeight = resources.getDimensionPixelSize(R.dimen.app_bar_height)
        val bottomPadding = binding.guideContent.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, 0, bars.right, 0)
            binding.appBar.setPadding(0, bars.top, 0, 0)
            binding.appBar.layoutParams = binding.appBar.layoutParams.apply {
                height = appBarHeight + bars.top
            }
            binding.guideContent.setPadding(
                binding.guideContent.paddingLeft,
                binding.guideContent.paddingTop,
                binding.guideContent.paddingRight,
                bottomPadding + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setupNavigation() {
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        onBackPressedDispatcher.addCallback(this) {
            confirmLeave { quickSetup.finishPreview(); finish() }
        }
    }

    private fun setupActions() {
        binding.accessibilityButton.setOnClickListener { openAccessibilitySettings() }
        binding.batteryButton.setOnClickListener {
            openSystemSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        binding.autoStartButton.setOnClickListener {
            openSystemSettings(backgroundAutoStartIntent(this))
        }
        binding.notificationButton.setOnClickListener { requestNotifications() }
        binding.notificationAccessButton.setOnClickListener {
            openSystemSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        binding.contactsButton.setOnClickListener {
            contactsPermission.launch(Manifest.permission.READ_CONTACTS)
        }
        binding.advancedButton.setOnClickListener { showAdvancedOptions() }
        binding.finishButton.setOnClickListener {
            lifecycleScope.launch {
                if (quickSetup.hasUnsavedChanges) {
                    quickSetup.save()
                }
                refreshStatus()
                if (binding.finishButton.isEnabled) leaveGuide(completed = true)
            }
        }
        binding.skipButton.setOnClickListener { confirmLeave { leaveGuide(completed = false) } }
    }

    private fun confirmLeave(leave: () -> Unit) {
        if (quickSetup.isSaving) return
        if (!quickSetup.hasUnsavedChanges) {
            leave()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.quick_setup_unsaved_title)
            .setMessage(R.string.quick_setup_unsaved_exit)
            .setPositiveButton(R.string.quick_setup_save_and_exit) { _, _ ->
                lifecycleScope.launch {
                    if (quickSetup.save()) leave()
                    else Toast.makeText(this@SettingsGuideActivity, R.string.quick_setup_save_failed, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.quick_setup_discard) { _, _ -> leave() }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun openAccessibilitySettings() {
        openSystemSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun requestNotifications() {
        if (!NotificationPermissionUtils.canPostNotifications(this)) {
            openPage(NotificationPermissionPromptActivity::class.java)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            openSystemSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            })
        } else {
            openSystemSettings(appDetailsIntent())
        }
    }

    private fun showAdvancedOptions() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.guide_advanced_button)
            .setMessage(R.string.guide_advanced_details)
            .setPositiveButton(R.string.shizuku_request_permission) { _, _ ->
                lifecycleScope.launch {
                    AShizuku.checkPermission(this@SettingsGuideActivity)
                    refreshOptionalPermissions()
                }
            }
            .setNeutralButton(R.string.background_stability_adb_title) { _, _ ->
                showAdbAuthorization()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAdbAuthorization() {
        val command = getString(R.string.background_stability_adb_command, packageName)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.background_stability_adb_title)
            .setMessage(command)
            .setPositiveButton(R.string.background_stability_adb_copy) { _, _ ->
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ADB", command))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshOptionalPermissions() {
        if (!::binding.isInitialized) return
        fun status(enabled: Boolean): Int = if (enabled) R.string.guide_enabled else R.string.guide_optional
        binding.notificationStatus.setText(status(NotificationPermissionUtils.canPostNotifications(this)))
        binding.notificationAccessStatus.setText(status(
            packageName in NotificationManagerCompat.getEnabledListenerPackages(this)
        ))
        binding.contactsStatus.setText(status(
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
        ))
        val shizukuGranted = Shizuku.pingBinder() &&
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
                .getOrDefault(false)
        binding.advancedStatus.setText(status(shizukuGranted))
    }

    private fun openPage(destination: Class<out AppCompatActivity>, anchorId: Int? = null) {
        startActivity(Intent(this, destination).apply {
            anchorId?.let { putExtra(SettingsSearchNavigator.EXTRA_TARGET_VIEW_ID, it) }
        })
    }

    private fun openSystemSettings(intent: Intent) {
        if (!tryStartActivity(intent) && !tryStartActivity(appDetailsIntent())) {
            Toast.makeText(this, R.string.guide_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun appDetailsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null)
    )

    private fun tryStartActivity(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "Settings destination unavailable: ${intent.action}", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Settings destination denied: ${intent.action}", e)
        false
    }

    private fun leaveGuide(completed: Boolean) {
        if (leaving) return
        leaving = true
        quickSetup.finishPreview()
        binding.finishButton.isEnabled = false
        binding.skipButton.isEnabled = false
        lifecycleScope.launch {
            try {
                if (completed) SettingsGuideState.markCompleted(this@SettingsGuideActivity)
                else SettingsGuideState.markSkipped(this@SettingsGuideActivity)
                if (intent.getBooleanExtra(EXTRA_FROM_LAUNCHER, false)) {
                    if (settingsStore.readKey(SearchEngineSettingsActivity.KEY_DEFAULT_SEARCH_PAGE, false)) {
                        QuickSearchActivity.start(this@SettingsGuideActivity)
                    } else {
                        startActivity(Intent(this@SettingsGuideActivity, SettingsActivity::class.java).apply {
                            if (!settingsStore.readKey(BackgroundStabilityActivity.HIDE_TASK, true)) {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                            }
                        })
                    }
                }
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Unable to save guide state or open destination", e)
                leaving = false
                quickSetup.resumePreview()
                binding.skipButton.isEnabled = true
                Toast.makeText(this@SettingsGuideActivity, R.string.guide_save_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val EXTRA_FROM_LAUNCHER = "settings_guide_from_launcher"
        private const val TAG = "SettingsGuideActivity"
    }
}
