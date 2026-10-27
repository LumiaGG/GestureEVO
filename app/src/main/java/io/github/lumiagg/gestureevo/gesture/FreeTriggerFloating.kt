package io.github.lumiagg.gestureevo.gesture

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
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
import io.github.lumiagg.gestureevo.gesture.config.gestureConfigurationWriteMutex
import io.github.lumiagg.gestureevo.gesture.ui.TriggerCallBack
import io.github.lumiagg.gestureevo.gesture.ui.TriggerView
import io.github.lumiagg.gestureevo.utils.DynamicColorUtils
import io.github.lumiagg.gestureevo.utils.ScreenUtil
import io.github.lumiagg.gestureevo.utils.dp2px
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference
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

        @Volatile
        private var instanceRef: WeakReference<FreeTriggerFloating>? = null

        fun current(): FreeTriggerFloating? = instanceRef?.get()
    }

    private val triggerViews = mutableMapOf<String, TriggerViewGroup>()
    private val actionCooldownJobs = mutableMapOf<String, Job>()
    private val cooldownDeadlines = mutableMapOf<String, Long>()
    private val cooldownTriggerNames = mutableMapOf<String, String>()
    private val cooldownTotalDurations = mutableMapOf<String, Long>()
    private var triggers = listOf<FreeTriggerConfig>()
    private var lastPackageName: String? = context.packageName
    private var lastOrientation: Int = Configuration.ORIENTATION_UNDEFINED
    private var guidePreviewActive = false
    init {
        instanceRef = WeakReference(this)
        context.launch {
            reloadTriggers()
            checkTriggerOrientation()
            resetAllTriggerEnable()
        }
    }

    suspend fun migrateActionMap(context: Context, configs: List<FreeTriggerConfig>) = withContext(
        Dispatchers.IO
    ) {
        if (configs.none { it.actionMap.isNotEmpty() }) return@withContext configs
        gestureConfigurationWriteMutex.withLock {
            // The snapshot can become stale while waiting for a guide commit.
            val current = FreeTriggerDataManager.readTriggers(context).map { it.copy() }
            if (current.none { it.actionMap.isNotEmpty() }) return@withLock current
            current.forEach { config ->
                if (config.actionMap.isNotEmpty()) {
                    val items = ArrayList(config.actionMap.map { (key, link) ->
                        GestureActionItem().apply { tag = key; firstStageLink = link }
                    })
                    GestureActionDataManager.saveActionItemList(context, config.tag, items)
                    config.actionMap = emptyMap()
                    config.gestureActionList = items
                }
            }
            FreeTriggerDataManager.saveTriggers(context, current)
            current
        }
    }

    suspend fun reloadTriggers() = withContext(Dispatchers.Main) {
        // Read all trigger configurations
        val newConfigs = migrateActionMap(context, FreeTriggerDataManager.readTriggers(context))

        // Collect all view tags that should exist
        val expectedViewTags = newConfigs.filter { it.enable }.map { it.tag }.toSet()

        // Remove view groups that are no longer needed
        val tagsToRemove = triggerViews.keys - expectedViewTags
        tagsToRemove.forEach { tag ->
            actionCooldownJobs.remove(tag)?.cancel()
            cooldownDeadlines.remove(tag)
            cooldownTriggerNames.remove(tag)
            cooldownTotalDurations.remove(tag)
            PauseGestureNotification.cancel(context, tag)
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
            if (guidePreviewActive) visibility = View.GONE
        }

        addViewSafely(view, layoutParams)
        return view
    }

    private fun createWindowLayoutParams(
        config: FreeTriggerConfig,
        isMirror: Boolean
    ): WindowManager.LayoutParams {
        // Ensure view dimensions remain within screen bounds
        val bounds = freeTriggerBounds(context, config, isMirror)

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
                width = bounds.width(),
                height = bounds.height(),
                title = if (isMirror) {
                    "GestureEVO:FreeTriggerFloating:Mirror:${config.tag}"
                } else {
                    "GestureEVO:FreeTriggerFloating:${config.tag}"
                },
            )

            x = bounds.left
            y = bounds.top
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun pauseTriggerTemporarily(
        tag: String,
        durationMs: Long,
        triggerName: String? = null,
        extend: Boolean = false
    ): Boolean = withContext(Dispatchers.Main) {
        if (triggerViews[tag] == null) return@withContext false
        // Deadline-based: the timestamp is the source of truth, so a repeated pause only
        // pushes the deadline out and visibility checks cannot restore the trigger early.
        val now = SystemClock.elapsedRealtime()
        val base = if (extend) maxOf(cooldownDeadlines[tag] ?: 0L, now) else now
        cooldownDeadlines[tag] = base + durationMs
        setVisibility(tag, false)
        val displayName = sequenceOf(
            triggerName,
            cooldownTriggerNames[tag],
            triggers.find { it.tag == tag }?.name
        ).firstOrNull { !it.isNullOrBlank() } ?: tag
        cooldownTriggerNames[tag] = displayName
        // Total time is cumulative across extensions, mirroring how the DND notification
        // reports the whole session rather than only what is left.
        val totalMs = if (extend) {
            (cooldownTotalDurations[tag] ?: 0L) + durationMs
        } else {
            durationMs
        }
        cooldownTotalDurations[tag] = totalMs
        PauseGestureNotification.show(
            context,
            tag,
            displayName,
            totalMs,
            remainingMs = (cooldownDeadlines[tag] ?: 0L) - now
        )
        // A repeated pause replaces the pending restore so only one timer per trigger survives.
        actionCooldownJobs.remove(tag)?.cancel()
        val job = context.launch {
            while (true) {
                val remaining = (cooldownDeadlines[tag] ?: break) - SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                delay(remaining)
            }
            if (cooldownDeadlines[tag]?.let { it <= SystemClock.elapsedRealtime() } == true) {
                clearPauseState(tag)
                val currentGroup = triggerViews[tag] ?: return@launch
                checkTriggerVisibility(currentGroup.originalView)
            }
        }
        actionCooldownJobs[tag] = job
        job.invokeOnCompletion {
            if (actionCooldownJobs[tag] === job) actionCooldownJobs.remove(tag)
        }
        true
    }

    /** Ends a temporary pause right away, e.g. from the countdown notification. */
    suspend fun restorePausedTrigger(tag: String) = withContext(Dispatchers.Main) {
        val hadDeadline = cooldownDeadlines.remove(tag) != null
        cooldownTriggerNames.remove(tag)
        cooldownTotalDurations.remove(tag)
        actionCooldownJobs.remove(tag)?.cancel()
        PauseGestureNotification.cancel(context, tag)
        if (!hadDeadline) return@withContext
        val currentGroup = triggerViews[tag] ?: return@withContext
        checkTriggerVisibility(currentGroup.originalView)
    }

    private fun clearPauseState(tag: String) {
        cooldownDeadlines.remove(tag)
        cooldownTriggerNames.remove(tag)
        cooldownTotalDurations.remove(tag)
        actionCooldownJobs.remove(tag)
        PauseGestureNotification.cancel(context, tag)
    }

    private fun isCoolingDown(tag: String): Boolean =
        (cooldownDeadlines[tag] ?: 0L) > SystemClock.elapsedRealtime()

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun setVisibility(tag: String, isVisible: Boolean): Boolean =
        withContext(Dispatchers.Main) {
            if (isVisible && isCoolingDown(tag)) {
                return@withContext triggerViews.containsKey(tag)
            }
            if (guidePreviewActive) {
                triggerViews[tag]?.getAllViews()?.forEach { it.visibility = View.GONE }
                return@withContext triggerViews.containsKey(tag)
            }
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

    suspend fun setGuidePreviewActive(active: Boolean) = withContext(Dispatchers.Main) {
        if (guidePreviewActive == active) return@withContext
        guidePreviewActive = active
        if (active) {
            triggerViews.values.forEach { group ->
                group.getAllViews().forEach { it.visibility = View.GONE }
            }
        } else {
            refreshTriggerVisibility()
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
        actionCooldownJobs.values.forEach(Job::cancel)
        actionCooldownJobs.clear()
        cooldownDeadlines.clear()
        cooldownTotalDurations.clear()
        cooldownTriggerNames.keys.toList().forEach { tag ->
            PauseGestureNotification.cancel(context, tag)
        }
        cooldownTriggerNames.clear()
        if (instanceRef?.get() === this) instanceRef = null
        triggerViews.values.forEach { viewGroup ->
            viewGroup.cleanUp { detachViewSafely(it, immediate = false) }
        }
        triggerViews.clear()
    }
}

internal fun freeTriggerBounds(
    context: Context,
    config: FreeTriggerConfig,
    isMirror: Boolean = false,
): Rect {
    val screenWidth = ScreenUtil.getScreenRealWidth(context, false)
    val screenHeight = ScreenUtil.getScreenRealHeight(context, false)
    val width = min(config.width.dp2px.toInt(), screenWidth)
    val height = min(config.height.dp2px.toInt(), screenHeight)
    val initialX = if (isMirror) {
        screenWidth - (config.xRelativeValue * screenWidth).toInt() - width
    } else {
        (config.xRelativeValue * screenWidth).toInt()
    }
    val x = initialX.coerceIn(0, max(0, screenWidth - width))
    val y = (config.yRelativeValue * screenHeight).toInt().coerceIn(0, max(0, screenHeight - height))
    return Rect(x, y, x + width, y + height)
}
