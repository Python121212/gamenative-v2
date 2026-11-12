package app.gamenative.service.drive

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * High-level facade for the Drive pseudo-SSD.
 *
 * Callers use [saveFile] / [saveFolder] to push finished files into the
 * sequential upload queue, and [restoreFile] / [restoreFolder] to pull them
 * back. The underlying [DriveAccountStore] index tracks what lives on Drive
 * and which of the registered 15 GB accounts holds it.
 *
 * Because files cannot be passed directly to Drive, everything flows through
 * the queue: a file is first "finished" locally, then uploaded in order.
 */
class DriveStorageManager(
    context: Context,
    val store: DriveAccountStore = DriveAccountStore(context),
) {
    private val appContext: Context = context.applicationContext
    val queue: DriveUploadQueue = DriveUploadQueue(appContext, store)

    private val _entries = MutableStateFlow<List<DriveFileEntry>>(emptyList())
    val entries: StateFlow<List<DriveFileEntry>> = _entries.asStateFlow()

    private val _accounts = MutableStateFlow<List<DriveAccount>>(emptyList())
    val accounts: StateFlow<List<DriveAccount>> = _accounts.asStateFlow()

    /** Reloads accounts + index from disk into the observable flows. */
    suspend fun refresh() {
        _accounts.value = store.accounts()
        _entries.value = store.entries()
    }

    // ------------------------------------------------------------------
    // Accounts
    // ------------------------------------------------------------------

    suspend fun registerAccount(account: DriveAccount) {
        store.addAccount(account)
        // Prime quota from Drive so usage display is accurate.
        refreshQuota(account.id)
        refresh()
    }

    suspend fun removeAccount(accountId: String) {
        store.removeAccount(accountId)
        refresh()
    }

    suspend fun setAccountEnabled(accountId: String, enabled: Boolean) {
        val account = store.account(accountId) ?: return
        store.updateAccount(account.copy(enabled = enabled))
        refresh()
    }

    suspend fun refreshQuota(accountId: String): DriveAccount? {
        val account = store.account(accountId) ?: return null
        val client = DriveApiClient(appContext, account)
        val refreshed = client.refreshQuota() ?: return account
        store.updateAccount(refreshed)
        refresh()
        return refreshed
    }

    suspend fun refreshAllQuotas() {
        store.accounts().forEach { refreshQuota(it.id) }
        refresh()
    }

    suspend fun totalQuota(): DriveTotalQuota = store.totalQuota()

    // ------------------------------------------------------------------
    // Save (enqueue for sequential upload)
    // ------------------------------------------------------------------

    /** Queues a finished file for sequential upload to Drive. */
    suspend fun saveFile(
        localFile: File,
        displayName: String? = null,
        mimeType: String? = null,
        note: String? = null,
    ): DriveFileEntry {
        val entry = queue.enqueueFile(localFile, displayName, mimeType, note)
        refresh()
        return entry
    }

    /** Queues a finished folder (zipped) for sequential upload to Drive. */
    suspend fun saveFolder(folder: File, displayName: String? = null): DriveFileEntry {
        val entry = queue.enqueueFolder(folder, displayName)
        refresh()
        return entry
    }

    // ------------------------------------------------------------------
    // Restore (download back to local storage)
    // ------------------------------------------------------------------

    /**
     * Downloads a stored file back to [destFile].
     * @return true on success.
     */
    suspend fun restoreFile(entry: DriveFileEntry, destFile: File): Boolean =
        withContext(Dispatchers.IO) {
            val driveFileId = entry.driveFileId ?: return@withContext false
            val account = entry.accountId?.let { store.account(it) } ?: return@withContext false
            val client = DriveApiClient(appContext, account)
            destFile.parentFile?.mkdirs()
            FileOutputStream(destFile).use { output ->
                client.downloadFile(driveFileId, output)
            }.also { success ->
                if (success) {
                    Timber.i("DriveStorageManager: restored ${entry.name} -> ${destFile.absolutePath}")
                }
            }
        }

    /**
     * Downloads a stored folder archive and extracts it into [destDir].
     * @return true on success.
     */
    suspend fun restoreFolder(entry: DriveFileEntry, destDir: File): Boolean =
        withContext(Dispatchers.IO) {
            val archive = File(appContext.cacheDir, "restore_${entry.id}.zip")
            if (!restoreFile(entry, archive)) return@withContext false
            DriveArchiveHelper.unzip(archive, destDir)
            archive.delete()
            true
        }

    // ------------------------------------------------------------------
    // Manage
    // ------------------------------------------------------------------

    /** Deletes a stored file from Drive and drops its index entry. */
    suspend fun delete(entry: DriveFileEntry): Boolean {
        val driveFileId = entry.driveFileId
        val account = entry.accountId?.let { store.account(it) }
        var deleted = false
        if (driveFileId != null && account != null) {
            deleted = DriveApiClient(appContext, account).deleteFile(driveFileId)
            if (deleted) {
                store.updateAccount(
                    account.copy(
                        quotaUsedBytes = (account.quotaUsedBytes - entry.sizeBytes).coerceAtLeast(0L),
                    ),
                )
            }
        }
        store.removeEntry(entry.id)
        refresh()
        return deleted
    }

    /** Stored files (fully uploaded), newest first. */
    suspend fun storedFiles(): List<DriveFileEntry> =
        store.entries().filter { it.state == DriveFileState.STORED }

    /** Files still waiting or uploading. */
    suspend fun pendingFiles(): List<DriveFileEntry> =
        store.entries().filter {
            it.state == DriveFileState.QUEUED || it.state == DriveFileState.UPLOADING
        }

    companion object {
        @Volatile
        private var instance: DriveStorageManager? = null

        /** Process-wide instance so any service can enqueue finished files. */
        fun getInstance(context: Context): DriveStorageManager =
            instance ?: synchronized(this) {
                instance ?: DriveStorageManager(context.applicationContext).also { instance = it }
            }
    }
}
