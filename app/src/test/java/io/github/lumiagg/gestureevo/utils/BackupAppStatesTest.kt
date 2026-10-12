package io.github.lumiagg.gestureevo.utils

import io.github.lumiagg.gestureevo.db.APPModel
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupAppStatesTest {
    @Test
    fun overlappingListsRestoreEveryFlag() {
        val app = APPModel().apply { pkgName = "mail" }
        BackupAppStates(setOf("mail"), setOf("mail"), setOf("mail"), setOf("mail")).applyTo(app)
        assertEquals(
            APPModel.FLAG_GESTURE_DISABLE or APPModel.FLAG_RECENT_APPS_DISABLE or
                APPModel.FLAG_NOTIFICATION_ICON_DISABLE or APPModel.FLAG_NOTIFICATION_APPS_ENABLE,
            app.state,
        )
    }

    @Test
    fun restorationPreservesCatalogFlagsAndReplacesOnlyBackedUpFlags() {
        val app = APPModel().apply {
            pkgName = "mail"
            state = APPModel.FLAG_SYSTEM or APPModel.FLAG_LAUNCHER or APPModel.FLAG_RECENT_APPS_DISABLE
        }
        BackupAppStates(setOf("mail"), emptySet(), emptySet(), emptySet()).applyTo(app)
        assertEquals(APPModel.FLAG_SYSTEM or APPModel.FLAG_LAUNCHER or APPModel.FLAG_GESTURE_DISABLE, app.state)
    }

    @Test
    fun listsForAnotherPackageDoNotChangeThisPackage() {
        val app = APPModel().apply { pkgName = "browser"; state = APPModel.FLAG_USER }
        BackupAppStates(setOf("mail"), setOf("mail"), setOf("mail"), setOf("mail")).applyTo(app)
        assertEquals(APPModel.FLAG_USER, app.state)
    }
}
