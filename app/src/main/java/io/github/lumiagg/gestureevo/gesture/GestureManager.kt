package io.github.lumiagg.gestureevo.gesture

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import io.github.lumiagg.gestureevo.db.APPModel
import io.github.lumiagg.gestureevo.db.getAPPDao
import io.github.lumiagg.gestureevo.gesture.EventBusManager.accessibilityActionEventBus
import io.github.lumiagg.gestureevo.gesture.EventBusManager.actionViewEventBus
import io.github.lumiagg.gestureevo.gesture.EventBusManager.freeTriggerEventBus
import io.github.lumiagg.gestureevo.gesture.EventBusManager.inspireEventBus
import io.github.lumiagg.gestureevo.gesture.EventBusManager.keyboardEventBus
import io.github.lumiagg.gestureevo.gesture.EventBusManager.notificationActionEventBus
import io.github.lumiagg.gestureevo.gesture.EventBusManager.pinPoolEventBus
import io.github.lumiagg.gestureevo.gesture.EventBusManager.pointerEventBus
import io.github.lumiagg.gestureevo.gesture.EventBusManager.screenSearchEventBus
import io.github.lumiagg.gestureevo.gesture.EventBusManager.searchEventBus
import io.github.lumiagg.gestureevo.gesture.config.ActionConfig
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerConfig
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.gesture.ui.ActionView
import io.github.lumiagg.gestureevo.gesture.ui.GestureFluidAppearance
import io.github.lumiagg.gestureevo.gesture.ui.GesturePhase
import io.github.lumiagg.gestureevo.gesture.ui.GestureType
import io.github.lumiagg.gestureevo.gesture.ui.GestureTypeEvent
import io.github.lumiagg.gestureevo.gesture.ui.TriggerCallBack
import io.github.lumiagg.gestureevo.gesture.ui.TriggerView
import io.github.lumiagg.gestureevo.inspire.InspireFloating
import io.github.lumiagg.gestureevo.inspire.PictureFloating
import io.github.lumiagg.gestureevo.notification.NotificationFloating
import io.github.lumiagg.gestureevo.notification.NotificationService
import io.github.lumiagg.gestureevo.pin.PinPoolFloating
import io.github.lumiagg.gestureevo.pointer.PointerFloating
import io.github.lumiagg.gestureevo.quick.QuickStartActivity
import io.github.lumiagg.gestureevo.screen_search.ScreenSearchFloating
import io.github.lumiagg.gestureevo.search.AutomatePerformer
import io.github.lumiagg.gestureevo.settings.ActionViewSettingsActivity.Companion.KEY_DISABLE_ALL_WHEEL_VIBRATE
import io.github.lumiagg.gestureevo.settings.ActionViewSettingsActivity.Companion.KEY_MAIN_SHOW_VIBRATE
import io.github.lumiagg.gestureevo.utils.ActionProxy
import io.github.lumiagg.gestureevo.utils.CurrentPackageProvider
import io.github.lumiagg.gestureevo.utils.RecentAppsRecorder
import io.github.lumiagg.gestureevo.utils.VibratorUtils
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class GestureManager(
    private val gestureService: GestureService, private val lifecycleScope: CoroutineScope
) {
    enum class InteractionMode {
        NORMAL,
        POINTER_DRAG_SELECT,
        INSPIRE_SELECT
    }

    private val context = gestureService.applicationContext
    private val actionViewFloatingLazy = lazy { GestureMainFloating(gestureService) }
    private val actionViewFloating: GestureMainFloating
        get() = actionViewFloatingLazy.value
    private val freeTriggerFloating =
        FreeTriggerFloating(gestureService, initTriggerTouchProxyCallBack())
    private val gestureFeedbackFloatingLazy = lazy { GestureFeedbackFloating(gestureService) }
    private val gestureFeedbackFloating: GestureFeedbackFloating
        get() = gestureFeedbackFloatingLazy.value
    private val inspireFloatingLazy = lazy { InspireFloating(gestureService) }
    private val inspireFloating: InspireFloating
        get() = inspireFloatingLazy.value
    private val pointerFloatingLazy = lazy { PointerFloating(gestureService) }
    private val pointerFloating: PointerFloating
        get() = pointerFloatingLazy.value
    private val notificationFloatingLazy = lazy { NotificationFloating(gestureService) }
    private val notificationFloating: NotificationFloating
        get() = notificationFloatingLazy.value
    private val screenSearchFloatingLazy = lazy { ScreenSearchFloating(gestureService) }
    private val screenSearchFloating: ScreenSearchFloating
        get() = screenSearchFloatingLazy.value
    private val automatePerformer = AutomatePerformer(gestureService)
    private var lastConfig: Configuration = Configuration(context.resources.configuration)

    @Volatile
    private var interactionMode: InteractionMode = InteractionMode.NORMAL

    init {
        actionViewFloating.preload()
        initSharedFlow()
        lifecycleScope.launch {
            GestureFluidAppearance.refresh(context)
        }
    }

    private fun initSharedFlow() {
        // Wheel / Gesture action view
        lifecycleScope.launch(Dispatchers.Main) {
            actionViewEventBus.collect {
                if (!it.show && !actionViewFloatingLazy.isInitialized()) return@collect
                with(actionViewFloating) {
                    it.workMode?.let {
                        actionView.workMode = it
                    }
                    if (it.show) {
                        val mainItemListTag =
                            it.mainItemListTag ?: actionView.state.itemsTag
                        if (it.triggerConfig != null && it.workMode == ActionView.WorkMode.ACTIVE) {
                            showActionView(mainItemListTag, it.triggerConfig, it.isMirror)
                        } else {
                            showConfigActionView(
                                tag = mainItemListTag,
                                triggerConfig = it.triggerConfig,
                                isMirror = it.isMirror,
                                subItemListTag = if (it.workMode == ActionView.WorkMode.CONFIG_SUB) {
                                    it.subItemListTag
                                } else {
                                    null
                                },
                            )
                        }
                    } else {
                        actionView.hide(true)
                    }
                }
            }
        }
        // Free trigger gesture
        lifecycleScope.launch(Dispatchers.Main) {
            freeTriggerEventBus.collect {
                Log.d(TAG, "initSharedFlow: $it")
                freeTriggerFloating.reloadTriggers()
                if (it.isEnable != null) {
                    if (it.isEnable) {
                        freeTriggerFloating.resetAllTriggerEnable()
                    } else {
                        freeTriggerFloating.disableAllTrigger()
                    }
                }
                if (it.configTrigger != null) {
                    freeTriggerFloating.exitToConfigMode()
                    freeTriggerFloating.enterToConfigMode(it.configTrigger, it.isConfig)
                } else if (it.isConfig) {
                    freeTriggerFloating.enterToConfigMode()
                } else {
                    freeTriggerFloating.exitToConfigMode()
                }
            }
        }
        // Keyboard
        lifecycleScope.launch(Dispatchers.Main) {
            keyboardEventBus.collect {
                if (it.show) {
                    QuickStartActivity.start(context, side = it.side)
                }
            }
        }
        // Accessibility event
        lifecycleScope.launch(Dispatchers.Main) {
            accessibilityActionEventBus.collect {
                gestureService.performGlobalAction(it.action)
            }
        }
        // Text grab / Inspire
        lifecycleScope.launch(Dispatchers.Main) {
            inspireEventBus.collect {
                Log.d(TAG, "inspireEventBus: ${it.rect}")
                when {
                    it.image != null -> PictureFloating.build(
                        gestureService,
                        it.image,
                        side = it.side
                    )

                    it.text != null -> PictureFloating.build(
                        gestureService,
                        it.text,
                        side = it.side
                    )

                    else -> {
                        if (it.rect != null) {
                            if (it.reSelect == true) {
                                inspireFloating.showByReselect(
                                    rect = it.rect,
                                    forceImageTextSelection = it.forceImageTextSelection,
                                    side = it.side
                                )
                            } else {
                                inspireFloating.show(
                                    rect = it.rect,
                                    forceImageTextSelection = it.forceImageTextSelection,
                                    side = it.side
                                )
                            }
                        } else if (it.fullscreen) {
                            inspireFloating.showFullscreen(
                                forceImageTextSelection = it.forceImageTextSelection,
                                side = it.side
                            )
                        } else {
                            inspireFloating.show(
                                forceImageTextSelection = it.forceImageTextSelection,
                                side = it.side
                            )
                        }
                    }
                }
            }
        }
        // Pointer
        lifecycleScope.launch(Dispatchers.Main) {
            pointerEventBus.collect {
                if (it.enable || pointerFloatingLazy.isInitialized()) {
                    pointerFloating.enable = it.enable
                    pointerFloating.needShowTouchView = it.needShowTouchView
                    if (it.enable && it.side != null) {
                        pointerFloating.triggerSide = it.side
                    }
                }
            }
        }
        // Notification
        lifecycleScope.launch(Dispatchers.Main) {
            notificationActionEventBus.collect {
                val notifications = NotificationService.getSortedNotifications()
                if (it.isAdd) {
                    if (notifications.isNotEmpty() || notificationFloatingLazy.isInitialized()) {
                        notificationFloating.show(notifications)
                    }
                } else if (notificationFloatingLazy.isInitialized()) {
                    notificationFloating.update(notifications)
                }
            }
        }
        // Search
        lifecycleScope.launch(Dispatchers.Default) {
            searchEventBus.collect {
                automatePerformer.startInjection(it.task)
            }
        }
        // Pin pool
        lifecycleScope.launch(Dispatchers.Main) {
            pinPoolEventBus.collect {
                if (it.show) {
                    PinPoolFloating.start(gestureService, side = it.side)
                } else {
                    PinPoolFloating.finishIfShowing()
                }
            }
        }
        // Screen search
        lifecycleScope.launch(Dispatchers.Main) {
            screenSearchEventBus.collect {
                if (it.show) {
                    screenSearchFloating.show()
                } else if (screenSearchFloatingLazy.isInitialized()) {
                    screenSearchFloating.hide()
                }
            }
        }
        // Foreground app
        lifecycleScope.launch(Dispatchers.IO) {
            CurrentPackageProvider.currentPackageFlow.collect { foregroundPackage ->
                val packageName = foregroundPackage ?: return@collect
                Log.d(TAG, "Foreground application changed")
                freeTriggerFloating.checkTriggerPackage(packageName)
                if (packageName == context.packageName) return@collect
                val model = getAPPDao().findByPkg(pkg = packageName) ?: return@collect
                RecentAppsRecorder.recordApp(context, model)
                val notificationEnabled = !model.getState(APPModel.FLAG_NOTIFICATION_ICON_DISABLE)
                if (notificationEnabled || notificationFloatingLazy.isInitialized()) {
                    notificationFloating.enable = notificationEnabled
                }
            }
        }
    }

    private fun initTriggerTouchProxyCallBack(): TriggerCallBack =
        object : TriggerCallBack {
            private var actionExecuted = false

            suspend fun gestureIsEnable(
                gestureTypeEvent: GestureTypeEvent,
                triggerConfig: FreeTriggerConfig,
                currentActionType: GestureActionType
            ): Boolean {
                if (currentActionType != GestureActionType.NONE) return true

                gestureTypeEvent.let {
                    val rdAction =
                        triggerConfig.getGestureAction(it.toActionKey(GesturePhase.RD))
                    if (rdAction?.firstStageAction != GestureActionType.NONE) {
                        return true
                    }

                    val backAction =
                        triggerConfig.getGestureAction(it.toActionKey(GesturePhase.BACK))
                    if (backAction?.firstStageAction != GestureActionType.NONE) {
                        return true
                    }
                }
                return false
            }

            fun executeAction(
                triggerView: TriggerView,
                gestureType: GestureTypeEvent,
                event: MotionEvent
            ) =
                lifecycleScope.launch {
                    if (!isTriggerActionAllowed()) {
                        return@launch
                    }
                    val triggerConfig = triggerView.triggerConfig
                    val action = triggerConfig.getGestureAction(gestureType.toActionKey())
                    val actionType = action?.firstStageAction ?: GestureActionType.SHOW_ACTION_VIEW

                    context.settingsStore.readKey(KEY_MAIN_SHOW_VIBRATE, true)
                    val disableAllWheelVibrate =
                        context.settingsStore.readKey(KEY_DISABLE_ALL_WHEEL_VIBRATE, false)
                    val isShowActionView = actionType == GestureActionType.SHOW_ACTION_VIEW
                    val allowTriggerVibration = if (isShowActionView) {
                        false
                    } else {
                        !disableAllWheelVibrate
                    }
                    if (gestureType.type != GestureType.TOUCH &&
                        gestureType.phase == GesturePhase.TD &&
                        gestureIsEnable(gestureType, triggerConfig, actionType) &&
                        actionType != GestureActionType.CLICK_THROUGH &&
                        allowTriggerVibration
                    ) {
                        VibratorUtils.vibrator(context, true)
                    }

                    when (actionType) {
                        GestureActionType.NONE -> {
                            actionExecuted = false
                        }

                        GestureActionType.SHOW_ACTION_VIEW -> {
                            val tag = action?.firstStageUri?.getQueryParameter("actionItemsTag")
                                ?: ActionConfig.DEFAULT_TAG
                            val startPoint =
                                if (triggerConfig.getState(FreeTriggerConfig.FLAG_WHEEL_FOLLOW_TRIGGER_POINT)) {
                                    triggerView.getTouchPointOnScreen(event)
                                } else {
                                    triggerView.getViewCenterPoint()
                                }
                            actionViewFloating.showActionView(
                                tag,
                                startPoint.x,
                                startPoint.y,
                                triggerConfig,
                                triggerView.isMirror,
                                gestureType
                            )
                        }

                        GestureActionType.CLICK_THROUGH -> {
                            val success =
                                freeTriggerFloating.setVisibility(triggerConfig.tag, false)
                            delay(25)
                            if (success) {
                                val longPressDuration =
                                    ViewConfiguration.getLongPressTimeout().toLong() + 100L
                                val clickDuration =
                                    if (gestureType.type == GestureType.LONG_PRESS) {
                                        longPressDuration
                                    } else {
                                        100L
                                    }
                                gestureService.performClickAsync(
                                    event.rawX,
                                    event.rawY,
                                    clickDuration
                                )
                                delay(25)
                            }
                            freeTriggerFloating.setVisibility(triggerConfig.tag, true)
                        }

                        else -> {
                            ActionProxy(context, action?.firstStageLink ?: "")
                                .execute(triggerView.screenAnchor(event))
                        }
                    }
                }

            private fun forwardPointerTouchIfNeeded(
                gestureTypeEvent: GestureTypeEvent,
                event: MotionEvent
            ) {
                if (gestureTypeEvent.type == GestureType.TOUCH) {
                    pointerFloating.pointerManger.onTouchEvent(event)
                }
            }

            private fun handleTouchGesture(
                triggerView: TriggerView,
                gestureTypeEvent: GestureTypeEvent,
                currentDirection: GestureType,
                event: MotionEvent
            ) {
                if (pointerFloatingLazy.isInitialized() && pointerFloating.enable) {
                    if (pointerFloating.triggerSide == null) {
                        val sw = triggerView.resources.displayMetrics.widthPixels
                        pointerFloating.triggerSide = if (event.rawX < sw / 2f) TriggerSide.LEFT else TriggerSide.RIGHT
                    }
                }
                pointerFloating.pointerManger.onTouchEvent(event)
                if (actionViewFloatingLazy.isInitialized() &&
                    actionViewFloating.actionView.workMode == ActionView.WorkMode.ACTIVE
                ) {
                    actionViewFloating.actionView.onTouchEvent(event)
                }
                if (triggerView.triggerConfig.getState(FreeTriggerConfig.FLAG_FEEDBACK)) {
                    gestureFeedbackFloating.onTouchEvent(event, triggerView.triggerConfig)
                    gestureFeedbackFloating.updatePredictedActions(
                        triggerView.triggerConfig,
                        gestureTypeEvent,
                        currentDirection
                    )
                }
            }

            private fun handleActionGesture(
                triggerView: TriggerView,
                gestureTypeEvent: GestureTypeEvent,
                currentDirection: GestureType,
                event: MotionEvent
            ) {
                if (triggerView.triggerConfig.getState(FreeTriggerConfig.FLAG_FEEDBACK)) {
                    gestureFeedbackFloating.updatePredictedActions(
                        triggerView.triggerConfig,
                        gestureTypeEvent,
                        currentDirection
                    )
                }
                if (!actionExecuted) {
                    actionExecuted = true
                    executeAction(triggerView, gestureTypeEvent, event)
                }
            }

            override fun onGesture(
                triggerView: TriggerView,
                gestureTypeEvent: GestureTypeEvent,
                currentDirection: GestureType,
                event: MotionEvent
            ) {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    actionExecuted = false
                }
                if (!isTriggerActionAllowed()) {
                    forwardPointerTouchIfNeeded(gestureTypeEvent, event)
                    return
                }
                when (gestureTypeEvent.type) {
                    GestureType.TOUCH -> handleTouchGesture(
                        triggerView,
                        gestureTypeEvent,
                        currentDirection,
                        event
                    )

                    else -> handleActionGesture(
                        triggerView,
                        gestureTypeEvent,
                        currentDirection,
                        event
                    )
                }
            }
        }

    fun onConfigurationChanged(newConfig: Configuration) {
        val diff = lastConfig.diff(newConfig)
        val nightChanged =
            (lastConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) !=
                (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        val orientationChanged =
            (diff and (ActivityInfo.CONFIG_ORIENTATION or ActivityInfo.CONFIG_SCREEN_SIZE)) != 0 ||
                    lastConfig.orientation != newConfig.orientation
        lastConfig = Configuration(newConfig)

        if (orientationChanged) {
            Log.d(TAG, "Screen orientation changed")
            PictureFloating.onOrientationChanged()
            if (notificationFloatingLazy.isInitialized()) {
                notificationFloating.onConfigurationChanged(newConfig)
            }
            lifecycleScope.launch {
                freeTriggerFloating.reloadTriggers()
                delay(100)
                freeTriggerFloating.checkTriggerOrientation(newConfig.orientation)
            }
        }
        if (nightChanged) {
            Log.d(TAG, "UI mode changed")
            refreshOverlayThemes()
        }
    }

    private fun refreshOverlayThemes() {
        if (actionViewFloatingLazy.isInitialized()) actionViewFloating.refreshTheme()
        if (notificationFloatingLazy.isInitialized()) notificationFloating.refreshTheme()
        if (inspireFloatingLazy.isInitialized()) inspireFloating.refreshTheme()
        if (screenSearchFloatingLazy.isInitialized()) screenSearchFloating.refreshTheme()
        if (pointerFloatingLazy.isInitialized()) pointerFloating.refreshTheme()
        if (gestureFeedbackFloatingLazy.isInitialized()) gestureFeedbackFloating.refreshTheme()
        PictureFloating.refreshAllThemes()
    }

    fun onWindowStateChanged(event: AccessibilityEvent) {
        if (!automatePerformer.isTaskActive()) return
        val trustedWindowContext = CurrentPackageProvider.getTrustedWindowContext(event) ?: return
        val (packageName, className) = trustedWindowContext

        // Prevent missed detections
        automatePerformer.checkAndInject(
            currentPackage = packageName,
            currentActivity = className
        )
    }

    fun inactiveAllFloating() = lifecycleScope.launch(Dispatchers.Main) {
        Log.d(TAG, "inactiveAllFloating: ")
        freeTriggerFloating.disableAllTrigger()
        if (actionViewFloatingLazy.isInitialized()) actionViewFloating.hide()
        if (inspireFloatingLazy.isInitialized()) inspireFloating.hide()
        if (pointerFloatingLazy.isInitialized()) pointerFloating.hide()
        if (screenSearchFloatingLazy.isInitialized()) screenSearchFloating.hide()
        if (notificationFloatingLazy.isInitialized()) notificationFloating.enable = false
    }

    fun activeSomeFloating() = lifecycleScope.launch(Dispatchers.Main) {
        Log.d(TAG, "activeSomeFloating: ")
        if (notificationFloatingLazy.isInitialized()) notificationFloating.enable = true
        freeTriggerFloating.resetAllTriggerEnable()
    }

    fun inactiveTriggerFloating() = lifecycleScope.launch(Dispatchers.Main) {
        freeTriggerFloating.disableAllTrigger()
    }

    fun activeTriggerFloating() = lifecycleScope.launch(Dispatchers.Main) {
        freeTriggerFloating.resetAllTriggerEnable()
    }

    fun setActionViewTouchEnabled(enabled: Boolean) {
        if (actionViewFloatingLazy.isInitialized()) actionViewFloating.setTouchEnabled(enabled)
    }

    fun setInteractionMode(mode: InteractionMode) {
        interactionMode = mode
        if (mode != InteractionMode.NORMAL && gestureFeedbackFloatingLazy.isInitialized()) {
            gestureFeedbackFloating.forceHide()
        }
    }

    fun destroy() {
        interactionMode = InteractionMode.NORMAL
        freeTriggerFloating.destroy()
        if (actionViewFloatingLazy.isInitialized()) actionViewFloating.destroy()
        if (gestureFeedbackFloatingLazy.isInitialized()) gestureFeedbackFloating.destroy()
        if (inspireFloatingLazy.isInitialized()) inspireFloating.destroy()
        if (pointerFloatingLazy.isInitialized()) pointerFloating.destroy()
        if (notificationFloatingLazy.isInitialized()) notificationFloating.destroy()
        if (screenSearchFloatingLazy.isInitialized()) screenSearchFloating.destroy()
        PictureFloating.destroyAll()
        PinPoolFloating.finishIfShowing()
    }

    private fun isTriggerActionAllowed(): Boolean {
        return interactionMode == InteractionMode.NORMAL
    }

    companion object {
        const val TAG = "GestureManager"
    }
}

private fun TriggerView.screenAnchor(event: MotionEvent? = null): TriggerSide.Point {
    val displayMetrics = resources.displayMetrics
    val sw = displayMetrics.widthPixels
    val sh = displayMetrics.heightPixels
    return if (event != null) {
        TriggerSide.Point(event.rawX, sw, event.rawY, sh)
    } else {
        val center = getViewCenterPoint()
        TriggerSide.Point(center.x, sw, center.y, sh)
    }
}
