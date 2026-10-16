package io.github.lumiagg.gestureevo.settings

import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import io.github.lumiagg.gestureevo.diagnostics.LogLevel
import io.github.lumiagg.gestureevo.diagnostics.LogModule
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec
import com.google.android.material.progressindicator.IndeterminateDrawable
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActivityDataBackBinding
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.db.getAPPDao
import io.github.lumiagg.gestureevo.db.updateDB
import io.github.lumiagg.gestureevo.pin.PinPoolItem
import io.github.lumiagg.gestureevo.pin.validatePinImagePaths
import io.github.lumiagg.gestureevo.pin.PinPoolStore
import io.github.lumiagg.gestureevo.search.ContactSearchHelper
import io.github.lumiagg.gestureevo.search.SettingsShortcutSearchHelper
import io.github.lumiagg.gestureevo.settings.ui.DefaultSelectAPPBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.SelectAPPCallBack
import io.github.lumiagg.gestureevo.settings.ui.showEditTextBottomSheet
import io.github.lumiagg.gestureevo.settings.ui.showWebDAVConfigDialog
import io.github.lumiagg.gestureevo.utils.BackupAppStates
import io.github.lumiagg.gestureevo.utils.ExternalActionPermissionStore
import io.github.lumiagg.gestureevo.utils.MoshiUtils
import io.github.lumiagg.gestureevo.utils.WebDAVManager
import io.github.lumiagg.gestureevo.utils.app.appDisplayName
import io.github.lumiagg.gestureevo.utils.app.syncAppCatalog
import io.github.lumiagg.gestureevo.utils.exportAllDataStoresSafely
import io.github.lumiagg.gestureevo.utils.restoreAllDataStoresSafely
import io.github.lumiagg.gestureevo.utils.rebaseBackupFilePaths
import io.github.lumiagg.gestureevo.utils.extractBackupZip
import com.thegrizzlylabs.sardineandroid.DavResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DataBackActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDataBackBinding
    private var currentProgressDialog: androidx.appcompat.app.AlertDialog? = null
    private lateinit var webDavManager: WebDAVManager
    private var isWebDavConfigured = false

    companion object {
        const val BACKUP_SETTINGS_DIR = "settings"
        const val BACKUP_ICONS_DIR = "icons"
        const val BACKUP_DB_STATES_DIR = "db_states"
        const val BACKUP_PIN_POOL_DIR = "pin_pool"
        const val PIN_POOL_ITEMS_FILE = "pin_pool_items.json"
        const val BACKUP_FILENAME = "backup.zip"
        const val WEBDAV_BACKUPS_DIR = "GestureEvoBackups"
        const val EXTERNAL_ACTION_PERMISSIONS_FILE = "external_action_permissions.json"
        const val APP_ALIASES_FILE = "app_aliases.json"
    }

    private data class AppAliasEntry(
        val pkgName: String,
        val alias: String
    )

    private val selectBackupLocation = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                performLocalBackup(uri)
            }
        }
    }

    private val selectRestoreFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            performLocalRestore(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityDataBackBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        webDavManager = WebDAVManager()
        setupButtons()
        checkProVersion()
        checkWebDAVStatus()
        SettingsSearchNavigator.focusAnchorIfNeeded(binding.root, intent)
    }

    private fun setupButtons() {
        binding.logsEntryButton.setOnClickListener {
            startActivity(Intent(this, LogManagerActivity::class.java))
        }
        binding.statsEntryButton.setOnClickListener {
            startActivity(Intent(this, DataStatsActivity::class.java))
        }

        // Local backup/restore
        binding.backupButton.setOnClickListener {
            showBackupConfirmDialog()
        }

        binding.restoreButton.setOnClickListener {
            showRestoreConfirmDialog()
        }

        // WebDAV config and backup/restore
        binding.configWebdavButton.setOnClickListener {
            showWebDAVConfigDialog(this, lifecycleScope) {
                // Callback when WebDAV is configured
                checkWebDAVStatus()
            }
        }

        binding.backupToWebdavButton.setOnClickListener {
            showWebDAVBackupConfirmDialog()
        }

        binding.restoreFromWebdavButton.setOnClickListener {
            showWebDAVRestoreSelectionDialog()
        }

        binding.appAliasButton.setOnClickListener {
            showAppAliasSelector()
        }

        binding.rebuildDBButton.setOnClickListener {
            rebuildDB()
        }
    }

    private fun checkProVersion() {
        updateActionEnabledState(binding.backupButton, true)
        updateActionEnabledState(binding.restoreButton, true)
        updateActionEnabledState(binding.configWebdavButton, true)
        updateActionEnabledState(binding.backupToWebdavButton, isWebDavConfigured)
        updateActionEnabledState(binding.restoreFromWebdavButton, isWebDavConfigured)
    }

    private fun checkWebDAVStatus() {
        lifecycleScope.launch {
            val serverUrl = WebDAVSettings.getServerUrl(this@DataBackActivity)
            val username = WebDAVSettings.getUsername(this@DataBackActivity)
            val password = WebDAVSettings.getPassword(this@DataBackActivity)

            if (serverUrl.isNotEmpty() && username.isNotEmpty()) {
                val loginResult = webDavManager.login(serverUrl, username, password)
                isWebDavConfigured = loginResult.isSuccess

                withContext(Dispatchers.Main) {
                    binding.webdavStatusText.text = if (isWebDavConfigured) {
                        getString(R.string.data_backup_webdav_connected, serverUrl)
                    } else {
                        getString(
                            R.string.data_backup_webdav_disconnected,
                            loginResult.exceptionOrNull()?.localizedMessage
                                ?: getString(R.string.data_backup_webdav_connect_failed)
                        )
                    }
                    updateActionEnabledState(binding.backupToWebdavButton, isWebDavConfigured)
                    updateActionEnabledState(
                        binding.restoreFromWebdavButton,
                        isWebDavConfigured
                    )
                }
            }
        }
    }

    private fun showBackupConfirmDialog() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.data_backup_confirm_title)
            .setMessage(R.string.data_backup_confirm_message)
            .setPositiveButton(R.string.data_backup_confirm_pick_location) { _, _ ->
                selectBackupLocation()
            }
            .setNegativeButton(R.string.data_backup_cancel, null).show()
    }

    private fun showWebDAVBackupConfirmDialog() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.data_backup_webdav_confirm_title)
            .setMessage(R.string.data_backup_webdav_confirm_message)
            .setPositiveButton(R.string.data_backup_webdav_confirm_action) { _, _ ->
                performWebDAVBackup()
            }
            .setNegativeButton(R.string.data_backup_cancel, null).show()
    }

    private fun showRestoreConfirmDialog() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.data_restore_confirm_title)
            .setMessage(R.string.data_restore_confirm_message)
            .setPositiveButton(R.string.data_restore_confirm_pick_file) { _, _ ->
                selectRestoreFile()
            }
            .setNegativeButton(R.string.data_backup_cancel, null).show()
    }

    private fun selectBackupLocation() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        selectBackupLocation.launch(intent)
    }

    private fun selectRestoreFile() {
        selectRestoreFile.launch(arrayOf("application/zip"))
    }

    // =============================== General Backup/Restore Methods ===============================

    /**
     * Creates a general backup.
     * @return Pair containing backup file and timestamp
     */
    private suspend fun createBackup(progressUpdater: suspend (String) -> Unit): Pair<File, String> = logDataOperation("backup") {
        val tempDir = createTempWorkDir()
        val backupDir = File(tempDir, "backup").apply { mkdirs() }

        // Create backup directory structure
        val settingsDir = File(backupDir, BACKUP_SETTINGS_DIR).apply { mkdirs() }
        val iconsDir = File(backupDir, BACKUP_ICONS_DIR).apply { mkdirs() }
        val dbStatesDir = File(backupDir, BACKUP_DB_STATES_DIR).apply { mkdirs() }
        val pinPoolDir = File(backupDir, BACKUP_PIN_POOL_DIR).apply { mkdirs() }

        // Backup settings
        progressUpdater(getString(R.string.data_backup_progress_settings))
        exportAllDataStoresSafely(settingsDir, true).getOrThrow()

        // Backup icons
        progressUpdater(getString(R.string.data_backup_progress_icons))
        backupIcons(iconsDir)

        // Backup database state
        progressUpdater(getString(R.string.data_backup_progress_app_states))
        backupDbStates(dbStatesDir)
        backupPinPool(pinPoolDir, dbStatesDir)

        // Create ZIP archive
        progressUpdater(getString(R.string.data_backup_progress_zip))
        val zipFile = File(tempDir, BACKUP_FILENAME)
        createZipFile(backupDir, zipFile)

        // Generate timestamp
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

        Pair(zipFile, timestamp)
    }

    /**
     * Restores data from backup archive.
     */
    private suspend fun restoreFromBackup(
        source: Any,
        tempDir: File,
        progressUpdater: suspend (String) -> Unit
    ) = logDataOperation("restore") {
        try {
            // Unzip backup archive
            progressUpdater(getString(R.string.data_restore_progress_unzip))
            when (source) {
                is File -> unzipBackupFile(source, tempDir)
                is Uri -> unzipBackupFile(source, tempDir)
                else -> throw IllegalArgumentException(
                    getString(R.string.data_backup_source_type_not_supported)
                )
            }

            // Restore settings
            progressUpdater(getString(R.string.data_restore_progress_settings))
            val settingsDir = File(tempDir, BACKUP_SETTINGS_DIR)
            restoreAllDataStoresSafely(settingsDir).getOrThrow()

            // Restore icons
            progressUpdater(getString(R.string.data_restore_progress_icons))
            restoreIcons(File(tempDir, BACKUP_ICONS_DIR))

            // Restore database state
            progressUpdater(getString(R.string.data_restore_progress_app_states))
            restoreDbStates(File(tempDir, BACKUP_DB_STATES_DIR))
            restorePinPool(File(tempDir, BACKUP_PIN_POOL_DIR), File(tempDir, BACKUP_DB_STATES_DIR))

            dismissProgressDialog()
        } finally {
            // Clean up temporary files
            tempDir.deleteRecursively()
        }
    }

    /**
     * Restores data from unzipped backup directory.
     */
    private suspend fun restoreFromExtractedBackup(
        extractedDir: File,
        progressUpdater: suspend (String) -> Unit
    ) = logDataOperation("restore") {
        // Restore settings
        progressUpdater(getString(R.string.data_restore_progress_settings))
        val settingsDir = File(extractedDir, BACKUP_SETTINGS_DIR)
        restoreAllDataStoresSafely(settingsDir).getOrThrow()

        // Restore icons
        progressUpdater(getString(R.string.data_restore_progress_icons))
        restoreIcons(File(extractedDir, BACKUP_ICONS_DIR))

        // Restore database state
        progressUpdater(getString(R.string.data_restore_progress_app_states))
        restoreDbStates(File(extractedDir, BACKUP_DB_STATES_DIR))
        restorePinPool(File(extractedDir, BACKUP_PIN_POOL_DIR), File(extractedDir, BACKUP_DB_STATES_DIR))
    }

    private suspend fun <T> logDataOperation(type: String, operation: suspend () -> T): T {
        val trace = java.util.UUID.randomUUID().toString()
        val started = android.os.SystemClock.elapsedRealtime()
        AppLogger.event(
            LogModule.BACKUP, "operation_started", trace = trace,
            fields = mapOf("type" to type))
        try {
            return operation().also {
                AppLogger.event(
                    LogModule.BACKUP, "operation_completed", trace = trace,
                    fields = mapOf("type" to type, "durationMs" to android.os.SystemClock.elapsedRealtime() - started))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.event(
                LogModule.BACKUP, "operation_failed",
                LogLevel.ERROR, trace = trace, error = e)
            throw e
        }
    }

    // =============================== WebDAV Backup/Restore ===============================

    private fun performWebDAVBackup() {
        setButtonsEnabled(false)
        showProgressDialog(
            getString(R.string.data_backup_webdav_progress_title),
            getString(R.string.data_backup_collecting_files)
        )

        lifecycleScope.launch {
            try {
                // Create backup
                val (zipFile, timestamp) = createBackup { message ->
                    updateProgress(message)
                }

                val tempDir = zipFile.parentFile!!
                val backupFileName = "backup_$timestamp.zip"

                try {
                    // Ensure backup directory exists on WebDAV server
                    updateProgress(getString(R.string.data_backup_webdav_check_dir))
                    webDavManager.createDirectory(WEBDAV_BACKUPS_DIR)

                    // Upload backup file
                    updateProgress(getString(R.string.data_backup_webdav_uploading))
                    val remotePath = "$WEBDAV_BACKUPS_DIR/$backupFileName"
                    val uploadResult = webDavManager.uploadFile(zipFile, remotePath)
                    if (uploadResult.isFailure) {
                        throw uploadResult.exceptionOrNull()
                            ?: Exception(getString(R.string.data_backup_webdav_upload_failed))
                    }

                    dismissProgressDialog()
                    showSuccessDialog(
                        getString(R.string.data_backup_webdav_success_title),
                        getString(R.string.data_backup_webdav_success_message)
                    )
                } finally {
                    // Clean up temporary files
                    tempDir.deleteRecursively()
                }
            } catch (e: Exception) {
                dismissProgressDialog()
                showErrorDialog(
                    getString(R.string.data_backup_webdav_failed_title),
                    e.localizedMessage ?: getString(R.string.data_backup_unknown_error)
                )
            } finally {
                setButtonsEnabled(true)
            }
        }
    }

    private fun showWebDAVRestoreSelectionDialog() {
        setButtonsEnabled(false)
        showProgressDialog(
            getString(R.string.data_backup_webdav_connecting),
            getString(R.string.data_backup_webdav_fetching_list)
        )

        lifecycleScope.launch {
            try {
                val backupFiles = withContext(Dispatchers.IO) {
                    val listResult = webDavManager.listFiles(WEBDAV_BACKUPS_DIR)
                    if (listResult.isFailure) {
                        if (listResult.exceptionOrNull()?.message?.contains(
                                "not found",
                                ignoreCase = true
                            ) == true
                        ) {
                            // Create backup directory on first use
                            webDavManager.createDirectory(WEBDAV_BACKUPS_DIR)
                            emptyList()
                        } else {
                            throw listResult.exceptionOrNull()
                                ?: Exception(getString(R.string.data_backup_webdav_list_failed))
                        }
                    } else {
                        // Filter out root directory entry and keep only .zip files
                        listResult.getOrNull()?.filter {
                            it.name != WEBDAV_BACKUPS_DIR &&
                                    it.name.endsWith(".zip", ignoreCase = true) &&
                                    !it.isDirectory
                        }?.sortedByDescending { it.modified } ?: emptyList()
                    }
                }

                dismissProgressDialog()

                if (backupFiles.isEmpty()) {
                    showErrorDialog(
                        getString(R.string.data_backup_webdav_empty_title),
                        getString(R.string.data_backup_webdav_empty_message)
                    )
                } else {
                    showWebDAVBackupSelectionDialog(backupFiles)
                }
            } catch (e: Exception) {
                dismissProgressDialog()
                showErrorDialog(
                    getString(R.string.data_backup_webdav_list_failed_title),
                    e.localizedMessage ?: getString(R.string.data_backup_unknown_error)
                )
            } finally {
                setButtonsEnabled(true)
            }
        }
    }

    private fun showWebDAVBackupSelectionDialog(backupFiles: List<DavResource>) {
        val backupNames = backupFiles.map { it.name }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.data_backup_select_file_title)
            .setItems(backupNames) { _, which ->
                val selectedBackup = backupFiles[which]
                performWebDAVRestore(selectedBackup.name)
            }
            .setNegativeButton(R.string.data_backup_cancel, null)
            .show()
    }

    private fun performWebDAVRestore(backupFileName: String) {
        setButtonsEnabled(false)
        showProgressDialog(
            getString(R.string.data_backup_webdav_restore_title),
            getString(R.string.data_backup_webdav_downloading)
        )

        lifecycleScope.launch {
            try {
                val tempDir = createTempWorkDir()
                val zipFile = File(tempDir, BACKUP_FILENAME)

                try {
                    // Download backup file
                    val remotePath = "$WEBDAV_BACKUPS_DIR/$backupFileName"
                    val downloadResult = webDavManager.downloadFile(remotePath, zipFile)
                    if (downloadResult.isFailure) {
                        throw downloadResult.exceptionOrNull()
                            ?: Exception(getString(R.string.data_backup_webdav_download_failed))
                    }

                    // Restore backup file
                    restoreFromBackup(zipFile, tempDir) { message ->
                        updateProgress(message)
                    }

                    dismissProgressDialog()
                    showRestoreSuccessDialog()
                } finally {
                    // Clean up temporary files
                    tempDir.deleteRecursively()
                }
            } catch (e: Exception) {
                dismissProgressDialog()
                showErrorDialog(
                    getString(R.string.data_backup_webdav_restore_failed_title),
                    e.localizedMessage ?: getString(R.string.data_backup_unknown_error)
                )
                e.printStackTrace()
            } finally {
                setButtonsEnabled(true)
            }
        }
    }

    // =============================== Local Backup/Restore ===============================

    private fun performLocalBackup(folderUri: Uri) {
        setButtonsEnabled(false)
        showProgressDialog(
            getString(R.string.data_backup_local_progress_title),
            getString(R.string.data_backup_collecting_files)
        )

        lifecycleScope.launch {
            try {
                // Create backup
                val (zipFile, timestamp) = createBackup { message ->
                    updateProgress(message)
                }

                val tempDir = zipFile.parentFile!!
                val targetFileName = "backup_$timestamp.zip"

                try {
                    // Save to user-selected location
                    updateProgress(getString(R.string.data_backup_saving_file))
                    saveBackupFile(zipFile, folderUri, targetFileName)

                    dismissProgressDialog()
                    showSuccessDialog(
                        getString(R.string.data_backup_local_success_title),
                        getString(R.string.data_backup_local_success_message)
                    )
                } finally {
                    // Clean up temporary files
                    tempDir.deleteRecursively()
                }
            } catch (e: Exception) {
                dismissProgressDialog()
                showErrorDialog(
                    getString(R.string.data_backup_local_failed_title),
                    e.localizedMessage ?: getString(R.string.data_backup_unknown_error)
                )
            } finally {
                setButtonsEnabled(true)
            }
        }
    }

    private fun performLocalRestore(fileUri: Uri) {
        setButtonsEnabled(false)
        showProgressDialog(
            getString(R.string.data_restore_local_progress_title),
            getString(R.string.data_restore_local_progress_message)
        )

        lifecycleScope.launch {
            try {
                // Execute restore
                val tempDir = createTempWorkDir()
                restoreFromBackup(fileUri, tempDir) { message ->
                    updateProgress(message)
                }

                dismissProgressDialog()
                showRestoreSuccessDialog()
            } catch (e: Exception) {
                e.printStackTrace()
                dismissProgressDialog()
                showErrorDialog(
                    getString(R.string.data_restore_local_failed_title),
                    e.localizedMessage ?: getString(R.string.data_backup_unknown_error)
                )
            } finally {
                setButtonsEnabled(true)
            }
        }
    }

    // =============================== Helper Methods ===============================

    private fun backupIcons(targetDir: File) {
        val iconDir = File(filesDir, "icon")
        if (!iconDir.exists()) return

        iconDir.listFiles()?.forEach { iconFile ->
            if (iconFile.isFile && iconFile.name.startsWith("saveIcon-")) {
                iconFile.copyTo(File(targetDir, iconFile.name))
            }
        }
    }

    private fun restoreIcons(sourceDir: File) {
        if (!sourceDir.exists()) return

        val iconDir = File(filesDir, "icon").apply {
            deleteRecursively() // Clear existing icons
            mkdirs()
        }

        sourceDir.listFiles()?.forEach { iconFile ->
            if (iconFile.isFile && iconFile.name.startsWith("saveIcon-")) {
                iconFile.copyTo(File(iconDir, iconFile.name))
            }
        }
    }

    private suspend fun backupPinPool(imageDir: File, dbStatesDir: File) {
        val pins = PinPoolStore.loadPins(applicationContext)
        File(dbStatesDir, PIN_POOL_ITEMS_FILE).writeText(MoshiUtils.toJson(pins, true))
        val sourceDir = PinPoolStore.pinImageDir(applicationContext)
        if (!sourceDir.exists()) return
        sourceDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                file.copyTo(File(imageDir, file.name), overwrite = true)
            }
        }
    }

    private suspend fun restorePinPool(imageDir: File, dbStatesDir: File) {
        val targetDir = PinPoolStore.pinImageDir(applicationContext)
        val indexFile = File(dbStatesDir, PIN_POOL_ITEMS_FILE)
        val items = if (indexFile.exists()) {
            val parsed = requireNotNull(MoshiUtils.toModel<List<PinPoolItem>>(indexFile.readText())) {
                "Invalid Pin index in backup"
            }
            validatePinImagePaths(targetDir, parsed.map { item ->
                item.copy(imagePath = item.imagePath?.let { rebaseBackupFilePaths(it, filesDir.absolutePath) })
            })
        } else null

        if (imageDir.exists()) {
            targetDir.deleteRecursively()
            targetDir.mkdirs()
            imageDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    file.copyTo(File(targetDir, file.name), overwrite = true)
                }
            }
        }
        if (items != null) {
            PinPoolStore.replaceAll(applicationContext, items)
        } else {
            PinPoolStore.loadPins(applicationContext)
        }
    }

    private suspend fun backupDbStates(targetDir: File) {
        val dao = getAPPDao()

        // Create files for each type of state
        val gestureBlackFile = File(targetDir, "gesture_black.txt")
        val recentBlackFile = File(targetDir, "recent_black.txt")
        val notificationIconBlackFile = File(targetDir, "notification_icon_black.txt")
        val notificationListenerWhiteFile = File(targetDir, "notification_listener_white.txt")

        // Write package names to files
        dao.getAll(state = APPModel.FLAG_GESTURE_DISABLE).joinToString("\n") { it.pkgName }
            .let { gestureBlackFile.writeText(it) }

        dao.getAll(state = APPModel.FLAG_RECENT_APPS_DISABLE).joinToString("\n") { it.pkgName }
            .let { recentBlackFile.writeText(it) }

        dao.getAll(state = APPModel.FLAG_NOTIFICATION_ICON_DISABLE)
            .joinToString("\n") { it.pkgName }
            .let { notificationIconBlackFile.writeText(it) }

        dao.getAll(state = APPModel.FLAG_NOTIFICATION_APPS_ENABLE).joinToString("\n") { it.pkgName }
            .let { notificationListenerWhiteFile.writeText(it) }

        val externalPermissionsFile = File(targetDir, EXTERNAL_ACTION_PERMISSIONS_FILE)
        val externalPermissionsJson =
            MoshiUtils.toJson(ExternalActionPermissionStore.getAllPermissions(), true)
        externalPermissionsFile.writeText(externalPermissionsJson)

        val appAliasesFile = File(targetDir, APP_ALIASES_FILE)
        val appAliasesJson = MoshiUtils.toJson(collectAppAliases(dao.getAll()), true)
        appAliasesFile.writeText(appAliasesJson)
    }

    private suspend fun restoreDbStates(sourceDir: File) {
        if (!sourceDir.exists()) return

        // Get the DAO
        val dao = getAPPDao()

        // Read package names from files
        val backedUpStates = BackupAppStates(
            gestureDisabled = readPackageListFromFile(File(sourceDir, "gesture_black.txt")),
            recentDisabled = readPackageListFromFile(File(sourceDir, "recent_black.txt")),
            notificationIconDisabled = readPackageListFromFile(File(sourceDir, "notification_icon_black.txt")),
            notificationEnabled = readPackageListFromFile(File(sourceDir, "notification_listener_white.txt")),
        )
        val externalPermissionsFile = File(sourceDir, EXTERNAL_ACTION_PERMISSIONS_FILE)
        val externalPermissions =
            MoshiUtils.toModel<List<io.github.lumiagg.gestureevo.db.ExternalActionPermissionModel>>(
                externalPermissionsFile.takeIf { it.exists() }?.readText().orEmpty()
            ) ?: emptyList()
        val appAliasesFile = File(sourceDir, APP_ALIASES_FILE)
        val appAliases = MoshiUtils.toModel<List<AppAliasEntry>>(
            appAliasesFile.takeIf { it.exists() }?.readText().orEmpty()
        ) ?: emptyList()
        val appAliasMap = appAliases.associate { it.pkgName to it.alias }

        // Clear and rebuild DB
        dao.deleteAll()
        syncAppCatalog(applicationContext)

        // Apply states to apps
        dao.getAll().forEach { appModel ->
            appAliasMap[appModel.pkgName]?.let { alias ->
                appModel.name = alias
            }
            backedUpStates.applyTo(appModel)
            // Update database
            updateDB(appModel)
        }

        ContactSearchHelper.syncContactsToDatabase(applicationContext, force = true)
        SettingsShortcutSearchHelper.syncShortcutsToDatabase(applicationContext, force = true)
        ExternalActionPermissionStore.replaceAllPermissions(externalPermissions)
    }

    private fun readPackageListFromFile(file: File): Set<String> {
        return if (file.exists()) {
            file.readLines().filter { it.isNotBlank() }.toSet()
        } else {
            emptySet()
        }
    }

    private fun createZipFile(sourceDir: File, zipFile: File) {
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            sourceDir.walk().forEach { file ->
                if (file.isFile) {
                    val entryPath = file.relativeTo(sourceDir).path
                    val entry = ZipEntry(entryPath)
                    zip.putNextEntry(entry)
                    file.inputStream().use { input ->
                        input.copyTo(zip)
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun unzipBackupFile(sourceUri: Uri, targetDir: File) {
        val input = contentResolver.openInputStream(sourceUri)
            ?: throw java.io.IOException("Cannot open backup")
        input.use { extractBackupZip(it, targetDir) }
    }

    private fun unzipBackupFile(sourceFile: File, targetDir: File) {
        sourceFile.inputStream().use { extractBackupZip(it, targetDir) }
    }

    private fun saveBackupFile(sourceFile: File, folderUri: Uri, targetFileName: String) {
        val folder =
            DocumentFile.fromTreeUri(this, folderUri)
                ?: throw Exception(getString(R.string.data_backup_folder_access_failed))

        val backupFile = folder.createFile("application/zip", targetFileName)
            ?: throw Exception(getString(R.string.data_backup_file_create_failed))

        contentResolver.openOutputStream(backupFile.uri)?.use { output ->
            sourceFile.inputStream().use { input ->
                input.copyTo(output)
            }
        }
    }

    private fun createTempWorkDir(): File {
        return File(cacheDir, "backup_temp").apply {
            deleteRecursively() // Clear any existing obsolete directory
            mkdirs()
        }
    }

    private fun showProgressDialog(title: String, message: String) {
        val spec = CircularProgressIndicatorSpec(
            this,
            null,
            0,
            com.google.android.material.R.style.Widget_Material3_CircularProgressIndicator_Small
        )
        val progressIndicatorDrawable = IndeterminateDrawable.createCircularDrawable(this, spec)

        currentProgressDialog = MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message)
            .setIcon(progressIndicatorDrawable).setCancelable(false).show()
    }

    private suspend fun updateProgress(message: String) {
        withContext(Dispatchers.Main) {
            currentProgressDialog?.setMessage(message)
        }
    }

    private fun dismissProgressDialog() {
        currentProgressDialog?.dismiss()
        currentProgressDialog = null
    }

    private fun showSuccessDialog(title: String, message: String) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message)
            .setPositiveButton(R.string.data_backup_ok, null).show()
    }

    private fun showErrorDialog(title: String, message: String) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message)
            .setPositiveButton(R.string.data_backup_ok, null).show()
    }

    private fun showRestoreSuccessDialog() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.data_restore_success_title)
            .setMessage(R.string.data_restore_success_message)
            .setPositiveButton(R.string.data_backup_ok, null).show()
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        updateActionEnabledState(binding.backupButton, enabled)
        updateActionEnabledState(binding.restoreButton, enabled)
    }

    private fun updateActionEnabledState(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.5f
    }

    private fun rebuildDB() = lifecycleScope.launch(Dispatchers.IO) {
        withContext(Dispatchers.Main) {
            binding.rebuildDBProgress.visibility = View.VISIBLE
        }

        val dao = getAPPDao()
        val appAliases = collectAppAliases(dao.getAll())
        val appAliasMap = appAliases.associate { it.pkgName to it.alias }
        // Retrieve package names for gestureBlack and recentBlack
        val gestureBlackPackages =
            dao.getAll(state = APPModel.FLAG_GESTURE_DISABLE).map { it.pkgName }
        val recentBlackPackages =
            dao.getAll(state = APPModel.FLAG_RECENT_APPS_DISABLE).map { it.pkgName }
        val notificationIconBlackPackages =
            dao.getAll(state = APPModel.FLAG_NOTIFICATION_ICON_DISABLE).map { it.pkgName }
        val notificationListenerWhitePackages =
            dao.getAll(state = APPModel.FLAG_NOTIFICATION_APPS_ENABLE).map { it.pkgName }

        dao.deleteAll()
        syncAppCatalog(applicationContext)

        dao.getAll().forEach { appModel ->
            appAliasMap[appModel.pkgName]?.let { alias ->
                appModel.name = alias
            }
            // Set status if package name is present in gestureBlackPackages or recentBlackPackages
            when {
                gestureBlackPackages.contains(appModel.pkgName) -> {
                    appModel.setState(APPModel.FLAG_GESTURE_DISABLE, true)
                }

                recentBlackPackages.contains(appModel.pkgName) -> {
                    appModel.setState(APPModel.FLAG_RECENT_APPS_DISABLE, true)
                }

                notificationIconBlackPackages.contains(appModel.pkgName) -> {
                    appModel.setState(APPModel.FLAG_NOTIFICATION_ICON_DISABLE, true)
                }

                notificationListenerWhitePackages.contains(appModel.pkgName) -> {
                    appModel.setState(APPModel.FLAG_NOTIFICATION_APPS_ENABLE, true)
                }
            }
            // Update database
            updateDB(appModel)
        }

        ContactSearchHelper.syncContactsToDatabase(applicationContext, force = true)
        SettingsShortcutSearchHelper.syncShortcutsToDatabase(applicationContext, force = true)

        withContext(Dispatchers.Main) {
            binding.rebuildDBProgress.visibility = View.INVISIBLE
            Toast.makeText(
                applicationContext,
                getString(R.string.data_backup_rebuild_done),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showAppAliasSelector() {
        DefaultSelectAPPBottomSheet(this, lifecycleScope, object : SelectAPPCallBack {
            override fun selectCompleted(model: APPModel) {
                showAppAliasEditor(model)
            }
        }).show()
    }

    private fun showAppAliasEditor(model: APPModel) {
        showEditTextBottomSheet(
            activity = this,
            coroutineScope = lifecycleScope,
            title = getString(R.string.data_app_alias_edit_title),
            hint = getString(R.string.data_app_alias_edit_hint),
            defaultText = model.name,
            onConfirm = { inputText ->
                val alias = inputText.trim()
                if (alias.isEmpty()) {
                    return@showEditTextBottomSheet
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    model.name = alias
                    updateDB(model)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@DataBackActivity,
                            getString(R.string.data_app_alias_saved, alias),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    private fun collectAppAliases(apps: List<APPModel>): List<AppAliasEntry> {
        return apps.mapNotNull { app ->
            val alias = app.name.trim()
            if (alias.isEmpty()) {
                return@mapNotNull null
            }
            val defaultName = appDisplayName(applicationContext, app.pkgName)?.trim().orEmpty()
            if (alias == defaultName) {
                null
            } else {
                AppAliasEntry(pkgName = app.pkgName, alias = alias)
            }
        }
    }
}
