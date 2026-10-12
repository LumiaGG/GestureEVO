package io.github.lumiagg.gestureevo.inspire

import android.graphics.Bitmap
import android.os.Build
import java.io.Closeable

/**
 * Reference-counted bitmap handle used for large images shared across Inspire flows.
 */
class ManagedBitmap private constructor(
    private val state: State
) : Closeable {

    private var closed = false

    val bitmapOrNull: Bitmap?
        get() = synchronized(state.lock) {
            state.bitmap?.takeIf { !it.isRecycled }
        }

    fun requireBitmap(): Bitmap = checkNotNull(bitmapOrNull) {
        "Bitmap has already been released"
    }

    fun acquire(): ManagedBitmap? = synchronized(state.lock) {
        val bitmap = state.bitmap
        if (bitmap == null || bitmap.isRecycled) {
            null
        } else {
            state.refCount += 1
            ManagedBitmap(state)
        }
    }

    override fun close() {
        synchronized(state.lock) {
            if (closed) return
            closed = true
            state.refCount -= 1
            if (state.refCount <= 0) {
                state.bitmap?.takeIf { !it.isRecycled }?.recycle()
                state.bitmap = null
                state.refCount = 0
            }
        }
    }

    private class State(
        var bitmap: Bitmap?,
        var refCount: Int,
        val lock: Any = Any(),
    )

    companion object {
        fun from(bitmap: Bitmap): ManagedBitmap = ManagedBitmap(State(bitmap = bitmap, refCount = 1))
    }
}

inline fun <T> Bitmap.useSoftwareBitmap(block: (Bitmap) -> T): T {
    val needsSoftwareCopy =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            config == Bitmap.Config.HARDWARE || config == null
        } else {
            false
        }
    val temporaryBitmap: Bitmap? = if (needsSoftwareCopy) {
        try {
            copy(Bitmap.Config.ARGB_8888, false)
                ?: copy(Bitmap.Config.RGB_565, false)
        } catch (_: Throwable) {
            null
        }
    } else {
        null
    }
    val target = temporaryBitmap ?: this
    return try {
        block(target)
    } finally {
        temporaryBitmap?.takeIf { !it.isRecycled }?.recycle()
    }
}
