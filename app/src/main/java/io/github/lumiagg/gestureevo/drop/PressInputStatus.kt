package io.github.lumiagg.gestureevo.drop

import io.github.lumiagg.gestureevo.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object PressInputStatus {
    private val current = MutableStateFlow(R.string.global_press_status_stopped)
    val message = current.asStateFlow()
    fun update(message: Int) { current.value = message }
}
