package app.gamenative.service.drive

import kotlinx.serialization.Serializable

/**
 * A Google account registered for Drive pseudo-SSD storage.
 *
 * Each account provides ~15 GB of Drive quota. Multiple accounts can be
 * registered so the total pseudo-SSD capacity scales with the account count.
 */
@Serializable
data class DriveAccount(
    /** Stable local identifier (UUID). */
    val id: String,
    /** Google account email address. */
    val email: String,
    /** Google account display name, if available. */
    val displayName: String? = null,
    /** Account avatar URL, if available. */
    val photoUrl: String? = null,
    /** Total Drive storage quota in bytes (typically 15 GB). */
    val quotaTotalBytes: Long = DEFAULT_QUOTA_BYTES,
    /** Drive storage currently used in bytes (last known). */
    val quotaUsedBytes: Long = 0L,
    /** Timestamp of the last quota refresh. */
    val quotaUpdatedAt: Long = 0L,
    /** Whether this account is currently enabled for uploads. */
    val enabled: Boolean = true,
    /** Drive folder ID dedicated to this pseudo-SSD (created on first use). */
    val folderId: String? = null,
) {
    val quotaFreeBytes: Long
        get() = (quotaTotalBytes - quotaUsedBytes).coerceAtLeast(0L)

    val usageFraction: Float
        get() = if (quotaTotalBytes > 0) {
            (quotaUsedBytes.toFloat() / quotaTotalBytes.toFloat()).coerceIn(0f, 1f)
        } else 0f

    companion object {
        /** Free Google accounts provide 15 GB of Drive storage. */
        const val DEFAULT_QUOTA_BYTES: Long = 15L * 1024L * 1024L * 1024L
    }
}

/** Lifecycle state of a file tracked by the pseudo-SSD. */
enum class DriveFileState {
    /** Queued for upload, not yet started. */
    QUEUED,

    /** Currently uploading. */
    UPLOADING,

    /** Fully stored on Drive. */
    STORED,

    /** Upload failed (see [DriveFileEntry.errorMessage]). */
    FAILED,

    /** Removed from Drive. */
    DELETED,
}

/**
 * Metadata for a single file (or folder archive) stored in the pseudo-SSD.
 *
 * Because Drive is object storage rather than a filesystem, the app keeps a
 * local index of what lives on Drive so it can be browsed and restored.
 */
@Serializable
data class DriveFileEntry(
    /** Stable local identifier (UUID). */
    val id: String,
    /** Human readable name (original file name). */
    val name: String,
    /** Original absolute path on local storage, if known. */
    val localPath: String? = null,
    /** Drive file ID on the remote account. */
    val driveFileId: String? = null,
    /** ID of the [DriveAccount] that holds this file. */
    val accountId: String? = null,
    /** File size in bytes. */
    val sizeBytes: Long = 0L,
    /** SHA-256 of the content, used to verify integrity after download. */
    val sha256: String? = null,
    /** MIME type, if known. */
    val mimeType: String? = null,
    /** Current lifecycle state. */
    val state: DriveFileState = DriveFileState.QUEUED,
    /** Upload progress 0..1 while [DriveFileState.UPLOADING]. */
    val progress: Float = 0f,
    /** Last error message when [state] is FAILED. */
    val errorMessage: String? = null,
    /** When the entry was created. */
    val createdAt: Long = System.currentTimeMillis(),
    /** When the file finished uploading. */
    val storedAt: Long? = null,
    /** Arbitrary user note / tag. */
    val note: String? = null,
)

/** A pending upload job in the sequential upload queue. */
@Serializable
data class DriveUploadTask(
    /** Stable local identifier (UUID). */
    val id: String,
    /** ID of the [DriveFileEntry] being uploaded. */
    val entryId: String,
    /** Local file to upload. */
    val localPath: String,
    /** Display name for the remote file. */
    val name: String,
    /** File size in bytes. */
    val sizeBytes: Long,
    /** SHA-256 computed before upload, if available. */
    val sha256: String? = null,
    /** MIME type, if known. */
    val mimeType: String? = null,
    /** Enqueue order (lower runs first). */
    val order: Long = System.currentTimeMillis(),
    /** Number of attempts made so far. */
    val attempts: Int = 0,
)

/** Aggregate quota across all registered accounts. */
data class DriveTotalQuota(
    val accountCount: Int,
    val totalBytes: Long,
    val usedBytes: Long,
) {
    val freeBytes: Long get() = (totalBytes - usedBytes).coerceAtLeast(0L)

    val usageFraction: Float
        get() = if (totalBytes > 0) {
            (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else 0f
}
