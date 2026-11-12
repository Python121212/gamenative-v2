package app.gamenative.service.drive

import android.content.Context
import com.google.api.client.googleapis.media.MediaHttpUploader
import com.google.api.client.googleapis.media.MediaHttpUploaderProgressListener
import com.google.api.client.googleapis.media.MediaHttpUploader.UploadState
import com.google.api.client.http.FileContent
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File
import java.io.FileInputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.coroutineContext

/**
 * Thin wrapper around the Google Drive API used by the pseudo-SSD.
 *
 * Uploads use resumable media upload so multi-GB game files survive
 * connection drops. All operations are cancellable via coroutines.
 */
class DriveApiClient(
    private val context: Context,
    private val account: DriveAccount,
) {
    private val service: Drive = DriveAuthManager.driveService(context, account)

    /** Name of the root folder that holds this pseudo-SSD's files. */
    private val rootFolderName: String = "GameNative Drive SSD"

    /**
     * Returns the ID of the pseudo-SSD root folder, creating it on first use.
     */
    suspend fun ensureRootFolder(): String? = withContext(Dispatchers.IO) {
        account.folderId?.let { return@withContext it }

        // Look for an existing folder with our name.
        val existing = exec(
            service.files().list()
                .setQ("name = '$rootFolderName' and mimeType = 'application/vnd.google-apps.folder' and trashed = false")
                .setFields("files(id,name)")
                .setPageSize(1),
        )
        val found = existing?.files?.firstOrNull()?.id
        if (found != null) return@withContext found

        // Create it.
        val metadata = File().apply {
            name = rootFolderName
            mimeType = "application/vnd.google-apps.folder"
        }
        val created = exec(
            service.files().create(metadata).setFields("id,name"),
        )
        created?.id
    }

    /**
     * Uploads [localFile] into the pseudo-SSD root folder.
     *
     * @param onProgress invoked with 0..1 progress.
     * @return the created Drive file ID, or null on failure.
     */
    suspend fun uploadFile(
        localFile: java.io.File,
        displayName: String,
        mimeType: String? = null,
        onProgress: (Float) -> Unit = {},
    ): String? = withContext(Dispatchers.IO) {
        val folderId = ensureRootFolder() ?: return@withContext null

        val metadata = File().apply {
            name = displayName
            parents = listOf(folderId)
            if (mimeType != null) this.mimeType = mimeType
        }

        val mediaContent = FileContent(
            mimeType ?: "application/octet-stream",
            localFile,
        )

        val request = service.files().create(metadata, mediaContent)
            .setFields("id,name,size")

        // Resumable upload with progress + cancellation. The uploader is
        // created by the Drive client itself, so auth headers are inherited.
        val uploader = request.mediaHttpUploader
        if (uploader != null) {
            uploader.chunkSize = MediaHttpUploader.MINIMUM_CHUNK_SIZE * 16
            uploader.isDirectUploadEnabled = false
            uploader.progressListener = MediaHttpUploaderProgressListener { current ->
                coroutineContext.ensureActive()
                val total = localFile.length().coerceAtLeast(1L)
                val state = current.uploadState
                if (state == UploadState.IN_PROGRESS) {
                    onProgress((current.numBytesUploaded.toFloat() / total).coerceIn(0f, 1f))
                } else if (state == UploadState.MEDIA_COMPLETE) {
                    onProgress(1f)
                }
            }
        }

        val created = exec(request)
        if (created?.id == null) {
            Timber.w("DriveApiClient: upload failed for $displayName")
        }
        created?.id
    }

    /**
     * Downloads the Drive file [driveFileId] into [output].
     *
     * @return true on success.
     */
    suspend fun downloadFile(
        driveFileId: String,
        output: OutputStream,
        onProgress: (Float) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val metadata = exec(
                service.files().get(driveFileId).setFields("size"),
            )
            val total = metadata?.size ?: -1L

            var downloaded = 0L
            service.files().get(driveFileId).executeMediaAndDownloadTo(
                object : OutputStream() {
                    override fun write(b: Int) {
                        output.write(b)
                        downloaded++
                        if (total > 0) onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                    }

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        coroutineContext.ensureActive()
                        output.write(b, off, len)
                        downloaded += len
                        if (total > 0) onProgress((downloaded.toFloat() / total).coerceIn(0f, 1f))
                    }

                    override fun flush() = output.flush()
                    override fun close() = output.close()
                },
            )
            onProgress(1f)
            true
        } catch (e: Exception) {
            Timber.w(e, "DriveApiClient: download failed for $driveFileId")
            false
        }
    }

    /** Lists files stored in the pseudo-SSD root folder. */
    suspend fun listFiles(): List<File> = withContext(Dispatchers.IO) {
        val folderId = ensureRootFolder() ?: return@withContext emptyList()
        val result = exec(
            service.files().list()
                .setQ("'$folderId' in parents and trashed = false")
                .setFields("files(id,name,size,mimeType,modifiedTime)")
                .setPageSize(200)
                .setOrderBy("modifiedTime desc"),
        )
        result?.files ?: emptyList()
    }

    /** Permanently deletes a Drive file. */
    suspend fun deleteFile(driveFileId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            service.files().delete(driveFileId).execute()
            true
        } catch (e: Exception) {
            Timber.w(e, "DriveApiClient: delete failed for $driveFileId")
            false
        }
    }

    /** Refreshes the account quota from Drive. */
    suspend fun refreshQuota(): DriveAccount? = withContext(Dispatchers.IO) {
        val about = exec(
            service.about().get().setFields("storageQuota"),
        ) ?: return@withContext null
        val quota = about.storageQuota ?: return@withContext null
        account.copy(
            quotaTotalBytes = quota.limit ?: DriveAccount.DEFAULT_QUOTA_BYTES,
            quotaUsedBytes = quota.usage ?: 0L,
            quotaUpdatedAt = System.currentTimeMillis(),
        )
    }

    /** Runs a Drive request with the account token, refreshing once on 401. */
    private suspend fun <T> exec(request: com.google.api.client.googleapis.services.AbstractGoogleClientRequest<T>): T? =
        DriveAuthManager.execute(request, account.email)

    /** Reads a local file as a stream (helper for callers). */
    fun openLocal(file: java.io.File): FileInputStream = FileInputStream(file)
}
