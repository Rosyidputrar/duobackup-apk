package io.github.rosyidputrar.duobackup.drive

/** Identifies the part an upload session belongs to. */
data class UploadKey(val sha256: String, val partIndex: Int)

/**
 * A resumable upload session worth keeping across process death.
 *
 * @property failures consecutive failures since the last chunk that advanced
 * @property scratchPath the file being uploaded; a session is only reusable with the same file
 */
data class SavedSession(
    val uri: String,
    val totalBytes: Long,
    val createdAt: Long,
    val failures: Int,
    val scratchPath: String
)

/** Where upload sessions are persisted. The app backs this with the `drive_sessions` table. */
interface UploadSessionStore {
    suspend fun load(key: UploadKey): SavedSession?

    suspend fun save(key: UploadKey, session: SavedSession)

    suspend fun clear(key: UploadKey)
}
