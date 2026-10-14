package io.github.lumiagg.gestureevo.gesture

import io.github.lumiagg.gestureevo.databinding.GestureActionViewBinding

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import android.view.animation.LinearInterpolator
import androidx.core.view.isVisible
import androidx.core.view.doOnLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.floating.BaseFloatingWindow
import io.github.lumiagg.gestureevo.gesture.GestureManager.Companion.TAG
import io.github.lumiagg.gestureevo.gesture.config.ActionConfig
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerConfig
import io.github.lumiagg.gestureevo.gesture.config.GestureActionDataManager
import io.github.lumiagg.gestureevo.gesture.config.GestureActionItem
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.gesture.config.IconType
import io.github.lumiagg.gestureevo.gesture.ui.ActionView
import io.github.lumiagg.gestureevo.gesture.ui.ActionView.ModelItem
import io.github.lumiagg.gestureevo.gesture.ui.GestureTypeEvent
import io.github.lumiagg.gestureevo.gesture.ui.MainActionView
import io.github.lumiagg.gestureevo.notification.NotificationService
import io.github.lumiagg.gestureevo.search.QuickSearchActivity
import io.github.lumiagg.gestureevo.settings.ActionViewSettingsActivity.Companion.KEY_MAIN_SHOW_ANIM
import io.github.lumiagg.gestureevo.settings.ActionViewSettingsActivity.Companion.KEY_MAIN_SHOW_VIBRATE
import io.github.lumiagg.gestureevo.settings.ActionViewSettingsActivity.Companion.KEY_DISABLE_ALL_WHEEL_VIBRATE
import io.github.lumiagg.gestureevo.settings.ActionViewSettingsActivity.Companion.KEY_SELECTED_HINT_ICON
import io.github.lumiagg.gestureevo.settings.AppearanceSettingsActivity.Companion.KEY_WHEEL_BACKGROUND_BLUR
import io.github.lumiagg.gestureevo.settings.GestureAddActionActivity
import io.github.lumiagg.gestureevo.settings.guide.QuickSetupRepository
import io.github.lumiagg.gestureevo.utils.ActionProxy
import io.github.lumiagg.gestureevo.utils.GestureActionLink
import io.github.lumiagg.gestureevo.utils.OverlaySurfaceBlur
import io.github.lumiagg.gestureevo.utils.RecentAppsRecorder
import io.github.lumiagg.gestureevo.utils.Rom
import io.github.lumiagg.gestureevo.utils.ScreenUtil
import io.github.lumiagg.gestureevo.utils.ToastHelper
import io.github.lumiagg.gestureevo.utils.VibratorUtils
import io.github.lumiagg.gestureevo.utils.postOnMain
import io.github.lumiagg.gestureevo.utils.readKey
import io.github.lumiagg.gestureevo.utils.settingsStore
import io.github.lumiagg.gestureevo.utils.toGestureActionType
import io.github.lumiagg.gestureevo.utils.dp2px
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GestureMainFloating(private val context: GestureService) :
    BaseFloatingWindow(context, context, TAG) {

    val actionView: MainActionView =
        GestureActionViewBinding.inflate(LayoutInflater.from(context)).root
    val layoutParams: WindowManager.LayoutParams = WindowManager.LayoutParams()
    private var touchEnabled = true
    private var backgroundBlurActive = false
    private var backgroundBlurAnimator: ValueAnimator? = null
    private var backgroundBlurRadius = 0
    private var guidePreviewRevision = 0
    private var guidePreview = false
    private val originOsSurfaceBlur = Rom.isOriginOS()

    init {
        initFloatingWindow()
        initActionView()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initFloatingWindow() {
        layoutParams.configureAccessibilityOverlay(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            title = "GestureEVO:GestureMainFloating",
        )
    }

    private fun ensureAttached() {
        attachViewSafely(
            actionView,
            layoutParams,
            onFailure = {
                ToastHelper.showInUIThread(
                    context,
                    context.getString(R.string.gesture_floating_init_failed)
                )
            }
        )
    }

    private fun detachIfNeeded() {
        val result = detachViewSafely(actionView)
        Log.d(
            TAG,
            "GestureMainFloating detachIfNeeded result=$result attached=${actionView.isAttachedToWindow} " +
                    "visible=${actionView.isVisible} requested=${isAttachRequested(actionView)}"
        )
    }

    private fun playBackgroundBlurAnim(isShow: Boolean, force: Boolean = false) = context.postOnMain {
        val showBlur = context.settingsStore.readKey(KEY_WHEEL_BACKGROUND_BLUR, false)
        if (!showBlur && !force) return@postOnMain
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@postOnMain
        backgroundBlurActive = isShow
        animateWheelBlur(show = isShow)
    }

    private fun clearBackgroundBlur() = context.postOnMain {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@postOnMain
        backgroundBlurAnimator?.cancel()
        backgroundBlurAnimator = null
        backgroundBlurActive = false
        applyWheelBlurRadius(0)
    }

    private fun animateWheelBlur(show: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val targetBlur = if (show) 80 else 0
        if (!show && backgroundBlurRadius == 0) return
        val animator = ValueAnimator.ofInt(backgroundBlurRadius, targetBlur).apply {
            duration = 250
            interpolator = LinearInterpolator()
            addUpdateListener { valueAnimator ->
                applyWheelBlurRadius(valueAnimator.animatedValue as Int)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (backgroundBlurAnimator !== this@apply) return
                    backgroundBlurAnimator = null
                }
            })
        }
        val previous = backgroundBlurAnimator
        backgroundBlurAnimator = animator
        previous?.cancel()
        animator.start()
    }

    private fun applyWheelBlurRadius(radius: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        backgroundBlurRadius = radius
        if (originOsSurfaceBlur) {
            OverlaySurfaceBlur.apply(actionView, radius)
        } else {
            applyBlurBehind(radius)
        }
    }

    private fun applyBlurBehind(radius: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val blurFlag = WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        layoutParams.blurBehindRadius = radius
        layoutParams.flags = if (radius > 0) {
            layoutParams.flags or blurFlag
        } else {
            layoutParams.flags and blurFlag.inv()
        }
        if (!actionView.isAttachedToWindow) return
        runCatching { windowManager.updateViewLayout(actionView, layoutParams) }
    }

    private fun keepBackgroundBlurForSearchHandoff() {
        actionView.keepBackgroundBlurOnNextHide = true
        QuickSearchActivity.markKeepBackgroundBlurOnNextEnter {
            clearBackgroundBlur()
        }
        context.postOnMain {
            delay(500)
            if (!actionView.isVisible && backgroundBlurActive) {
                clearBackgroundBlur()
            }
        }
    }

    private fun initActionView() {
        actionView.backgroundBlurCallback = { isShow -> playBackgroundBlurAnim(isShow) }
        actionView.onHideRequestedCallback = {
            Log.d(
                TAG,
                "GestureMainFloating onHideRequested attached=${actionView.isAttachedToWindow} " +
                        "visible=${actionView.isVisible} requested=${isAttachRequested(actionView)}"
            )
            clearAttachRequest(actionView)
        }
        actionView.onHiddenCallback = {
            Log.d(
                TAG,
                "GestureMainFloating onHidden attached=${actionView.isAttachedToWindow} " +
                        "visible=${actionView.isVisible} requested=${isAttachRequested(actionView)}"
            )
            if (!isAttachRequested(actionView)) {
                detachIfNeeded()
            }
        }
        context.launch {
            actionView.subActionView.resetConfig(context, actionView.config)
        }
        actionView.statusCallback = object : MainActionView.StatusCallback {
            fun modelItemsToActionItems(modelItems: ArrayList<ModelItem>): ArrayList<GestureActionItem> {
                return ArrayList<GestureActionItem>().apply {
                    modelItems.forEach {
                        if (it.type != ActionView.ItemType.WHITEBOARD) {
                            add(it.model)
                        }
                    }
                }
            }

            override fun onRemovedItem(tag: String, itemList: ArrayList<ModelItem>) {
                Log.d(TAG, "onRemovedItem: ")
                onDragCompleted(tag, itemList)
            }

            override fun onDragCompleted(tag: String, itemList: ArrayList<ModelItem>) {
                context.launch(Dispatchers.IO) {
                    Log.d(TAG, "onDragCompleted: ")
                    GestureActionDataManager.saveActionItemList(
                        context, tag, modelItemsToActionItems(itemList)
                    )
                }
            }

            override fun onShowSubView(modelItem: ModelItem) {
                context.launch(Dispatchers.IO) {
                    var tag = modelItem.model.subActionListTag
                    if (tag == null && actionView.subActionView.workMode == ActionView.WorkMode.CONFIG_SUB) {
                        Log.d(TAG, "onShowSubView: new sub action")
                        tag = GestureActionDataManager.buildTag()
                        modelItem.model.subActionListTag = tag
                        GestureActionDataManager.saveActionItemList(
                            context,
                            actionView.state.itemsTag,
                            modelItemsToActionItems(actionView.modelList)
                        )
                    }
                    if (tag == null) return@launch
                    val itemList = GestureActionDataManager.readActionItemList(context, tag)
                    if (actionView.workMode == ActionView.WorkMode.ACTIVE) {
                        if (itemList?.isEmpty() == true) return@launch
                    }
                    actionView.subActionView.resetConfig(context, actionView.config)
                    withContext(Dispatchers.Main) {
                        actionView.prepareSubActionViewForDisplay()
                        actionView.subActionView.setModeList(tag, itemList ?: ArrayList())
                        actionView.subActionView.showSpread(
                            modelItem.view,
                            actionView.state.mirror
                        )
                    }
                }
            }

            override fun onAddItem(source: ActionView.ActionViewType, tag: String) {
                actionView.hide(true)
                context.startActivity(Intent(context, GestureAddActionActivity::class.java).apply {
                    addFlags(FLAG_ACTIVITY_NEW_TASK)
                    putExtra("tag", tag)
                    putExtra("source", source.name)
                })
            }

            override fun onEditItem(
                source: ActionView.ActionViewType, tag: String, item: GestureActionItem
            ) {
                context.startActivity(Intent(context, GestureAddActionActivity::class.java).apply {
                    addFlags(FLAG_ACTIVITY_NEW_TASK)
                    putExtra("tag", tag)
                    putExtra("source", source.name)
                    putExtra("editMode", true)
                    putExtra("item", item.toJson())
                })
            }

            override fun onSelectedCompleted(
                modelItem: ModelItem?,
                touchX: Float,
                touchY: Float,
                isPress: Boolean
            ) {
                if (actionView.workMode == ActionView.WorkMode.ACTIVE &&
                    modelItem == null &&
                    actionView.touchInMinRadius(touchX, touchY)
                ) {
                    executeAction(actionView.modelList.first(), isPress = isPress, isUp = true)
                }
            }

            override fun executeAction(
                modelItem: ModelItem, isPress: Boolean, isUp: Boolean
            ): Boolean {
                fun isSearchActionLink(link: String?): Boolean {
                    return link.toGestureActionType() == GestureActionType.SEARCH
                }

                fun execute(link: String?) {
                    val keepBackgroundBlur = backgroundBlurActive && isSearchActionLink(link)
                    if (keepBackgroundBlur) {
                        keepBackgroundBlurForSearchHandoff()
                    }
                    context.launch(Dispatchers.Default) {
                        link?.let {
                            ActionProxy(context, it).execute(actionViewAnchor())
                        }
                    }
                }

                fun getActionLink(model: GestureActionItem, isPress: Boolean): String? {
                    return if (isPress) {
                        model.secondStageLink
                    } else {
                        model.firstStageLink
                    }
                }

                val hasTimeout = (!isPress && modelItem.model.getFirstStageParam("timeout")) ||
                        (isPress && modelItem.model.getSecondStageParam("timeout"))
                return when {
                    // When timeout is set, execute only on press down
                    hasTimeout && !isUp -> {
                        execute(getActionLink(modelItem.model, isPress))
                        true
                    }
                    // When timeout is not set, execute on release
                    !hasTimeout && isUp -> {
                        execute(getActionLink(modelItem.model, isPress))
                        true
                    }
                    // Otherwise do not execute
                    else -> false
                }
            }
        }
    }

    suspend fun showActionView(
        tag: String,
        downX: Float,
        downY: Float,
        triggerConfig: FreeTriggerConfig,
        isMirror: Boolean,
        gestureType: GestureTypeEvent
    ) {
        showActionViewInternal(
            tag = tag,
            selectedIconY = downY,
            gestureType = gestureType
        ) {
            if (triggerConfig.quadrantValue == 0) {
                relayoutActionViewQuadrant(downX, downY)
            } else {
                relayoutActionViewQuadrantByTrigger(
                    triggerConfig,
                    downX,
                    downY,
                    isMirror = isMirror
                )
            }
        }
    }

    suspend fun showActionView(tag: String, triggerConfig: FreeTriggerConfig, isMirror: Boolean = false) {
        val (centerX, centerY) = resolveTriggerCenter(triggerConfig, isMirror)
        showActionViewInternal(
            tag = tag,
            selectedIconY = centerY
        ) {
            if (triggerConfig.quadrantValue == 0) {
                relayoutActionViewQuadrant(centerX, centerY)
            } else {
                relayoutActionViewQuadrantByTrigger(
                    triggerConfig,
                    centerX,
                    centerY,
                    isMirror = isMirror
                )
            }
        }
    }

    suspend fun showConfigActionView(
        tag: String,
        triggerConfig: FreeTriggerConfig? = null,
        isMirror: Boolean = false,
        subItemListTag: String? = null,
    ) {
        withContext(Dispatchers.Main) {
            ensureAttached()
        }
        reloadItemData(tag, force = true)
        val disableAllWheelVibrate =
            context.settingsStore.readKey(KEY_DISABLE_ALL_WHEEL_VIBRATE, false)
        actionView.disableAllWheelVibrate = disableAllWheelVibrate
        actionView.subActionView.disableAllWheelVibrate = disableAllWheelVibrate
        actionView.show(showAnim = false) {
            actionView.relayoutSelectedIcon(enable = false)
            if (triggerConfig != null) {
                val (downX, downY) = resolveTriggerCenter(triggerConfig, isMirror)
                if (triggerConfig.quadrantValue == 0) {
                    relayoutActionViewQuadrant(downX, downY)
                } else {
                    relayoutActionViewQuadrantByTrigger(
                        triggerConfig,
                        downX,
                        downY,
                        isMirror = isMirror
                    )
                }
            }
        }
        actionView.relayoutConfigView()
        if (!subItemListTag.isNullOrEmpty()) {
            actionView.modelList.forEach { item ->
                if (item.model.subActionListTag == subItemListTag) {
                    actionView.statusCallback.onShowSubView(item)
                }
            }
        }
    }

    fun preload() {
        actionView
        context.launch {
            actionView.subActionView.resetConfig(context, actionView.config)
            reloadItemData(actionView.state.itemsTag, force = true)
        }
    }

    fun refreshTheme() {
        if (!actionView.isAttachedToWindow || !actionView.isVisible) return
        actionView.refreshTheme()
        if (guidePreview) actionView.background = null else actionView.refreshWheelBackgroundForTheme()
    }

    internal fun showGuidePreview(
        config: ActionConfig,
        items: List<GestureActionItem>,
        trigger: FreeTriggerConfig,
        editable: Boolean = false,
        onWheelSelection: ((Int) -> Unit)? = null,
        onLayout: (() -> Unit)? = null,
    ) {
        guidePreview = true
        val revision = ++guidePreviewRevision
        actionView.backgroundBlurCallback = {}
        actionView.keepGuidePreviewVisible = true
        actionView.statusCallback = object : MainActionView.StatusCallback {
            override fun executeAction(modelItem: ModelItem, isPress: Boolean, isUp: Boolean): Boolean {
                if (!isUp || modelItem.type != ActionView.ItemType.NORMAL) return false
                val index = actionView.modelList.indexOf(modelItem)
                if (index < 0 || index >= items.size) return false
                onWheelSelection?.invoke(index)
                return onWheelSelection != null
            }
        }
        setTouchEnabled(false)
        actionView.config = config
        actionView.state.itemsTag = config.tag
        actionView.setModeList(items)
        actionView.workMode = if (editable) ActionView.WorkMode.CONFIG_MAIN else ActionView.WorkMode.ACTIVE
        actionView.disableAllWheelVibrate = true
        actionView.visibility = View.VISIBLE
        ensureAttached()
        actionView.doOnLayout {
            if (revision == guidePreviewRevision && isAttachRequested(actionView)) {
                val (x, y) = resolveTriggerCenter(trigger)
                if (trigger.quadrantValue == 0) relayoutActionViewQuadrant(x, y)
                else relayoutActionViewQuadrantByTrigger(trigger, x, y)
                actionView.relayoutSelectedIcon(enable = false)
                actionView.fitGuideWheelToScreen(guideWheelScreenBounds(actionView.width, actionView.height))
                actionView.layoutGuidePreview()
                onLayout?.invoke()
            }
        }
    }

    internal fun destroyGuidePreview() {
        guidePreviewRevision++
        actionView.keepGuidePreviewVisible = false
        actionView.release()
        detachViewSafely(actionView)
    }

    private suspend fun showActionViewInternal(
        tag: String,
        selectedIconY: Float,
        gestureType: GestureTypeEvent? = null,
        relayout: (showHintIcon: Boolean) -> Unit
    ) {
        withContext(Dispatchers.Main) {
            ensureAttached()
        }
        reloadItemData(tag, force = false)
        updateRecentAppsIcon()
        val showAnim = context.settingsStore.readKey(KEY_MAIN_SHOW_ANIM, true)
        val showHintIcon = context.settingsStore.readKey(KEY_SELECTED_HINT_ICON, true)
        val showVibrate = context.settingsStore.readKey(KEY_MAIN_SHOW_VIBRATE, true)
        val disableAllWheelVibrate =
            context.settingsStore.readKey(KEY_DISABLE_ALL_WHEEL_VIBRATE, false)

        actionView.disableAllWheelVibrate = disableAllWheelVibrate
        actionView.subActionView.disableAllWheelVibrate = disableAllWheelVibrate
        if (showVibrate) {
            VibratorUtils.vibrator(context, true)
        }
        actionView.show(showAnim = showAnim, gestureType = gestureType) {
            actionView.relayoutSelectedIcon(selectedIconY, enable = showHintIcon)
            relayout(showHintIcon)
        }
    }

    fun resolveTriggerCenter(
        triggerConfig: FreeTriggerConfig,
        isMirror: Boolean = false
    ): Pair<Float, Float> {
        val bounds = freeTriggerBounds(context, triggerConfig, isMirror)
        return Pair(bounds.exactCenterX(), bounds.exactCenterY())
    }

    fun hide() {
        Log.d(
            TAG,
            "GestureMainFloating hide attached=${actionView.isAttachedToWindow} " +
                    "visible=${actionView.isVisible} requested=${isAttachRequested(actionView)}"
        )
        actionView.hide()
    }

    fun destroy() = context.postOnMain {
        Log.d(
            TAG,
            "GestureMainFloating destroy attached=${actionView.isAttachedToWindow} " +
                    "visible=${actionView.isVisible} requested=${isAttachRequested(actionView)}"
        )
        clearAttachRequest(actionView)
        clearBackgroundBlur()
        actionView.release()
        actionView.visibility = View.GONE
        detachIfNeeded()
    }

    fun setTouchEnabled(enabled: Boolean) {
        if (touchEnabled == enabled) return
        touchEnabled = enabled
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        layoutParams.flags = if (enabled) {
            layoutParams.flags and flag.inv()
        } else {
            layoutParams.flags or flag
        }
        if (actionView.isAttachedToWindow) {
            runCatching { windowManager.updateViewLayout(actionView, layoutParams) }.onFailure {
                Log.d(TAG, "setTouchEnabled: $it")
            }
        }
    }

    suspend fun updateRecentAppsIcon() = withContext(Dispatchers.IO) {
        val recentAppsItems = actionView.modelList.filter {
            it.model.firstStageAction == GestureActionType.RECENT_APP
        }
        if (recentAppsItems.isEmpty()) return@withContext

        val notificationApps = NotificationService.getSortedNotifications()
        val notificationAppPackages = notificationApps.map { it.packageName }
        val excludeAppPackages = actionView.modelList
            .filter { it.model.firstStageAction == GestureActionType.OPEN_APP }
            .map { it.model.firstStagePkg }.plus(notificationAppPackages)
        val requiredAppCount =
            recentAppsItems.size + excludeAppPackages.size + notificationApps.size + 1
        val recentAppPackages = RecentAppsRecorder.getRecentApps(requiredAppCount)
            .filterNot { it.pkgName in excludeAppPackages }
            .map { it.pkgName }

        val allApps = notificationAppPackages.plus(recentAppPackages)
        val changedIconItems = ArrayList<ModelItem>(recentAppsItems.size)

        recentAppsItems.forEachIndexed { index, item ->
            val targetPkg = allApps.getOrNull(index) ?: return@forEachIndexed
            val isNotificationItem = index < notificationApps.size
            val pkgChanged =
                item.model.firstStagePkg != targetPkg || item.model.secondStagePkg != targetPkg
            val notificationChanged =
                item.model.getFirstStageParam("notification") != isNotificationItem ||
                        item.model.getSecondStageParam("notification") != isNotificationItem

            if (!pkgChanged && !notificationChanged) return@forEachIndexed

            with(item.model) {
                if (pkgChanged) {
                    addParam("pkg", targetPkg, true)
                    addParam("pkg", targetPkg, false)
                }
                if (notificationChanged) {
                    val notificationValue = isNotificationItem.toString()
                    addParam("notification", notificationValue, true)
                    addParam("notification", notificationValue, false)
                }
            }

            if (pkgChanged) {
                changedIconItems.add(item)
            }
        }

        if (changedIconItems.isEmpty()) return@withContext
        withContext(Dispatchers.Main) {
            changedIconItems.forEach { actionView.setIcon(it) }
        }
    }

    suspend fun reloadItemData(tag: String, force: Boolean = false) =
        withContext(Dispatchers.IO) {
            if (!force && tag == actionView.state.itemsTag && actionView.modelList.isNotEmpty()) {
                return@withContext
            }
            val config = GestureActionDataManager.readActionConfig(context, tag, false)
            actionView.state.itemsTag = tag
            actionView.config = config
            var itemList = GestureActionDataManager.readActionItemList(context, tag)
            if (itemList.isNullOrEmpty()) {
                itemList = createDefaultActionItems(tag)
            }
            withContext(Dispatchers.Main) {
                actionView.setModeList(itemList)
            }
        }

    private fun createDefaultActionItems(tag: String): ArrayList<GestureActionItem> {
        if (tag != ActionConfig.DEFAULT_TAG) {
            return ArrayList<GestureActionItem>().apply {
                repeat(4) {
                    add(GestureActionItem())
                }
            }
        }

        return arrayListOf(
            buildBuiltInActionItem(
                name = context.getString(R.string.gesture_action_search),
                actionType = GestureActionType.SEARCH
            ),
            buildBuiltInActionItem(
                name = context.getString(R.string.gesture_action_screen_content),
                actionType = GestureActionType.SCREEN_CONTENT
            ),
            buildBuiltInActionItem(
                name = context.getString(R.string.gesture_action_screen_search),
                actionType = GestureActionType.SCREEN_SEARCH
            ),
            buildBuiltInActionItem(
                name = context.getString(R.string.pin_pool_title),
                actionType = GestureActionType.PIN_POOL
            ),
            buildBuiltInActionItem(
                name = context.getString(R.string.gesture_action_keyboard),
                actionType = GestureActionType.KEYBOARD
            ),
            buildBuiltInActionItem(
                name = context.getString(R.string.acc_action_select_back),
                actionType = GestureActionType.ACC_ACTION,
                queryParams = listOf(
                    "accAction" to AccessibilityService.GLOBAL_ACTION_BACK.toString()
                )
            ),
            buildBuiltInActionItem(
                name = context.getString(R.string.acc_action_select_notifications),
                actionType = GestureActionType.ACC_ACTION,
                queryParams = listOf(
                    "accAction" to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS.toString()
                )
            ),
            buildBuiltInActionItem(
                name = context.getString(R.string.gesture_action_temp_dnd),
                actionType = GestureActionType.TEMP_DND,
                queryParams = listOf("minutes" to "15")
            )
        )
    }

    private fun buildBuiltInActionItem(
        name: String,
        actionType: GestureActionType,
        queryParams: List<Pair<String, String>> = emptyList()
    ): GestureActionItem {
        return GestureActionItem().apply {
            this.name = name
            iconType = IconType.OTHER
            firstStageLink = GestureActionLink(actionType = actionType).apply {
                queryParams.forEach { (key, value) ->
                    addParams(key, value)
                }
            }.buildLink()
        }
    }

    fun relayoutActionViewQuadrant(
        downX: Float,
        downY: Float,
        viewWidth: Int = actionView.width,
        viewHeight: Int = actionView.height
    ) {
        Log.d(TAG, "relayoutActionViewItem: $downX, $downY, width:$viewWidth, height:$viewHeight")
        actionView.restoreGuideWheelScreenFit()

        val actionConfig = actionView.config
        val actionState = actionView.state

        val quadrant = ArrayList<Boolean>(4).apply {
            add(true) // First quadrant
            add(true) // Second quadrant
            add(true) // Third quadrant
            add(true) // Fourth quadrant
        }

        // Calculate required maximum radius
        val (maxRadius, _) = actionView.calculateMaxOuterRadius()
        val halfRadius = maxRadius / 2
        // Check whether surroundings can fit halfRadius
        val isNearLeftEdge = downX < halfRadius
        val isNearRightEdge = downX > viewWidth - halfRadius
        val isNearTopEdge = downY < halfRadius
        val isNearBottomEdge = downY > viewHeight - halfRadius

        // Check whether horizontal and vertical spaces are sufficient
        val hasHorizontalSpace = !isNearLeftEdge && !isNearRightEdge
        val hasVerticalSpace = !isNearTopEdge && !isNearBottomEdge

        if (hasHorizontalSpace && hasVerticalSpace) {
            // In middle area of screen - show full 360 degrees
            actionState.centerX = downX.toInt()
            actionState.centerY = downY.toInt()
            // All quadrants enabled (default true)
        } else if (hasHorizontalSpace) {
            // At top or bottom edge - show 180 degrees
            actionState.centerX = downX.toInt()

            if (isNearTopEdge) {
                // Top edge
                actionState.centerY = actionConfig.actionOffsetY.toInt()
                // Disable top half
                quadrant[0] = false
                quadrant[1] = false
            } else {
                // Bottom edge
                actionState.centerY = viewHeight - actionConfig.actionOffsetY.toInt()
                // Disable bottom half
                quadrant[2] = false
                quadrant[3] = false
            }
        } else if (hasVerticalSpace) {
            // At left or right side - show 180 degrees
            actionState.centerY = downY.toInt()

            if (downX <= viewWidth / 2) {
                // Left side
                actionState.centerX = downX.toInt() + actionConfig.actionOffsetX.toInt()
                // Disable left quadrants
                quadrant[1] = false
                quadrant[2] = false
            } else {
                // Right side
                actionState.centerX = downX.toInt() - actionConfig.actionOffsetX.toInt()
                // Disable right quadrants
                quadrant[0] = false
                quadrant[3] = false
            }
        } else {
            // In corners - show 90 degrees
            if (downX <= viewWidth / 2) {
                // Left side
                actionState.centerX = actionConfig.actionOffsetX.toInt()
                // Disable left quadrants
                quadrant[1] = false
                quadrant[2] = false
            } else {
                // Right side
                actionState.centerX = viewWidth - actionConfig.actionOffsetX.toInt()
                // Disable right quadrants
                quadrant[0] = false
                quadrant[3] = false
            }

            if (downY < viewHeight / 2) {
                // Top
                actionState.centerY = actionConfig.actionOffsetY.toInt()
                quadrant[0] = quadrant[0] && false
                quadrant[1] = quadrant[1] && false
            } else {
                // Bottom
                actionState.centerY = viewHeight - actionConfig.actionOffsetY.toInt()
                quadrant[2] = quadrant[2] && false
                quadrant[3] = quadrant[3] && false
            }
        }

        actionState.mirror = downX <= viewWidth / 2
        actionState.setAngleByQuadrant(quadrant)
    }


    fun relayoutActionViewQuadrantByTrigger(
        triggerConfig: FreeTriggerConfig,
        downX: Float,
        downY: Float,
        viewWidth: Int = actionView.width,
        isMirror: Boolean = false
    ) {
        actionView.restoreGuideWheelScreenFit()
        actionView.state.mirror = downX <= viewWidth / 2
        val guideTrigger = triggerConfig.tag == QuickSetupRepository.PREVIEW_LEFT_TRIGGER_TAG ||
            triggerConfig.tag == QuickSetupRepository.PREVIEW_RIGHT_TRIGGER_TAG ||
            triggerConfig.tag == QuickSetupRepository.LEFT_TRIGGER_TAG ||
            triggerConfig.tag == QuickSetupRepository.RIGHT_TRIGGER_TAG
        if (guideTrigger) {
            val halfCenter = actionView.config.initRadius / 2
            val insetX = actionView.config.actionOffsetX
            val centerX = if (actionView.state.mirror) downX + insetX else downX - insetX
            actionView.state.centerX = centerX.coerceIn(halfCenter, (viewWidth - halfCenter).coerceAtLeast(halfCenter)).toInt()
            actionView.state.centerY = downY.coerceIn(halfCenter, (actionView.height - halfCenter).coerceAtLeast(halfCenter)).toInt()
        } else {
            actionView.state.centerX = downX.toInt() + actionView.config.actionOffsetX.toInt()
            actionView.state.centerY = downY.toInt() + actionView.config.actionOffsetY.toInt()
        }
        if (!actionView.config.rectangleLayoutEnabled && triggerConfig.quadrantValue == 15) {
            actionView.state.mirror = false
        }
        val quadrant = triggerConfig.getQuadrant(isMirror)
        actionView.state.setAngleByQuadrant(quadrant)
        if (guideTrigger) actionView.fitGuideWheelToScreen(guideWheelScreenBounds(viewWidth, actionView.height))
    }

    @SuppressLint("InternalInsetResource", "DiscouragedApi")
    private fun guideWheelScreenBounds(viewWidth: Int, viewHeight: Int): Rect {
        val insets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.maximumWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            ).let { androidx.core.graphics.Insets.of(it.left, it.top, it.right, it.bottom) }
        } else {
            ViewCompat.getRootWindowInsets(actionView)?.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            ) ?: run {
                val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
                val bottom = if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
                androidx.core.graphics.Insets.of(0, ScreenUtil.getStatusBarHeight(context, false), 0, bottom)
            }
        }
        val left = insets.left.coerceAtMost((viewWidth - 3).coerceAtLeast(0))
        val top = (insets.top + 136f.dp2px.toInt()).coerceAtMost((viewHeight - 3).coerceAtLeast(0))
        return Rect(
            left, top, (viewWidth - insets.right).coerceAtLeast(left + 3),
            (viewHeight - insets.bottom).coerceAtLeast(top + 3)
        )
    }

    private fun actionViewAnchor(): TriggerSide.Point {
        val displayMetrics = context.resources.displayMetrics
        val sw = actionView.width.takeIf { it > 0 } ?: displayMetrics.widthPixels
        val sh = actionView.height.takeIf { it > 0 } ?: displayMetrics.heightPixels
        return TriggerSide.Point(
            actionView.state.centerX.toFloat(),
            sw,
            actionView.state.centerY.toFloat(),
            sh
        )
    }

}
