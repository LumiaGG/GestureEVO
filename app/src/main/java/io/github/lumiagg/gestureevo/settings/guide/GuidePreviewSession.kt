package io.github.lumiagg.gestureevo.settings.guide

import io.github.lumiagg.gestureevo.gesture.config.ActionConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GuideWheelMode { HIDDEN, PREVIEW }

data class GuidePreviewRequest(
    val owner: String,
    val config: QuickSetupConfig,
    val practice: QuickGesture? = null,
    val practiceId: String? = null,
    val wheelSelection: Boolean = false,
    val wheelMode: GuideWheelMode = GuideWheelMode.HIDDEN,
    val actionConfig: ActionConfig? = null,
) {
    val showsWheel: Boolean get() = wheelSelection || wheelMode == GuideWheelMode.PREVIEW
}

data class GuidePreviewGesture(
    val owner: String,
    val practiceId: String,
    val gesture: QuickGesture,
    val wheelItemIndex: Int? = null,
)

/** Holds drafts only; the accessibility service may connect after the guide opens. */
object GuidePreviewSession {
    private val mutableCurrent = MutableStateFlow<GuidePreviewRequest?>(null)
    val current: StateFlow<GuidePreviewRequest?> = mutableCurrent.asStateFlow()
    private val mutableGestures = MutableSharedFlow<GuidePreviewGesture>(extraBufferCapacity = 1)
    val gestures: SharedFlow<GuidePreviewGesture> = mutableGestures.asSharedFlow()

    fun show(owner: String, config: QuickSetupConfig, practice: QuickGesture? = null, practiceId: String? = null,
        wheelSelection: Boolean = false, wheelMode: GuideWheelMode = GuideWheelMode.HIDDEN, actionConfig: ActionConfig? = null) {
        mutableCurrent.value = GuidePreviewRequest(owner, config, practice, practiceId, wheelSelection, wheelMode, actionConfig)
    }

    fun clear(owner: String) {
        if (mutableCurrent.value?.owner == owner) mutableCurrent.value = null
    }

    internal fun refresh(owner: String, config: QuickSetupConfig, wheelMode: GuideWheelMode, actionConfig: ActionConfig? = null) {
        val practice = mutableCurrent.value?.takeIf { it.owner == owner && it.practice != null }
        // A practice request owns its target wheel, appearance and selection state.
        // Status refreshes must not replace any of them with the setup draft.
        if (practice != null) return
        show(owner, config, wheelMode = wheelMode, actionConfig = actionConfig)
    }

    internal fun reportGesture(request: GuidePreviewRequest, gesture: QuickGesture): Boolean {
        val practiceId = request.practiceId ?: return false
        if (mutableCurrent.value != request || request.practice != gesture) return false
        return mutableGestures.tryEmit(GuidePreviewGesture(request.owner, practiceId, gesture))
    }

    internal fun reportWheelSelection(request: GuidePreviewRequest, index: Int): Boolean {
        val practiceId = request.practiceId ?: return false
        val practice = request.practice ?: return false
        if (mutableCurrent.value != request || !request.wheelSelection || index !in request.config.wheelItems.indices) return false
        return mutableGestures.tryEmit(GuidePreviewGesture(request.owner, practiceId, practice, index))
    }
}
