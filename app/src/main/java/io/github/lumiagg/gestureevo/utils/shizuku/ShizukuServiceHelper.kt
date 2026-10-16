package io.github.lumiagg.gestureevo.utils.shizuku

import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import io.github.lumiagg.gestureevo.diagnostics.LogLevel
import io.github.lumiagg.gestureevo.diagnostics.LogModule
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import io.github.lumiagg.gestureevo.BuildConfig
import io.github.lumiagg.gestureevo.IUserService
import io.github.lumiagg.gestureevo.utils.app.AShizuku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
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

    @Volatile private var _service: IUserService? = null
    val service get() = _service
    val isServiceBound get() = _service != null
    private val onServiceConnectedListeners = mutableListOf<() -> Unit>()
    private var bindingRequested = false

    init {
        Shizuku.addBinderDeadListener { onConnectionLost() }
    }

    private fun onConnectionLost() {
        AppLogger.event(
            LogModule.SHIZUKU, "service_disconnected",
            LogLevel.WARN)
        _service = null
        bindingRequested = false
        notifyConnectionListeners()
    }

    suspend fun awaitService(): IUserService = withContext(Dispatchers.Main) {
        service?.let { return@withContext it }
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
        .version(BuildConfig.VERSION_CODE + 2) // Diagnostic stream protocol changed; replace a previously bound user service.

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
        bindingRequested = false
        _service = null
        notifyConnectionListeners()
    }

    fun isSupported(): Boolean {
        return Shizuku.getVersion() >= 10
    }
}
