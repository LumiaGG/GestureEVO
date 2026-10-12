package io.github.lumiagg.gestureevo.settings

import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.databinding.ActivityDataStatsBinding
import io.github.lumiagg.gestureevo.databinding.ItemDataStatsRankBinding
import io.github.lumiagg.gestureevo.databinding.ItemDataStatsRecentBinding
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.db.getAPPDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DataStatsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDataStatsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityDataStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.refreshButton.setOnClickListener {
            loadStats()
        }

        loadStats()
    }

    private fun loadStats() {
        binding.refreshButton.isEnabled = false
        binding.loadingProgress.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            val allApps = getAPPDao().getAll()

            val totalOpenCount = allApps.sumOf { it.openCount }
            val openedApps = allApps.filter { it.openCount > 0 }
            val openedAppCount = openedApps.size
            val averageOpenCount = if (openedAppCount == 0) 0.0 else totalOpenCount.toDouble() / openedAppCount

            val topOpenedApps = openedApps
                .sortedByDescending { it.openCount }
                .take(5)

            val recentUsedApps = allApps
                .filter { it.lastUsageTime > 0L }
                .sortedByDescending { it.lastUsageTime }
                .take(5)

            val latestUsageTime = recentUsedApps.firstOrNull()?.lastUsageTime ?: 0L

            withContext(Dispatchers.Main) {
                renderSummary(
                    totalOpenCount = totalOpenCount,
                    openedAppCount = openedAppCount,
                    averageOpenCount = averageOpenCount,
                    latestUsageTime = latestUsageTime
                )
                renderTopOpened(topOpenedApps)
                renderRecentUsed(recentUsedApps)
                binding.loadingProgress.visibility = View.GONE
                binding.refreshButton.isEnabled = true
            }
        }
    }

    private fun renderSummary(
        totalOpenCount: Long,
        openedAppCount: Int,
        averageOpenCount: Double,
        latestUsageTime: Long
    ) {
        val numberFormat = NumberFormat.getIntegerInstance()
        binding.statTotalOpenValue.text = numberFormat.format(totalOpenCount)
        binding.statTrackedAppsValue.text = numberFormat.format(openedAppCount)
        binding.statAverageOpenValue.text = getString(
            R.string.data_stats_avg_open_format,
            String.format(Locale.getDefault(), "%.1f", averageOpenCount)
        )

        binding.statLastActiveValue.text = if (latestUsageTime > 0L) {
            val relative = DateUtils.getRelativeTimeSpanString(
                latestUsageTime,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )
            val absolute = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date(latestUsageTime))
            getString(R.string.data_stats_last_active_format, relative, absolute)
        } else {
            getString(R.string.data_stats_empty_value)
        }
    }

    private fun renderTopOpened(topOpenedApps: List<APPModel>) {
        binding.topUsageContainer.removeAllViews()
        if (topOpenedApps.isEmpty()) {
            binding.topUsageHighlightName.text = getString(R.string.data_stats_empty_value)
            binding.topUsageHighlightCount.text = getString(R.string.data_stats_no_usage_data)
            return
        }

        val numberFormat = NumberFormat.getIntegerInstance()
        val highest = topOpenedApps.first().openCount.coerceAtLeast(1)

        binding.topUsageHighlightName.text = topOpenedApps.first().displayName
        binding.topUsageHighlightCount.text = getString(
            R.string.data_stats_open_count_format,
            numberFormat.format(topOpenedApps.first().openCount)
        )

        topOpenedApps.forEachIndexed { index, app ->
            val itemBinding = ItemDataStatsRankBinding.inflate(layoutInflater, binding.topUsageContainer, true)
            val progress = ((app.openCount.toDouble() / highest.toDouble()) * 100.0).toInt().coerceIn(0, 100)

            itemBinding.rankIndexText.text = getString(R.string.data_stats_rank_format, index + 1)
            itemBinding.rankAppNameText.text = app.displayName
            itemBinding.rankCountText.text = getString(
                R.string.data_stats_open_count_short_format,
                numberFormat.format(app.openCount)
            )
            itemBinding.rankProgress.setProgressCompat(progress, false)
        }
    }

    private fun renderRecentUsed(recentUsedApps: List<APPModel>) {
        binding.recentUsageContainer.removeAllViews()
        if (recentUsedApps.isEmpty()) {
            binding.recentUsageHighlightName.text = getString(R.string.data_stats_empty_value)
            binding.recentUsageHighlightTime.text = getString(R.string.data_stats_no_recent_data)
            return
        }

        binding.recentUsageHighlightName.text = recentUsedApps.first().displayName
        binding.recentUsageHighlightTime.text = formatTime(recentUsedApps.first().lastUsageTime)

        recentUsedApps.forEach { app ->
            val itemBinding = ItemDataStatsRecentBinding.inflate(layoutInflater, binding.recentUsageContainer, true)
            itemBinding.recentItemNameText.text = app.displayName
            itemBinding.recentItemTimeText.text = formatTime(app.lastUsageTime)
        }
    }

    private fun formatTime(timestamp: Long): String {
        if (timestamp <= 0L) {
            return getString(R.string.data_stats_empty_value)
        }
        val relative = DateUtils.getRelativeTimeSpanString(
            timestamp,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS
        )
        val absolute = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
        return getString(R.string.data_stats_last_active_format, relative, absolute)
    }

    private val APPModel.displayName: String
        get() = name.ifBlank { pkgName }
}
