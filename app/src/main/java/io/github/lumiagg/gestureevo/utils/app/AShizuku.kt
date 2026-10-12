package io.github.lumiagg.gestureevo.utils.app

import android.content.Context
import android.content.pm.PackageManager
import android.system.Os
import android.util.Log
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.utils.ToastHelper
import io.github.lumiagg.gestureevo.utils.shizuku.ShizukuServiceHelper
import io.github.lumiagg.gestureevo.utils.shizuku.awaitPermissionDecision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

object AShizuku {
    private const val TAG = "AShizuku"
    private const val PERMISSION_REQUEST_CODE = 0
    private val permissionMutex = Mutex()

    suspend fun doCmdWithShizuku(command: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            val service = ShizukuServiceHelper.awaitService()
            val result = service.execute(mutableListOf("/system/bin/sh", "-c", command), null, null)
            result.isSuccess to if (result.isSuccess) {
                result.output.orEmpty()
            } else {
                result.error?.takeIf { it.isNotEmpty() } ?: result.output ?: "Unknown error"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku command failed", e)
            false to (e.message ?: "Unknown error")
        }
    }

    suspend fun operate(packageName: String, enable: Boolean, context: Context): Boolean {
        if (!checkPermission(context)) {
            ToastHelper.showInUIThread(context, context.getString(R.string.shizuku_call_failed))
            return false
        }
        if (context.getAppEnabledState(packageName) == null) return false
        val command = packageStateCommand(packageName, enable, Os.getuid() / ANDROID_UIDS_PER_USER)
        val (success, _) = doCmdWithShizuku(command)
        return success && context.getAppEnabledState(packageName) == enable
    }

    suspend fun ensureAppEnabledIfFrozen(packageName: String, context: Context) {
        if (context.getAppEnabledState(packageName) == false) operate(packageName, true, context)
    }

    suspend fun checkPermission(context: Context): Boolean = permissionMutex.withLock {
        // Main dispatching also prevents listener cleanup from modifying Shizuku's callback list
        // inside the library's permission-result dispatch loop.
        withContext(Dispatchers.Main) {
            try {
                when {
                    Shizuku.isPreV11() -> false
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> true
                    Shizuku.shouldShowRequestPermissionRationale() -> {
                        ToastHelper.showInUIThread(context, context.getString(R.string.shizuku_call_failed))
                        false
                    }
                    else -> {
                        ToastHelper.showInUIThread(context, context.getString(R.string.shizuku_request_permission))
                        awaitPermissionDecision(
                            subscribe = { complete ->
                                val listener = Shizuku.OnRequestPermissionResultListener { code, grant ->
                                    if (code == PERMISSION_REQUEST_CODE) complete(grant == PackageManager.PERMISSION_GRANTED)
                                }
                                val onDeath = Shizuku.OnBinderDeadListener { complete(false) }
                                Shizuku.addRequestPermissionResultListener(listener)
                                Shizuku.addBinderDeadListener(onDeath)
                                val unsubscribe: () -> Unit = {
                                    Shizuku.removeRequestPermissionResultListener(listener)
                                    Shizuku.removeBinderDeadListener(onDeath)
                                    Unit
                                }
                                unsubscribe
                            },
                            request = { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) },
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Unable to obtain Shizuku permission", e)
                false
            }
        }
    }
}
