package io.github.rosyidputrar.duobackup.core.catalog

import io.github.rosyidputrar.duobackup.core.model.DestinationId

enum class FileState { NEEDS_HASH, HASHED, DELETED, ERROR }

enum class GcState { LIVE, DELETING, GONE }

enum class RunTrigger { SCHEDULED, MANUAL }

enum class RunResult { OK, PARTIAL, FAILED }

/** One path ever seen on a volume. `id` is 0 until the row is stored. */
data class FileRecord(
    val id: Long = 0,
    val volume: String,
    val path: String,
    val size: Long,
    val mtime: Long,
    val currentSha256: String? = null,
    val state: FileState = FileState.NEEDS_HASH,
    val error: String? = null,
    val lastSeenScan: Long,
    val hold: Boolean = false
)

/** One distinct plaintext content, addressed by its SHA-256. */
data class ContentRecord(
    val sha256: String,
    val oid: String,
    val size: Long,
    val partSize: Long,
    val partCount: Int,
    val encrypted: Boolean,
    val mime: String? = null,
    val createdAt: Long,
    val gcState: GcState = GcState.LIVE
)

data class ContentPartRecord(
    val sha256: String,
    val partIndex: Int,
    val plainOffset: Long,
    val plainSize: Long,
    val partSha256: String
)

/** Where one part lives on one destination. Its presence is the proof the part was uploaded. */
data class PartLocationRecord(
    val sha256: String,
    val partIndex: Int,
    val dest: DestinationId,
    val storedSize: Long,
    val tgChatId: Long? = null,
    val tgMessageId: Long? = null,
    val tgRemoteFileId: String? = null,
    val tgRemoteUniqueId: String? = null,
    val driveFileId: String? = null,
    val uploadedAt: Long,
    val verifiedAt: Long? = null,
    val missing: Boolean = false
)

/** A version is live in the half-open interval `[firstSeen, supersededAt)`. */
data class FileVersionRecord(
    val fileId: Long,
    val sha256: String,
    val mtime: Long,
    val firstSeen: Long,
    val supersededAt: Long? = null
)

/** A file together with the version of it that was live at the queried time. */
data class LiveVersion(val file: FileRecord, val version: FileVersionRecord)

data class RunRecord(
    val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val trigger: RunTrigger,
    val result: RunResult? = null,
    val stopReason: String? = null,
    val bytesUploadedDrive: Long = 0,
    val bytesUploadedTg: Long = 0,
    val filesHashed: Int = 0,
    val errors: Int = 0
)

/** How a run ended, written to its `runs` row. */
data class RunSummary(
    val endedAt: Long,
    val result: RunResult,
    val stopReason: String? = null,
    val bytesUploadedDrive: Long = 0,
    val bytesUploadedTg: Long = 0,
    val filesHashed: Int = 0,
    val errors: Int = 0
)

/** One row of the browse tree: a file, or a folder summarising everything below it. */
data class FolderEntry(val name: String, val isFolder: Boolean, val fileCount: Int, val totalBytes: Long)

/** Upload progress of one content on one destination. */
data class ContentCompleteness(val sha256: String, val partCount: Int, val locatedParts: Int) {
    val complete: Boolean get() = locatedParts >= partCount
}
