package app.gamenative.service.drive

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Persists registered [DriveAccount]s, the pseudo-SSD file index and the
 * pending upload queue to a JSON file in the app's private storage.
 *
 * All access is serialized through a [Mutex] so the queue worker and the UI
 * can safely read/write concurrently.
 */
class DriveAccountStore(context: Context) {

    private val file: File = File(context.filesDir, FILE_NAME)
    private val mutex = Mutex()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    @Serializable
    private data class StoreData(
        val accounts: List<DriveAccount> = emptyList(),
        val entries: List<DriveFileEntry> = emptyList(),
        val queue: List<DriveUploadTask> = emptyList(),
    )

    private suspend fun read(): StoreData = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext StoreData()
        try {
            val text = file.readText()
            if (text.isBlank()) return@withContext StoreData()
            json.decodeFromString<StoreData>(text)
        } catch (e: Exception) {
            Timber.w(e, "DriveAccountStore: failed to read store, starting fresh")
            StoreData()
        }
    }

    private suspend fun write(data: StoreData) = withContext(Dispatchers.IO) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(data))
        } catch (e: Exception) {
            Timber.e(e, "DriveAccountStore: failed to write store")
        }
    }

    // ------------------------------------------------------------------
    // Accounts
    // ------------------------------------------------------------------

    suspend fun accounts(): List<DriveAccount> = mutex.withLock { read().accounts }

    suspend fun account(id: String): DriveAccount? =
        mutex.withLock { read().accounts.firstOrNull { it.id == id } }

    suspend fun accountByEmail(email: String): DriveAccount? =
        mutex.withLock { read().accounts.firstOrNull { it.email.equals(email, ignoreCase = true) } }

    suspend fun addAccount(account: DriveAccount) {
        mutex.withLock {
            val data = read()
            val existing = data.accounts.filterNot { it.email.equals(account.email, ignoreCase = true) }
            write(data.copy(accounts = existing + account))
        }
    }

    suspend fun updateAccount(account: DriveAccount) {
        mutex.withLock {
            val data = read()
            val updated = data.accounts.map { if (it.id == account.id) account else it }
            write(data.copy(accounts = updated))
        }
    }

    suspend fun removeAccount(id: String) {
        mutex.withLock {
            val data = read()
            write(
                data.copy(
                    accounts = data.accounts.filterNot { it.id == id },
                    entries = data.entries.filterNot { it.accountId == id },
                ),
            )
        }
    }

    suspend fun totalQuota(): DriveTotalQuota {
        val list = accounts()
        return DriveTotalQuota(
            accountCount = list.size,
            totalBytes = list.sumOf { it.quotaTotalBytes },
            usedBytes = list.sumOf { it.quotaUsedBytes },
        )
    }

    /**
     * Picks the enabled account with the most free space that can hold
     * [sizeBytes]. Returns null when no account has enough room.
     */
    suspend fun pickAccountFor(sizeBytes: Long): DriveAccount? {
        val list = accounts().filter { it.enabled }
        return list
            .filter { it.quotaFreeBytes >= sizeBytes }
            .maxByOrNull { it.quotaFreeBytes }
    }

    // ------------------------------------------------------------------
    // File index
    // ------------------------------------------------------------------

    suspend fun entries(): List<DriveFileEntry> = mutex.withLock { read().entries }

    suspend fun entry(id: String): DriveFileEntry? =
        mutex.withLock { read().entries.firstOrNull { it.id == id } }

    suspend fun entriesForAccount(accountId: String): List<DriveFileEntry> =
        mutex.withLock { read().entries.filter { it.accountId == accountId } }

    suspend fun upsertEntry(entry: DriveFileEntry) {
        mutex.withLock {
            val data = read()
            val updated = data.entries.filterNot { it.id == entry.id } + entry
            write(data.copy(entries = updated))
        }
    }

    suspend fun removeEntry(id: String) {
        mutex.withLock {
            val data = read()
            write(data.copy(entries = data.entries.filterNot { it.id == id }))
        }
    }

    // ------------------------------------------------------------------
    // Upload queue
    // ------------------------------------------------------------------

    suspend fun queue(): List<DriveUploadTask> =
        mutex.withLock { read().queue.sortedBy { it.order } }

    suspend fun enqueue(task: DriveUploadTask) {
        mutex.withLock {
            val data = read()
            write(data.copy(queue = data.queue + task))
        }
    }

    suspend fun dequeue(taskId: String) {
        mutex.withLock {
            val data = read()
            write(data.copy(queue = data.queue.filterNot { it.id == taskId }))
        }
    }

    suspend fun updateTask(task: DriveUploadTask) {
        mutex.withLock {
            val data = read()
            val updated = data.queue.map { if (it.id == task.id) task else it }
            write(data.copy(queue = updated))
        }
    }

    suspend fun clearQueue() {
        mutex.withLock {
            val data = read()
            write(data.copy(queue = emptyList()))
        }
    }

    companion object {
        private const val FILE_NAME = "drive_pseudo_ssd.json"

        fun newId(): String = UUID.randomUUID().toString()
    }
}
