package io.github.rosyidputrar.duobackup.core.crypto

import io.github.rosyidputrar.duobackup.core.Limits
import io.github.rosyidputrar.duobackup.core.PartSizing
import io.github.rosyidputrar.duobackup.core.testing.CountingRandom
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import org.junit.jupiter.api.Test

class Dbe1Test {
    private val key = ByteArray(32) { (it * 3).toByte() }
    private val frame = Limits.FRAME
    private val storedFrame = frame + Dbe1.FRAME_OVERHEAD

    private fun encrypt(plain: ByteArray, key: ByteArray = this.key, chunk: Int = 70_001): ByteArray {
        val out = ByteArrayOutputStream()
        Dbe1.newWriter(out, key, CountingRandom()).use { writer ->
            var offset = 0
            while (offset < plain.size) {
                val take = minOf(chunk, plain.size - offset)
                writer.write(plain, offset, take)
                offset += take
            }
        }
        return out.toByteArray()
    }

    private fun decrypt(stored: ByteArray, key: ByteArray = this.key): ByteArray =
        Dbe1.newReader(ByteArrayInputStream(stored), key).use { it.readBytes() }

    private fun data(size: Int): ByteArray = Random(size).nextBytes(size)

    /** A three-frame stream: two full frames and a 7-byte final frame. */
    private val threeFramePlain = data(2 * frame + 7)
    private val threeFrames = encrypt(threeFramePlain)

    @Test
    fun `round trip at frame boundaries`() {
        for (size in listOf(0, 1, frame - 1, frame, frame + 1, 5 * frame + 7)) {
            val plain = data(size)
            val stored = encrypt(plain)
            assertEquals(PartSizing.storedSize(size.toLong(), encrypted = true), stored.size.toLong(), "size=$size")
            assertContentEquals(plain, decrypt(stored), "size=$size")
        }
    }

    @Test
    fun `single byte writes and reads work`() {
        val plain = data(300)
        val out = ByteArrayOutputStream()
        Dbe1.newWriter(out, key, CountingRandom()).use { writer -> plain.forEach { writer.write(it.toInt()) } }
        val reader = Dbe1.newReader(ByteArrayInputStream(out.toByteArray()), key)
        val read = ByteArray(300) { reader.read().toByte() }
        assertContentEquals(plain, read)
        assertEquals(-1, reader.read())
        assertEquals(0, reader.read(ByteArray(4), 0, 0))
    }

    @Test
    fun `header carries magic version and the salt from the random source`() {
        val stored = encrypt(ByteArray(0))
        assertContentEquals("DBE1".toByteArray(), stored.copyOfRange(0, 4))
        assertEquals(1, stored[4].toInt())
        assertContentEquals(ByteArray(16) { it.toByte() }, stored.copyOfRange(5, 21))
    }

    @Test
    fun `same plaintext under different salts gives different ciphertext`() {
        val plain = data(1000)
        val a = ByteArrayOutputStream().also { out ->
            Dbe1.newWriter(out, key, CountingRandom(0)).use { it.write(plain) }
        }
        val b = ByteArrayOutputStream().also { out ->
            Dbe1.newWriter(out, key, CountingRandom(1)).use { it.write(plain) }
        }
        assertFalse(a.toByteArray().copyOfRange(21, 1041).contentEquals(b.toByteArray().copyOfRange(21, 1041)))
    }

    @Test
    fun `close is idempotent and writing after close fails`() {
        val out = ByteArrayOutputStream()
        val writer = Dbe1.newWriter(out, key, CountingRandom())
        writer.write(1)
        writer.flush()
        writer.close()
        writer.close()
        assertEquals(PartSizing.storedSize(1, encrypted = true), out.size().toLong())
        assertFailsWith<IllegalStateException> { writer.write(1) }
    }

    @Test
    fun `wrong key fails authentication`() {
        assertFailsWith<CryptoException.AuthFailed> { decrypt(encrypt(data(100)), ByteArray(32)) }
    }

    @Test
    fun `flipped magic byte is rejected`() {
        val stored = threeFrames.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFailsWith<CryptoException.BadHeader> { decrypt(stored) }
    }

    @Test
    fun `unknown version is rejected`() {
        val stored = threeFrames.clone().also { it[4] = 2 }
        assertFailsWith<CryptoException.BadHeader> { decrypt(stored) }
    }

    @Test
    fun `flipped salt byte fails authentication`() {
        val stored = threeFrames.clone().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertFailsWith<CryptoException.AuthFailed> { decrypt(stored) }
    }

    @Test
    fun `flipped ciphertext byte fails authentication`() {
        val stored = threeFrames.clone().also { it[21 + 4 + 500] = (it[21 + 4 + 500].toInt() xor 1).toByte() }
        assertFailsWith<CryptoException.AuthFailed> { decrypt(stored) }
    }

    @Test
    fun `flipped tag byte fails authentication`() {
        val stored = threeFrames.clone().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFailsWith<CryptoException.AuthFailed> { decrypt(stored) }
    }

    @Test
    fun `no plaintext of a tampered frame is released`() {
        val stored = threeFrames.clone()
        val inSecondFrame = 21 + storedFrame + 4 + 9
        stored[inSecondFrame] = (stored[inSecondFrame].toInt() xor 1).toByte()
        val reader = Dbe1.newReader(ByteArrayInputStream(stored), key)
        val firstFrame = ByteArray(frame)
        var total = 0
        while (total < frame) total += reader.read(firstFrame, total, frame - total)
        assertContentEquals(threeFramePlain.copyOfRange(0, frame), firstFrame)
        assertFailsWith<CryptoException.AuthFailed> { reader.read(ByteArray(16)) }
        assertFailsWith<CryptoException.AuthFailed> { reader.read(ByteArray(16)) }
    }

    @Test
    fun `swapped frames fail authentication`() {
        val stored = threeFrames.clone()
        val first = stored.copyOfRange(21, 21 + storedFrame)
        System.arraycopy(stored, 21 + storedFrame, stored, 21, storedFrame)
        System.arraycopy(first, 0, stored, 21 + storedFrame, storedFrame)
        assertFailsWith<CryptoException.AuthFailed> { decrypt(stored) }
    }

    @Test
    fun `truncated last frame is rejected`() {
        assertFailsWith<CryptoException.Truncated> { decrypt(threeFrames.copyOf(threeFrames.size - 3)) }
    }

    @Test
    fun `dropped final frame is rejected`() {
        assertFailsWith<CryptoException.Truncated> { decrypt(threeFrames.copyOf(21 + 2 * storedFrame)) }
    }

    @Test
    fun `stream cut inside a length field is rejected`() {
        assertFailsWith<CryptoException.Truncated> { decrypt(threeFrames.copyOf(21 + storedFrame + 2)) }
    }

    @Test
    fun `header cut short is rejected`() {
        assertFailsWith<CryptoException.BadHeader> { decrypt(threeFrames.copyOf(10)) }
        assertFailsWith<CryptoException.BadHeader> { decrypt(ByteArray(0)) }
    }

    @Test
    fun `extra frame after the final frame is rejected`() {
        val extra = encrypt(data(5)).let { it.copyOfRange(21, it.size) }
        assertFailsWith<CryptoException.TrailingData> { decrypt(threeFrames + extra) }
        assertFailsWith<CryptoException.TrailingData> { decrypt(threeFrames + byteArrayOf(0)) }
    }

    @Test
    fun `cleared final flag is rejected`() {
        val oneFrame = encrypt(data(frame))
        oneFrame[21] = (oneFrame[21].toInt() and 0x7f).toByte()
        assertIs<CryptoException.AuthFailed>(assertFailsWith<CryptoException> { decrypt(oneFrame) })

        val shortFinal = threeFrames.clone()
        val finalHeader = 21 + 2 * storedFrame
        shortFinal[finalHeader] = (shortFinal[finalHeader].toInt() and 0x7f).toByte()
        assertFailsWith<CryptoException.BadFrame> { decrypt(shortFinal) }
    }

    @Test
    fun `final flag set on an earlier frame is rejected`() {
        val stored = threeFrames.clone()
        stored[21] = (stored[21].toInt() or 0x80).toByte()
        assertFailsWith<CryptoException.AuthFailed> { decrypt(stored) }
    }

    @Test
    fun `length field above 1 MiB is rejected`() {
        val stored = threeFrames.clone()
        val length = frame + 1
        stored[21] = (length ushr 24).toByte()
        stored[22] = (length ushr 16).toByte()
        stored[23] = (length ushr 8).toByte()
        stored[24] = length.toByte()
        assertFailsWith<CryptoException.FrameTooLarge> { decrypt(stored) }
    }

    @Test
    fun `seek offset points at the frame holding a plaintext offset`() {
        assertEquals(21L, Dbe1.seekOffset(0))
        assertEquals(21L, Dbe1.seekOffset(frame - 1L))
        assertEquals(21L + storedFrame, Dbe1.seekOffset(frame.toLong()))
        assertEquals(21L + 2L * storedFrame, Dbe1.seekOffset(2L * frame + 7))
        assertEquals(21L + 511L * storedFrame, Dbe1.seekOffset(512L * frame - 1))
        assertFailsWith<IllegalArgumentException> { Dbe1.seekOffset(-1) }
    }

    @Test
    fun `key must be 32 bytes`() {
        assertFailsWith<IllegalArgumentException> {
            Dbe1.newWriter(ByteArrayOutputStream(), ByteArray(16), CountingRandom())
        }
    }

    @Test
    fun `cipher wrapper round trips`() {
        val cipher = Dbe1Cipher(key, CountingRandom())
        val out = ByteArrayOutputStream()
        cipher.wrap(out).use { it.write(data(1234)) }
        assertContentEquals(data(1234), cipher.unwrap(ByteArrayInputStream(out.toByteArray())).use { it.readBytes() })
    }
}
