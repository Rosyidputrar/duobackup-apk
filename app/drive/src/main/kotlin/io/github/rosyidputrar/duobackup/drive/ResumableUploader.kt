package io.github.rosyidputrar.duobackup.drive

import io.github.rosyidputrar.duobackup.core.transport.TransportException
import io.github.rosyidputrar.duobackup.core.util.Backoff
import io.github.rosyidputrar.duobackup.core.util.Clock
import io.github.rosyidputrar.duobackup.core.util.Hex
import io.github.rosyidputrar.duobackup.core.util.SystemClock
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.random.Random
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

/**
 * Drive's resumable upload protocol: open a session, send the file in chunks, and after any
 * interruption ask the server how far it got instead of starting again.
 *
 * - Network errors, 5xx and rate limits keep the session and back off.
 * - Other 4xx responses, and 404/410 on a position query, drop the session and start from zero.
 * - The failure counter resets whenever a chunk advances, so a slow upload stays alive and only
 *   one that is stuck gives up.
 * - The finished file's size and MD5 must match the local file, or it is deleted and resent.
 */
class ResumableUploader(
    private val api: DriveApi,
    private val sessions: UploadSessionStore,
    private val clock: Clock = SystemClock,
    private val backoff: Backoff = Backoff(),
    private val random: Random = Random.Default,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val initialChunkBytes: Long = INITIAL_CHUNK_BYTES
) {
    private sealed interface ChunkResult {
        data class Incomplete(val nextOffset: Long) : ChunkResult

        data class Complete(val file: DriveFile) : ChunkResult

        data object SessionGone : ChunkResult
    }

    /**
     * Uploads `file` and returns the created Drive file.
     *
     * @param key persists the session so the upload survives a restart; null for small one-off files
     * @param progress called with the number of bytes the server has confirmed
     * @throws TransportException.QuotaExceeded, [TransportException.AuthRequired] at once, and
     *   [TransportException.Transient] after [MAX_FAILURES] consecutive failures
     */
    suspend fun upload(
        key: UploadKey?,
        metadata: DriveFileMetadata,
        file: File,
        progress: (Long) -> Unit = {}
    ): DriveFile {
        val total = file.length()
        val saved = key?.let { loadUsable(it, file, total) }
        var uri = saved?.uri
        var createdAt = saved?.createdAt ?: 0L
        var failures = saved?.failures ?: 0
        var offset: Long? = null
        var chunkBytes = initialChunkBytes
        val md5 = MessageDigest.getInstance("MD5")

        suspend fun dropSession() {
            uri = null
            offset = null
            if (key != null) sessions.clear(key)
        }

        suspend fun failed(error: TransportException, hintMs: Long = 0) {
            failures++
            if (failures >= MAX_FAILURES) throw TransportException.Transient(error)
            uri?.let { current ->
                key?.let { sessions.save(it, SavedSession(current, total, createdAt, failures, file.path)) }
            }
            delay(maxOf(hintMs, backoff.nextDelay(failures - 1, random)))
        }

        while (true) {
            try {
                var session = uri
                if (session != null && clock.now() - createdAt > SESSION_MAX_AGE_MS) {
                    dropSession()
                    session = null
                }
                if (session == null) {
                    session = open(metadata, total)
                    uri = session
                    createdAt = clock.now()
                    offset = 0
                    md5.reset()
                    if (key != null) sessions.save(key, SavedSession(session, total, createdAt, failures, file.path))
                }
                var position = offset
                if (position == null) {
                    when (val state = queryOffset(session, total)) {
                        is ChunkResult.Complete -> return finish(state.file, file, total, key, md5, 0)
                        is ChunkResult.SessionGone -> {
                            dropSession()
                            continue
                        }
                        is ChunkResult.Incomplete -> {
                            position = state.nextOffset
                            offset = position
                            md5.reset()
                            digest(md5, file, 0, position)
                            progress(position)
                        }
                    }
                }
                val length = minOf(chunkBytes, total - position)
                val startedAt = clock.now()
                when (val result = putChunk(session, file, position, length, total)) {
                    is ChunkResult.Complete -> return finish(result.file, file, total, key, md5, position)
                    is ChunkResult.SessionGone -> dropSession()
                    is ChunkResult.Incomplete -> {
                        val next = result.nextOffset
                        if (next > position) {
                            failures = 0
                            digest(md5, file, position, next)
                            chunkBytes = nextChunkSize(next - position, clock.now() - startedAt)
                            offset = next
                            progress(next)
                        } else {
                            // The server kept nothing of this chunk: handled like a failed request.
                            throw TransportException.Transient()
                        }
                    }
                }
            } catch (e: TransportException.RateLimited) {
                offset = null
                failed(e, e.retryAfterMs)
            } catch (e: TransportException.Transient) {
                offset = null
                failed(e)
            } catch (e: TransportException.NotFound) {
                dropSession()
                failed(e)
            } catch (e: TransportException.Permanent) {
                // A rejected session is void. One that could not even be opened will not get better.
                if (uri == null) throw e
                dropSession()
                failed(e)
            } catch (e: UploadMismatch) {
                dropSession()
                failed(TransportException.Transient(e))
            }
        }
    }

    private suspend fun loadUsable(key: UploadKey, file: File, total: Long): SavedSession? {
        val saved = sessions.load(key) ?: return null
        val usable = saved.totalBytes == total &&
            saved.scratchPath == file.path &&
            clock.now() - saved.createdAt <= SESSION_MAX_AGE_MS
        if (!usable) sessions.clear(key)
        return saved.takeIf { usable }
    }

    private suspend fun open(metadata: DriveFileMetadata, total: Long): String {
        val target = api.url("upload/drive/v3/files") {
            addQueryParameter("uploadType", "resumable")
            addQueryParameter("fields", RESULT_FIELDS)
        }
        val response = api.http.executeOk {
            Request.Builder().url(target)
                .header("X-Upload-Content-Type", OCTET_STREAM.toString())
                .header("X-Upload-Content-Length", total.toString())
                .post(api.json(metadata))
        }
        return response.use { it.header("Location") }
            ?: throw TransportException.Transient(IOException("upload session has no location"))
    }

    private suspend fun queryOffset(uri: String, total: Long): ChunkResult {
        val response = api.http.execute {
            Request.Builder().url(uri).header("Content-Range", "bytes */$total").put(EMPTY_BODY)
        }
        return response.use {
            when {
                it.code == 308 -> ChunkResult.Incomplete(confirmedBytes(it.header("Range")))
                it.isSuccessful -> ChunkResult.Complete(api.decode(it.body.string(), DriveFile.serializer()))
                it.code == 404 || it.code == 410 -> ChunkResult.SessionGone
                else -> throw api.http.failure(it)
            }
        }
    }

    private suspend fun putChunk(uri: String, file: File, offset: Long, length: Long, total: Long): ChunkResult {
        val range = if (length == 0L) "bytes */$total" else "bytes $offset-${offset + length - 1}/$total"
        val response = api.http.execute {
            Request.Builder().url(uri).header("Content-Range", range).put(FileSliceBody(file, offset, length))
        }
        return response.use {
            when {
                it.code == 308 -> ChunkResult.Incomplete(confirmedBytes(it.header("Range")))
                it.isSuccessful -> ChunkResult.Complete(api.decode(it.body.string(), DriveFile.serializer()))
                it.code == 404 || it.code == 410 -> ChunkResult.SessionGone
                else -> throw api.http.failure(it)
            }
        }
    }

    /** Checks the finished file against the local one. A mismatch removes the remote copy. */
    private suspend fun finish(
        remote: DriveFile,
        file: File,
        total: Long,
        key: UploadKey?,
        md5: MessageDigest,
        digestedUpTo: Long
    ): DriveFile {
        digest(md5, file, digestedUpTo, total)
        val localMd5 = Hex.encode(md5.digest())
        if (remote.size != total || !remote.md5Checksum.equals(localMd5, ignoreCase = true)) {
            try {
                api.deleteFile(remote.id)
            } catch (e: TransportException.NotFound) {
                // Already gone, which is what was wanted.
            }
            throw UploadMismatch()
        }
        if (key != null) sessions.clear(key)
        return remote
    }

    private suspend fun digest(md5: MessageDigest, file: File, from: Long, to: Long) {
        if (to <= from) return
        withContext(io) {
            try {
                RandomAccessFile(file, "r").use { input ->
                    input.seek(from)
                    val buffer = ByteArray(COPY_BUFFER)
                    var remaining = to - from
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) throw IOException("file is shorter than expected")
                        md5.update(buffer, 0, read)
                        remaining -= read
                    }
                }
            } catch (e: IOException) {
                throw TransportException.Permanent("scratch file cannot be read")
            }
        }
    }

    private class UploadMismatch : Exception("uploaded file does not match the local file")

    private class FileSliceBody(private val file: File, private val offset: Long, private val length: Long) :
        RequestBody() {
        override fun contentType(): MediaType = OCTET_STREAM

        override fun contentLength(): Long = length

        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset)
                val buffer = ByteArray(COPY_BUFFER)
                var remaining = length
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) throw IOException("file is shorter than expected")
                    sink.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
    }

    companion object {
        const val CHUNK_UNIT: Long = 256L * 1024
        const val INITIAL_CHUNK_BYTES: Long = 1024L * 1024
        const val MIN_CHUNK_BYTES: Long = CHUNK_UNIT
        const val MAX_CHUNK_BYTES: Long = 64L * 1024 * 1024
        const val TARGET_CHUNK_MS: Long = 10_000
        const val MAX_FAILURES: Int = 100
        const val SESSION_MAX_AGE_MS: Long = 6L * 24 * 60 * 60 * 1000
        const val RESULT_FIELDS: String = "id,size,md5Checksum"

        private const val COPY_BUFFER = 64 * 1024
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)

        /**
         * The next chunk size: what the measured speed moves in [TARGET_CHUNK_MS], rounded down
         * to a multiple of 256 KiB and kept within the allowed range.
         */
        fun nextChunkSize(sentBytes: Long, elapsedMs: Long): Long {
            val perTarget = if (elapsedMs <= 0) MAX_CHUNK_BYTES else sentBytes * TARGET_CHUNK_MS / elapsedMs
            return (perTarget / CHUNK_UNIT * CHUNK_UNIT).coerceIn(MIN_CHUNK_BYTES, MAX_CHUNK_BYTES)
        }

        /** Bytes the server holds, from a `Range: bytes=0-n` header. No header means none. */
        internal fun confirmedBytes(range: String?): Long {
            val last = range?.substringAfter('-', "")?.trim()?.toLongOrNull() ?: return 0
            return last + 1
        }
    }
}
