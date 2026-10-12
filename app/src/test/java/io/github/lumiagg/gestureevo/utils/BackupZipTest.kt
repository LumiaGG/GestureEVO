package io.github.lumiagg.gestureevo.utils

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupZipTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun archive(name: String, content: String = "settings"): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            if (!name.endsWith('/')) zip.write(content.toByteArray())
            zip.closeEntry()
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    @Test fun nestedFilesAndDirectoryEntriesAreRestored() {
        val destination = temporaryFolder.newFolder()
        extractBackupZip(archive("settings/"), destination)
        extractBackupZip(archive("settings/settings.json", "{}"), destination)
        assertEquals("{}", destination.resolve("settings/settings.json").readText())
    }

    @Test fun parentTraversalCannotWriteOutsideTheExtractionRoot() {
        val destination = temporaryFolder.newFolder("backup")
        val outside = temporaryFolder.root.resolve("outside.txt")
        try {
            extractBackupZip(archive("../outside.txt"), destination)
            fail("Expected unsafe ZIP rejection")
        } catch (_: IOException) {
            assertFalse(outside.exists())
        }
    }

    @Test fun absoluteAndWindowsTraversalPathsAreRejected() {
        for (name in listOf("/absolute.txt", "C:/absolute.txt", "..\\outside.txt")) {
            try {
                extractBackupZip(archive(name), temporaryFolder.newFolder())
                fail("Expected unsafe ZIP rejection: $name")
            } catch (_: IOException) {
                // No destination outside the fresh backup directory is opened.
            }
        }
    }
}
