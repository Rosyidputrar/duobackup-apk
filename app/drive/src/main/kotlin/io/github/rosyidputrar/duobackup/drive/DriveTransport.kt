package io.github.rosyidputrar.duobackup.drive

import io.github.rosyidputrar.duobackup.core.Limits
import io.github.rosyidputrar.duobackup.core.format.DriveProps
import io.github.rosyidputrar.duobackup.core.format.RemoteNames
import io.github.rosyidputrar.duobackup.core.model.DestinationId
import io.github.rosyidputrar.duobackup.core.model.DestinationLimits
import io.github.rosyidputrar.duobackup.core.model.RemoteLocation
import io.github.rosyidputrar.duobackup.core.transport.BackupTransport
import io.github.rosyidputrar.duobackup.core.transport.IndexMeta
import io.github.rosyidputrar.duobackup.core.transport.IndexRef
import io.github.rosyidputrar.duobackup.core.transport.PartPresence
import io.github.rosyidputrar.duobackup.core.transport.PartUpload
import io.github.rosyidputrar.duobackup.core.transport.PauseReason
import io.github.rosyidputrar.duobackup.core.transport.PrepareResult
import io.github.rosyidputrar.duobackup.core.transport.TransportException
import io.github.rosyidputrar.duobackup.core.util.Clock
import io.github.rosyidputrar.duobackup.core.util.Hex
import io.github.rosyidputrar.duobackup.core.util.SystemClock
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** @property uploadMs and [downloadMs] are wall-clock times for [bytes] bytes each way */
data class ProbeResult(val bytes: Long, val uploadMs: Long, val downloadMs: Long)

/** Google Drive as a backup destination. Files are private to the app's `drive.file` scope. */
class DriveTransport(
    private val api: DriveApi,
    private val uploader: ResumableUploader,
    private val layout: DriveLayout,
    private val clock: Clock = SystemClock,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : BackupTransport {
    override val id: DestinationId = DestinationId.DRIVE

    override suspend fun prepare(): PrepareResult = try {
        val resolved = layout.resolve()
        PrepareResult.Ready(limits(), resolved.warnings)
    } catch (e: TransportException.AuthRequired) {
        PrepareResult.Paused(PauseReason.AUTH)
    } catch (e: TransportException.QuotaExceeded) {
        PrepareResult.Paused(PauseReason.QUOTA)
    } catch (e: TransportException) {
        PrepareResult.Paused(PauseReason.OFFLINE, e.javaClass.simpleName)
    }

    override suspend fun limits(): DestinationLimits = DestinationLimits(
        maxFileBytes = Limits.DRIVE_MAX_FILE_BYTES,
        // Drive has no captions; the largest value keeps it out of any minimum over destinations.
        captionMax = Int.MAX_VALUE,
        quotaFreeBytes = api.about("storageQuota").storageQuota.freeBytes
    )

    override suspend fun uploadPart(req: PartUpload, progress: (sentBytes: Long) -> Unit): RemoteLocation =
        withFolders { folders ->
            // A part that finished uploading just before a crash is found again here, instead
            // of being stored twice.
            existingPart(folders.data, req)?.let { return@withFolders RemoteLocation.Drive(it.id) }
            val metadata = DriveFileMetadata(
                name = RemoteNames.part(req.oid, req.partIndex),
                parents = listOf(folders.data),
                appProperties = DriveProps.part(req.oid, req.partIndex, req.partHash)
            )
            val key = UploadKey(req.caption.contentSha256, req.partIndex)
            RemoteLocation.Drive(uploader.upload(key, metadata, req.file, progress).id)
        }

    override suspend fun downloadPart(loc: RemoteLocation, target: File, progress: (receivedBytes: Long) -> Unit) {
        val fileId = fileId(loc)
        val expected = api.getFile(fileId, "id,size,trashed").size
            ?: throw TransportException.Permanent("Drive file has no size")
        if (target.exists() && target.length() > expected) target.delete()
        val start = if (target.exists()) target.length() else 0
        val written = api.download(fileId, target, start, progress)
        if (written != expected) throw TransportException.Transient()
    }

    override suspend fun checkPart(loc: RemoteLocation): PartPresence = try {
        if (api.getFile(fileId(loc), "id,size,trashed").trashed) PartPresence.MISSING else PartPresence.PRESENT
    } catch (e: TransportException.NotFound) {
        PartPresence.MISSING
    } catch (e: TransportException) {
        PartPresence.UNKNOWN
    }

    override suspend fun deletePart(loc: RemoteLocation) = deleteIfPresent(fileId(loc))

    override suspend fun putIndex(file: File, meta: IndexMeta): RemoteLocation = withFolders { folders ->
        val metadata = DriveFileMetadata(
            name = RemoteNames.index(meta.generatedAt, meta.encrypted),
            parents = listOf(folders.index),
            appProperties = DriveProps.index(meta.generatedAt, meta.keyId)
        )
        RemoteLocation.Drive(uploader.upload(null, metadata, file).id)
    }

    override suspend fun listIndexes(): List<IndexRef> = withFolders { folders ->
        api.listAllFiles(DriveLayout.indexQuery(folders.index))
            .mapNotNull { file ->
                val generatedAt = file.appProperties["ts"]?.toLongOrNull() ?: return@mapNotNull null
                IndexRef(RemoteLocation.Drive(file.id), generatedAt, file.appProperties["kid"])
            }
            .sortedByDescending { it.generatedAt }
    }

    override suspend fun getIndex(ref: IndexRef, target: File) {
        api.download(fileId(ref.location), target)
    }

    override suspend fun deleteIndex(ref: IndexRef) = deleteIfPresent(fileId(ref.location))

    override suspend fun putKeyFile(bytes: ByteArray): RemoteLocation =
        throw UnsupportedOperationException("key files arrive with encryption")

    override suspend fun getKeyFile(): ByteArray? =
        throw UnsupportedOperationException("key files arrive with encryption")

    /** The signed-in account and its storage quota. */
    suspend fun account(): DriveAbout = api.about()

    /**
     * Uploads random bytes as a throwaway file, reads them back, compares them and deletes the
     * file. It proves the whole path works without touching any backup data.
     *
     * @param scratchDir a directory for the two temporary files, which are removed afterwards
     * @throws TransportException.Permanent when the bytes read back differ
     */
    suspend fun probe(scratchDir: File, bytes: Int = PROBE_BYTES, random: Random = Random.Default): ProbeResult {
        val source = File(scratchDir, "probe-upload.bin")
        val copy = File(scratchDir, "probe-download.bin")
        var remoteId: String? = null
        try {
            withContext(io) { source.writeBytes(random.nextBytes(bytes)) }
            val folders = layout.resolve().folders
            val metadata = DriveFileMetadata(
                name = "probe.dbp",
                parents = listOf(folders.data),
                appProperties = mapOf(DriveProps.KEY_KIND to KIND_PROBE)
            )
            val uploadStarted = clock.now()
            remoteId = uploader.upload(null, metadata, source).id
            val downloadStarted = clock.now()
            api.download(remoteId, copy)
            val finished = clock.now()
            if (withContext(io) {
                    sha256(source) != sha256(copy)
                }
            ) {
                throw TransportException.Permanent("probe read back different bytes")
            }
            return ProbeResult(bytes.toLong(), downloadStarted - uploadStarted, finished - downloadStarted)
        } finally {
            source.delete()
            copy.delete()
            remoteId?.let { deleteIfPresent(it) }
        }
    }

    /** Runs `block` with the folder ids, looking them up again once if a cached folder is gone. */
    private suspend fun <T> withFolders(block: suspend (DriveFolders) -> T): T = try {
        block(layout.resolve().folders)
    } catch (e: TransportException.NotFound) {
        layout.invalidate()
        block(layout.resolve().folders)
    }

    private suspend fun existingPart(dataDir: String, req: PartUpload): DriveFile? {
        val query = listOf(
            "'$dataDir' in parents",
            DriveLayout.property("oid", req.oid),
            DriveLayout.property("pi", req.partIndex.toString()),
            "trashed = false"
        ).joinToString(" and ")
        val candidates = api.listAllFiles(query).filter {
            it.size == req.storedSize && it.appProperties["ph"] == req.partHash && it.md5Checksum != null
        }
        if (candidates.isEmpty()) return null
        val localMd5 = withContext(io) { Hex.encode(digest("MD5", req.file)) }
        return candidates.firstOrNull { it.md5Checksum.equals(localMd5, ignoreCase = true) }
    }

    private suspend fun deleteIfPresent(fileId: String) {
        try {
            api.deleteFile(fileId)
        } catch (e: TransportException.NotFound) {
            // Already gone, which is what was wanted.
        }
    }

    private fun fileId(loc: RemoteLocation): String =
        (loc as? RemoteLocation.Drive)?.fileId ?: throw TransportException.Permanent("not a Drive location")

    private fun sha256(file: File): String = Hex.encode(digest("SHA-256", file))

    private fun digest(algorithm: String, file: File): ByteArray {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    companion object {
        const val PROBE_BYTES: Int = 1024 * 1024
        const val KIND_PROBE: String = "probe"
    }
}
