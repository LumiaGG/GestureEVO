package io.github.lumiagg.gestureevo.utils.shizuku

import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import io.github.lumiagg.gestureevo.diagnostics.LogLevel
import io.github.lumiagg.gestureevo.diagnostics.LogModule
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import io.github.lumiagg.gestureevo.BuildConfig
import io.github.lumiagg.gestureevo.IUserService
import io.github.lumiagg.gestureevo.utils.app.AShizuku
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs

suspend fun initShizukuService(context: Context) {
    withContext(Dispatchers.Main) {
        if (AShizuku.checkPermission(context)) {
            try {
                ShizukuServiceHelper.bindUserService(null)
            } catch (e: Exception) {
                Log.e("App", "Failed to pre-bind Shizuku service", e)
            }
        }
    }
}

object ShizukuServiceHelper {
    private const val TAG = "ShizukuHelper"

    /**
     * Upper bound for one user service bind attempt. A user service whose server-side start
     * failed never reports back to the client (no connected/died callback exists for that
     * case), so waiting without a timeout would never end and freeze every later launch.
     */
    private const val BIND_TIMEOUT_MS = 10_000L

    @Volatile private var _service: IUserService? = null
    val service get() = _service
    val isServiceBound get() = _service != null
    private val onServiceConnectedListeners = mutableListOf<() -> Unit>()
    private var bindingRequested = false
    private val bindMutex = Mutex()

    init {
        Shizuku.addBinderDeadListener { onConnectionLost() }
        Shizuku.addBinderReceivedListenerSticky { prebindIfPermitted() }
    }

    /**
     * The Shizuku server pushes its binder to running apps on every start, so this fires again
     * after each server restart. Re-binding right away keeps the user service warm, instead of
     * paying the service start cost on the first frozen-app launch after a restart.
     */
    private fun prebindIfPermitted() {
        if (bindingRequested || isServiceBound) return
        runCatching {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bindUserService(null)
            }
        }.onFailure {
            Log.i(TAG, "Skip pre-bind on binder received: ${it.message}")
        }
    }

    private fun onConnectionLost() {
        AppLogger.event(
            LogModule.SHIZUKU, "service_disconnected",
            LogLevel.WARN)
        clearBinding()
    }

    private fun clearBinding() {
        _service = null
        bindingRequested = false
        notifyConnectionListeners()
    }

    suspend fun awaitService(): IUserService = withContext(Dispatchers.Main) {
        usableServiceOrNull()?.let { return@withContext it }
        bindMutex.withLock {
            usableServiceOrNull()?.let { return@withLock it }
            try {
                bindAndAwait(BIND_TIMEOUT_MS)
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                AppLogger.event(
                    LogModule.SHIZUKU, "bind_timeout",
                    LogLevel.WARN, error = e)
                Log.w(TAG, "Shizuku user service bind timed out; dropping stale binding and retrying once", e)
                resetStaleBinding()
                try {
                    bindAndAwait(BIND_TIMEOUT_MS)
                } catch (retry: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    resetStaleBinding()
                    throw IllegalStateException("Shizuku user service did not connect within ${BIND_TIMEOUT_MS} ms", retry)
                }
            }
        }
    }

    /** Returns the cached service only when its binder is still alive; clears stale bindings. */
    private fun usableServiceOrNull(): IUserService? {
        val cached = _service ?: return null
        if (cached.asBinder().pingBinder()) return cached
        AppLogger.event(
            LogModule.SHIZUKU, "service_binder_dead",
            LogLevel.WARN)
        Log.w(TAG, "Cached Shizuku user service binder is dead without a death callback")
        clearBinding()
        return null
    }

    private suspend fun bindAndAwait(timeoutMs: Long): IUserService = withTimeout(timeoutMs) {
        val result = CompletableDeferred<IUserService>()
        val onBound = {
            val connected = service
            if (connected == null) result.completeExceptionally(IllegalStateException("Shizuku service disconnected"))
            else result.complete(connected)
            Unit
        }
        try {
            bindUserService(onBound)
            result.await()
        } finally {
            onServiceConnectedListeners.remove(onBound)
        }
    }

    /**
     * Drops a binding that can never complete. Removing the user service on the server side
     * also deletes the record whose start failed, so the next bind spawns a fresh service.
     */
    private fun resetStaleBinding() {
        runCatching {
            Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true)
        }.onFailure {
            Log.w(TAG, "Failed to drop the stale Shizuku user service record", it)
        }
        clearBinding()
    }

    private fun notifyConnectionListeners() {
        val listeners = onServiceConnectedListeners.toList()
        onServiceConnectedListeners.clear()
        listeners.forEach { it() }
    }

    private val userServiceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
            if (!binder.pingBinder()) {
                Log.e(TAG, "onServiceConnected: invalid binder for $componentName received")
                onConnectionLost()
                return
            }
            _service = IUserService.Stub.asInterface(binder)
            AppLogger.event(
                LogModule.SHIZUKU, "service_connected")
            notifyConnectionListeners()
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            onConnectionLost()
        }

        override fun onNullBinding(componentName: ComponentName) {
            onConnectionLost()
        }

        override fun onBindingDied(componentName: ComponentName) {
            onConnectionLost()
        }
    }

    private val userServiceArgs = UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, UserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("service")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE + 5) // Touch workers now have an EOF lease and an independent native deadline.

    fun bindUserService(onBound: (() -> Unit)?) {
        if (isServiceBound) {
            onBound?.invoke()
            return
        }
        if (!isSupported()) {
            throw RuntimeException("Current Shizuku version is not supported: ${Shizuku.getVersion()}")
        }
        if (onBound != null) {
            onServiceConnectedListeners.add(onBound)
        }
        if (bindingRequested) return
        bindingRequested = true
        runCatching {
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
        }.onFailure {
            bindingRequested = false
            if (onBound != null) {
                onServiceConnectedListeners.remove(onBound)
            }
            throw it
        }
    }

    fun unbindUserService() {
        if (!bindingRequested) return
        if (!isSupported()) {
            throw RuntimeException("Current Shizuku version is not supported: ${Shizuku.getVersion()}")
        }
        Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true)
        clearBinding()
    }

    fun isSupported(): Boolean {
        return Shizuku.getVersion() >= 10
    }
}
