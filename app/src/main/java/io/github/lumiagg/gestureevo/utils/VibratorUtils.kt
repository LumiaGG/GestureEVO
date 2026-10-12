package io.github.lumiagg.gestureevo.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

object VibratorUtils {
    var vibrator: Vibrator? = null

    @SuppressLint("MissingPermission")
    fun vibrator(context: Context, isDouble: Boolean) {
        if (vibrator == null) {
            vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        vibrator?.cancel()

        when {
            isDouble -> createDoubleVibration()
            else -> createSingleVibration()
        }
    }

    private fun createDoubleVibration() {
        vibrator?.let { v ->
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                }

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    v.vibrate(
                        VibrationEffect.createWaveform(
                            longArrayOf(16L, 16L),
                            intArrayOf(255, 255),
                            -1
                        )
                    )
                }

                else -> {
                    @Suppress("DEPRECATION")
                    v.vibrate(longArrayOf(16L, 16L), -1)
                }
            }
        }
    }

    private fun createSingleVibration() {
        vibrator?.let { v ->
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
                }

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    v.vibrate(VibrationEffect.createOneShot(13, 255))
                }

                else -> {
                    @Suppress("DEPRECATION")
                    v.vibrate(13)
                }
            }
        }
    }
}
