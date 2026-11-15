package io.github.lumiagg.gestureevo.drop

import android.content.ClipData
import android.util.Log
import io.github.lumiagg.gestureevo.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Owns conversion dwell, cancellation and alternate content for one drag session. */
internal class DragConversionController(
    private val scope: CoroutineScope,
    private val convert: suspend (ClipData) -> ClipData?,
    private val onPreview: suspend (ClipData, Int?) -> Unit,
    private val prepareContent: suspend (ClipData) -> Unit,
    private val onConverted: (ClipData) -> Unit,
    private val onError: (Int) -> Unit,
    private val onFinished: () -> Unit,
) {
    private val state = DragConversionState<ClipData>()
    private var hoverJob: Job? = null
    private var conversionJob: Job? = null
    private var generation = 0
    val busy: Boolean get() = state.busy

    fun retain(content: ClipData) {
        clear()
        state.retain(content)
    }

    fun clear() {
        generation++
        hoverJob?.cancel()
        hoverJob = null
        conversionJob?.cancel()
        conversionJob = null
        state.clear()
    }

    fun hover(active: Boolean) {
        if (!state.hover(active)) return
        hoverJob?.cancel()
        hoverJob = null
        if (!active) return
        hoverJob = scope.launch(Dispatchers.Main) {
            delay(500)
            convertContent()
        }
    }

    private fun convertContent() {
        val original = state.content ?: return
        val request = state.begin() ?: return
        val session = generation
        conversionJob = scope.launch(Dispatchers.Main) {
            try {
                val hint = if (state.alternate == null) {
                    if (original.description.hasMimeType("image/*")) R.string.external_drop_recognizing_hint
                    else R.string.external_drop_converting_hint
                } else null
                onPreview(original, hint)
                if (session != generation || !currentCoroutineContext().isActive) return@launch
                val converted = state.alternate ?: convert(original)
                if (session != generation || !currentCoroutineContext().isActive) return@launch
                if (converted == null) {
                    state.fail(request)
                    onError(R.string.external_drop_conversion_empty)
                    return@launch
                }
                prepareContent(converted)
                if (session != generation || !currentCoroutineContext().isActive) return@launch
                if (state.complete(request, converted)) onConverted(converted)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (session == generation) {
                    state.fail(request)
                    Log.w("ExternalDrop", "Cannot convert dragged content", error)
                    onError(if (error is IllegalArgumentException) R.string.external_drop_conversion_too_long
                        else R.string.external_drop_failed)
                }
            } finally {
                if (session == generation && currentCoroutineContext().isActive) {
                    state.content?.let { onPreview(it, null) }
                    onFinished()
                }
            }
        }
    }
}
