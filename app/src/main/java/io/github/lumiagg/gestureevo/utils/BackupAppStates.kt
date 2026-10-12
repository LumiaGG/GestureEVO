package io.github.lumiagg.gestureevo.utils

import io.github.lumiagg.gestureevo.db.APPModel

/** These lists are independent: one package can belong to several of them. */
internal data class BackupAppStates(
    val gestureDisabled: Set<String>,
    val recentDisabled: Set<String>,
    val notificationIconDisabled: Set<String>,
    val notificationEnabled: Set<String>,
) {
    fun applyTo(app: APPModel) {
        app.setState(APPModel.FLAG_GESTURE_DISABLE, app.pkgName in gestureDisabled)
        app.setState(APPModel.FLAG_RECENT_APPS_DISABLE, app.pkgName in recentDisabled)
        app.setState(APPModel.FLAG_NOTIFICATION_ICON_DISABLE, app.pkgName in notificationIconDisabled)
        app.setState(APPModel.FLAG_NOTIFICATION_APPS_ENABLE, app.pkgName in notificationEnabled)
    }
}
