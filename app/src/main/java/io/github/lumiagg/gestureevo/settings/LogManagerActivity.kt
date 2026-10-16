package io.github.lumiagg.gestureevo.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.DiffUtil
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActivityLogManagerBinding
import io.github.lumiagg.gestureevo.databinding.LogManagerHeaderBinding
import io.github.lumiagg.gestureevo.databinding.LogManagerFooterBinding
import io.github.lumiagg.gestureevo.databinding.LogRecordItemBinding
import io.github.lumiagg.gestureevo.databinding.LogSettingsSheetBinding
import io.github.lumiagg.gestureevo.diagnostics.*
import kotlinx.coroutines.*
import java.io.File
import java.text.DateFormat
import java.util.Date

class LogManagerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLogManagerBinding
    private lateinit var header: LogManagerHeaderBinding
    private lateinit var footer: LogManagerFooterBinding
    private var settings: LogSettingsSheetBinding? = null
    private var levelIndex = 0
    private var moduleIndex = 0
    private var timeIndex = 0
    private var storageDetails = ""
    private var healthDetails = ""
    private val records = ArrayList<LogRecord>()
    private val adapter = RecordAdapter()
    private var queryJob: Job? = null
    private var generation = 0
    private var busy = false
    private var syncingToggles = false
    private var fingerprint: String? = null
    private var selectedSession: ProblemSession? = null
    private var pendingArchive: File? = null
    private val saveArchive = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val file = pendingArchive
        pendingArchive = null
        if (uri != null && file != null) lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    file.inputStream().use { input -> requireNotNull(contentResolver.openOutputStream(uri)).use { input.copyTo(it) } }
                }
                toast(R.string.log_saved)
            } catch (e: Exception) { showFailure(e) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.logList.layoutManager = LinearLayoutManager(this)
        header = LogManagerHeaderBinding.inflate(layoutInflater, binding.logList, false)
        footer = LogManagerFooterBinding.inflate(layoutInflater, binding.logList, false)
        levelIndex = savedInstanceState?.getInt("level") ?: 0
        moduleIndex = savedInstanceState?.getInt("module") ?: 0
        timeIndex = savedInstanceState?.getInt("time") ?: 0
        pendingArchive = savedInstanceState?.getString("archive")?.let(::File)
        selectedSession = ProblemSessions.history.value.firstOrNull { it.id == savedInstanceState?.getString("session") }
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.logList.adapter = ConcatAdapter(FixedViewAdapter(header.root), adapter, FixedViewAdapter(footer.root))
        binding.toolbar.menu.add(0, MENU_EXPORT, 0, R.string.log_export).apply {
            setIcon(R.drawable.ic_baseline_share_24)
            icon?.setTint(MaterialColors.getColor(binding.toolbar, com.google.android.material.R.attr.colorOnSurface))
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        binding.toolbar.menu.add(0, MENU_SETTINGS, 1, R.string.log_settings_title)
        binding.toolbar.menu.add(0, MENU_STATUS, 2, R.string.log_status_title)
        binding.toolbar.menu.add(0, MENU_CLEAR, 3, R.string.log_clear)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_EXPORT -> chooseExport()
                MENU_SETTINGS -> showSettings()
                MENU_STATUS -> showStatus()
                MENU_CLEAR -> confirmClear()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        header.loggingStatus.setOnClickListener { showSettings() }
        header.healthEntry.setOnClickListener { showStatus() }
        syncToggles()
        setupFilter(header.levelFilter, R.string.log_level,
            listOf(getString(R.string.log_all_levels)) + resources.getStringArray(R.array.log_level_names),
            { levelIndex }, { levelIndex = it })
        setupFilter(header.moduleFilter, R.string.log_module,
            listOf(getString(R.string.log_all_modules)) + resources.getStringArray(R.array.log_modules),
            { moduleIndex }, { moduleIndex = it })
        setupFilter(header.timeFilter, R.string.log_time, resources.getStringArray(R.array.log_time_ranges).toList(),
            { timeIndex }, { timeIndex = it })
        header.searchInput.setText(savedInstanceState?.getString("query").orEmpty())
        header.searchInput.doAfterTextChanged { reload() }
        footer.loadMoreButton.setOnClickListener { load(reset = false) }
        header.sessionButton.setOnClickListener { recordProblem() }
        header.sessionHistoryButton.setOnClickListener { showHistory() }
        header.captureButton.setOnClickListener {
            if (SystemLogCapture.status.value.running) SystemLogCapture.stop()
            else if (!AppLogger.enabled) toast(R.string.log_enable_first)
            else MaterialAlertDialogBuilder(this).setTitle(R.string.log_system).setMessage(R.string.log_system_description)
                .setPositiveButton(R.string.log_use_shizuku) { _, _ -> SystemLogCapture.start(false) }
                .setNeutralButton(R.string.log_use_root) { _, _ -> SystemLogCapture.start(true) }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    SystemLogCapture.status.collect { status ->
                        header.captureButton.setText(if (status.running) R.string.log_stop else R.string.log_system)
                        val message = when (status.result) {
                            "starting" -> R.string.log_capture_starting
                            "running" -> R.string.log_capture_running
                            "complete" -> R.string.log_capture_complete
                            "stopped" -> R.string.log_capture_stopped
                            "idle" -> R.string.log_capture_idle
                            else -> R.string.log_capture_failure_detail
                        }
                        header.captureStatus.isVisible = status.result != "idle"
                        header.captureStatus.text = if (message == R.string.log_capture_failure_detail) {
                            getString(message, captureReason(status.result), status.exitCode?.toString() ?: "—", status.count, status.discardedLines)
                        } else getString(message, status.count)
                    }
                }
                launch {
                    ProblemSessions.history.collect { history ->
                        selectedSession = history.firstOrNull { it.id == selectedSession?.id }
                        renderSession()
                        syncToggles()
                        reload()
                    }
                }
                reload()
                while (true) {
                    delay(5000)
                    syncToggles()
                    renderHealth()
                    if (!busy && records.size <= PAGE_SIZE && !binding.logList.canScrollVertically(-1)) {
                        val token = withContext(Dispatchers.IO) { sourceFingerprint() }
                        if (token != fingerprint) load(reset = true, debounce = false)
                    }
                }
            }
        }
    }

    private fun syncToggles() {
        syncingToggles = true
        settings?.let {
            it.loggingSwitch.isChecked = AppLogger.enabled
            it.detailedSwitch.isChecked = AppLogger.detailed
            it.detailedSwitch.isEnabled = AppLogger.enabled
        }
        header.loggingStatus.setText(if (AppLogger.enabled) R.string.log_status_on else R.string.log_status_off)
        header.sessionButton.setText(when {
            ProblemSessions.active != null -> R.string.log_problem_finish
            !AppLogger.enabled -> R.string.log_problem_enable_start
            else -> R.string.log_problem_start
        })
        syncingToggles = false
        renderHealth()
    }

    private fun showSettings() {
        val sheet = LogSettingsSheetBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        settings = sheet
        dialog.setContentView(sheet.root)
        syncToggles()
        sheet.loggingSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!syncingToggles) { AppLogger.setEnabled(enabled); syncToggles(); reload() }
        }
        sheet.detailedSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!syncingToggles) { AppLogger.setDetailed(enabled); syncToggles() }
        }
        sheet.doneButton.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { settings = null }
        dialog.show()
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
    }

    private fun showStatus() {
        renderHealth()
        MaterialAlertDialogBuilder(this).setTitle(R.string.log_status_title)
            .setMessage(listOf(storageDetails, healthDetails).filter { it.isNotEmpty() }.joinToString("\n\n"))
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.log_clear).setMessage(R.string.log_clear_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> clearLogs() }.show()
    }

    private fun captureReason(reason: String): String = getString(when (reason) {
        "permission_missing" -> R.string.log_reason_permission
        "service_timeout" -> R.string.log_reason_timeout
        "service_disconnected" -> R.string.log_reason_disconnected
        "protocol_unsupported" -> R.string.log_reason_protocol
        "root_unavailable" -> R.string.log_reason_root
        "command_failed" -> R.string.log_reason_command
        "output_limit", "event_limit" -> R.string.log_reason_limit
        "collector_busy" -> R.string.log_reason_busy
        else -> R.string.log_reason_closed
    })

    private fun renderHealth() {
        val health = AppLogger.health()
        val reason = getString(when (health.lastFailure) {
            "none" -> R.string.log_no_error
            "queue_full" -> R.string.log_reason_queue
            "encoding_failed" -> R.string.log_reason_encoding
            "write_rejected" -> R.string.log_reason_rejected
            else -> R.string.log_reason_io
        })
        healthDetails = getString(R.string.log_health, health.queued, health.dropped, health.failedWrites,
            reason + if (health.lastFailureAt > 0) " · ${time(health.lastFailureAt)}" else "")
        val warning = health.dropped > 0 || health.failedWrites > 0
        header.healthLabel.setText(when {
            warning -> R.string.log_health_warning
            !AppLogger.enabled -> R.string.log_health_paused
            else -> R.string.log_health_normal
        })
        header.healthLabel.setTextColor(MaterialColors.getColor(header.root, if (warning)
            androidx.appcompat.R.attr.colorError else com.google.android.material.R.attr.colorOnSurfaceVariant))
    }

    private fun renderSession() {
        val selected = selectedSession
        val active = ProblemSessions.active
        header.sessionStatus.text = when {
            selected != null -> getString(R.string.log_problem_selected, selected.id.take(8), time(selected.startedAt),
                selected.endedAt?.let(::time) ?: getString(R.string.log_problem_recording))
            active != null -> getString(R.string.log_problem_active, active.id.take(8))
            else -> getString(R.string.log_problem_description)
        }
    }

    private fun recordProblem() {
        if (busy) return
        if (ProblemSessions.active != null) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.log_problem_finish)
                .setItems(arrayOf(getString(R.string.log_share), getString(R.string.log_save))) { _, index ->
                    lifecycleScope.launch {
                        setBusy(true)
                        try {
                            val finished = ProblemSessions.finish()
                            selectedSession = finished
                            if (finished != null) exportArchive(index == 0, finished.filter(), finished)
                        } catch (e: Exception) { showFailure(e) }
                        finally { setBusy(false); renderSession(); reload() }
                    }
                }.setNegativeButton(android.R.string.cancel, null).show()
        } else lifecycleScope.launch {
            setBusy(true)
            try {
                if (!AppLogger.enabled) AppLogger.setEnabled(true)
                selectedSession = ProblemSessions.start()
                syncToggles()
                renderSession()
            } catch (e: Exception) { showFailure(e) }
            finally { setBusy(false); reload() }
        }
    }

    private fun showHistory() {
        val history = ProblemSessions.history.value
        val labels = listOf(getString(R.string.log_all_records)) + history.map {
            "${time(it.startedAt)} · ${it.id.take(8)}" + if (it.endedAt == null) " · ${getString(R.string.log_problem_recording)}" else ""
        }
        MaterialAlertDialogBuilder(this).setTitle(R.string.log_problem_history).setItems(labels.toTypedArray()) { _, index ->
            selectedSession = history.getOrNull(index - 1)
            renderSession()
            reload()
        }.show()
    }

    override fun onStop() { queryJob?.cancel(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        pendingArchive?.let { outState.putString("archive", it.absolutePath) }
        selectedSession?.let { outState.putString("session", it.id) }
        outState.putInt("level", levelIndex)
        outState.putInt("module", moduleIndex)
        outState.putInt("time", timeIndex)
        outState.putString("query", header.searchInput.text?.toString())
        super.onSaveInstanceState(outState)
    }

    private fun setupFilter(chip: Chip, title: Int, values: List<String>, selected: () -> Int, update: (Int) -> Unit) {
        fun render() {
            chip.text = values[selected()]
            chip.contentDescription = "${getString(title)}: ${chip.text}"
        }
        render()
        chip.setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle(title)
                .setSingleChoiceItems(values.toTypedArray(), selected()) { dialog, index ->
                    update(index)
                    render()
                    dialog.dismiss()
                    reload()
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    private fun currentFilter(): LogFilter {
        val since = when (timeIndex) {
            1 -> System.currentTimeMillis() - 60 * 60 * 1000L
            2 -> System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            else -> Long.MIN_VALUE
        }
        val session = selectedSession
        return LogFilter(since = maxOf(since, session?.startedAt ?: Long.MIN_VALUE), until = session?.endedAt ?: Long.MAX_VALUE,
            level = LogLevel.entries.getOrNull(levelIndex - 1),
            module = LogModule.entries.getOrNull(moduleIndex - 1),
            query = header.searchInput.text?.toString().orEmpty().trim(), session = session?.id)
    }

    private fun sourceFingerprint(): String = AppLogger.allStores().joinToString("|") { it.fingerprint() }
    private fun reload() { load(reset = true) }
    private fun load(reset: Boolean, debounce: Boolean = true) {
        if (busy) return
        queryJob?.cancel()
        val revision = ++generation
        val filter = currentFilter()
        val before = if (reset) null else records.lastOrNull()
        queryJob = lifecycleScope.launch {
            if (debounce) delay(250)
            try {
                val (pages, token) = runInterruptible(Dispatchers.IO) {
                    val token = sourceFingerprint()
                    AppLogger.allStores().map { it.page(filter, before, PAGE_SIZE) } to token
                }
                if (revision != generation) return@launch
                val page = pages.flatMap { it.records }.sortedWith(LogStore.NEWEST_FIRST).take(PAGE_SIZE)
                if (reset) records.clear()
                records.addAll(page)
                adapter.submitList(records.toList())
                fingerprint = token
                footer.emptyView.isVisible = records.isEmpty()
                footer.loadMoreButton.isVisible = page.size == PAGE_SIZE
                val error = pages.mapNotNull { it.latestError }.minWithOrNull(LogStore.NEWEST_FIRST)
                header.storageSummary.text = getString(R.string.log_storage_compact,
                    pages.sumOf { it.stats.first }, pages.sumOf { it.stats.second } / 1024)
                storageDetails = getString(R.string.log_summary, pages.sumOf { it.stats.first }, pages.sumOf { it.stats.second } / 1024,
                    error?.let { "${time(it.timestamp)} ${it.event}" } ?: getString(R.string.log_no_error))
                renderHealth()
            } catch (e: Exception) { showFailure(e) }
        }
    }

    private fun clearLogs() {
        if (busy) return
        lifecycleScope.launch {
            setBusy(true)
            queryJob?.cancel()
            try {
                ProblemSessions.finish("cleared")
                SystemLogCapture.stopAndJoin()
                AppLogger.ordered { AppLogger.allStores().forEach { it.clear() } }
                ProblemSessions.clear()
                withContext(Dispatchers.IO) {
                    File(cacheDir, "diagnostics").listFiles()?.forEach { if (it.isDirectory) it.deleteRecursively() else it.delete() }
                }
                selectedSession = null
                records.clear()
                adapter.submitList(emptyList())
                footer.emptyView.isVisible = true
                footer.loadMoreButton.isVisible = false
                toast(R.string.log_cleared)
            } catch (e: Exception) { showFailure(e) }
            finally { setBusy(false); reload() }
        }
    }

    private fun chooseExport() {
        val problem = selectedSession ?: ProblemSessions.active
        val labels = listOfNotNull(if (problem != null) getString(R.string.log_export_problem) else null,
            getString(R.string.log_export_filter), getString(R.string.log_export_all), getString(R.string.log_export_trace))
        MaterialAlertDialogBuilder(this).setTitle(R.string.log_export_scope).setItems(labels.toTypedArray()) { _, index ->
            val choice = index - if (problem != null) 1 else 0
            when (choice) {
                -1 -> chooseDestination(problem?.filter(), problem)
                0 -> chooseDestination(currentFilter(), selectedSession)
                1 -> chooseDestination(null)
                else -> {
                    val input = TextInputEditText(this).apply { hint = getString(R.string.log_trace_hint); isSingleLine = true }
                    MaterialAlertDialogBuilder(this).setTitle(R.string.log_export_trace).setView(input)
                        .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ ->
                            val trace = input.text?.toString().orEmpty().trim()
                            if (trace.matches(Regex("[A-Za-z0-9_-]{1,100}"))) chooseDestination(LogFilter(trace = trace))
                            else toast(R.string.log_invalid_trace)
                        }.show()
                }
            }
        }.show()
    }

    private fun chooseDestination(filter: LogFilter?, problem: ProblemSession? = null) {
        MaterialAlertDialogBuilder(this).setTitle(R.string.log_export)
            .setItems(arrayOf(getString(R.string.log_share), getString(R.string.log_save))) { _, index ->
                if (!busy) lifecycleScope.launch {
                    setBusy(true)
                    try { exportArchive(index == 0, filter, problem) }
                    catch (e: Exception) { showFailure(e) }
                    finally { setBusy(false); reload() }
                }
            }.show()
    }

    private suspend fun exportArchive(share: Boolean, filter: LogFilter?, problem: ProblemSession? = null) {
        val archive = LogArchive.export(cacheDir, filter, problem)
        if (share) {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", archive)
            val intent = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri("logs", uri)
            startActivity(Intent.createChooser(intent, getString(R.string.log_share)))
        } else { pendingArchive = archive; saveArchive.launch(archive.name) }
    }

    private fun showFailure(error: Exception) {
        if (error is CancellationException) throw error
        toast(R.string.log_operation_failed)
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.toolbar.menu.findItem(MENU_EXPORT).isEnabled = !value
        binding.toolbar.menu.findItem(MENU_CLEAR).isEnabled = !value
        footer.loadMoreButton.isEnabled = !value
        header.sessionButton.isEnabled = !value
    }

    private fun showRecord(record: LogRecord) {
        val text = format(record)
        val view = TextView(this).apply {
            this.text = text; setTextIsSelectable(true)
            setPadding(24, 16, 24, 16)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scroll = android.widget.ScrollView(this).apply { addView(view) }
        MaterialAlertDialogBuilder(this).setTitle(R.string.log_details).setView(scroll)
            .setPositiveButton(android.R.string.ok, null).setNeutralButton(R.string.log_copy) { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("log", text))
            }.apply {
                record.trace?.let { trace -> setNegativeButton(R.string.log_export_trace) { _, _ -> chooseDestination(LogFilter(trace = trace)) } }
            }.show()
    }

    private fun format(record: LogRecord): String = buildString {
        appendLine("${time(record.timestamp)} ${record.level} / ${record.module}")
        appendLine(record.event)
        record.trace?.let { appendLine("trace: $it") }
        record.session?.let { appendLine("problem: $it") }
        record.fields.forEach { (key, value) -> appendLine("$key: $value") }
        record.error?.let { appendLine(it) }
    }
    private fun time(timestamp: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(timestamp))
    private fun toast(message: Int) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }

    private inner class RecordAdapter : ListAdapter<LogRecord, RecordHolder>(object : DiffUtil.ItemCallback<LogRecord>() {
        override fun areItemsTheSame(oldItem: LogRecord, newItem: LogRecord): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: LogRecord, newItem: LogRecord): Boolean = oldItem == newItem
    }) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordHolder =
            RecordHolder(LogRecordItemBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: RecordHolder, position: Int) {
            val record = getItem(position)
            val row = holder.binding
            val module = resources.getStringArray(R.array.log_modules)[record.module.ordinal]
            row.metadata.text = "${time(record.timestamp)} · $module"
            row.levelBadge.text = resources.getStringArray(R.array.log_level_names)[record.level.ordinal]
            val color = when (record.level) {
                LogLevel.ERROR -> com.google.android.material.R.attr.colorErrorContainer
                LogLevel.WARN -> com.google.android.material.R.attr.colorTertiaryContainer
                else -> com.google.android.material.R.attr.colorSecondaryContainer
            }
            val textColor = when (record.level) {
                LogLevel.ERROR -> com.google.android.material.R.attr.colorOnErrorContainer
                LogLevel.WARN -> com.google.android.material.R.attr.colorOnTertiaryContainer
                else -> com.google.android.material.R.attr.colorOnSecondaryContainer
            }
            row.levelBadge.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 8 * resources.displayMetrics.density
                setColor(MaterialColors.getColor(row.root, color))
            }
            row.levelBadge.setTextColor(MaterialColors.getColor(row.root, textColor))
            row.eventTitle.text = eventTitle(record.event)
            val target = record.fields.entries.firstOrNull {
                it.key in setOf("package", "targetPackage", "target_package", "target", "component")
            }?.value
            row.contextSummary.text = listOfNotNull(target,
                record.trace?.let { getString(R.string.log_trace_short, it.take(8)) }
                    ?: record.session?.let { getString(R.string.log_session_short, it.take(8)) }).joinToString(" · ")
            row.contextSummary.isVisible = row.contextSummary.text.isNotEmpty()
            row.root.setOnClickListener { showRecord(record) }
        }
    }

    private fun eventTitle(event: String): String {
        val title = when (event) {
            "process_started" -> R.string.log_event_process_started
            "logging_enabled" -> R.string.log_event_logging_enabled
            "diagnostics_enabled" -> R.string.log_event_diagnostics_enabled
            "diagnostics_disabled" -> R.string.log_event_diagnostics_disabled
            "problem_recording_started" -> R.string.log_event_problem_started
            "problem_recording_finished" -> R.string.log_event_problem_finished
            "action_triggered" -> R.string.log_event_action_triggered
            "action_dispatch_returned" -> R.string.log_event_action_dispatched
            "action_failed" -> R.string.log_event_action_failed
            "shortcut_parsed" -> R.string.log_event_shortcut_parsed
            "launch_requested" -> R.string.log_event_launch_requested
            "launch_request_submitted" -> R.string.log_event_launch_submitted
            "launch_permission_denied" -> R.string.log_event_permission_denied
            "activity_not_found" -> R.string.log_event_activity_missing
            "launch_intent_missing" -> R.string.log_event_intent_missing
            "uncaught_exception" -> R.string.log_event_uncaught_exception
            else -> return event
        }
        return getString(title)
    }

    private class RecordHolder(val binding: LogRecordItemBinding) : RecyclerView.ViewHolder(binding.root)

    private class FixedViewAdapter(private val view: View) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(view) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }

    companion object {
        private const val PAGE_SIZE = 100
        private const val MENU_EXPORT = 1
        private const val MENU_SETTINGS = 2
        private const val MENU_STATUS = 3
        private const val MENU_CLEAR = 4
    }
}
