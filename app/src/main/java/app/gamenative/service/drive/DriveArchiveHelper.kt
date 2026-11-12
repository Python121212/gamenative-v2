package app.gamenative.service.drive

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Zips folders for upload and unzips them on restore.
 *
 * Drive stores single files, so a folder is archived into one zip before it
 * enters the sequential upload queue.
 */
object DriveArchiveHelper {

    /** Zips [folder] (recursively) into [archive]. */
    suspend fun zipFolder(folder: File, archive: File): File = withContext(Dispatchers.IO) {
        archive.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            folder.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val entryName = file.relativeTo(folder).path
                    zip.putNextEntry(ZipEntry(entryName))
                    FileInputStream(file).use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
        }
        Timber.i("DriveArchiveHelper: zipped ${folder.name} -> ${archive.name} (${archive.length()} bytes)")
        archive
    }

    /** Extracts [archive] into [destDir]. */
    suspend fun unzip(archive: File, destDir: File): File = withContext(Dispatchers.IO) {
        destDir.mkdirs()
        ZipInputStream(FileInputStream(archive)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val target = File(destDir, entry.name)
                // Guard against zip-slip.
                if (!target.canonicalPath.startsWith(destDir.canonicalPath)) {
                    Timber.w("DriveArchiveHelper: skipping unsafe entry ${entry.name}")
                } else if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        destDir
    }
}
