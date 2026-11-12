package app.gamenative.service.drive

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Sequential upload queue for the Drive pseudo-SSD.
 *
 * Because Drive is object storage (files cannot be streamed or passed
 * directly), finished files/folders are uploaded one-by-one in enqueue
 * order. When the selected account runs out of quota, the queue rotates to
 * the next registered account with free space, so capacity scales with the
 * number of 15 GB accounts.
 *
 * The queue is persisted in [DriveAccountStore], so pending uploads survive
 * app restarts. Processing is driven by a single worker coroutine.
 */
class DriveUploadQueue(
    context: Context,
    private val store: DriveAccountStore,
) {
    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<QueueState>(QueueState.Idle)
    val state: StateFlow<QueueState> = _state.asStateFlow()

    private val workerMutex = Mutex()
    private var workerJob: Job? = null

    sealed interface QueueState {
        data object Idle : QueueState
        data object Running : QueueState
        data class Paused(val reason: String) : QueueState
    }

    /** Adds a file to the queue and returns its index entry. */
    suspend fun enqueueFile(
        localFile: File,
        displayName: String? = null,
        mimeType: String? = null,
        note: String? = null,
    ): DriveFileEntry {
        val name = displayName ?: localFile.name
        val sha = computeSha256(localFile)
        val entry = DriveFileEntry(
            id = DriveAccountStore.newId(),
            name = name,
            localPath = localFile.absolutePath,
            sizeBytes = localFile.length(),
            sha256 = sha,
            mimeType = mimeType,
            state = DriveFileState.QUEUED,
            note = note,
        )
        store.upsertEntry(entry)
        store.enqueue(
            DriveUploadTask(
                id = DriveAccountStore.newId(),
                entryId = entry.id,
                localPath = localFile.absolutePath,
                name = name,
                sizeBytes = localFile.length(),
                sha256 = sha,
                mimeType = mimeType,
            ),
        )
        Timber.i("DriveUploadQueue: enqueued $name (${localFile.length()} bytes)")
        start()
        return entry
    }

    /** Adds a whole folder by archiving it first, then queuing the archive. */
    suspend fun enqueueFolder(
        folder: File,
        displayName: String? = null,
    ): DriveFileEntry {
        val archive = File(appContext.cacheDir, "${folder.name}.zip")
        DriveArchiveHelper.zipFolder(folder, archive)
        return enqueueFile(
            localFile = archive,
            displayName = displayName ?: "${folder.name}.zip",
            mimeType = "application/zip",
            note = "folder:${folder.absolutePath}",
        )
    }

    /** Starts (or resumes) the worker. Safe to call repeatedly. */
    fun start() {
        scope.launch {
            workerMutex.withLock {
                if (workerJob?.isActive == true) return@launch
                _state.value = QueueState.Running
                workerJob = scope.launch { processLoop() }
            }
        }
    }

    /** Pauses the worker after the current upload finishes. */
    fun pause(reason: String = "paused") {
        _state.value = QueueState.Paused(reason)
    }

    /** Cancels the in-flight upload (the task stays queued for retry). */
    fun cancelCurrent() {
        workerJob?.cancel()
        workerJob = null
        _state.value = QueueState.Idle
    }

    private suspend fun processLoop() {
        while (true) {
            val task = store.queue().firstOrNull() ?: break
            if (_state.value is QueueState.Paused) break
            val uploaded = processTask(task)
            if (!uploaded) {
                // Back off briefly before retrying the same task.
                delay(RETRY_BACKOFF_MS)
            }
        }
        _state.value = QueueState.Idle
    }

    /** @return true when the task completed (uploaded or permanently failed). */
    private suspend fun processTask(task: DriveUploadTask): Boolean {
        val localFile = File(task.localPath)
        if (!localFile.exists()) {
            Timber.w("DriveUploadQueue: local file missing for ${task.name}, dropping task")
            store.dequeue(task.id)
            markEntry(task.entryId) { it.copy(state = DriveFileState.FAILED, errorMessage = "local file missing") }
            return true
        }

        // Pick an account with enough free space (rotates across accounts).
        val account = store.pickAccountFor(task.sizeBytes)
        if (account == null) {
            Timber.w("DriveUploadQueue: no account has ${task.sizeBytes} bytes free; pausing")
            _state.value = QueueState.Paused("no account has enough free space")
            markEntry(task.entryId) { it.copy(state = DriveFileState.FAILED, errorMessage = "no account has enough free space") }
            store.dequeue(task.id)
            return true
        }

        markEntry(task.entryId) {
            it.copy(state = DriveFileState.UPLOADING, progress = 0f, errorMessage = null)
        }

        val client = DriveApiClient(appContext, account)
        val driveFileId = try {
            client.uploadFile(localFile, task.name, task.mimeType) { progress ->
                // Progress is updated on the IO dispatcher; keep it cheap.
                scope.launch {
                    markEntry(task.entryId) { it.copy(progress = progress) }
                }
            }
        } catch (e: CancellationException) {
            Timber.i("DriveUploadQueue: upload cancelled for ${task.name}")
            markEntry(task.entryId) { it.copy(state = DriveFileState.QUEUED, progress = 0f) }
            throw e
        }

        if (driveFileId == null) {
            val attempts = task.attempts + 1
            store.updateTask(task.copy(attempts = attempts))
            markEntry(task.entryId) {
                it.copy(state = DriveFileState.FAILED, errorMessage = "upload failed (attempt $attempts)")
            }
            if (attempts >= MAX_ATTEMPTS) {
                store.dequeue(task.id)
                return true
            }
            return false
        }

        // Success: record the mapping and bump the account usage.
        markEntry(task.entryId) {
            it.copy(
                state = DriveFileState.STORED,
                driveFileId = driveFileId,
                accountId = account.id,
                progress = 1f,
                storedAt = System.currentTimeMillis(),
            )
        }
        store.updateAccount(
            account.copy(quotaUsedBytes = account.quotaUsedBytes + task.sizeBytes),
        )
        store.dequeue(task.id)
        Timber.i("DriveUploadQueue: stored ${task.name} on ${account.email}")
        return true
    }

    private suspend fun markEntry(id: String, transform: (DriveFileEntry) -> DriveFileEntry) {
        val entry = store.entry(id) ?: return
        store.upsertEntry(transform(entry))
    }

    private suspend fun computeSha256(file: File): String? = withContext(Dispatchers.IO) {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Timber.w(e, "DriveUploadQueue: sha256 failed for ${file.name}")
            null
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MS = 5_000L
        private const val BUFFER_SIZE = 64 * 1024
    }
}
