package io.github.lumiagg.gestureevo.drop

import android.content.ComponentName
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent

/** Runs only in the privileged user service, before EVO attaches any touchable drag windows. */
internal class ShizukuTouchInterrupter {
    private class Input {
        val type = Class.forName("android.hardware.input.IInputManager")
        val manager: Any = Class.forName("android.hardware.input.IInputManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null,
                Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                    .invoke(null, "input"))!!
        val inject = type.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        val setDisplay = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }
    private val input by lazy { Input() }

    fun interrupt(
        sourcePackage: String, x: Float, y: Float, displayId: Int,
        isCurrent: () -> Boolean,
    ): Boolean {
        if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 || displayId < 0) return false
        val identity = Binder.clearCallingIdentity()
        return try {
            // Resolve hidden APIs before the final session check, so a cold lookup cannot revive a stale drag.
            val api = input
            if (!sourceIsFocused(sourcePackage, displayId)) return false
            val start = SystemClock.uptimeMillis()
            fun inject(action: Int): Boolean {
                val properties = arrayOf(MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                })
                val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
                    this.x = x
                    this.y = y
                    pressure = 1f
                    size = 1f
                })
                val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, 1,
                    properties, coordinates, 0, 0, 1f, 1f, -1, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                return try {
                    api.setDisplay.invoke(event, displayId)
                    api.inject.invoke(api.manager, event, 2 /* WAIT_FOR_FINISH */) as Boolean
                } finally { event.recycle() }
            }
            TouchInterruptionSequence.run(
                isCurrent = isCurrent,
                down = { inject(MotionEvent.ACTION_DOWN) },
                cancel = { inject(MotionEvent.ACTION_CANCEL) },
                pause = { SystemClock.sleep(40) },
            )
        } catch (error: Exception) {
            // Do not persist coordinates, source content or raw remote exception messages.
            Log.w("EVO-TouchBlock", "shizuku_cancel_failed type=${(error.cause ?: error).javaClass.simpleName}")
            false
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    private fun sourceIsFocused(packageName: String, displayId: Int): Boolean {
        if (packageName.isBlank()) return false
        val modern = runCatching { Class.forName("android.app.ActivityTaskManager") }.getOrNull()
        val type = modern ?: Class.forName("android.app.ActivityManager")
        val manager = type.getMethod("getService").invoke(null)
        val api = Class.forName(if (modern != null) "android.app.IActivityTaskManager" else "android.app.IActivityManager")
        val focused = api.methods.firstOrNull {
            it.parameterTypes.isEmpty() && it.name in setOf("getFocusedRootTaskInfo", "getFocusedStackInfo")
        } ?: return false
        val task = focused.invoke(manager) ?: return false
        val top = task.javaClass.getField("topActivity").get(task) as? ComponentName ?: return false
        return top.packageName == packageName && task.javaClass.getField("displayId").getInt(task) == displayId
    }
}
