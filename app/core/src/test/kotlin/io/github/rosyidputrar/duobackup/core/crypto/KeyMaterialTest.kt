package io.github.rosyidputrar.duobackup.core.crypto

import io.github.rosyidputrar.duobackup.core.util.Hex
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.junit.jupiter.api.Test

class KeyMaterialTest {
    private val masterKey = ByteArray(32) { it.toByte() }
    private val keys = KeyMaterial(masterKey)

    @Test
    fun `subkeys come from HKDF with an empty salt and the documented info strings`() {
        fun expected(info: String) = Hkdf.sha256(masterKey, ByteArray(0), info.toByteArray(), 32)
        assertContentEquals(expected("duobackup/content/v1"), keys.contentKey)
        assertContentEquals(expected("duobackup/meta/v1"), keys.metaKey)
        assertContentEquals(expected("duobackup/id/v1"), keys.idKey)
        assertFalse(keys.contentKey.contentEquals(keys.metaKey))
        assertFalse(keys.metaKey.contentEquals(keys.idKey))
    }

    @Test
    fun `key id is the first 16 hex chars of the tagged master key hash`() {
        val digest = MessageDigest.getInstance("SHA-256").digest("duobackup/kid/v1".toByteArray() + masterKey)
        assertEquals(Hex.encode(digest).take(16), keys.keyId)
        assertEquals(16, keys.keyId.length)
    }

    @Test
    fun `master key must be 32 bytes`() {
        assertFailsWith<IllegalArgumentException> { KeyMaterial(ByteArray(31)) }
    }

    @Test
    fun `oid of unencrypted content is its hash`() {
        val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(hash, Oid.of(hash, null))
        assertEquals(hash, Oid.of(hash.uppercase(), null))
    }

    @Test
    fun `oid of encrypted content is the HMAC of the hash bytes under the id key`() {
        val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(keys.idKey, "HmacSHA256")) }
        val expected = Hex.encode(mac.doFinal(Hex.decode(hash)))
        assertEquals(expected, Oid.of(hash, keys.idKey))
        assertEquals(64, expected.length)
        assertFalse(expected == hash)
    }

    @Test
    fun `oid rejects strings that are not a SHA-256`() {
        assertFailsWith<IllegalArgumentException> { Oid.of("abcd", null) }
        assertFailsWith<IllegalArgumentException> { Oid.of("z".repeat(64), null) }
    }
}
