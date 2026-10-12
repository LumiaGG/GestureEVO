package io.github.lumiagg.gestureevo.db

import androidx.room.Entity
import java.util.Objects

@Entity()
class APPModel() : BaseModel() {

    var pkgName: String = ""
    var userId: Int = 0
    var openCount: Long = 0
    var lastUsageTime: Long = 0

    constructor(name: String, pkg: String) : this() {
        this.name = name
        this.pkgName = pkg
    }

    companion object {
        const val FLAG_SYSTEM: Int = 1.shl(0)
        const val FLAG_USER: Int = 1.shl(1)

        const val FLAG_NO_LAUNCHER: Int = 1.shl(2)
        const val FLAG_LAUNCHER: Int = 1.shl(3)

        // Launcher / home applications
        const val FLAG_HOME: Int = 1.shl(4)

        // Gesture blacklisted applications
        const val FLAG_GESTURE_DISABLE: Int = 1.shl(5)

        // Recent apps blacklisted applications
        const val FLAG_RECENT_APPS_DISABLE: Int = 1.shl(6)

        // Notification whitelisted applications
        const val FLAG_NOTIFICATION_APPS_ENABLE: Int = 1.shl(7)

        // Notification icon blacklisted applications
        const val FLAG_NOTIFICATION_ICON_DISABLE: Int = 1.shl(8)
    }

    override fun equals(other: Any?): Boolean {
        return when (other) {
            !is APPModel -> false
            else -> this === other || (this.name == other.name && this.pkgName == other.pkgName && this.userId == other.userId && this.state == other.state)
        }
    }

    override fun hashCode(): Int {
        return Objects.hash(name, pkgName, userId, state)
    }

    /*
     * Clear a specific bit:
     * state = state and FLAG_SHOW_QUICK_PIN.inv()
     * Clears the specified bit in state.
     */
}
