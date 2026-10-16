package io.github.lumiagg.gestureevo.utils

import FreeFormUtils
import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import io.github.lumiagg.gestureevo.diagnostics.LogLevel
import io.github.lumiagg.gestureevo.diagnostics.LogModule
import io.github.lumiagg.gestureevo.diagnostics.SystemLogCapture
import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService
import androidx.core.net.toUri
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.ShareOpenAppProxyActivity
import io.github.lumiagg.gestureevo.db.getAPPDao
import io.github.lumiagg.gestureevo.db.updateDB
import io.github.lumiagg.gestureevo.gesture.AccessibilityActionEvent
import io.github.lumiagg.gestureevo.gesture.EventBusManager.produceAccessibilityActionEvent
import io.github.lumiagg.gestureevo.gesture.EventBusManager.produceActionViewEvent
import io.github.lumiagg.gestureevo.gesture.EventBusManager.produceInspireEvent
import io.github.lumiagg.gestureevo.gesture.EventBusManager.produceKeyboardEvent
import io.github.lumiagg.gestureevo.gesture.EventBusManager.producePinPoolEvent
import io.github.lumiagg.gestureevo.gesture.EventBusManager.producePointerEvent
import io.github.lumiagg.gestureevo.gesture.EventBusManager.produceScreenSearchEvent
import io.github.lumiagg.gestureevo.gesture.InspireEvent
import io.github.lumiagg.gestureevo.gesture.KeyboardEvent
import io.github.lumiagg.gestureevo.gesture.PinPoolEvent
import io.github.lumiagg.gestureevo.gesture.PointerEvent
import io.github.lumiagg.gestureevo.gesture.TriggerSide
import io.github.lumiagg.gestureevo.gesture.ScreenSearchEvent
import io.github.lumiagg.gestureevo.gesture.ActionViewEvent
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerDataManager
import io.github.lumiagg.gestureevo.gesture.config.FreeTriggerConfig
import io.github.lumiagg.gestureevo.gesture.config.GestureActionType
import io.github.lumiagg.gestureevo.gesture.ui.ActionView
import io.github.lumiagg.gestureevo.notification.NotificationEvent
import io.github.lumiagg.gestureevo.notification.NotificationService.Companion.produceNotificationEvent
import io.github.lumiagg.gestureevo.search.QuickSearchActivity
import io.github.lumiagg.gestureevo.settings.FreeFormSettingsActivity.Companion.KEY_USE_MI_WINDOW
import io.github.lumiagg.gestureevo.utils.RecentAppsRecorder.isLauncherPackage
import io.github.lumiagg.gestureevo.utils.Rom.RomFreeFormCode
import io.github.lumiagg.gestureevo.utils.Rom.getFreeFormCode
import io.github.lumiagg.gestureevo.utils.Rom.isHuawei
import io.github.lumiagg.gestureevo.utils.app.AShell
import io.github.lumiagg.gestureevo.utils.app.AShizuku
import io.github.lumiagg.gestureevo.utils.app.AShizuku.ensureAppEnabledIfFrozen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.InvocationTargetException
import java.util.UUID

class ActionProxy(private val context: Context, linkString: String) {
    private val trace = UUID.randomUUID().toString()
    private val link = linkString.toUri()

    val event: GestureActionType = link.toGestureActionType()
    var freeform: Boolean = link.getBooleanQueryParameter("freeform", false)
    var newWindow: Boolean = link.getBooleanQueryParameter("newWindow", false)
    val userId: Long = link.getQueryParameter("userId")?.toLong() ?: 0L
    val pkg: String? = link.getQueryParameter("pkg")
    val otherLink: String? = link.getQueryParameter("uri")
    val shell: String? = link.getQueryParameter("shell")
    val isNotification: Boolean = link.getBooleanQueryParameter("notification", false)
    val timeout: Boolean = link.getBooleanQueryParameter("timeout", false)
    val useCustomTabs: Boolean = link.getBooleanQueryParameter("useCustomTabs", false)
    val triggerSideParam: String? = link.getQueryParameter("triggerSide")

    suspend fun execute(anchor: TriggerSide.Point? = null, side: String? = null) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        SystemLogCapture.rememberTarget(pkg)
        AppLogger.event(LogModule.ACTION, "action_triggered", trace = trace,
            fields = mapOf("type" to event.name, "package" to pkg, "foreground" to AppLogger.foreground))
        if (AppLogger.detailed) AppLogger.event(LogModule.ACTION, "action_environment", LogLevel.DEBUG,
            trace = trace, fields = AppLogger.actionState())
        val effectiveSide = side ?: triggerSideParam
        if (anchor != null) {
            TriggerSide.record(anchor)
        } else if (effectiveSide != null) {
            TriggerSide.record(effectiveSide)
        }
        try {
            executeAction()
            AppLogger.event(LogModule.ACTION, "action_dispatch_returned", trace = trace,
                fields = mapOf("durationMs" to android.os.SystemClock.elapsedRealtime() - startedAt))
        } catch (e: kotlinx.coroutines.CancellationException) {
            AppLogger.event(LogModule.ACTION, "action_cancelled", LogLevel.WARN, trace)
            throw e
        } catch (e: Exception) {
            AppLogger.event(LogModule.ACTION, "action_failed", LogLevel.ERROR, trace, error = e)
            throw e
        } finally {
            TriggerSide.take()
        }
    }

    private suspend fun executeAction() {
        when (event) {
            GestureActionType.OPEN_APP -> {
                pkg?.let {
                    launcherAPP(it)
                }
            }

            GestureActionType.FREEZE_APP -> {
                AShizuku.operate(pkg ?: return, false, context)
            }

            GestureActionType.RECENT_APP -> {
                if (isNotification) {
                    produceNotificationEvent(NotificationEvent(pkg ?: return, freeform))
                } else {
                    launcherAPP(pkg ?: return)
                }
            }

            GestureActionType.SCREEN_CONTENT -> {
                produceInspireEvent(InspireEvent(side = TriggerSide.peek()))
            }

            GestureActionType.SCREEN_CONTENT_FULLSCREEN -> {
                produceInspireEvent(
                    InspireEvent(
                        fullscreen = true,
                        forceImageTextSelection = true,
                        side = TriggerSide.peek(),
                    )
                )
            }

            GestureActionType.SCREEN_SEARCH -> {
                produceScreenSearchEvent(ScreenSearchEvent(show = true, side = TriggerSide.peek()))
            }

            GestureActionType.OTHER_LINK, GestureActionType.OTHER_SHORTCUT -> {
                launchUri(otherLink ?: return, pkg ?: "")
            }

            GestureActionType.OPEN_ACTIVITY -> {
                val isExported: Boolean = link.getBooleanQueryParameter("isExported", false)
                val activityName: String? = link.getQueryParameter("activityName")
                if (pkg != null && activityName != null && !isExported) {
                    if (freeform) {
                        launcherAPP(pkg, countOpen = false)
                    }
                    ensureAppEnabledIfFrozen(pkg, context)
                    val launched = if (!launchTemporaryAssistant(context, pkg, activityName)) {
                        launchUri(otherLink ?: return, pkg)
                    } else {
                        true
                    }
                    if (launched) {
                        recordOpenCount(pkg)
                    }
                } else {
                    launchUri(otherLink ?: return, pkg ?: "")
                }
            }

            GestureActionType.OPEN_SHELL -> {
                launchShell(shell ?: return)
            }

            GestureActionType.KEYBOARD -> {
                produceKeyboardEvent(event = KeyboardEvent(show = true, side = TriggerSide.peek()))
            }

            GestureActionType.SEARCH -> {
                QuickSearchActivity.start(
                    context,
                    query = link.getQueryParameter("query"),
                    side = TriggerSide.peek()
                )
            }

            GestureActionType.ACC_ACTION -> {
                val action = link.getQueryParameter("accAction")?.toInt() ?: return
                if (action == AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) {
                    // Allow time for the pie menu to dismiss before capturing the screen.
                    delay(500)
                }
                produceAccessibilityActionEvent(AccessibilityActionEvent(action))
            }

            GestureActionType.CLIPBOARD_ACTION -> {
                val action = link.getQueryParameter("clipAction")
                context.startActivity(
                    Intent(
                        context,
                        ShareOpenAppProxyActivity::class.java
                    ).apply {
                        addFlags(FLAG_ACTIVITY_NEW_TASK)
                        putExtra("clipAction", action)
                    })
            }

            GestureActionType.FREEFORM_FOREGROUND -> {
                val currentPkg = CurrentPackageProvider.resolveCurrentPackageName() ?: return
                if (isLauncherPackage(currentPkg)) return
                freeform = true
                launcherAPP(currentPkg)
            }

            GestureActionType.POINTER -> {
                producePointerEvent(PointerEvent(enable = true, needShowTouchView = !timeout, side = TriggerSide.peek()))
            }

            GestureActionType.PIN_POOL -> {
                producePinPoolEvent(PinPoolEvent(show = true, side = TriggerSide.take()))
            }

            GestureActionType.TEMP_DND -> {
                val minutes = link.getQueryParameter("minutes")?.toIntOrNull() ?: 15
                TempDndManager.enableTempDnd(context, minutes)
            }

            GestureActionType.SHOW_ACTION_VIEW -> {
                val selectedActionItemsTag = link.getQueryParameter("actionItemsTag") ?: return
                val selectedTriggerTag = link.getQueryParameter("triggerTag")
                val triggerConfig = selectedTriggerTag?.takeIf { it.isNotBlank() }?.let {
                    FreeTriggerDataManager.getTrigger(context, it)
                }
                val isMirror = link.getQueryParameter("isMirror")?.toBooleanStrictOrNull() == true &&
                        triggerConfig?.getState(FreeTriggerConfig.FLAG_MIRROR) == true
                produceActionViewEvent(
                    ActionViewEvent(
                        show = true,
                        workMode = ActionView.WorkMode.ACTIVE,
                        mainItemListTag = selectedActionItemsTag,
                        triggerConfig = triggerConfig,
                        isMirror = isMirror
                    )
                )
            }
            GestureActionType.CLICK_THROUGH -> {}
            GestureActionType.NONE -> {}
        }

    }

    private suspend fun launcherAPP(pkg: String, countOpen: Boolean = true): Boolean =
        withContext(Dispatchers.Default) {
            ensureAppEnabledIfFrozen(pkg, context)
            val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: let {
                AppLogger.event(LogModule.ACTION, "launch_intent_missing", LogLevel.WARN, trace,
                    mapOf("package" to pkg))
                return@withContext false
            }
            val useMiFreeForm = context.settingsStore.readKey(KEY_USE_MI_WINDOW, false)
            val launched = when {
                freeform && useMiFreeForm -> launchIntentMi(intent)
                userId > 0 -> launchAndroidForWork(intent.component)
                else -> launchIntent(intent)
            }
            if (launched && countOpen) {
                recordOpenCount(pkg)
            }
            launched
        }

    private suspend fun launchUri(uri: String, pkgName: String): Boolean {
        try {
            if (pkgName.isNotEmpty()) {
                ensureAppEnabledIfFrozen(pkgName, context)
            }
            val launchUri = UrlDetector.normalizeForOpening(uri) ?: uri
            val intent = Intent.parseUri(launchUri, Intent.URI_ALLOW_UNSAFE)
            AppLogger.event(LogModule.ACTION, "shortcut_parsed", LogLevel.DEBUG, trace,
                mapOf("package" to (intent.component?.packageName ?: pkgName), "component" to intent.component?.flattenToShortString()))
            intent.addFlags(FLAG_ACTIVITY_NEW_TASK)
            if (pkgName.isNotEmpty()) {
                intent.setPackage(pkgName)
            }
            val launched = if (useCustomTabs) {
                launchCustomTabsIntent(intent, pkgName) || launchIntent(intent)
            } else {
                launchIntent(intent)
            }
            if (launched) {
                val targetPkg = pkgName.ifBlank { extractPackageFromUri(launchUri) ?: "" }
                recordOpenCount(targetPkg)
            }
            return launched
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.event(LogModule.ACTION, "shortcut_launch_failed", LogLevel.ERROR, trace, error = e)
        }
        return false
    }

    private suspend fun launchCustomTabsIntent(intent: Intent, pkgName: String): Boolean {
        if (intent.action != Intent.ACTION_VIEW) return false
        val uri = intent.data ?: return false
        if (!uri.isWebScheme()) return false

        val customTabsPackage = resolveCustomTabsPackage(pkgName) ?: return false
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        customTabsIntent.intent.apply {
            data = uri
            setPackage(customTabsPackage)
            addFlags(FLAG_ACTIVITY_NEW_TASK)
        }
        return launchIntent(customTabsIntent.intent)
    }

    private fun resolveCustomTabsPackage(requestedPackage: String): String? {
        if (requestedPackage.isNotBlank()) {
            return requestedPackage.takeIf(::supportsCustomTabs)
        }

        val viewIntent = Intent(Intent.ACTION_VIEW, "https://www.example.com".toUri())
        val activities = packageManagerCompat().queryIntentActivities(
            viewIntent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
        return activities
            .asSequence()
            .map { it.activityInfo.packageName }
            .distinct()
            .firstOrNull(::supportsCustomTabs)
    }

    private fun supportsCustomTabs(packageName: String): Boolean {
        val serviceIntent = Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION).apply {
            `package` = packageName
        }
        return packageManagerCompat().queryIntentServices(serviceIntent, 0).isNotEmpty()
    }

    private fun packageManagerCompat() = context.packageManager

    private fun Uri.isWebScheme(): Boolean {
        return scheme.equals("http", ignoreCase = true) ||
                scheme.equals("https", ignoreCase = true)
    }

    private suspend fun launchShell(shell: String) {
        if (!AShell.doCmd(context, shell.removePrefix("adb ").removePrefix("shell ")).first) {
            ToastHelper.showInUIThread(
                context,
                context.getString(R.string.root_shizuku_permission_failed)
            )
        }
    }

    private suspend fun launchAndroidForWork(componentName: ComponentName?): Boolean =
        withContext(Dispatchers.Default) {
            val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
            val launcherApps =
                context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            try {
                AppLogger.event(LogModule.ACTION, "launch_requested", trace = trace,
                    fields = mapOf("method" to "work_profile", "component" to componentName?.flattenToShortString()))
                launcherApps.startMainActivity(
                    componentName,
                    userManager.getUserForSerialNumber(userId),
                    null,
                    if (freeform) getActivityOptions().toBundle() else null
                )
                AppLogger.event(LogModule.ACTION, "launch_request_submitted", trace = trace,
                    fields = mapOf("method" to "work_profile"))
                return@withContext true
            } catch (e: Exception) {
                AppLogger.event(LogModule.ACTION, "launch_failed", LogLevel.ERROR, trace, error = e)
            }
            return@withContext false
        }

    private suspend fun launchIntent(intent: Intent): Boolean =
        withContext(Dispatchers.Default) {
            if (newWindow) {
                intent.addFlags(FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            } else if (freeform) {
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val target = intent.component?.packageName ?: intent.`package`
            SystemLogCapture.rememberTarget(target)
            val metadata = mapOf("package" to target, "component" to intent.component?.flattenToShortString(),
                "action" to intent.action, "flags" to intent.flags, "freeform" to freeform,
                "foreground" to AppLogger.foreground, "method" to if (freeform) "freeform" else "startActivity")
            AppLogger.event(LogModule.ACTION, "launch_requested", trace = trace, fields = metadata)
            try {
                if (freeform) {
                    when (getFreeFormCode(context)) {
                        RomFreeFormCode.VIVO.code -> shareOpenIntent(intent)
                        RomFreeFormCode.NUBIA.code -> startNubiaFreeformActivity(context, intent)
                        else -> context.startActivity(intent, getActivityOptions().toBundle())
                    }
                } else {
                    context.startActivity(intent)
                }
                AppLogger.event(LogModule.ACTION, "launch_request_submitted", trace = trace, fields = metadata)
                return@withContext true
            } catch (ignored: ActivityNotFoundException) {
                AppLogger.event(LogModule.ACTION, "activity_not_found", LogLevel.ERROR, trace, metadata, ignored)
            } catch (ignored: SecurityException) {
                AppLogger.event(LogModule.ACTION, "launch_permission_denied", LogLevel.ERROR, trace, metadata, ignored)
            }
            return@withContext false
        }

    private suspend fun launchIntentMi(intent: Intent): Boolean =
        withContext(Dispatchers.Default) {
            val intentV2 = Intent("com.sunshine.freeform.start_freeform").apply {
                setPackage("com.sunshine.freeform")
                // Package name to launch in freeform mode (e.g. com.sunshine.freeform)
                putExtra("packageName", intent.`package`)
                // Activity name to launch in freeform mode; note that it may need to be exported.
                putExtra("activityName", intent.component?.className)
                // Optional target userId (default 0). Can specify userId for cloned/dual app environments.
                if (userId > -1) {
                    putExtra("userId", userId)
                }
            }
            context.sendBroadcast(intentV2)

            val intentV1 = Intent("com.sunshine.freeform.start_by_mi_freeform")
            intentV1.setPackage("com.sunshine.freeform")
            intentV1.putExtra("packageName", intent.`package`)
            intentV1.putExtra("activityName", intent.component?.flattenToString())
            context.sendBroadcast(intentV1)
            AppLogger.event(LogModule.ACTION, "launch_broadcast_submitted", trace = trace,
                fields = mapOf("method" to "mi_freeform", "package" to intent.`package`))
            true
        }

    private fun extractPackageFromUri(uri: String): String? {
        return runCatching {
            val intent = Intent.parseUri(uri, Intent.URI_ALLOW_UNSAFE)
            intent.`package` ?: intent.component?.packageName
        }.getOrNull()
    }

    private suspend fun recordOpenCount(pkgName: String?) {
        val targetPkg = pkgName?.takeIf { it.isNotBlank() } ?: return
        val model = getAPPDao().findByPkg(targetPkg) ?: return
        model.openCount += 1
        model.lastUsageTime = System.currentTimeMillis()
        updateDB(model)
    }

    suspend fun getActivityOptions(): ActivityOptions {
        return buildFreeformActivityOptions(context)
    }

    suspend fun shareOpenIntent(
        targetIntent: Intent,
        timeoutMillis: Long = 10_000L  // 10-second timeout
    ): ShareOpenResult {
        val requestId = UUID.randomUUID().toString()

        val intent = Intent(Intent.ACTION_SEND).apply {
            component = ComponentName(context, ShareOpenAppProxyActivity::class.java)
            type = "text/plain"
            putExtra("REQUEST_ID", requestId)
            putExtra(Intent.EXTRA_INTENT, targetIntent)
            putExtra(Intent.EXTRA_TEXT, targetIntent.`package`)
            addFlags(FLAG_ACTIVITY_NEW_TASK)
        }

        val chooser = Intent.createChooser(intent, null).apply {
            addFlags(FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(chooser)

        // Await result with timeout
        return withTimeoutOrNull(timeoutMillis) {
            ShareOpenResultManager.awaitResult(requestId)
        } ?: ShareOpenResult(
            requestId = requestId,
            success = false,
            data = null
        )
    }

    suspend fun launchTemporaryAssistant(context: Context, pkg: String, activity: String): Boolean =
        withContext(Dispatchers.IO) {
            val searchManager =
                context.getSystemService(Context.SEARCH_SERVICE) as? android.app.SearchManager
            val currentAssistant = Settings.Secure.getString(context.contentResolver, "assistant")

            val targetComponent = ComponentName(
                pkg,
                activity
            )
            val replacedAssistant = targetComponent.flattenToString()

            runCatching {
                allowReflectionLaunchAssist()
                // Temporarily replace system assistant
                Settings.Secure.putString(context.contentResolver, "assistant", replacedAssistant)

                // Launch assistant
                searchManager?.let {
                    val method =
                        searchManager.javaClass.getMethod("launchAssist", Bundle::class.java)
                    method.invoke(searchManager, Bundle())

                    // Brief delay before restoring
                    delay(100)
                    Settings.Secure.putString(
                        context.contentResolver,
                        "assistant",
                        currentAssistant
                    )
                    return@withContext true
                }

                return@withContext false
            }.onFailure { e ->
                runCatching {
                    Settings.Secure.putString(
                        context.contentResolver,
                        "assistant",
                        currentAssistant
                    )
                }
            }

            return@withContext false
        }

    companion object {
        private const val NUBIA_FREEFORM_IDENTIFIER = "_WindowReply"
        private var activityOptionsAPIPass = false
        private var launchAssistAPIPass = false

        /**
         * Nubia / RedMagic freeform protocol requires both identifier and Bundle.
         * The Bundle must not be omitted even if it has no parameters.
         */
        fun startNubiaFreeformActivity(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                intent.identifier = NUBIA_FREEFORM_IDENTIFIER
            }
            context.startActivity(intent, Bundle())
        }

        suspend fun buildFreeformActivityOptions(context: Context, side: String? = null): ActivityOptions {
            if (side != null) {
                TriggerSide.record(side)
            }
            val options = ActivityOptions.makeBasic()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    allowReflectionActivityOptions(options)
                }
                val method = ActivityOptions::class.java.getMethod(
                    "setLaunchWindowingMode",
                    Int::class.javaPrimitiveType
                )
                method.invoke(options, getFreeFormCode(context))
                if (isHuawei(context)) {
                    val setLaunchStackIdtMethod = ActivityOptions::class.java.getMethod(
                        "setLaunchStackId",
                        Int::class.javaPrimitiveType
                    )
                    setLaunchStackIdtMethod.invoke(options, 2)
                }
            } catch (ignored: Exception) {
            }

            try {
                options.launchBounds = FreeFormUtils.getRect(context, context.settingsStore)
            } finally {
                if (side != null) {
                    TriggerSide.take()
                }
            }

            return options
        }

        fun allowReflectionLaunchAssist() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
            if (launchAssistAPIPass) return
            HiddenApiBypass.addHiddenApiExemptions("Landroid/app/SearchManager")
            launchAssistAPIPass = true
        }

        fun allowReflectionActivityOptions(options: ActivityOptions?) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
            if (activityOptionsAPIPass) return
            try {
                val method = ActivityOptions::class.java.getMethod(
                    "setLaunchWindowingMode",
                    Int::class.javaPrimitiveType
                )
                method.invoke(options, 5)
            } catch (e: NoSuchMethodException) {
                HiddenApiBypass.addHiddenApiExemptions("Landroid/app/ActivityOptions")
            } catch (e: IllegalAccessException) {
                e.printStackTrace()
            } catch (e: InvocationTargetException) {
                e.printStackTrace()
            }
            activityOptionsAPIPass = true
        }
    }

    object ShareOpenResultManager {
        private val _resultFlow = MutableSharedFlow<ShareOpenResult>(
            replay = 0,
            extraBufferCapacity = 1
        )

        suspend fun awaitResult(requestId: String): ShareOpenResult {
            return _resultFlow.first { it.requestId == requestId }
        }

        fun sendResult(result: ShareOpenResult) {
            _resultFlow.tryEmit(result)
        }
    }

    data class ShareOpenResult(
        val requestId: String,
        val success: Boolean,
        val data: Intent? = null
    )
}

fun uriBuilder(type: GestureActionType): Uri.Builder {
    return Uri.Builder().scheme("gesture-evo").authority("action")
        .appendPath(type.toString())
}
