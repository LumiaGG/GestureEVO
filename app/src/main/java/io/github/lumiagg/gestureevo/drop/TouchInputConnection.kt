package io.github.lumiagg.gestureevo.drop

import android.os.ParcelFileDescriptor
import java.io.Closeable

/** Owns one backend attempt, including cancellation while its stream is opening. */
internal interface TouchInputConnection : Closeable {
    suspend fun open(): ParcelFileDescriptor
}
