package io.github.lumiagg.gestureevo.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

fun CoroutineScope.postOnMain(action: suspend CoroutineScope.() -> Unit): Job =
    launch(Dispatchers.Main.immediate) {
        action()
    }

fun postOnMain(action: suspend CoroutineScope.() -> Unit): Job =
    CoroutineScope(Dispatchers.Main.immediate).launch {
        action()
    }
