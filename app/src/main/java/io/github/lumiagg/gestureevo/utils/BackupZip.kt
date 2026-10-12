package io.github.lumiagg.gestureevo.utils

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** All entries stay inside the fresh extraction directory, including legacy ZIPs. */
internal fun extractBackupZip(input: InputStream, destination: File) {
    val root = destination.canonicalFile
    if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create backup extraction directory")
    ZipInputStream(input).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val name = entry.name.replace('\\', '/')
            if (name.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(name)) {
                throw IOException("Absolute backup entry is not allowed")
            }
            val file = File(root, name).canonicalFile
            if (!file.path.startsWith(root.path + File.separator)) {
                throw IOException("Backup entry escapes extraction directory")
            }
            if (entry.isDirectory) {
                if (!file.isDirectory && !file.mkdirs()) throw IOException("Cannot create backup directory")
            } else {
                val parent = file.parentFile ?: throw IOException("Backup entry has no parent")
                if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create backup directory")
                file.outputStream().use { zip.copyTo(it) }
            }
            zip.closeEntry()
        }
    }
}
