package io.github.lumiagg.gestureevo.utils

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 系统级触感反馈辅助工具（LinkGo util/HapticHelper.kt 1:1 移植）
 * 解决悬浮窗在 FLAG_NOT_FOCUSABLE 或 Accessibility Overlay 下
 * 系统 ViewRootImpl 判定窗口无输入焦点而静默丢弃 performHapticFeedback 的问题。
 *
 * 针对高频扫掠与连续碰撞场景深度优化：
 * - 缓存 Vibrator 实例，消除 Binder 获取开销
 * - 击发前瞬态 cancel()，清除余震残波，杜绝拖沓粘连
 * - Android 11+ 优先走 USAGE_TOUCH 低延迟硬件原语 (PRIMITIVE_LOW_TICK / PRIMITIVE_TICK)
 * - 降级走超短 10ms 脉冲，杜绝厂商长缓波形
 */
object HapticHelper {

    private var cachedVibrator: Vibrator? = null

    /**
     * 极轻脆触感（用于手势摸到边缘、滑过链接瞬间，零延迟、绝不拖沓）
     */
    fun tick(context: Context, fallbackView: View? = null) {
        performVibration(context, VibrationType.TICK, fallbackView)
    }

    /**
     * 明确点击（用于常规按钮点击、选中状态变更）
     */
    fun click(context: Context, fallbackView: View? = null) {
        performVibration(context, VibrationType.CLICK, fallbackView)
    }

    /**
     * 沉稳确认（用于松手直达触发打开、重击操作）
     */
    fun heavyClick(context: Context, fallbackView: View? = null) {
        performVibration(context, VibrationType.HEAVY_CLICK, fallbackView)
    }

    private enum class VibrationType {
        TICK,
        CLICK,
        HEAVY_CLICK
    }

    private fun performVibration(context: Context, type: VibrationType, fallbackView: View?) {
        try {
            val vibrator = getVibrator(context)
            if (vibrator != null && vibrator.hasVibrator()) {
                // 立即刹车前序残余震动，保证新脉冲干净清脆击发
                vibrator.cancel()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val attrs = VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_TOUCH)
                        .build()

                    // Android 11+ 触控硬件级低延迟原语
                    if (type == VibrationType.TICK && vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)) {
                        val effect = VibrationEffect.startComposition()
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.75f)
                            .compose()
                        vibrator.vibrate(effect, attrs)
                        return
                    } else if (type == VibrationType.TICK && vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)) {
                        val effect = VibrationEffect.startComposition()
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.6f)
                            .compose()
                        vibrator.vibrate(effect, attrs)
                        return
                    } else if (type == VibrationType.CLICK && vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                        val effect = VibrationEffect.startComposition()
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f)
                            .compose()
                        vibrator.vibrate(effect, attrs)
                        return
                    }

                    // 短脉冲高敏波形 (10ms 零拖沓)
                    val (duration, amplitude) = when (type) {
                        VibrationType.TICK -> Pair(10L, 120)
                        VibrationType.CLICK -> Pair(18L, 180)
                        VibrationType.HEAVY_CLICK -> Pair(35L, 255)
                    }
                    vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude), attrs)
                    return
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val (duration, amplitude) = when (type) {
                        VibrationType.TICK -> Pair(10L, 120)
                        VibrationType.CLICK -> Pair(18L, 180)
                        VibrationType.HEAVY_CLICK -> Pair(35L, 255)
                    }
                    vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
                    return
                } else {
                    val duration = when (type) {
                        VibrationType.TICK -> 10L
                        VibrationType.CLICK -> 18L
                        VibrationType.HEAVY_CLICK -> 35L
                    }
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(duration)
                    return
                }
            }
        } catch (_: Throwable) {
            // 忽略 Vibrator 异常，继续尝试 fallbackView
        }

        // 降级使用 View 系统触感
        fallbackView?.let { v ->
            try {
                val constant = when (type) {
                    VibrationType.TICK -> HapticFeedbackConstants.CLOCK_TICK
                    VibrationType.CLICK -> HapticFeedbackConstants.KEYBOARD_TAP
                    VibrationType.HEAVY_CLICK -> HapticFeedbackConstants.LONG_PRESS
                }
                v.isHapticFeedbackEnabled = true
                v.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
            } catch (_: Throwable) {}
        }
    }

    @Suppress("DEPRECATION")
    private fun getVibrator(context: Context): Vibrator? {
        if (cachedVibrator == null) {
            cachedVibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator ?: context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }
        return cachedVibrator
    }
}
