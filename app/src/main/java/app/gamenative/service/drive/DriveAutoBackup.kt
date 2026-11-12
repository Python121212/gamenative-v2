package app.gamenative.service.drive

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Fire-and-forget bridge between "a file just finished writing" and the
 * Drive pseudo-SSD upload queue.
 *
 * Because Drive cannot receive a file handle directly, callers hand over the
 * finished [File] here and the queue uploads it in order in the background.
 */
object DriveAutoBackup {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun enabled(): Boolean = try {
        app.gamenative.PrefManager.drivePseudoSsdEnabled
    } catch (e: Exception) {
        Timber.w(e, "DriveAutoBackup: could not read preference")
        false
    }

    /** Queues [file] for sequential upload when the pseudo-SSD is enabled. */
    fun enqueueIfEnabled(context: Context, file: File, note: String? = null) {
        if (!enabled()) return
        if (!file.exists() || !file.isFile) return
        val appContext = context.applicationContext
        scope.launch {
            try {
                DriveStorageManager.getInstance(appContext)
                    .saveFile(file, note = note ?: "auto")
            } catch (e: Exception) {
                Timber.w(e, "DriveAutoBackup: could not queue ${file.name}")
            }
        }
    }

    /** Queues a finished folder (archived as a zip) for sequential upload. */
    fun enqueueFolderIfEnabled(context: Context, folder: File) {
        if (!enabled()) return
        if (!folder.exists() || !folder.isDirectory) return
        val appContext = context.applicationContext
        scope.launch {
            try {
                DriveStorageManager.getInstance(appContext).saveFolder(folder)
            } catch (e: Exception) {
                Timber.w(e, "DriveAutoBackup: could not queue ${folder.name}")
            }
        }
    }
}
