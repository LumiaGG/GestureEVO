package io.github.lumiagg.gestureevo.utils

import androidx.annotation.MainThread
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.db.getAPPDao
import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import io.github.lumiagg.gestureevo.diagnostics.LogLevel
import io.github.lumiagg.gestureevo.diagnostics.LogModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Runtime package gate for accessibility-driven actions and content reads. */
internal object AccessibilityBlacklist {
    // Null means the policy is unavailable. An empty set means it loaded without entries.
    @Volatile
    private var blockedPackages: Set<String>? = null
    private var observeJob: Job? = null

    @MainThread
    fun start(scope: CoroutineScope) {
        stop()
        observeJob = scope.launch(Dispatchers.Main.immediate) {
            getAPPDao().observeSelectedPackages(APPModel.FLAG_ACCESSIBILITY_DISABLED)
                .distinctUntilChanged()
                .catch {
                    blockedPackages = null
                    AppLogger.event(LogModule.SERVICE, "accessibility_blacklist_read_failed", LogLevel.WARN)
                }
                .collect { blockedPackages = it.toSet() }
        }
    }

    @MainThread
    fun stop() {
        observeJob?.cancel()
        observeJob = null
        blockedPackages = null
    }

    fun isBlocked(packageName: String?): Boolean {
        val blocked = blockedPackages ?: return true
        return packageName in blocked
    }

    fun isBlocked(resolvePackage: () -> String?): Boolean {
        val blocked = blockedPackages ?: return true
        return blocked.isNotEmpty() && resolvePackage() in blocked
    }
}
