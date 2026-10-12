package io.github.lumiagg.gestureevo.utils

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

object CurrentPackageProvider {
    private const val REFRESH_DEBOUNCE_MS = 500
    private const val TAG = "CurrentPackageProvider"

    private enum class PackageSource {
        WINDOWS,
        ACTIVE_ROOT,
        TRUSTED_EVENT,
        FALLBACK
    }

    private data class PackageSnapshot(
        val packageName: String?,
        val source: PackageSource
    )

    @Volatile
    private var serviceRef: WeakReference<AccessibilityService>? = null

    @Volatile
    private var lastTrustedPackageName: String? = null
    private val _currentPackageFlow = MutableStateFlow<String?>(null)
    val currentPackageFlow: StateFlow<String?> = _currentPackageFlow.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var refreshJob: Job? = null

    fun setService(service: AccessibilityService) {
        serviceRef = WeakReference(service)
        resolveCurrentPackageName()
    }

    fun accessibilityService(): AccessibilityService? = serviceRef?.get()

    fun clearService() {
        refreshJob?.cancel()
        refreshJob = null
        serviceRef?.clear()
        serviceRef = null
        updateCurrentPackage(PackageSnapshot(null, PackageSource.FALLBACK))
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        getTrustedWindowContext(event)?.let { (packageName, _) ->
            val previousPackage = _currentPackageFlow.value
            updateCurrentPackage(PackageSnapshot(packageName, PackageSource.TRUSTED_EVENT))
            if (packageName != previousPackage) {
                scheduleRefresh(packageName)
            }
        }
    }

    fun resolveCurrentPackageName(): String? {
        val service = serviceRef?.get() ?: return null
        val snapshot = resolveFromApplicationWindows(service)?.let {
            PackageSnapshot(it, PackageSource.WINDOWS)
        } ?: resolveFromActiveRoot(service)?.let {
            PackageSnapshot(it, PackageSource.ACTIVE_ROOT)
        } ?: PackageSnapshot(lastTrustedPackageName, PackageSource.FALLBACK)
        updateCurrentPackage(snapshot)
        return snapshot.packageName
    }

    fun refreshCurrentPackage(): String? {
        return resolveCurrentPackageName()
    }

    private fun updateCurrentPackage(snapshot: PackageSnapshot) {
        lastTrustedPackageName = snapshot.packageName
        if (_currentPackageFlow.value != snapshot.packageName) {
            _currentPackageFlow.value = snapshot.packageName
        }
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

    private fun scheduleRefresh(trustedPackage: String) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay(REFRESH_DEBOUNCE_MS.toLong())
            val resolvedPackage = refreshCurrentPackage()
            Log.d(
                TAG,
                "refreshCurrentPackage executed: trustedPackage=$trustedPackage resolvedPackage=$resolvedPackage"
            )
        }
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
