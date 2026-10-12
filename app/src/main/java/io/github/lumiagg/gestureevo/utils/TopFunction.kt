package io.github.lumiagg.gestureevo.utils


import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Resources.getSystem
import android.os.Build
import android.util.TypedValue


val Int.px2dp: Int get() = (this / getSystem().displayMetrics.density).toInt()
val Int.dp2px: Float get() = (this * getSystem().displayMetrics.density)
val Int.sp2px: Float
    get() = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        this.toFloat(),
        getSystem().displayMetrics
    )
val Float.px2dp: Int get() = (this / getSystem().displayMetrics.density).toInt()
val Float.dp2px: Float get() = (this * getSystem().displayMetrics.density)
val Double.px2dp: Int get() = (this / getSystem().displayMetrics.density).toInt()
val Double.dp2px: Float get() = (this * getSystem().displayMetrics.density).toFloat()

fun Int.getBit(x: Int): Boolean = (this shr x) and 1 == 1

fun Int.setBit(position: Int, value: Boolean): Int {
    if (position < 0 || position >= 32) {
        throw IllegalArgumentException("Position should be between 0 and 31 for Int type")
    }

    // Sets or clears the bit at the given position using bitwise operators.
    val mask = 1 shl position
    return if (value) {
        this or mask
    } else {
        this and mask.inv()
    }
}

fun getThemeColor(context: Context, resId: Int): Int {
    val value = TypedValue()
    context.theme.resolveAttribute(resId, value, true)
    return value.data
}

fun Float.coerceInSafe(minimumValue: Float, maximumValue: Float): Float {
    return when {
        minimumValue > maximumValue -> minimumValue
        this < minimumValue -> minimumValue
        this > maximumValue -> maximumValue
        else -> this
    }
}

fun Int.coerceInSafe(minimumValue: Int, maximumValue: Int): Int {
    return when {
        minimumValue > maximumValue -> minimumValue
        this < minimumValue -> minimumValue
        this > maximumValue -> maximumValue
        else -> this
    }
}

fun Context.createPendingIntent(intent: Intent): PendingIntent {
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    } else {
        PendingIntent.FLAG_UPDATE_CURRENT
    }

    return PendingIntent.getActivity(this, 0, intent, flags)
}
