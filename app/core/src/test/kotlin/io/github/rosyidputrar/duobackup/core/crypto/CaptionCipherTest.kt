package io.github.rosyidputrar.duobackup.core.crypto

import io.github.rosyidputrar.duobackup.core.testing.CountingRandom
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class CaptionCipherTest {
    private val cipher = CaptionCipher(ByteArray(32) { it.toByte() }, CountingRandom())

    @Test
    fun `round trip and layout`() {
        val plain = "hello caption".toByteArray()
        val sealed = cipher.seal(plain)
        assertEquals(plain.size + CaptionCipher.OVERHEAD, sealed.size)
        assertContentEquals(ByteArray(12) { it.toByte() }, sealed.copyOfRange(0, 12))
        assertContentEquals(plain, cipher.open(sealed))
        assertContentEquals(ByteArray(0), cipher.open(cipher.seal(ByteArray(0))))
    }

    @Test
    fun `every flipped byte fails authentication`() {
        val sealed = cipher.seal("hello".toByteArray())
        for (i in sealed.indices) {
            val tampered = sealed.clone().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFailsWith<CryptoException.AuthFailed>("byte $i") { cipher.open(tampered) }
        }
    }

    @Test
    fun `wrong key and short input fail authentication`() {
        val sealed = cipher.seal("hello".toByteArray())
        val other = CaptionCipher(ByteArray(32) { 9 }, CountingRandom())
        assertFailsWith<CryptoException.AuthFailed> { other.open(sealed) }
        assertFailsWith<CryptoException.AuthFailed> { cipher.open(ByteArray(27)) }
    }

    @Test
    fun `key must be 32 bytes`() {
        assertFailsWith<IllegalArgumentException> { CaptionCipher(ByteArray(16), CountingRandom()) }
    }
}
