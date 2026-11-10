package io.github.rosyidputrar.duobackup.core.format

import io.github.rosyidputrar.duobackup.core.crypto.CaptionCipher
import io.github.rosyidputrar.duobackup.core.crypto.CryptoException
import io.github.rosyidputrar.duobackup.core.testing.CountingRandom
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class CaptionCodecTest {
    private val hash = "9f2c0a1b33d4e5f60718293a4b5c6d7e8f909192a3b4c5d6e7f8091a2b3c4d5e"
    private val cipher = CaptionCipher(ByteArray(32) { it.toByte() }, CountingRandom())

    private fun caption(path: String = "DCIM/Camera/IMG_20261110_101500.jpg", encrypted: Int = 0) = PartCaption(
        oid = hash,
        contentSha256 = hash,
        contentSize = 734_003_200,
        contentPartSize = 536_870_912,
        partCount = 2,
        partIndex = 1,
        plainOffset = 536_870_912,
        plainSize = 197_132_288,
        partSha256 = hash.reversed(),
        encrypted = encrypted,
        path = path,
        volume = "primary",
        mtime = 1_762_700_000_000,
        mime = "image/jpeg"
    )

    @Test
    fun `plain caption is db1 plus compact JSON with the documented keys in order`() {
        val text = CaptionCodec.encode(caption(), 1024, null)
        assertEquals(
            "db1:{\"v\":1,\"o\":\"$hash\",\"h\":\"$hash\",\"s\":734003200,\"ps0\":536870912,\"pc\":2,\"pi\":1," +
                "\"po\":536870912,\"pl\":197132288,\"ph\":\"${hash.reversed()}\",\"e\":0," +
                "\"p\":\"DCIM/Camera/IMG_20261110_101500.jpg\",\"vol\":\"primary\",\"mt\":1762700000000," +
                "\"m\":\"image/jpeg\"}",
            text
        )
        assertEquals(caption(), CaptionCodec.decode(text))
    }

    @Test
    fun `optional fields are omitted when absent`() {
        val text = CaptionCodec.encode(caption().copy(mime = null), 1024, null)
        assertFalse("\"m\"" in text)
        assertFalse("\"pt\"" in text)
        assertEquals(caption().copy(mime = null), CaptionCodec.decode(text))
    }

    @Test
    fun `encrypted caption round trips and hides its content`() {
        val text = CaptionCodec.encode(caption(encrypted = 1), 1024, cipher)
        assertTrue(text.startsWith("dbe1:"))
        assertFalse(hash in text)
        assertFalse("=" in text)
        assertTrue(text.length <= 1024)
        assertEquals(caption(encrypted = 1), CaptionCodec.decode(text, cipher))
    }

    @Test
    fun `encrypted caption needs a key and the right one`() {
        val text = CaptionCodec.encode(caption(encrypted = 1), 1024, cipher)
        assertFailsWith<FormatException.KeyRequired> { CaptionCodec.decode(text) }
        val other = CaptionCipher(ByteArray(32) { 1 }, CountingRandom())
        assertFailsWith<CryptoException.AuthFailed> { CaptionCodec.decode(text, other) }
        val tampered = text.dropLast(1) + if (text.last() == 'A') 'B' else 'A'
        assertFailsWith<CryptoException.AuthFailed> { CaptionCodec.decode(tampered, cipher) }
        assertFailsWith<FormatException.Malformed> { CaptionCodec.decode("dbe1:***", cipher) }
    }

    @Test
    fun `a long path is shortened from the front and keeps the file name`() {
        val path = "Download/" + "nested-directory/".repeat(60) + "holiday-video-final.mp4"
        for (budget in listOf(1024, 2048)) {
            for (active in listOf(null, cipher)) {
                val full = CaptionCodec.encode(caption(path), 100_000, active)
                val text = CaptionCodec.encode(caption(path), budget, active)
                val decoded = CaptionCodec.decode(text, active)!!
                if (full.length <= budget) {
                    assertEquals(path, decoded.path)
                    assertNull(decoded.pathTruncated)
                    continue
                }
                assertTrue(text.length <= budget, "budget=$budget length=${text.length}")
                assertEquals(1, decoded.pathTruncated)
                assertTrue(path.endsWith(decoded.path))
                assertTrue(decoded.path.endsWith("/holiday-video-final.mp4"))
                assertEquals(caption(path).copy(path = decoded.path, pathTruncated = 1), decoded)
                // The kept tail is maximal: one more character would not fit.
                val longer = caption(path).copy(path = path.takeLast(decoded.path.length + 1), pathTruncated = 1)
                assertTrue(CaptionCodec.encode(longer, 100_000, active).length > budget)
            }
        }
    }

    @Test
    fun `plain budget is measured in UTF-16 code units`() {
        val path = "Pictures/" + "写真".repeat(400) + "/😀.jpg"
        val text = CaptionCodec.encode(caption(path), 1024, null)
        assertEquals(1024, text.length)
        val decoded = CaptionCodec.decode(text)!!
        assertEquals(1, decoded.pathTruncated)
        assertTrue(decoded.path.endsWith("/😀.jpg"))
        assertTrue(path.endsWith(decoded.path))
    }

    @Test
    fun `shortening never splits a surrogate pair`() {
        val path = "😀".repeat(600)
        for (budget in 420..440) {
            val decoded = CaptionCodec.decode(CaptionCodec.encode(caption(path), budget, null))!!
            assertFalse(Character.isLowSurrogate(decoded.path.first()), "budget=$budget")
            assertEquals(0, decoded.path.length % 2, "budget=$budget")
        }
    }

    @Test
    fun `non ASCII path fits an encrypted budget`() {
        val path = "Pictures/" + "写真".repeat(400) + "/😀.jpg"
        val text = CaptionCodec.encode(caption(path, encrypted = 1), 1024, cipher)
        assertTrue(text.length <= 1024)
        val decoded = CaptionCodec.decode(text, cipher)!!
        assertEquals(1, decoded.pathTruncated)
        assertTrue(decoded.path.endsWith("/😀.jpg"))
    }

    @Test
    fun `a budget too small for anything but the path throws`() {
        val error = assertFailsWith<FormatException.CaptionTooLong> { CaptionCodec.encode(caption(), 200, null) }
        assertEquals(200, error.budget)
        assertFailsWith<FormatException.CaptionTooLong> { CaptionCodec.encode(caption(encrypted = 1), 300, cipher) }
        assertFailsWith<FormatException.CaptionTooLong> { CaptionCodec.encode(caption(path = "a"), 100, null) }
    }

    @Test
    fun `foreign captions decode to null`() {
        assertNull(CaptionCodec.decode(""))
        assertNull(CaptionCodec.decode("holiday photos"))
        assertNull(CaptionCodec.decode("td1:{\"v\":1}"))
        assertNull(CaptionCodec.decode("#duobackup-index v1 1762758000000"))
        assertNull(CaptionCodec.decode("DB1:{}"))
    }

    @Test
    fun `unknown version is rejected`() {
        val newer = CaptionCodec.encode(caption(), 1024, null).replace("\"v\":1", "\"v\":2")
        val error = assertFailsWith<FormatException.UnsupportedVersion> { CaptionCodec.decode(newer) }
        assertEquals("2", error.version)
    }

    @Test
    fun `unknown keys are ignored`() {
        val text = CaptionCodec.encode(caption(), 1024, null).replace("{\"v\":1", "{\"v\":1,\"future\":[1,2]")
        assertEquals(caption(), CaptionCodec.decode(text))
    }

    @Test
    fun `malformed captions with the prefix are errors`() {
        assertFailsWith<FormatException.Malformed> { CaptionCodec.decode("db1:") }
        assertFailsWith<FormatException.Malformed> { CaptionCodec.decode("db1:not json") }
        assertFailsWith<FormatException.Malformed> { CaptionCodec.decode("db1:[1]") }
        assertFailsWith<FormatException.Malformed> { CaptionCodec.decode("db1:{}") }
        assertFailsWith<FormatException.Malformed> { CaptionCodec.decode("db1:{\"v\":1}") }
        assertFailsWith<FormatException.Malformed> { CaptionCodec.decode("db1:{\"v\":\"x\"}") }
    }
}
