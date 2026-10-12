package io.github.lumiagg.gestureevo.pin

import io.github.lumiagg.gestureevo.utils.rebaseBackupFilePaths
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class PinImageFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun validImageCanBeRestoredAndDeleted() {
        val directory = temporary.newFolder("pin_pool")
        val image = File(directory, "pin_example.png").apply { writeText("image") }
        val restored = validatePinImagePaths(directory, listOf(imagePin(image.path)))

        assertEquals(image.canonicalPath, restored.single().imagePath)
        assertTrue(deletePinImageFile(directory, restored.single().imagePath))
        assertFalse(image.exists())
    }

    @Test fun craftedBackupCannotDeleteAnAppIcon() {
        val directory = temporary.newFolder("pin_pool")
        val icon = File(temporary.newFolder("icon"), "saveIcon-example.png").apply { writeText("keep") }

        assertRejected(directory, icon.path)
        assertFalse(deletePinImageFile(directory, icon.path))
        assertEquals("keep", icon.readText())
    }

    @Test fun traversalAndSimilarDirectoryPrefixCannotEscape() {
        val directory = temporary.newFolder("pin_pool")
        val other = File(temporary.newFolder("pin_pool_other"), "image.png").apply { writeText("keep") }
        val traversal = File(directory, "../pin_pool_other/image.png").path

        for (path in listOf(other.path, traversal)) {
            assertRejected(directory, path)
            assertFalse(deletePinImageFile(directory, path))
        }
        assertEquals("keep", other.readText())
    }

    @Test fun symlinkCannotRedirectDeletionOutsideTheDirectory() {
        val directory = temporary.newFolder("pin_pool")
        val outside = temporary.newFile("private-data").apply { writeText("keep") }
        val link = File(directory, "pin_link.png")
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        // Older Windows JVMs do not resolve symlinks through File.canonicalFile.
        // Android and the Linux CI runner must still exercise the full assertion.
        if (File.separatorChar == '\\') {
            assumeTrue(
                "This Windows JVM does not resolve symbolic links through File.canonicalFile",
                link.canonicalFile == outside.canonicalFile,
            )
        }

        assertRejected(directory, link.path)
        assertFalse(deletePinImageFile(directory, link.path))
        assertEquals("keep", outside.readText())
    }

    @Test fun missingImagePathCanBeValidatedBeforeCopyingBackupFiles() {
        val directory = temporary.newFolder("pin_pool")
        val image = File(directory, "pin_restored.png")

        assertEquals(image.canonicalPath,
            validatePinImagePaths(directory, listOf(imagePin(image.path))).single().imagePath)
        assertFalse(deletePinImageFile(directory, image.path))
    }

    @Test fun directoriesRelativePathsAndEmptyPathsAreRejected() {
        val directory = temporary.newFolder("pin_pool")
        val child = File(directory, "nested").apply { mkdir() }
        val nestedImage = File(child, "image.png").apply { writeText("keep") }

        for (path in listOf(null, "", "pin_example.png", directory.path, child.path, nestedImage.path)) {
            assertRejected(directory, path)
            assertFalse(deletePinImageFile(directory, path))
        }
        assertEquals("keep", nestedImage.readText())
    }

    @Test fun legacyBackupPathStillResolvesToTheCurrentImageDirectory() {
        val directory = temporary.newFolder("pin_pool")
        val old = "/data/user/0/com.jwg.gesture_evo/files/pin_pool/pin_old.png"
        val migrated = rebaseBackupFilePaths(old, temporary.root.path)

        assertEquals(File(directory, "pin_old.png").canonicalPath,
            validatePinImagePaths(directory, listOf(imagePin(migrated))).single().imagePath)
    }

    @Test fun textPinsDoNotRetainFileReferences() {
        val directory = temporary.newFolder("pin_pool")
        val item = PinPoolItem(id = "text", type = PinType.TEXT, text = "hello", imagePath = "/private/file")

        val normalized = validatePinImagePaths(directory, listOf(item)).single()
        assertNull(normalized.imagePath)
        assertEquals("hello", normalized.text)
    }

    private fun imagePin(path: String?) = PinPoolItem(id = "image", type = PinType.IMAGE, imagePath = path)

    private fun assertRejected(directory: File, path: String?) {
        assertNull(resolvePinImageFile(directory, path))
        assertThrows(IllegalArgumentException::class.java) {
            validatePinImagePaths(directory, listOf(imagePin(path)))
        }
    }
}
