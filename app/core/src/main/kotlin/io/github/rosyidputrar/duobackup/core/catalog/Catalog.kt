package io.github.rosyidputrar.duobackup.core.catalog

import io.github.rosyidputrar.duobackup.core.model.DestinationId
import kotlinx.coroutines.flow.Flow

/**
 * The local catalog: every file seen, every content, where each part is stored, and the log of
 * scans and runs. It is the only view of the database the engine has.
 */
interface Catalog {
    suspend fun startScan(root: String, startedAt: Long): Long

    suspend fun completeScan(scanId: Long, completedAt: Long)

    /** Inserts new paths and updates known ones, in one transaction. */
    suspend fun upsertFiles(files: List<FileRecord>)

    suspend fun fileByPath(volume: String, path: String): FileRecord?

    suspend fun filesByState(state: FileState, limit: Int): List<FileRecord>

    /** Direct children of `folder` ("" is the volume root), folders first, deleted files left out. */
    fun observeFolder(volume: String, folder: String): Flow<List<FolderEntry>>

    /** Stores a content and its parts in one transaction. A content that already exists is kept. */
    suspend fun insertContent(content: ContentRecord, parts: List<ContentPartRecord>)

    suspend fun contentBySha256(sha256: String): ContentRecord?

    /** A part counts as uploaded only once this has returned. */
    suspend fun recordPartLocation(location: PartLocationRecord)

    /** Parts of live contents with no usable location on `dest`, oldest content first. */
    suspend fun pendingParts(dest: DestinationId, limit: Int): List<ContentPartRecord>

    /** Plaintext bytes still to upload to `dest`. */
    fun observePendingBytes(dest: DestinationId): Flow<Long>

    suspend fun contentCompleteness(dest: DestinationId): List<ContentCompleteness>

    suspend fun addVersion(version: FileVersionRecord)

    /** Ends the current version of a file, because it changed or was deleted. */
    suspend fun supersedeCurrentVersion(fileId: Long, at: Long)

    suspend fun versionsLiveAt(time: Long): List<LiveVersion>

    /** Live contents that no file version refers to any more. */
    suspend fun unreferencedContents(): List<ContentRecord>

    suspend fun startRun(trigger: RunTrigger, startedAt: Long): Long

    suspend fun finishRun(runId: Long, summary: RunSummary)

    /** Newest first. */
    fun observeRuns(limit: Int): Flow<List<RunRecord>>
}
