package io.github.rosyidputrar.duobackup.core.crypto

import io.github.rosyidputrar.duobackup.core.Limits
import io.github.rosyidputrar.duobackup.core.util.SecureRandomSource
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The DBE1 authenticated frame stream (plan §4.6).
 *
 * ```
 * Header : "DBE1" ‖ version 0x01 ‖ salt (16 bytes)
 * Frame* : len (uint32 BE, bit 31 = final) ‖ ciphertext ‖ tag (16 bytes)
 * ```
 */
object Dbe1 {
    const val HEADER_SIZE: Int = 21
    const val SALT_SIZE: Int = 16
    const val TAG_SIZE: Int = 16
    const val FRAME_OVERHEAD: Int = 4 + TAG_SIZE

    private const val VERSION: Byte = 1
    private const val FINAL_FLAG: Int = 1 shl 31
    private const val TAG_BITS: Int = 128
    private val MAGIC = "DBE1".toByteArray(Charsets.US_ASCII)
    private val STREAM_INFO = "duobackup/stream/v1".toByteArray(Charsets.US_ASCII)

    /**
     * Returns a stream that encrypts everything written to it into `out`. The final frame is
     * written by `close()`, which also closes `out`.
     *
     * @param key the content key, or the meta key for index files
     */
    fun newWriter(out: OutputStream, key: ByteArray, random: SecureRandomSource): OutputStream =
        Writer(out, key, random)

    /**
     * Returns a stream of the verified plaintext of `input`. Each frame is authenticated before
     * any of its bytes are released. Failures are reported as [CryptoException].
     */
    fun newReader(input: InputStream, key: ByteArray): InputStream = Reader(input, key)

    /** Offset in the stored stream of the frame holding plaintext offset `plainOffset`. */
    fun seekOffset(plainOffset: Long): Long {
        require(plainOffset >= 0) { "negative offset" }
        return HEADER_SIZE + (plainOffset / Limits.FRAME) * (Limits.FRAME + FRAME_OVERHEAD)
    }

    private fun streamKey(key: ByteArray, salt: ByteArray): SecretKeySpec {
        require(key.size == KeyMaterial.KEY_SIZE) { "key must be ${KeyMaterial.KEY_SIZE} bytes" }
        return SecretKeySpec(Hkdf.sha256(key, salt, STREAM_INFO, KeyMaterial.KEY_SIZE), "AES")
    }

    private fun initCipher(cipher: Cipher, mode: Int, key: SecretKeySpec, index: Long, final: Boolean) {
        val i = index.toInt()
        val nonce = ByteArray(12)
        nonce[8] = (i ushr 24).toByte()
        nonce[9] = (i ushr 16).toByte()
        nonce[10] = (i ushr 8).toByte()
        nonce[11] = i.toByte()
        cipher.init(mode, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(byteArrayOf(VERSION, nonce[8], nonce[9], nonce[10], nonce[11], if (final) 1 else 0))
    }

    private fun checkIndex(index: Long) {
        check(index <= 0xffffffffL) { "too many frames in one stream" }
    }

    private class Writer(private val out: OutputStream, key: ByteArray, random: SecureRandomSource) : OutputStream() {
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val streamKey: SecretKeySpec
        private val buffer = ByteArray(Limits.FRAME)
        private var filled = 0
        private var index = 0L
        private var closed = false

        init {
            val salt = random.nextBytes(SALT_SIZE)
            check(salt.size == SALT_SIZE) { "random source returned a wrong salt size" }
            streamKey = streamKey(key, salt)
            out.write(MAGIC)
            out.write(VERSION.toInt())
            out.write(salt)
        }

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "stream is closed" }
            var offset = off
            var remaining = len
            while (remaining > 0) {
                // A full buffer is only flushed once more data arrives, so the last frame
                // of the stream is always the one flagged final.
                if (filled == buffer.size) writeFrame(final = false)
                val take = minOf(remaining, buffer.size - filled)
                System.arraycopy(b, offset, buffer, filled, take)
                filled += take
                offset += take
                remaining -= take
            }
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            closed = true
            try {
                writeFrame(final = true)
            } finally {
                out.close()
            }
        }

        private fun writeFrame(final: Boolean) {
            checkIndex(index)
            initCipher(cipher, Cipher.ENCRYPT_MODE, streamKey, index, final)
            val sealed = cipher.doFinal(buffer, 0, filled)
            val field = if (final) filled or FINAL_FLAG else filled
            out.write(
                byteArrayOf((field ushr 24).toByte(), (field ushr 16).toByte(), (field ushr 8).toByte(), field.toByte())
            )
            out.write(sealed)
            index++
            filled = 0
        }
    }

    private class Reader(private val input: InputStream, private val key: ByteArray) : InputStream() {
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private var streamKey: SecretKeySpec? = null
        private var frame = ByteArray(0)
        private var position = 0
        private var index = 0L
        private var finished = false
        private var failure: CryptoException? = null

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            failure?.let { throw it }
            while (position == frame.size) {
                if (finished) return -1
                try {
                    nextFrame()
                } catch (e: CryptoException) {
                    failure = e
                    throw e
                }
            }
            val take = minOf(len, frame.size - position)
            System.arraycopy(frame, position, b, off, take)
            position += take
            return take
        }

        override fun close() = input.close()

        private fun nextFrame() {
            val streamKey = streamKey ?: readHeader().also { streamKey = it }
            val head = ByteArray(4)
            if (readFully(head) != head.size) throw CryptoException.Truncated("stream ends before the final frame")
            val field = ((head[0].toInt() and 0xff) shl 24) or ((head[1].toInt() and 0xff) shl 16) or
                ((head[2].toInt() and 0xff) shl 8) or (head[3].toInt() and 0xff)
            val final = field and FINAL_FLAG != 0
            val length = field and FINAL_FLAG.inv()
            if (length > Limits.FRAME) throw CryptoException.FrameTooLarge(length)
            if (!final && length != Limits.FRAME) throw CryptoException.BadFrame("short frame before the final frame")
            val sealed = ByteArray(length + TAG_SIZE)
            if (readFully(sealed) != sealed.size) throw CryptoException.Truncated("frame is cut short")
            checkIndex(index)
            initCipher(cipher, Cipher.DECRYPT_MODE, streamKey, index, final)
            val plain = try {
                cipher.doFinal(sealed)
            } catch (e: AEADBadTagException) {
                throw CryptoException.AuthFailed().apply { initCause(e) }
            }
            if (final) {
                if (input.read() >= 0) throw CryptoException.TrailingData()
                finished = true
            }
            frame = plain
            position = 0
            index++
        }

        private fun readHeader(): SecretKeySpec {
            val header = ByteArray(HEADER_SIZE)
            if (readFully(header) != header.size) throw CryptoException.BadHeader("header is cut short")
            if (!header.copyOfRange(
                    0,
                    MAGIC.size
                ).contentEquals(MAGIC)
            ) {
                throw CryptoException.BadHeader("not a DBE1 stream")
            }
            if (header[MAGIC.size] != VERSION) {
                throw CryptoException.BadHeader("unsupported DBE1 version ${header[MAGIC.size]}")
            }
            return streamKey(key, header.copyOfRange(MAGIC.size + 1, HEADER_SIZE))
        }

        private fun readFully(target: ByteArray): Int {
            var total = 0
            while (total < target.size) {
                val read = input.read(target, total, target.size - total)
                if (read < 0) break
                total += read
            }
            return total
        }
    }
}

/** A DBE1 key bound to a randomness source, for callers that both write and read streams. */
class Dbe1Cipher(private val key: ByteArray, private val random: SecureRandomSource) {
    fun wrap(out: OutputStream): OutputStream = Dbe1.newWriter(out, key, random)

    fun unwrap(input: InputStream): InputStream = Dbe1.newReader(input, key)
}
