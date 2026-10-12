package io.github.lumiagg.gestureevo.gesture


import android.graphics.Rect
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerConfig
import io.github.lumiagg.gestureevo.gesture.ui.ActionView
import io.github.lumiagg.gestureevo.inspire.ManagedBitmap
import io.github.lumiagg.gestureevo.search.InjectionTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FreeTriggerEvent(
    val isConfig: Boolean,
    val configTrigger: FreeTriggerConfig? = null,
    val orientation: Int? = null,
    val isEnable: Boolean? = null
)

data class ActionViewEvent(
    val show: Boolean,
    val workMode: ActionView.WorkMode? = null,
    val mainItemListTag: String? = null,
    val subItemListTag: String? = null,
    val triggerConfig: FreeTriggerConfig? = null,
    val isMirror: Boolean = false
)

data class KeyboardEvent(
    val show: Boolean,
    val reloadKeyboard: Boolean? = null,
    val side: String? = null
)

data class InspireEvent(
    val rect: Rect? = null,
    val reSelect: Boolean? = null,
    val image: ManagedBitmap? = null,
    val text: String? = null,
    val skipAutoPin: Boolean = false,
    val fullscreen: Boolean = false,
    val forceImageTextSelection: Boolean = false,
    val side: String? = null
)

data class PointerEvent(val enable: Boolean, val needShowTouchView: Boolean = false, val side: String? = null)

data class AccessibilityActionEvent(
    val action: Int
)

data class NotificationActionEvent(val isAdd: Boolean)

data class SearchEvent(val task: InjectionTask)

data class PinPoolEvent(val show: Boolean, val side: String? = null)

data class ScreenSearchEvent(val show: Boolean = true, val side: String? = null)


object EventBusManager {
    val actionViewEventBus = MutableSharedFlow<ActionViewEvent>()
    val freeTriggerEventBus = MutableSharedFlow<FreeTriggerEvent>()
    val keyboardEventBus = MutableSharedFlow<KeyboardEvent>()
    val accessibilityActionEventBus = MutableSharedFlow<AccessibilityActionEvent>()
    val inspireEventBus = MutableSharedFlow<InspireEvent>()
    val pointerEventBus = MutableSharedFlow<PointerEvent>()
    val notificationActionEventBus = MutableSharedFlow<NotificationActionEvent>()
    val searchEventBus = MutableSharedFlow<SearchEvent>()
    val pinPoolEventBus = MutableSharedFlow<PinPoolEvent>()
    val screenSearchEventBus = MutableSharedFlow<ScreenSearchEvent>()

    fun produceActionViewEvent(lifecycleScope: CoroutineScope, event: ActionViewEvent) =
        lifecycleScope.launch {
            actionViewEventBus.emit(event)
        }

    suspend fun produceActionViewEvent(event: ActionViewEvent) = withContext(Dispatchers.IO) {
        actionViewEventBus.emit(event)
    }


    suspend fun produceKeyboardEvent(event: KeyboardEvent) = withContext(Dispatchers.IO) {
        keyboardEventBus.emit(event)
    }

    suspend fun produceAccessibilityActionEvent(
        action: AccessibilityActionEvent
    ) {
        accessibilityActionEventBus.emit(action)
    }

    suspend fun produceInspireEvent(event: InspireEvent) = withContext(Dispatchers.IO) {
        inspireEventBus.emit(event)
    }

    suspend fun producePointerEvent(event: PointerEvent) = withContext(Dispatchers.IO) {
        pointerEventBus.emit(event)
    }

    suspend fun produceNotificationActionEvent(event: NotificationActionEvent) =
        withContext(Dispatchers.IO) {
            notificationActionEventBus.emit(event)
        }

    suspend fun produceFreeTriggerEvent(event: FreeTriggerEvent) = withContext(Dispatchers.IO) {
        freeTriggerEventBus.emit(event)
    }

    suspend fun produceSearchEvent(event: SearchEvent) = withContext(Dispatchers.IO) {
        searchEventBus.emit(event)
    }

    suspend fun producePinPoolEvent(event: PinPoolEvent) = withContext(Dispatchers.IO) {
        pinPoolEventBus.emit(event)
    }

    suspend fun produceScreenSearchEvent(event: ScreenSearchEvent) = withContext(Dispatchers.IO) {
        screenSearchEventBus.emit(event)
    }

}
