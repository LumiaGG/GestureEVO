package io.github.lumiagg.gestureevo.settings.guide

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GuideWheelMode { HIDDEN, PREVIEW, EDIT }
enum class GuideWheelEditAction { EDIT, REMOVE, ADD }

data class GuidePreviewRequest(
    val owner: String,
    val config: QuickSetupConfig,
    val practice: QuickGesture? = null,
    val practiceId: String? = null,
    val wheelSelection: Boolean = false,
    val wheelMode: GuideWheelMode = GuideWheelMode.HIDDEN,
    val wheelInputEnabled: Boolean = true,
) {
    val showsWheel: Boolean get() = wheelInputEnabled && (wheelSelection || wheelMode != GuideWheelMode.HIDDEN)
}

data class GuideWheelEdit(
    val owner: String,
    val action: GuideWheelEditAction,
    val itemIndex: Int,
    val config: QuickSetupConfig,
)

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
    private val mutableWheelEdits = MutableSharedFlow<GuideWheelEdit>(extraBufferCapacity = 1)
    val wheelEdits: SharedFlow<GuideWheelEdit> = mutableWheelEdits.asSharedFlow()

    fun show(
        owner: String,
        config: QuickSetupConfig,
        practice: QuickGesture? = null,
        practiceId: String? = null,
        wheelSelection: Boolean = false,
        wheelMode: GuideWheelMode = GuideWheelMode.HIDDEN,
        wheelInputEnabled: Boolean = true,
    ) {
        mutableCurrent.value = GuidePreviewRequest(owner, config, practice, practiceId, wheelSelection, wheelMode, wheelInputEnabled)
    }

    fun clear(owner: String) {
        if (mutableCurrent.value?.owner == owner) mutableCurrent.value = null
    }

    internal fun reportGesture(request: GuidePreviewRequest, gesture: QuickGesture) {
        val practiceId = request.practiceId ?: return
        if (mutableCurrent.value != request || request.practice != gesture) return
        mutableGestures.tryEmit(GuidePreviewGesture(request.owner, practiceId, gesture))
    }

    internal fun reportWheelSelection(request: GuidePreviewRequest, index: Int) {
        val practiceId = request.practiceId ?: return
        val practice = request.practice ?: return
        if (mutableCurrent.value != request || !request.wheelSelection || index !in request.config.wheelItems.indices) return
        mutableGestures.tryEmit(GuidePreviewGesture(request.owner, practiceId, practice, index))
    }

    internal fun reportWheelEdit(request: GuidePreviewRequest, action: GuideWheelEditAction, index: Int) {
        if (mutableCurrent.value != request || request.wheelMode != GuideWheelMode.EDIT || !request.wheelInputEnabled) return
        if (action == GuideWheelEditAction.ADD) {
            if (index != request.config.wheelItems.size) return
        } else if (index !in request.config.wheelItems.indices) return
        mutableWheelEdits.tryEmit(GuideWheelEdit(request.owner, action, index, request.config))
    }
}
