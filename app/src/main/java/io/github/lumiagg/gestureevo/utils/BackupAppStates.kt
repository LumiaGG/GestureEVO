package io.github.lumiagg.gestureevo.utils

import io.github.lumiagg.gestureevo.db.APPModel

/** These lists are independent: one package can belong to several of them. */
internal data class BackupAppStates(
    val gestureDisabled: Set<String>,
    val recentDisabled: Set<String>,
    val notificationIconDisabled: Set<String>,
    val notificationEnabled: Set<String>,
    val accessibilityDisabled: Set<String> = emptySet(),
) {
    companion object {
        fun fromApps(apps: List<APPModel>): BackupAppStates {
            fun packagesWithFlag(flag: Int): Set<String> = apps.asSequence()
                .filter { it.getState(flag) }
                .map { it.pkgName }
                .toSet()

            return BackupAppStates(
                gestureDisabled = packagesWithFlag(APPModel.FLAG_GESTURE_DISABLE),
                recentDisabled = packagesWithFlag(APPModel.FLAG_RECENT_APPS_DISABLE),
                notificationIconDisabled = packagesWithFlag(APPModel.FLAG_NOTIFICATION_ICON_DISABLE),
                notificationEnabled = packagesWithFlag(APPModel.FLAG_NOTIFICATION_APPS_ENABLE),
                accessibilityDisabled = packagesWithFlag(APPModel.FLAG_ACCESSIBILITY_DISABLED),
            )
        }
    }

    fun applyTo(app: APPModel) {
        app.setState(APPModel.FLAG_GESTURE_DISABLE, app.pkgName in gestureDisabled)
        app.setState(APPModel.FLAG_RECENT_APPS_DISABLE, app.pkgName in recentDisabled)
        app.setState(APPModel.FLAG_NOTIFICATION_ICON_DISABLE, app.pkgName in notificationIconDisabled)
        app.setState(APPModel.FLAG_NOTIFICATION_APPS_ENABLE, app.pkgName in notificationEnabled)
        app.setState(APPModel.FLAG_ACCESSIBILITY_DISABLED, app.pkgName in accessibilityDisabled)
    }
}
