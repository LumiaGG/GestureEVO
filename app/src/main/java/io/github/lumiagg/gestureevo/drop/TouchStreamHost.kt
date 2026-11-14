package io.github.lumiagg.gestureevo.drop

import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.StructPollfd
import android.system.OsConstants
import android.system.Os
import io.github.lumiagg.gestureevo.utils.process.ProcessCommandRunner
import io.github.lumiagg.gestureevo.utils.process.ProcessStreamRelay
import java.io.Closeable
import android.os.SystemClock
import io.github.lumiagg.gestureevo.ITouchInterruptionGuard

/** Privileged touch worker: owns discovery, stream tokens, owner death and lease cleanup. */
internal class TouchStreamHost : Closeable {
    private var touchStream: ProcessStreamRelay? = null
    private var touchToken: String? = null
    private var touchOwner: IBinder? = null
    private var touchDeath: IBinder.DeathRecipient? = null
    private val cancelledTouchStreams = LinkedHashSet<String>()
    private val interrupter = ShizukuTouchInterrupter()

    @Synchronized
    fun open(token: String, owner: IBinder): ParcelFileDescriptor {
        require(token.matches(Regex("[a-f0-9-]{36}")))
        check(token !in cancelledTouchStreams)
        close(touchToken.orEmpty())
        val inventory = runCatching {
            val (code, output) = ProcessCommandRunner.run(listOf("/system/bin/cat", "/proc/bus/input/devices"))
            if (code == 0) output else ""
        }.getOrDefault("")
        val (rangeCode, ranges) = ProcessCommandRunner.run(listOf("/system/bin/getevent", "-lp"))
        check(rangeCode == 0) { "Touch device discovery failed: getevent exit=$rangeCode" }
        val device = checkNotNull(TouchInputProtocol.discover(inventory, ranges)) { "No readable multitouch screen" }
        val pipe = ParcelFileDescriptor.createPipe()
        val output = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        try {
            val death = IBinder.DeathRecipient { close(token) }
            owner.linkToDeath(death, 0)
            touchOwner = owner
            touchDeath = death
            touchToken = token
            output.write("EVO_TOUCH ${device.minX} ${device.maxX} ${device.minY} ${device.maxY}\n".toByteArray())
            touchStream = ProcessStreamRelay(
                TouchStreamProcess.arguments(device.path), output,
                durationMs = null, byteLimit = null, stdinLease = true,
                leaseHeartbeatMs = TouchStreamProcess.HEARTBEAT_MS,
                receiverClosed = {
                    val descriptor = StructPollfd().apply { fd = pipe[1].fileDescriptor; events = 0 }
                    Os.poll(arrayOf(descriptor), 0)
                    descriptor.revents.toInt() and (OsConstants.POLLERR or
                        OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0
                })
            return pipe[0]
        } catch (error: Exception) {
            pipe.forEach { runCatching { it.close() } }
            close(token)
            throw error
        }
    }

    @Synchronized
    fun close(token: String) {
        cancelledTouchStreams.add(token)
        while (cancelledTouchStreams.size > 16) cancelledTouchStreams.remove(cancelledTouchStreams.first())
        if (token != touchToken) return
        touchStream?.close()
        touchStream = null
        touchToken = null
        touchDeath?.let { death -> runCatching { touchOwner?.unlinkToDeath(death, 0) } }
        touchDeath = null
        touchOwner = null
    }

    @Synchronized
    fun interrupt(token: String, sourcePackage: String, x: Float, y: Float, displayId: Int,
        deadlineUptimeMs: Long, guard: ITouchInterruptionGuard): Boolean {
        fun current(): Boolean = token == touchToken && touchOwner?.isBinderAlive == true &&
            touchStream?.isProcessAlive() == true && guard.asBinder().isBinderAlive &&
            deadlineUptimeMs - SystemClock.uptimeMillis() in 1L..500L &&
            runCatching { guard.isCurrent }.getOrDefault(false)
        if (!current()) return false
        return interrupter.interrupt(sourcePackage, x, y, displayId, ::current)
    }

    @Synchronized
    override fun close() = close(touchToken.orEmpty())
}
