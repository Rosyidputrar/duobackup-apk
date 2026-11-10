package io.github.rosyidputrar.duobackup.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.rosyidputrar.duobackup.core.catalog.FileState
import io.github.rosyidputrar.duobackup.core.catalog.GcState
import io.github.rosyidputrar.duobackup.core.catalog.RunResult
import io.github.rosyidputrar.duobackup.core.catalog.RunTrigger
import io.github.rosyidputrar.duobackup.core.model.DestinationId

@Entity(
    tableName = "files",
    indices = [Index(value = ["volume", "path"], unique = true), Index("state")]
)
data class FileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val volume: String,
    val path: String,
    val size: Long,
    val mtime: Long,
    @ColumnInfo(name = "current_sha256") val currentSha256: String?,
    val state: FileState,
    val error: String?,
    @ColumnInfo(name = "last_seen_scan") val lastSeenScan: Long,
    @ColumnInfo(defaultValue = "0") val hold: Boolean = false
)

@Entity(tableName = "contents", indices = [Index(value = ["oid"], unique = true)])
data class ContentEntity(
    @PrimaryKey val sha256: String,
    val oid: String,
    val size: Long,
    @ColumnInfo(name = "part_size") val partSize: Long,
    @ColumnInfo(name = "part_count") val partCount: Int,
    val encrypted: Boolean,
    val mime: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "gc_state") val gcState: GcState
)

@Entity(
    tableName = "content_parts",
    primaryKeys = ["sha256", "part_index"],
    foreignKeys = [
        ForeignKey(
            entity = ContentEntity::class,
            parentColumns = ["sha256"],
            childColumns = ["sha256"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ContentPartEntity(
    val sha256: String,
    @ColumnInfo(name = "part_index") val partIndex: Int,
    @ColumnInfo(name = "plain_offset") val plainOffset: Long,
    @ColumnInfo(name = "plain_size") val plainSize: Long,
    @ColumnInfo(name = "part_sha256") val partSha256: String
)

@Entity(
    tableName = "part_locations",
    primaryKeys = ["sha256", "part_index", "dest"],
    foreignKeys = [
        ForeignKey(
            entity = ContentPartEntity::class,
            parentColumns = ["sha256", "part_index"],
            childColumns = ["sha256", "part_index"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class PartLocationEntity(
    val sha256: String,
    @ColumnInfo(name = "part_index") val partIndex: Int,
    val dest: DestinationId,
    @ColumnInfo(name = "stored_size") val storedSize: Long,
    @ColumnInfo(name = "tg_chat_id") val tgChatId: Long?,
    @ColumnInfo(name = "tg_message_id") val tgMessageId: Long?,
    @ColumnInfo(name = "tg_remote_file_id") val tgRemoteFileId: String?,
    @ColumnInfo(name = "tg_remote_unique_id") val tgRemoteUniqueId: String?,
    @ColumnInfo(name = "drive_file_id") val driveFileId: String?,
    @ColumnInfo(name = "uploaded_at") val uploadedAt: Long,
    @ColumnInfo(name = "verified_at") val verifiedAt: Long?,
    @ColumnInfo(defaultValue = "0") val missing: Boolean = false
)

@Entity(
    tableName = "file_versions",
    primaryKeys = ["file_id", "sha256", "first_seen"],
    foreignKeys = [
        ForeignKey(
            entity = FileEntity::class,
            parentColumns = ["id"],
            childColumns = ["file_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(entity = ContentEntity::class, parentColumns = ["sha256"], childColumns = ["sha256"])
    ],
    indices = [Index("sha256")]
)
data class FileVersionEntity(
    @ColumnInfo(name = "file_id") val fileId: Long,
    val sha256: String,
    val mtime: Long,
    @ColumnInfo(name = "first_seen") val firstSeen: Long,
    @ColumnInfo(name = "superseded_at") val supersededAt: Long?
)

/** A persisted Drive resumable upload session for one part. */
@Entity(tableName = "drive_sessions", primaryKeys = ["sha256", "part_index"])
data class DriveSessionEntity(
    val sha256: String,
    @ColumnInfo(name = "part_index") val partIndex: Int,
    @ColumnInfo(name = "session_uri") val sessionUri: String,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val failures: Int,
    @ColumnInfo(name = "scratch_path") val scratchPath: String
)

@Entity(tableName = "snapshots")
data class SnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "file_count") val fileCount: Int,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long,
    @ColumnInfo(name = "pending_parts") val pendingParts: Int,
    @ColumnInfo(name = "drive_file_id") val driveFileId: String?,
    @ColumnInfo(name = "tg_message_id") val tgMessageId: Long?,
    @ColumnInfo(name = "format_version") val formatVersion: Int
)

@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    val trigger: RunTrigger,
    val result: RunResult?,
    @ColumnInfo(name = "stop_reason") val stopReason: String?,
    @ColumnInfo(name = "bytes_uploaded_drive") val bytesUploadedDrive: Long,
    @ColumnInfo(name = "bytes_uploaded_tg") val bytesUploadedTg: Long,
    @ColumnInfo(name = "files_hashed") val filesHashed: Int,
    val errors: Int
)

@Entity(tableName = "scans")
data class ScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
    val root: String
)
