package io.github.rosyidputrar.duobackup.data.db

import io.github.rosyidputrar.duobackup.core.catalog.Catalog
import io.github.rosyidputrar.duobackup.core.catalog.ContentCompleteness
import io.github.rosyidputrar.duobackup.core.catalog.ContentPartRecord
import io.github.rosyidputrar.duobackup.core.catalog.ContentRecord
import io.github.rosyidputrar.duobackup.core.catalog.FileRecord
import io.github.rosyidputrar.duobackup.core.catalog.FileState
import io.github.rosyidputrar.duobackup.core.catalog.FileVersionRecord
import io.github.rosyidputrar.duobackup.core.catalog.FolderEntry
import io.github.rosyidputrar.duobackup.core.catalog.LiveVersion
import io.github.rosyidputrar.duobackup.core.catalog.PartLocationRecord
import io.github.rosyidputrar.duobackup.core.catalog.RunRecord
import io.github.rosyidputrar.duobackup.core.catalog.RunSummary
import io.github.rosyidputrar.duobackup.core.catalog.RunTrigger
import io.github.rosyidputrar.duobackup.core.model.DestinationId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The engine's view of the Room database. */
class RoomCatalog(db: CatalogDatabase) : Catalog {
    private val files = db.fileDao()
    private val contents = db.contentDao()
    private val versions = db.versionDao()
    private val runs = db.runDao()

    override suspend fun startScan(root: String, startedAt: Long): Long =
        runs.insertScan(ScanEntity(startedAt = startedAt, completedAt = null, root = root))

    override suspend fun completeScan(scanId: Long, completedAt: Long) = runs.completeScan(scanId, completedAt)

    override suspend fun upsertFiles(files: List<FileRecord>) = this.files.upsertAll(files.map { it.toEntity() })

    override suspend fun fileByPath(volume: String, path: String): FileRecord? = files.byPath(volume, path)?.toRecord()

    override suspend fun filesByState(state: FileState, limit: Int): List<FileRecord> =
        files.byState(state, limit).map { it.toRecord() }

    override fun observeFolder(volume: String, folder: String): Flow<List<FolderEntry>> {
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        return files.observeFolder(volume, prefix).map { rows ->
            rows.map { FolderEntry(it.name, it.isFolder, it.fileCount, it.totalBytes) }
        }
    }

    override suspend fun insertContent(content: ContentRecord, parts: List<ContentPartRecord>) =
        contents.insertWithParts(content.toEntity(), parts.map { it.toEntity() })

    override suspend fun contentBySha256(sha256: String): ContentRecord? = contents.bySha256(sha256)?.toRecord()

    override suspend fun recordPartLocation(location: PartLocationRecord) = contents.upsertLocation(location.toEntity())

    override suspend fun pendingParts(dest: DestinationId, limit: Int): List<ContentPartRecord> =
        contents.pendingParts(dest, limit).map { it.toRecord() }

    override fun observePendingBytes(dest: DestinationId): Flow<Long> = contents.observePendingBytes(dest)

    override suspend fun contentCompleteness(dest: DestinationId): List<ContentCompleteness> =
        contents.completeness(dest).map { ContentCompleteness(it.sha256, it.partCount, it.locatedParts) }

    override suspend fun addVersion(version: FileVersionRecord) = versions.insert(version.toEntity())

    override suspend fun supersedeCurrentVersion(fileId: Long, at: Long) = versions.supersedeCurrent(fileId, at)

    override suspend fun versionsLiveAt(time: Long): List<LiveVersion> =
        versions.liveAt(time).map { LiveVersion(it.file.toRecord(), it.version.toRecord()) }

    override suspend fun unreferencedContents(): List<ContentRecord> = contents.unreferenced().map { it.toRecord() }

    override suspend fun startRun(trigger: RunTrigger, startedAt: Long): Long = runs.insertRun(
        RunEntity(
            startedAt = startedAt,
            endedAt = null,
            trigger = trigger,
            result = null,
            stopReason = null,
            bytesUploadedDrive = 0,
            bytesUploadedTg = 0,
            filesHashed = 0,
            errors = 0
        )
    )

    override suspend fun finishRun(runId: Long, summary: RunSummary) = runs.finishRun(
        id = runId,
        endedAt = summary.endedAt,
        result = summary.result,
        stopReason = summary.stopReason,
        bytesDrive = summary.bytesUploadedDrive,
        bytesTg = summary.bytesUploadedTg,
        filesHashed = summary.filesHashed,
        errors = summary.errors
    )

    override fun observeRuns(limit: Int): Flow<List<RunRecord>> =
        runs.observeRuns(limit).map { rows -> rows.map { it.toRecord() } }
}

private fun FileRecord.toEntity() =
    FileEntity(id, volume, path, size, mtime, currentSha256, state, error, lastSeenScan, hold)

private fun FileEntity.toRecord() =
    FileRecord(id, volume, path, size, mtime, currentSha256, state, error, lastSeenScan, hold)

private fun ContentRecord.toEntity() =
    ContentEntity(sha256, oid, size, partSize, partCount, encrypted, mime, createdAt, gcState)

private fun ContentEntity.toRecord() =
    ContentRecord(sha256, oid, size, partSize, partCount, encrypted, mime, createdAt, gcState)

private fun ContentPartRecord.toEntity() = ContentPartEntity(sha256, partIndex, plainOffset, plainSize, partSha256)

private fun ContentPartEntity.toRecord() = ContentPartRecord(sha256, partIndex, plainOffset, plainSize, partSha256)

private fun PartLocationRecord.toEntity() = PartLocationEntity(
    sha256,
    partIndex,
    dest,
    storedSize,
    tgChatId,
    tgMessageId,
    tgRemoteFileId,
    tgRemoteUniqueId,
    driveFileId,
    uploadedAt,
    verifiedAt,
    missing
)

private fun FileVersionRecord.toEntity() = FileVersionEntity(fileId, sha256, mtime, firstSeen, supersededAt)

private fun FileVersionEntity.toRecord() = FileVersionRecord(fileId, sha256, mtime, firstSeen, supersededAt)

private fun RunEntity.toRecord() = RunRecord(
    id,
    startedAt,
    endedAt,
    trigger,
    result,
    stopReason,
    bytesUploadedDrive,
    bytesUploadedTg,
    filesHashed,
    errors
)
