package io.github.rosyidputrar.duobackup.core.crypto

import io.github.rosyidputrar.duobackup.core.testing.CountingRandom
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class KeyFileTest {
    private val masterKey = ByteArray(32) { (it + 100).toByte() }
    private val passphrase = "correct horse battery staple"

    private fun create(passphrase: String = this.passphrase): String = KeyFile.create(
        masterKey,
        passphrase.toCharArray(),
        CountingRandom(),
        createdAt = 1_762_758_000_000,
        iterations = 1000
    )

    private fun edit(
        text: String,
        change: (MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit
    ): String {
        val fields = Json.parseToJsonElement(text).jsonObject.toMutableMap()
        change(fields)
        return JsonObject(fields).toString()
    }

    @Test
    fun `round trip returns the master key`() {
        assertContentEquals(masterKey, KeyFile.unlock(create(), passphrase.toCharArray()))
    }

    @Test
    fun `non ASCII passphrase round trips`() {
        val text = create("sandi rahasia ✓ ñ 密码")
        assertContentEquals(masterKey, KeyFile.unlock(text, "sandi rahasia ✓ ñ 密码".toCharArray()))
    }

    @Test
    fun `document has the documented schema`() {
        val root = Json.parseToJsonElement(create()).jsonObject
        assertEquals(listOf("format", "v", "keyId", "kdf", "wrap", "createdAt"), root.keys.toList())
        assertEquals("duobackup-key", root.getValue("format").jsonPrimitive.content)
        assertEquals(1, root.getValue("v").jsonPrimitive.content.toInt())
        assertEquals(KeyMaterial(masterKey).keyId, root.getValue("keyId").jsonPrimitive.content)
        val kdf = root.getValue("kdf").jsonObject
        assertEquals(listOf("alg", "iter", "salt"), kdf.keys.toList())
        assertEquals("PBKDF2-HMAC-SHA256", kdf.getValue("alg").jsonPrimitive.content)
        assertEquals(1000, kdf.getValue("iter").jsonPrimitive.content.toInt())
        val wrap = root.getValue("wrap").jsonObject
        assertEquals(listOf("alg", "nonce", "ct"), wrap.keys.toList())
        assertEquals("AES-256-GCM", wrap.getValue("alg").jsonPrimitive.content)
        assertEquals(1_762_758_000_000, root.getValue("createdAt").jsonPrimitive.content.toLong())
        assertEquals(KeyMaterial(masterKey).keyId, KeyFile.keyIdOf(create()))
    }

    @Test
    fun `default iteration count is 600k`() {
        assertEquals(600_000, KeyFile.DEFAULT_ITERATIONS)
    }

    @Test
    fun `wrong passphrase is reported as such`() {
        assertFailsWith<CryptoException.WrongPassphrase> { KeyFile.unlock(create(), "wrong".toCharArray()) }
        assertFailsWith<CryptoException.WrongPassphrase> { KeyFile.unlock(create(), CharArray(0)) }
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val text = create()
        val ct = Json.parseToJsonElement(
            text
        ).jsonObject.getValue("wrap").jsonObject.getValue("ct").jsonPrimitive.content
        val flipped = (if (ct[0] == 'A') 'B' else 'A') + ct.substring(1)
        val error =
            assertFailsWith<CryptoException> { KeyFile.unlock(text.replace(ct, flipped), passphrase.toCharArray()) }
        assertIs<CryptoException.AuthFailed>(error)
    }

    @Test
    fun `changed key id fails authentication because it is bound as AAD`() {
        val text = edit(create()) { it["keyId"] = JsonPrimitive("0000000000000000") }
        assertFailsWith<CryptoException.AuthFailed> { KeyFile.unlock(text, passphrase.toCharArray()) }
    }

    @Test
    fun `unknown fields are ignored`() {
        val text = edit(create()) { it["future"] = JsonPrimitive("field") }
        assertContentEquals(masterKey, KeyFile.unlock(text, passphrase.toCharArray()))
    }

    @Test
    fun `unknown version and algorithms are rejected`() {
        val newer = edit(create()) { it["v"] = JsonPrimitive(2) }
        assertFailsWith<CryptoException.UnsupportedKeyFile> { KeyFile.unlock(newer, passphrase.toCharArray()) }
        val scrypt = create().replace("PBKDF2-HMAC-SHA256", "scrypt")
        assertFailsWith<CryptoException.UnsupportedKeyFile> { KeyFile.unlock(scrypt, passphrase.toCharArray()) }
    }

    @Test
    fun `files that are not key files are rejected`() {
        val pass = passphrase.toCharArray()
        assertFailsWith<CryptoException.BadKeyFile> { KeyFile.unlock("not json", pass) }
        assertFailsWith<CryptoException.BadKeyFile> { KeyFile.unlock("{}", pass) }
        assertFailsWith<CryptoException.BadKeyFile> { KeyFile.unlock("""{"format":"duobackup-key","v":1}""", pass) }
        val other = edit(create()) { it["format"] = JsonPrimitive("something-else") }
        assertFailsWith<CryptoException.BadKeyFile> { KeyFile.unlock(other, pass) }
        val badBase64 = create().replace("\"nonce\":\"", "\"nonce\":\"!!")
        assertFailsWith<CryptoException.BadKeyFile> { KeyFile.unlock(badBase64, pass) }
        val shortNonce = create().let { text ->
            val nonce = Json.parseToJsonElement(text).jsonObject.getValue("wrap").jsonObject.getValue("nonce")
            text.replace(nonce.jsonPrimitive.content, "AAAA")
        }
        assertFailsWith<CryptoException.BadKeyFile> { KeyFile.unlock(shortNonce, pass) }
    }

    @Test
    fun `create validates its arguments`() {
        assertFailsWith<IllegalArgumentException> {
            KeyFile.create(ByteArray(16), passphrase.toCharArray(), CountingRandom(), 0, 1000)
        }
        assertFailsWith<IllegalArgumentException> { KeyFile.create(masterKey, CharArray(0), CountingRandom(), 0, 1000) }
        assertFailsWith<IllegalArgumentException> {
            KeyFile.create(masterKey, passphrase.toCharArray(), CountingRandom(), 0, 0)
        }
        assertTrue(create().startsWith("{\"format\":\"duobackup-key\""))
    }
}
