package io.github.lumiagg.gestureevo.drop

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

class DropSearchState(private val savedState: SavedStateHandle) : ViewModel() {
    val searchStarted: Boolean
        get() = savedState[KEY_SEARCH_STARTED] ?: false

    fun tryStartSearch(): Boolean {
        if (searchStarted) return false
        savedState[KEY_SEARCH_STARTED] = true
        return true
    }

    companion object {
        private const val KEY_SEARCH_STARTED = "search_started"
    }
}
