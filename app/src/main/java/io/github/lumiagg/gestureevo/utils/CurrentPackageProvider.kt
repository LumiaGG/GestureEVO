package io.github.lumiagg.gestureevo.utils

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

object CurrentPackageProvider {
    @Volatile
    private var serviceRef: WeakReference<AccessibilityService>? = null

    private val _serviceConnectedFlow = MutableStateFlow(false)
    val serviceConnectedFlow: StateFlow<Boolean> = _serviceConnectedFlow.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tracker = ForegroundPackageTracker(scope, resolvePackage = {
        serviceRef?.get()?.let { service ->
            resolveFromApplicationWindows(service) ?: resolveFromActiveRoot(service)
        }
    })
    val currentPackageFlow: StateFlow<String?> = tracker.currentPackage

    fun setService(service: AccessibilityService) {
        serviceRef = WeakReference(service)
        _serviceConnectedFlow.value = true
        resolveCurrentPackageName()
    }

    fun accessibilityService(): AccessibilityService? = serviceRef?.get()

    fun clearService() {
        serviceRef?.clear()
        serviceRef = null
        _serviceConnectedFlow.value = false
        tracker.clear()
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> tracker.onWindowsChanged()
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                tracker.onWindowStateChanged(getTrustedWindowContext(event)?.first)
        }
    }

    fun resolveCurrentPackageName(): String? {
        if (serviceRef?.get() == null) return null
        return tracker.refresh()
    }

    fun refreshCurrentPackage(): String? {
        return resolveCurrentPackageName()
    }

    fun getTrustedWindowContext(event: AccessibilityEvent): Pair<String, String>? {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return null
        }
        val packageName = event.packageName?.toString() ?: return null
        val className = event.className?.toString().orEmpty()
        if (!isTrustedWindowEvent(packageName, className)) return null
        return packageName to className
    }

    private fun resolveFromApplicationWindows(service: AccessibilityService): String? {
        return runCatching {
            service.windows
                .asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .sortedWith(
                    compareByDescending<AccessibilityWindowInfo> { it.isActive }
                        .thenByDescending { it.isFocused }
                        .thenByDescending { it.layer }
                )
                .mapNotNull { it.root?.packageName?.toString() }
                .firstOrNull(::isTrustedPackageName)
        }.getOrNull()
    }

    private fun resolveFromActiveRoot(service: AccessibilityService): String? {
        return runCatching {
            service.rootInActiveWindow?.packageName?.toString()
        }.getOrNull()?.takeIf(::isTrustedPackageName)
    }

    private fun isTrustedWindowEvent(packageName: String, className: String): Boolean {
        return isTrustedPackageName(packageName) && isActivityLikeClassName(className)
    }

    private fun isTrustedPackageName(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val selfPackageName = serviceRef?.get()?.packageName
        return !packageName.contains("inputmethod", ignoreCase = true) &&
                packageName != "android" &&
                packageName != "com.android.systemui" &&
                packageName != selfPackageName
    }

    private fun isActivityLikeClassName(className: String): Boolean {
        if (className.isBlank()) return false

        val nonActivityTypes = listOf(
            "PopupWindow",
            "Dialog",
            "AlertDialog",
            "Toast",
            "PopupMenu",
            "DropDownListView"
        )
        if (nonActivityTypes.any { className.contains(it, ignoreCase = true) }) {
            return false
        }

        return className.contains("Activity", ignoreCase = true) ||
                className.contains("Fragment", ignoreCase = true) ||
                !className.startsWith("android.", ignoreCase = true)
    }
}
