package io.github.lumiagg.gestureevo.gesture

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.floating.BaseFloatingWindow
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerConfig
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerDataManager
import io.github.lumiagg.gestureevo.gesture.config.GestureActionDataManager
import io.github.lumiagg.gestureevo.gesture.config.GestureActionItem
import io.github.lumiagg.gestureevo.gesture.ui.TriggerCallBack
import io.github.lumiagg.gestureevo.gesture.ui.TriggerView
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.ScreenUtil
import io.github.lumiagg.gestureevo.utils.dp2px
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min


data class TriggerViewGroup(
    val tag: String,
    val originalView: TriggerView,
    var mirrorView: TriggerView? = null
) {
    /**
     * Returns a list of all active views.
     */
    fun getAllViews(): List<TriggerView> {
        return if (mirrorView != null) {
            listOf(originalView, mirrorView!!)
        } else {
            listOf(originalView)
        }
    }

    /**
     * Cleans up view resources.
     */
    fun cleanUp(removeView: (View) -> Unit) {
        removeView(originalView)
        mirrorView?.let(removeView)
    }
}

class FreeTriggerFloating(
    private val context: GestureService,
    private val triggerTouchProxyCallBack: TriggerCallBack
) : BaseFloatingWindow(context, context, TAG) {
    companion object {
        const val TAG = "FreeTriggerFloating"
        var realTriggerIsEnable = false
        var expectTriggerIsEnable = true
    }

    private val triggerViews = mutableMapOf<String, TriggerViewGroup>()
    private var triggers = listOf<FreeTriggerConfig>()
    private var lastPackageName: String? = context.packageName
    private var lastOrientation: Int = Configuration.ORIENTATION_UNDEFINED
    private val screenWidth: Int
        get() = ScreenUtil.getScreenRealWidth(context, false)

    private val screenHeight: Int
        get() = ScreenUtil.getScreenRealHeight(context, false)

    init {
        context.launch {
            reloadTriggers()
            checkTriggerOrientation()
            resetAllTriggerEnable()
        }
    }

    suspend fun migrateActionMap(context: Context, configs: List<FreeTriggerConfig>) = withContext(
        Dispatchers.IO
    ) {
        configs.forEach { config ->
            if (!config.actionMap.isEmpty()) {
                val newActionList = ArrayList<GestureActionItem>()
                config.actionMap.forEach {
                    val gestureActionItem = GestureActionItem()
                    gestureActionItem.tag = it.key
                    gestureActionItem.firstStageLink = it.value
                    newActionList.add(gestureActionItem)
                }
                config.actionMap = HashMap()
                GestureActionDataManager.saveActionItemList(context, config.tag, newActionList)
            }
        }
        FreeTriggerDataManager.saveTriggers(context, configs)
    }

    suspend fun reloadTriggers() = withContext(Dispatchers.Main) {
        // Read all trigger configurations
        val newConfigs = FreeTriggerDataManager.readTriggers(context)

        // Migrate action map
        migrateActionMap(context, newConfigs)

        // Collect all view tags that should exist
        val expectedViewTags = newConfigs.filter { it.enable }.map { it.tag }.toSet()

        // Remove view groups that are no longer needed
        val tagsToRemove = triggerViews.keys - expectedViewTags
        tagsToRemove.forEach { tag ->
            triggerViews[tag]?.cleanUp { detachViewSafely(it, immediate = false) }
            triggerViews.remove(tag)
        }

        // Update or create views
        newConfigs.forEach { config ->
            if (config.enable) {
                val tag = config.tag

                if (tag in triggerViews) {
                    // Update existing view group
                    updateTriggerViewGroup(config)
                } else {
                    // Create new view group
                    createTriggerViewGroup(config)
                }
            }
        }

        triggers = newConfigs
    }

    private fun createTriggerViewGroup(config: FreeTriggerConfig) {
        val originalView = buildView(config, false)
        val viewGroup = TriggerViewGroup(config.tag, originalView)

        // Create mirrored view if mirroring is enabled
        if (config.getState(FreeTriggerConfig.FLAG_MIRROR)) {
            viewGroup.mirrorView = buildView(config, true)
        }

        triggerViews[config.tag] = viewGroup
    }

    private fun updateTriggerViewGroup(config: FreeTriggerConfig) {
        val viewGroup = triggerViews[config.tag] ?: return

        // Update original view
        viewGroup.originalView.triggerConfig = config
        val originalLayoutParams = createWindowLayoutParams(config, false)
        updateViewLayoutSafely(viewGroup.originalView, originalLayoutParams)

        // Handle mirrored view
        if (config.getState(FreeTriggerConfig.FLAG_MIRROR)) {
            if (viewGroup.mirrorView == null) {
                // Need to create new mirrored view
                viewGroup.mirrorView = buildView(config, true)
            } else {
                // Update existing mirrored view
                viewGroup.mirrorView?.triggerConfig = config
                val mirrorLayoutParams = createWindowLayoutParams(config, true)
                viewGroup.mirrorView?.let { updateViewLayoutSafely(it, mirrorLayoutParams) }
            }
        } else if (viewGroup.mirrorView != null) {
            // Mirrored view no longer needed, remove it
            viewGroup.mirrorView?.let { detachViewSafely(it, immediate = false) }
            viewGroup.mirrorView = null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildView(config: FreeTriggerConfig, isMirror: Boolean): TriggerView {
        val layoutParams = createWindowLayoutParams(config, isMirror)

        val view = TriggerView(context).apply {
            tag = if (isMirror) "${config.tag}_mirror" else config.tag
            this.triggerConfig = config
            triggerCallBack = triggerTouchProxyCallBack
            isActivated = true
            this.isMirror = isMirror
            val color = context.getColor(R.color.transparent)
            setBackgroundColor(color)
        }

        addViewSafely(view, layoutParams)
        return view
    }

    private fun createWindowLayoutParams(
        config: FreeTriggerConfig,
        isMirror: Boolean
    ): WindowManager.LayoutParams {
        // Ensure view dimensions remain within screen bounds
        val adjustedWidth = min(config.width.dp2px.toInt(), screenWidth)
        val adjustedHeight = min(config.height.dp2px.toInt(), screenHeight)

        return WindowManager.LayoutParams().apply {
            var windowFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

            if (config.getState(FreeTriggerConfig.FLAG_BELOW_KEYBOARD)) {
                windowFlags = windowFlags or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            }

            configureAccessibilityOverlay(
                windowFlags,
                width = adjustedWidth,
                height = adjustedHeight,
                title = if (isMirror) {
                    "GestureEVO:FreeTriggerFloating:Mirror:${config.tag}"
                } else {
                    "GestureEVO:FreeTriggerFloating:${config.tag}"
                },
            )

            // Calculate initial position
            var initialX = if (isMirror) {
                screenWidth - (config.xRelativeValue * screenWidth).toInt() - adjustedWidth
            } else {
                (config.xRelativeValue * screenWidth).toInt()
            }

            var initialY = (config.yRelativeValue * screenHeight).toInt()

            // Ensure view stays within screen boundaries
            x = max(0, min(initialX, screenWidth - adjustedWidth))
            y = max(0, min(initialY, screenHeight - adjustedHeight))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun setVisibility(tag: String, isVisible: Boolean): Boolean =
        withContext(Dispatchers.Main) {
            if (isVisible) realTriggerIsEnable = true
            val viewGroup = triggerViews[tag] ?: return@withContext false
            val targetVisible = if (isVisible) View.VISIBLE else View.GONE
            var result = true

            // Set visibility for all views in the group
            viewGroup.getAllViews().forEach { view ->
                if (view.visibility != targetVisible) {
                    try {
                        result = result && suspendCancellableCoroutine { continuation ->
                            view.visibility = targetVisible
                            val listener = object : ViewTreeObserver.OnPreDrawListener {
                                override fun onPreDraw(): Boolean {
                                    view.viewTreeObserver.removeOnPreDrawListener(this)
                                    continuation.resume(true) {}
                                    return true
                                }
                            }
                            view.viewTreeObserver.addOnPreDrawListener(listener)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        result = false
                    }
                }
            }

            result
        }

    suspend fun checkTriggerVisibility(triggerView: TriggerView) {
        if (expectTriggerIsEnable) {
            if (triggerView.triggerConfig.enable && triggerView.enabledOrientation && triggerView.enabledPackage) {
                setVisibility(triggerView.triggerConfig.tag, true)
            } else {
                setVisibility(triggerView.triggerConfig.tag, false)
            }
        } else {
            setVisibility(triggerView.triggerConfig.tag, false)
        }
    }

    suspend fun checkTriggerPackage(targetPkg: String) {
        lastPackageName = targetPkg
        refreshTriggerVisibility()
    }

    suspend fun checkTriggerOrientation(newOrientation: Int = Configuration.ORIENTATION_UNDEFINED) {
        lastOrientation = resolveOrientation(newOrientation)
        refreshTriggerVisibility()
    }

    suspend fun enterToConfigMode(triggerConfig: FreeTriggerConfig, isConfig: Boolean) =
        withContext(Dispatchers.Main) {
            val viewGroup = triggerViews[triggerConfig.tag] ?: return@withContext

            val transparentColor = context.getColor(R.color.transparent)
            val originColor = DynamicColorUtils.primaryColor
            val mirrorColor = DynamicColorUtils.inversePrimaryColor
            // Set background color for all views in the group
            viewGroup.getAllViews().forEach { view ->
                if (view.isMirror) {
                    view.setBackgroundColor(if (isConfig) mirrorColor else transparentColor)
                } else {
                    view.setBackgroundColor(if (isConfig) originColor else transparentColor)
                }
            }
        }

    suspend fun enterToConfigMode() =
        withContext(Dispatchers.Main) {
            val originColor = DynamicColorUtils.primaryColor
            val mirrorColor = DynamicColorUtils.inversePrimaryColor
            triggerViews.forEach {
                // Set background color for all views in the group
                it.value.getAllViews().forEach { view ->
                    if (view.isMirror) {
                        view.setBackgroundColor(mirrorColor)
                    } else {
                        view.setBackgroundColor(originColor)
                    }
                }
            }
        }

    suspend fun exitToConfigMode() =
        withContext(Dispatchers.Main) {
            triggerViews.forEach {
                val color = context.getColor(R.color.transparent)
                // Set background color for all views in the group
                it.value.getAllViews().forEach { view ->
                    view.setBackgroundColor(color)
                }
            }
        }

    suspend fun resetAllTriggerEnable() =
        withContext(Dispatchers.Main) {
            Log.d(TAG, "resetAllTriggerEnable: ")
            refreshTriggerVisibility()
        }

    suspend fun disableAllTrigger() =
        withContext(Dispatchers.Main) {
            realTriggerIsEnable = false
            triggers.forEach { trigger ->
                setVisibility(trigger.tag, false)
            }
        }

    /**
     * Readjust all trigger positions when screen orientation changes.
     */
    suspend fun adjustPositionsForScreenSize() = withContext(Dispatchers.Main) {
        triggers.forEach { config ->
            val viewGroup = triggerViews[config.tag] ?: return@forEach

            // Update original view position
            val originalLayoutParams = createWindowLayoutParams(config, false)
            try {
                updateViewLayoutSafely(viewGroup.originalView, originalLayoutParams)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Update mirrored view position if present
            viewGroup.mirrorView?.let { mirrorView ->
                val mirrorLayoutParams = createWindowLayoutParams(config, true)
                try {
                    updateViewLayoutSafely(mirrorView, mirrorLayoutParams)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private suspend fun refreshTriggerVisibility() = withContext(Dispatchers.Main) {
        val currentOrientation = resolveOrientation(lastOrientation)
        val currentPackage = lastPackageName

        triggers.forEach { trigger ->
            val visibleByOrientation = isOrientationAllowed(trigger, currentOrientation)
            val visibleByPackage = currentPackage?.let { isPackageAllowed(trigger, it) } ?: true

            triggerViews[trigger.tag]?.originalView?.let {
                it.enabledOrientation = visibleByOrientation
                it.enabledPackage = visibleByPackage
                checkTriggerVisibility(it)
            }
        }
        adjustPositionsForScreenSize()
    }

    private fun resolveOrientation(inputOrientation: Int): Int {
        return if (inputOrientation == Configuration.ORIENTATION_UNDEFINED) {
            ScreenUtil.getDisplayOrientation(context)
        } else {
            inputOrientation
        }
    }

    private fun isOrientationAllowed(trigger: FreeTriggerConfig, orientation: Int): Boolean {
        val allowPortrait = trigger.getState(FreeTriggerConfig.FLAG_PORTRAIT)
        val allowLandscape = trigger.getState(FreeTriggerConfig.FLAG_LANDSCAPE)
        if (!allowPortrait && !allowLandscape) return true
        return when (orientation) {
            Configuration.ORIENTATION_PORTRAIT -> allowPortrait
            Configuration.ORIENTATION_LANDSCAPE -> allowLandscape
            else -> allowPortrait || allowLandscape
        }
    }

    fun isPackageAllowedForTrigger(trigger: FreeTriggerConfig, targetPkg: String): Boolean {
        return isPackageAllowed(trigger, targetPkg)
    }

    private fun isPackageAllowed(trigger: FreeTriggerConfig, targetPkg: String): Boolean {
        if (targetPkg == context.packageName) return true

        val useBlacklist = trigger.getState(FreeTriggerConfig.FLAG_BLACKLIST)
        val useWhitelist = trigger.getState(FreeTriggerConfig.FLAG_WHITELIST)

        return when {
            useBlacklist && useWhitelist -> {
                val inWhitelist =
                    trigger.whiteList.isEmpty() || trigger.whiteList.contains(targetPkg)
                val notBlacklisted = !trigger.blackList.contains(targetPkg)
                inWhitelist && notBlacklisted
            }

            useBlacklist -> !trigger.blackList.contains(targetPkg)
            useWhitelist -> trigger.whiteList.isEmpty() || trigger.whiteList.contains(targetPkg)
            else -> true
        }
    }

    fun destroy() {
        triggerViews.values.forEach { viewGroup ->
            viewGroup.cleanUp { detachViewSafely(it, immediate = false) }
        }
        triggerViews.clear()
    }
}
