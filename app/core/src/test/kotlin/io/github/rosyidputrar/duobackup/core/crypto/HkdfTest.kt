package io.github.rosyidputrar.duobackup.core.crypto

import io.github.rosyidputrar.duobackup.core.util.Hex
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

/** RFC 5869 appendix A, test cases 1 to 3. */
class HkdfTest {
    private fun hkdf(ikm: String, salt: String, info: String, len: Int): String =
        Hex.encode(Hkdf.sha256(Hex.decode(ikm), Hex.decode(salt), Hex.decode(info), len))

    @Test
    fun `RFC 5869 test case 1`() {
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            hkdf("0b".repeat(22), "000102030405060708090a0b0c", "f0f1f2f3f4f5f6f7f8f9", 42)
        )
    }

    @Test
    fun `RFC 5869 test case 2`() {
        val ikm = (0x00..0x4f).joinToString("") { "%02x".format(it) }
        val salt = (0x60..0xaf).joinToString("") { "%02x".format(it) }
        val info = (0xb0..0xff).joinToString("") { "%02x".format(it) }
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c59045a99cac7827271cb41c65e590e09" +
                "da3275600c2f09b8367793a9aca3db71cc30c58179ec3e87c14c01d5c1f3434f1d87",
            hkdf(ikm, salt, info, 82)
        )
    }

    @Test
    fun `RFC 5869 test case 3 with empty salt and info`() {
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            hkdf("0b".repeat(22), "", "", 42)
        )
    }

    @Test
    fun `output length is bounded`() {
        assertFailsWith<IllegalArgumentException> { Hkdf.sha256(ByteArray(1), ByteArray(0), ByteArray(0), 0) }
        assertFailsWith<IllegalArgumentException> {
            Hkdf.sha256(ByteArray(1), ByteArray(0), ByteArray(0), 255 * 32 + 1)
        }
    }
}
