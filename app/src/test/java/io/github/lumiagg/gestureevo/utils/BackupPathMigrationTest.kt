package io.github.lumiagg.gestureevo.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupPathMigrationTest {
    private val destination = "/data/user/10/io.github.lumiagg.gestureevo/files"

    @Test fun oldAppIconsMoveToTheDestinationProfile() {
        assertEquals("$destination/icon/saveIcon-1", rebaseBackupFilePaths(
            "/data/user/0/com.jwg.gesture_evo/files/icon/saveIcon-1", destination
        ))
    }

    @Test fun legacyDataDirectoryAndDebugBackupsAreSupported() {
        for (prefix in listOf("/data/data/com.jwg.gesture_evo", "/data/user/0/io.github.lumiagg.gestureevo.debug")) {
            assertEquals("$destination/pin_pool/a.png", rebaseBackupFilePaths("$prefix/files/pin_pool/a.png", destination))
        }
    }

    @Test fun embeddedJsonPathsAreMigratedWithoutChangingOtherValues() {
        val value = """{"pkg":"com.other.app","icon":"/data/user/0/com.jwg.gesture_evo/files/icon/a"}"""
        assertEquals("""{"pkg":"com.other.app","icon":"$destination/icon/a"}""", rebaseBackupFilePaths(value, destination))
    }

    @Test fun otherAppsExternalFilesAndCacheUrisAreUnchanged() {
        for (value in listOf("/data/user/0/com.other.app/files/icon/a", "/data/user/0/com.jwg.gesture_evo.extra/files/a", "/storage/emulated/0/pic.png", "content://com.jwg.gesture_evo.fileprovider/image/a")) {
            assertEquals(value, rebaseBackupFilePaths(value, destination))
        }
    }

    @Test fun repeatedImportDoesNotChangeAlreadyMigratedPaths() {
        val value = "$destination/icon/a"
        assertEquals(value, rebaseBackupFilePaths(rebaseBackupFilePaths(value, destination), destination))
    }

    @Test fun pathsMentionedInPlainTextOrWebUrlsAreNotRewritten() {
        for (value in listOf("See /data/user/0/com.jwg.gesture_evo/files/icon/a", "https://host/data/user/0/com.jwg.gesture_evo/files/icon/a")) {
            assertEquals(value, rebaseBackupFilePaths(value, destination))
        }
    }
}
