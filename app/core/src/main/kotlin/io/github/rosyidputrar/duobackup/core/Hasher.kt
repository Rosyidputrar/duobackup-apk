package io.github.rosyidputrar.duobackup.core

import io.github.rosyidputrar.duobackup.core.util.Hex
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Whole-content SHA-256, its size, and the SHA-256 of every part, all lowercase hex. */
data class HashResult(val sha256: String, val size: Long, val partHashes: List<String>)

/** Computes the content hash and all part hashes in a single read pass. */
object Hasher {
    fun hash(input: InputStream, partSize: Long): HashResult = hash(input, partSize) {}

    /** Same as [hash], but stops with a `CancellationException` when the caller is cancelled. */
    suspend fun hashCancellable(input: InputStream, partSize: Long): HashResult {
        val context = coroutineContext
        return hash(input, partSize) { context.ensureActive() }
    }

    private inline fun hash(input: InputStream, partSize: Long, checkpoint: () -> Unit): HashResult {
        require(partSize > 0) { "part size must be positive" }
        val whole = MessageDigest.getInstance("SHA-256")
        val part = MessageDigest.getInstance("SHA-256")
        val partHashes = ArrayList<String>()
        val buffer = ByteArray(Limits.FRAME)
        var size = 0L
        var inPart = 0L
        while (true) {
            checkpoint()
            val read = input.read(buffer)
            if (read < 0) break
            whole.update(buffer, 0, read)
            size += read
            var offset = 0
            while (offset < read) {
                val take = minOf((read - offset).toLong(), partSize - inPart).toInt()
                part.update(buffer, offset, take)
                offset += take
                inPart += take
                if (inPart == partSize) {
                    partHashes += Hex.encode(part.digest())
                    inPart = 0
                }
            }
        }
        if (inPart > 0) partHashes += Hex.encode(part.digest())
        return HashResult(Hex.encode(whole.digest()), size, partHashes)
    }
}
