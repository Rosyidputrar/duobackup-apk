package io.github.rosyidputrar.duobackup.core.transport

import io.github.rosyidputrar.duobackup.core.format.PartCaption
import io.github.rosyidputrar.duobackup.core.model.DestinationId
import io.github.rosyidputrar.duobackup.core.model.DestinationLimits
import io.github.rosyidputrar.duobackup.core.model.RemoteLocation
import java.io.File

/** Why a destination sits out a session. */
enum class PauseReason { AUTH, QUOTA, OFFLINE }

sealed interface PrepareResult {
    /** The destination is signed in and its container exists. */
    data class Ready(val limits: DestinationLimits, val warnings: List<String> = emptyList()) : PrepareResult

    data class Paused(val reason: PauseReason, val detail: String? = null) : PrepareResult
}

enum class PartPresence { PRESENT, MISSING, UNKNOWN }

/**
 * One part ready to upload.
 *
 * @property file the scratch file holding the stored bytes, named with the remote part name
 * @property partHash the part SHA-256, or its HMAC under the id key when encrypted
 * @property storedSize size of `file` in bytes
 * @property caption the manifest a destination may attach to the part
 */
data class PartUpload(
    val file: File,
    val oid: String,
    val partIndex: Int,
    val partHash: String,
    val storedSize: Long,
    val caption: PartCaption
)

/** @property keyId set when the index is encrypted */
data class IndexMeta(val generatedAt: Long, val keyId: String? = null) {
    val encrypted: Boolean get() = keyId != null
}

data class IndexRef(val location: RemoteLocation, val generatedAt: Long, val keyId: String? = null) {
    val encrypted: Boolean get() = keyId != null
}

/**
 * A backup destination. Implementations throw [TransportException] and never report a part as
 * uploaded before the destination has confirmed it.
 */
interface BackupTransport {
    val id: DestinationId

    suspend fun prepare(): PrepareResult

    suspend fun limits(): DestinationLimits

    suspend fun uploadPart(req: PartUpload, progress: (sentBytes: Long) -> Unit): RemoteLocation

    suspend fun downloadPart(loc: RemoteLocation, target: File, progress: (receivedBytes: Long) -> Unit)

    suspend fun checkPart(loc: RemoteLocation): PartPresence

    suspend fun deletePart(loc: RemoteLocation)

    suspend fun putIndex(file: File, meta: IndexMeta): RemoteLocation

    /** Newest first. */
    suspend fun listIndexes(): List<IndexRef>

    suspend fun getIndex(ref: IndexRef, target: File)

    suspend fun deleteIndex(ref: IndexRef)

    suspend fun putKeyFile(bytes: ByteArray): RemoteLocation

    suspend fun getKeyFile(): ByteArray?
}
