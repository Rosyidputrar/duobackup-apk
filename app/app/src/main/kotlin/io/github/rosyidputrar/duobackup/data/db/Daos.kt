package io.github.rosyidputrar.duobackup.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.rosyidputrar.duobackup.core.catalog.FileState
import io.github.rosyidputrar.duobackup.core.catalog.RunResult
import io.github.rosyidputrar.duobackup.core.model.DestinationId
import kotlinx.coroutines.flow.Flow

data class FolderEntryRow(
    val name: String,
    @ColumnInfo(name = "is_folder") val isFolder: Boolean,
    @ColumnInfo(name = "file_count") val fileCount: Int,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long
)

data class CompletenessRow(
    val sha256: String,
    @ColumnInfo(name = "part_count") val partCount: Int,
    @ColumnInfo(name = "located_parts") val locatedParts: Int
)

data class LiveVersionRow(@Embedded val file: FileEntity, @Embedded(prefix = "v_") val version: FileVersionEntity)

@Dao
interface FileDao {
    @Insert
    suspend fun insert(file: FileEntity): Long

    @Query("SELECT * FROM files WHERE volume = :volume AND path = :path")
    suspend fun byPath(volume: String, path: String): FileEntity?

    @Query(
        "UPDATE files SET size = :size, mtime = :mtime, current_sha256 = :currentSha256, state = :state, " +
            "error = :error, last_seen_scan = :lastSeenScan, hold = :hold WHERE id = :id"
    )
    suspend fun update(
        id: Long,
        size: Long,
        mtime: Long,
        currentSha256: String?,
        state: FileState,
        error: String?,
        lastSeenScan: Long,
        hold: Boolean
    )

    /** Inserts new paths and updates known ones by `(volume, path)`, keeping their ids. */
    @Transaction
    suspend fun upsertAll(files: List<FileEntity>) {
        for (file in files) {
            val existing = byPath(file.volume, file.path)
            if (existing == null) {
                insert(file.copy(id = 0))
            } else {
                update(
                    existing.id,
                    file.size,
                    file.mtime,
                    file.currentSha256,
                    file.state,
                    file.error,
                    file.lastSeenScan,
                    file.hold
                )
            }
        }
    }

    @Query("SELECT * FROM files WHERE state = :state ORDER BY id LIMIT :limit")
    suspend fun byState(state: FileState, limit: Int): List<FileEntity>

    /**
     * Direct children of a folder. `prefix` is "" for the volume root, otherwise the folder path
     * with a trailing slash. Everything below a sub-folder is summed into one folder row.
     */
    @Query(
        "SELECT CASE WHEN instr(rest, '/') = 0 THEN rest ELSE substr(rest, 1, instr(rest, '/') - 1) END AS name, " +
            "MAX(instr(rest, '/') > 0) AS is_folder, COUNT(*) AS file_count, SUM(size) AS total_bytes " +
            "FROM (SELECT substr(path, length(:prefix) + 1) AS rest, size FROM files " +
            "WHERE volume = :volume AND state != 'DELETED' AND substr(path, 1, length(:prefix)) = :prefix) " +
            "GROUP BY name ORDER BY is_folder DESC, name COLLATE NOCASE"
    )
    fun observeFolder(volume: String, prefix: String): Flow<List<FolderEntryRow>>
}

@Dao
interface ContentDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertContent(content: ContentEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertParts(parts: List<ContentPartEntity>)

    /** Stores a content with its parts atomically. An existing content is left untouched. */
    @Transaction
    suspend fun insertWithParts(content: ContentEntity, parts: List<ContentPartEntity>) {
        if (insertContent(content) != -1L) insertParts(parts)
    }

    @Query("SELECT * FROM contents WHERE sha256 = :sha256")
    suspend fun bySha256(sha256: String): ContentEntity?

    @Query("SELECT * FROM content_parts WHERE sha256 = :sha256 ORDER BY part_index")
    suspend fun partsOf(sha256: String): List<ContentPartEntity>

    @Upsert
    suspend fun upsertLocation(location: PartLocationEntity)

    @Query("SELECT * FROM part_locations WHERE sha256 = :sha256 ORDER BY part_index, dest")
    suspend fun locationsOf(sha256: String): List<PartLocationEntity>

    @Query(
        "SELECT p.* FROM content_parts p JOIN contents c ON c.sha256 = p.sha256 " +
            "WHERE c.gc_state = 'LIVE' AND NOT EXISTS (SELECT 1 FROM part_locations l " +
            "WHERE l.sha256 = p.sha256 AND l.part_index = p.part_index AND l.dest = :dest AND l.missing = 0) " +
            "ORDER BY c.created_at, p.sha256, p.part_index LIMIT :limit"
    )
    suspend fun pendingParts(dest: DestinationId, limit: Int): List<ContentPartEntity>

    @Query(
        "SELECT COALESCE(SUM(p.plain_size), 0) FROM content_parts p JOIN contents c ON c.sha256 = p.sha256 " +
            "WHERE c.gc_state = 'LIVE' AND NOT EXISTS (SELECT 1 FROM part_locations l " +
            "WHERE l.sha256 = p.sha256 AND l.part_index = p.part_index AND l.dest = :dest AND l.missing = 0)"
    )
    fun observePendingBytes(dest: DestinationId): Flow<Long>

    @Query(
        "SELECT c.sha256 AS sha256, c.part_count AS part_count, " +
            "(SELECT COUNT(*) FROM part_locations l WHERE l.sha256 = c.sha256 AND l.dest = :dest AND l.missing = 0) " +
            "AS located_parts FROM contents c WHERE c.gc_state = 'LIVE' ORDER BY c.created_at, c.sha256"
    )
    suspend fun completeness(dest: DestinationId): List<CompletenessRow>

    @Query(
        "SELECT * FROM contents c WHERE c.gc_state = 'LIVE' " +
            "AND NOT EXISTS (SELECT 1 FROM file_versions v WHERE v.sha256 = c.sha256) ORDER BY c.created_at, c.sha256"
    )
    suspend fun unreferenced(): List<ContentEntity>
}

@Dao
interface VersionDao {
    @Insert
    suspend fun insert(version: FileVersionEntity)

    @Query("UPDATE file_versions SET superseded_at = :at WHERE file_id = :fileId AND superseded_at IS NULL")
    suspend fun supersedeCurrent(fileId: Long, at: Long)

    @Query("SELECT * FROM file_versions WHERE file_id = :fileId ORDER BY first_seen")
    suspend fun versionsOf(fileId: Long): List<FileVersionEntity>

    @Query(
        "SELECT f.*, v.file_id AS v_file_id, v.sha256 AS v_sha256, v.mtime AS v_mtime, " +
            "v.first_seen AS v_first_seen, v.superseded_at AS v_superseded_at " +
            "FROM file_versions v JOIN files f ON f.id = v.file_id " +
            "WHERE v.first_seen <= :time AND (v.superseded_at IS NULL OR v.superseded_at > :time) " +
            "ORDER BY f.volume, f.path"
    )
    suspend fun liveAt(time: Long): List<LiveVersionRow>
}

@Dao
interface RunDao {
    @Insert
    suspend fun insertRun(run: RunEntity): Long

    @Query(
        "UPDATE runs SET ended_at = :endedAt, result = :result, stop_reason = :stopReason, " +
            "bytes_uploaded_drive = :bytesDrive, bytes_uploaded_tg = :bytesTg, files_hashed = :filesHashed, " +
            "errors = :errors WHERE id = :id"
    )
    suspend fun finishRun(
        id: Long,
        endedAt: Long,
        result: RunResult,
        stopReason: String?,
        bytesDrive: Long,
        bytesTg: Long,
        filesHashed: Int,
        errors: Int
    )

    @Query("SELECT * FROM runs ORDER BY started_at DESC, id DESC LIMIT :limit")
    fun observeRuns(limit: Int): Flow<List<RunEntity>>

    @Insert
    suspend fun insertScan(scan: ScanEntity): Long

    @Query("UPDATE scans SET completed_at = :completedAt WHERE id = :id")
    suspend fun completeScan(id: Long, completedAt: Long)

    @Query("SELECT * FROM scans WHERE id = :id")
    suspend fun scanById(id: Long): ScanEntity?
}

@Dao
interface SnapshotDao {
    @Insert
    suspend fun insert(snapshot: SnapshotEntity): Long

    @Query("SELECT * FROM snapshots ORDER BY created_at DESC, id DESC")
    suspend fun newestFirst(): List<SnapshotEntity>

    @Query("DELETE FROM snapshots WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface DriveSessionDao {
    @Upsert
    suspend fun upsert(session: DriveSessionEntity)

    @Query("SELECT * FROM drive_sessions WHERE sha256 = :sha256 AND part_index = :partIndex")
    suspend fun find(sha256: String, partIndex: Int): DriveSessionEntity?

    @Query("DELETE FROM drive_sessions WHERE sha256 = :sha256 AND part_index = :partIndex")
    suspend fun delete(sha256: String, partIndex: Int)

    /** Removes sessions created before `cutoff`; Drive drops them after about a week. */
    @Query("DELETE FROM drive_sessions WHERE created_at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int
}
