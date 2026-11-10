package io.github.rosyidputrar.duobackup.core.format

import io.github.rosyidputrar.duobackup.core.crypto.CryptoException
import io.github.rosyidputrar.duobackup.core.crypto.Dbe1Cipher
import io.github.rosyidputrar.duobackup.core.format.IndexSnapshot.Content
import io.github.rosyidputrar.duobackup.core.format.IndexSnapshot.Part
import io.github.rosyidputrar.duobackup.core.format.IndexSnapshot.TelegramRef
import io.github.rosyidputrar.duobackup.core.model.DestinationId
import io.github.rosyidputrar.duobackup.core.testing.CountingRandom
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class IndexSnapshotTest {
    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)
    private val hashC = "c".repeat(64)
    private val cipher = Dbe1Cipher(ByteArray(32) { it.toByte() }, CountingRandom())

    private fun tg(message: Long) =
        TelegramRef(chatId = -1_001_234_567_890, messageId = message, remoteFileId = "file-$message")

    private fun snapshot(generatedAt: Long = 1_762_758_000_000, contents: List<Content> = emptyList()) = IndexSnapshot(
        generatedAt = generatedAt,
        device = "Pixel 9",
        install = "0b9d5c2e-6a1f-4c1e-9b7a-3f2d1e0c9b8a",
        encryption = IndexSnapshot.Encryption(enabled = false),
        session = IndexSnapshot.Session(
            runId = 42,
            result = "PARTIAL",
            pendingParts = 12,
            bytesUploaded = mapOf(DestinationId.DRIVE to 123, DestinationId.TELEGRAM to 456)
        ),
        quota = mapOf(DestinationId.DRIVE to IndexSnapshot.Quota(limit = 429_496_729_600, usage = 123)),
        contents = contents,
        files = listOf(
            IndexSnapshot.FileEntry("primary", "DCIM/Camera/a.jpg", hashA, 12_345, 1_762_700_000_000, "image/jpeg")
        )
    )

    private fun content(hash: String, vararg parts: Part) =
        Content(hash, hash, 734_003_200, 536_870_912, 0, parts.toList())

    private fun gzip(text: String): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray()) } }.toByteArray()

    private fun write(snapshot: IndexSnapshot, cipher: Dbe1Cipher? = null): ByteArray =
        ByteArrayOutputStream().also { IndexSnapshotCodec.write(snapshot, it, cipher) }.toByteArray()

    @Test
    fun `JSON uses the documented keys`() {
        val full = snapshot(contents = listOf(content(hashA, Part(0, hashB, tg(456), "drive-id"))))
        assertEquals(
            "{\"format\":\"duobackup-index\",\"v\":1,\"generatedAt\":1762758000000,\"device\":\"Pixel 9\"," +
                "\"install\":\"0b9d5c2e-6a1f-4c1e-9b7a-3f2d1e0c9b8a\",\"encryption\":{\"enabled\":false}," +
                "\"session\":{\"runId\":42,\"result\":\"PARTIAL\",\"pendingParts\":12," +
                "\"bytesUploaded\":{\"DRIVE\":123,\"TELEGRAM\":456}}," +
                "\"quota\":{\"DRIVE\":{\"limit\":429496729600,\"usage\":123}}," +
                "\"contents\":[{\"h\":\"$hashA\",\"o\":\"$hashA\",\"s\":734003200,\"ps\":536870912,\"e\":0," +
                "\"parts\":[{\"i\":0,\"ph\":\"$hashB\"," +
                "\"tg\":{\"c\":-1001234567890,\"m\":456,\"f\":\"file-456\"},\"gd\":\"drive-id\"}]}]," +
                "\"files\":[{\"vol\":\"primary\",\"p\":\"DCIM/Camera/a.jpg\",\"h\":\"$hashA\",\"s\":12345," +
                "\"mt\":1762700000000,\"m\":\"image/jpeg\"}]}",
            IndexSnapshotCodec.toJson(full)
        )
    }

    @Test
    fun `plain round trip is gzip of the JSON`() {
        val original = snapshot(contents = listOf(content(hashA, Part(0, hashB, driveFileId = "drive-id"))))
        val bytes = write(original)
        assertEquals(original, IndexSnapshotCodec.read(ByteArrayInputStream(bytes)))
        val json = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }.toString(Charsets.UTF_8)
        assertEquals(IndexSnapshotCodec.toJson(original), json)
        assertFalse("\"tg\"" in json)
    }

    @Test
    fun `encrypted round trip is a DBE1 stream`() {
        val original = snapshot().copy(
            encryption = IndexSnapshot.Encryption(enabled = true, keyId = "0123456789abcdef")
        )
        val bytes = write(original, cipher)
        assertContentEquals("DBE1".toByteArray(), bytes.copyOfRange(0, 4))
        assertEquals(original, IndexSnapshotCodec.read(ByteArrayInputStream(bytes), cipher))
        val tampered = bytes.clone().also { it[30] = (it[30].toInt() xor 1).toByte() }
        assertFailsWith<CryptoException.AuthFailed> { IndexSnapshotCodec.read(ByteArrayInputStream(tampered), cipher) }
        assertFailsWith<CryptoException.TrailingData> {
            IndexSnapshotCodec.read(ByteArrayInputStream(bytes + 0), cipher)
        }
    }

    @Test
    fun `unknown fields are ignored and a minimal document reads`() {
        val json = "{\"format\":\"duobackup-index\",\"v\":1,\"generatedAt\":5,\"device\":\"d\",\"install\":\"i\"," +
            "\"future\":{\"x\":1},\"files\":[{\"vol\":\"primary\",\"p\":\"a\",\"h\":\"$hashA\",\"s\":1,\"mt\":2,\"z\":0}]}"
        val read = IndexSnapshotCodec.read(ByteArrayInputStream(gzip(json)))
        assertEquals(5, read.generatedAt)
        assertEquals("a", read.files.single().path)
        assertEquals(null, read.session)
        assertFalse(read.encryption.enabled)
    }

    @Test
    fun `unknown version and foreign documents are rejected`() {
        val base = IndexSnapshotCodec.toJson(snapshot())
        val newer = gzip(base.replace("\"v\":1", "\"v\":2"))
        val error =
            assertFailsWith<FormatException.UnsupportedVersion> { IndexSnapshotCodec.read(ByteArrayInputStream(newer)) }
        assertEquals("2", error.version)
        for (bad in listOf(
            base.replace("duobackup-index", "other"),
            "[]",
            "{}",
            "{\"format\":\"duobackup-index\"}",
            "nope"
        )) {
            assertFailsWith<FormatException.Malformed>(bad.take(20)) {
                IndexSnapshotCodec.read(ByteArrayInputStream(gzip(bad)))
            }
        }
        val missingField = gzip("{\"format\":\"duobackup-index\",\"v\":1}")
        assertFailsWith<FormatException.Malformed> { IndexSnapshotCodec.read(ByteArrayInputStream(missingField)) }
        assertFailsWith<FormatException.Malformed> {
            IndexSnapshotCodec.read(ByteArrayInputStream("plain text".toByteArray()))
        }
        val cut = write(snapshot()).let { it.copyOf(it.size - 5) }
        assertFailsWith<FormatException.Malformed> { IndexSnapshotCodec.read(ByteArrayInputStream(cut)) }
    }

    @Test
    fun `merge takes the newest snapshot as the base`() {
        val older = snapshot(generatedAt = 100).copy(device = "old")
        val newer = snapshot(generatedAt = 200).copy(device = "new")
        assertEquals("new", IndexSnapshot.merge(older, newer).device)
        assertEquals("new", IndexSnapshot.merge(newer, older).device)
        assertEquals(newer.files, IndexSnapshot.merge(older, newer).files)
        val tieA = snapshot(generatedAt = 100).copy(device = "a")
        val tieB = snapshot(generatedAt = 100).copy(device = "b")
        assertEquals("a", IndexSnapshot.merge(tieA, tieB).device)
    }

    @Test
    fun `merge unions contents and adds up part locations`() {
        val drive = snapshot(
            generatedAt = 200,
            contents = listOf(
                content(hashA, Part(0, hashB, driveFileId = "gd-a0"), Part(1, hashC, driveFileId = "gd-a1")),
                content(hashB, Part(0, hashA, driveFileId = "gd-b0"))
            )
        )
        val telegram = snapshot(
            generatedAt = 100,
            contents = listOf(
                content(hashA, Part(0, hashB, telegram = tg(1)), Part(2, hashA, telegram = tg(3))),
                content(hashC, Part(0, hashC, telegram = tg(9)))
            )
        )
        val expected = listOf(
            content(
                hashA,
                Part(0, hashB, tg(1), "gd-a0"),
                Part(1, hashC, driveFileId = "gd-a1"),
                Part(2, hashA, telegram = tg(3))
            ),
            content(hashB, Part(0, hashA, driveFileId = "gd-b0")),
            content(hashC, Part(0, hashC, telegram = tg(9)))
        )
        assertEquals(expected, IndexSnapshot.merge(drive, telegram).contents)
        assertEquals(expected.toSet(), IndexSnapshot.merge(telegram, drive).contents.toSet())
        assertTrue(IndexSnapshot.merge(drive, telegram).generatedAt == 200L)
    }

    @Test
    fun `merge prefers the base when both sides know a location`() {
        val newer = snapshot(generatedAt = 200, contents = listOf(content(hashA, Part(0, hashB, tg(2), "new"))))
        val older = snapshot(generatedAt = 100, contents = listOf(content(hashA, Part(0, hashB, tg(1), "old"))))
        assertEquals(Part(0, hashB, tg(2), "new"), IndexSnapshot.merge(older, newer).contents.single().parts.single())
    }
}
