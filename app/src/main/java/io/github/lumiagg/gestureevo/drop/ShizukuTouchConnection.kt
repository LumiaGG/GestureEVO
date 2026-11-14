package io.github.lumiagg.gestureevo.drop

import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import io.github.lumiagg.gestureevo.IUserService
import io.github.lumiagg.gestureevo.ITouchInterruptionGuard
import io.github.lumiagg.gestureevo.utils.shizuku.ShizukuServiceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.UUID

/** Permission requests belong to explicit UI actions; this backend only uses an existing grant. */
internal class ShizukuTouchConnection : TouchInputConnection {
    private val token = UUID.randomUUID().toString()
    private val owner = Binder()
    private val death = IBinder.DeathRecipient { close() }
    private var closed = false
    private var remote: IUserService? = null
    private var descriptor: ParcelFileDescriptor? = null

    override suspend fun open(): ParcelFileDescriptor = withContext(Dispatchers.IO) {
        check(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { "Shizuku permission unavailable" }
        val shell = ShizukuServiceHelper.awaitService()
        currentCoroutineContext().ensureActive()
        synchronized(this@ShizukuTouchConnection) {
            check(!closed) { "Shizuku input cancelled" }
            remote = shell
            shell.asBinder().linkToDeath(death, 0)
        }
        val pipe = shell.openTouchStream(token, owner)
        synchronized(this@ShizukuTouchConnection) {
            if (closed) {
                pipe.close()
                error("Shizuku input cancelled")
            }
            descriptor = pipe
        }
        pipe
    }

    override fun close() {
        val shell = synchronized(this) {
            if (closed) return
            closed = true
            runCatching { descriptor?.close() }
            descriptor = null
            remote.also { remote = null }
        }
        runCatching { shell?.asBinder()?.unlinkToDeath(death, 0) }
        runCatching { shell?.closeTouchStream(token) }
    }

    suspend fun interrupt(sourcePackage: String, x: Float, y: Float, displayId: Int,
        deadlineUptimeMs: Long, guard: ITouchInterruptionGuard): Boolean = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val shell = synchronized(this@ShizukuTouchConnection) { if (closed) null else remote }
            ?: return@withContext false
        shell.interruptTouchForDrag(token, sourcePackage, x, y, displayId, deadlineUptimeMs, guard)
    }
}
