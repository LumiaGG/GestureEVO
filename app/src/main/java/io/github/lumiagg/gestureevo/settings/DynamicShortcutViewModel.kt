package io.github.lumiagg.gestureevo.settings

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.db.DatabaseManager
import io.github.lumiagg.gestureevo.db.DynamicShortcutModel
import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import io.github.lumiagg.gestureevo.diagnostics.LogLevel
import io.github.lumiagg.gestureevo.diagnostics.LogModule
import io.github.lumiagg.gestureevo.utils.shortcuts.DynamicShortcuts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DynamicShortcutViewModel(application: Application) : AndroidViewModel(application) {
    data class State(
        val busy: Boolean = false,
        val imported: List<DynamicShortcutModel> = emptyList(),
        val candidates: List<DynamicShortcutModel>? = null,
        val message: String = "",
    )

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    val selected = mutableSetOf<DynamicShortcutModel>()

    fun dismissCandidates() {
        mutableState.value = mutableState.value.copy(candidates = null)
        selected.clear()
    }

    fun refresh() = runOperation { }

    fun query() = runOperation {
        if (Build.VERSION.SDK_INT < 25) error("Dynamic shortcuts require Android 7.1")
        val candidates = DynamicShortcuts.queryAndRecoverImports(getApplication())
        selected.clear()
        mutableState.value = mutableState.value.copy(
            candidates = candidates.takeIf { it.isNotEmpty() },
            message = if (candidates.isEmpty()) getApplication<Application>().getString(R.string.dynamic_shortcuts_no_candidates) else "",
        )
    }

    fun importSelected() {
        val snapshot = selected.toList()
        dismissCandidates()
        if (snapshot.isEmpty()) return
        runOperation {
            if (Build.VERSION.SDK_INT < 25) error("Dynamic shortcuts require Android 7.1")
            DynamicShortcuts.import(getApplication(), snapshot)
            mutableState.value = mutableState.value.copy(message = getApplication<Application>().resources
                .getQuantityString(R.plurals.dynamic_shortcuts_imported, snapshot.size, snapshot.size))
        }
    }

    private fun dao() = DatabaseManager.db.dynamicShortcutDao()

    private fun runOperation(block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true)
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.event(LogModule.ACTION, "dynamic_shortcut_import_failed", LogLevel.ERROR, error = e)
                mutableState.value = mutableState.value.copy(message = getApplication<Application>()
                    .getString(R.string.dynamic_shortcuts_failed))
            } finally {
                try {
                    withContext(NonCancellable) {
                        mutableState.value = mutableState.value.copy(imported = dao().getImported())
                    }
                } catch (e: Exception) {
                    AppLogger.event(LogModule.ACTION, "dynamic_shortcut_records_read_failed", LogLevel.ERROR, error = e)
                    mutableState.value = mutableState.value.copy(message = getApplication<Application>()
                        .getString(R.string.dynamic_shortcuts_failed))
                } finally {
                    mutableState.value = mutableState.value.copy(busy = false)
                }
            }
        }
    }
}
