package io.github.rosyidputrar.duobackup.core.golden

import io.github.rosyidputrar.duobackup.core.PartSizing
import io.github.rosyidputrar.duobackup.core.crypto.Dbe1
import io.github.rosyidputrar.duobackup.core.crypto.KeyFile
import io.github.rosyidputrar.duobackup.core.format.CaptionCodec
import io.github.rosyidputrar.duobackup.core.format.IndexSnapshotCodec
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks the remote formats: each committed file must still decode, and encoding the same input
 * with the same randomness must still produce the same bytes.
 */
class GoldenTest {
    private fun text(name: String): String = GoldenVectors.resource(name).toString(Charsets.UTF_8)

    private fun gunzip(bytes: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }.toString(Charsets.UTF_8)

    @Test
    fun `DBE1 streams decode and re-encode byte for byte`() {
        assertEquals(3, GoldenVectors.streams.size)
        for (stream in GoldenVectors.streams) {
            val plain = GoldenVectors.resource("${stream.name}.plain")
            val stored = GoldenVectors.resource("${stream.name}.bin")
            assertContentEquals(stream.plain, plain, stream.name)
            assertEquals(
                PartSizing.storedSize(plain.size.toLong(), encrypted = true),
                stored.size.toLong(),
                stream.name
            )
            val decoded = Dbe1.newReader(ByteArrayInputStream(stored), GoldenVectors.keys.contentKey).use {
                it.readBytes()
            }
            assertContentEquals(plain, decoded, stream.name)
            assertContentEquals(stored, stream.encrypt(), stream.name)
        }
    }

    @Test
    fun `key file unlocks and is recreated byte for byte`() {
        val golden = text("keyfile-v1.json")
        assertContentEquals(GoldenVectors.masterKey, KeyFile.unlock(golden, GoldenVectors.PASSPHRASE.toCharArray()))
        assertEquals(GoldenVectors.keys.keyId, KeyFile.keyIdOf(golden))
        assertEquals(golden, GoldenVectors.keyFile())
    }

    @Test
    fun `plain caption decodes and re-encodes byte for byte`() {
        val golden = text("caption-plain.txt")
        assertEquals(GoldenVectors.caption, CaptionCodec.decode(golden))
        assertEquals(golden, GoldenVectors.captionPlain())
    }

    @Test
    fun `encrypted caption decodes and re-encodes byte for byte`() {
        val golden = text("caption-enc.txt")
        assertEquals(GoldenVectors.captionEncrypted, CaptionCodec.decode(golden, GoldenVectors.captionCipher()))
        assertEquals(golden, GoldenVectors.captionEnc())
    }

    // Deflate output is not guaranteed to be identical across zlib builds, so the gzip-based
    // files are compared at the JSON layer rather than as compressed bytes.
    @Test
    fun `plain index decodes and its JSON is reproduced byte for byte`() {
        val golden = GoldenVectors.resource("index-v1.json.gz")
        assertEquals(GoldenVectors.index, IndexSnapshotCodec.read(ByteArrayInputStream(golden)))
        assertEquals(gunzip(golden), IndexSnapshotCodec.toJson(GoldenVectors.index))
        assertEquals(
            GoldenVectors.index,
            IndexSnapshotCodec.read(ByteArrayInputStream(GoldenVectors.indexPlainBytes()))
        )
    }

    @Test
    fun `encrypted index decodes and its JSON is reproduced byte for byte`() {
        val golden = GoldenVectors.resource("index-v1-enc.json.gz.dbe")
        assertEquals(
            GoldenVectors.indexEncrypted,
            IndexSnapshotCodec.read(ByteArrayInputStream(golden), GoldenVectors.indexCipher())
        )
        val inner = Dbe1.newReader(ByteArrayInputStream(golden), GoldenVectors.keys.metaKey).use { it.readBytes() }
        assertEquals(gunzip(inner), IndexSnapshotCodec.toJson(GoldenVectors.indexEncrypted))
        assertContentEquals(
            golden.copyOfRange(0, Dbe1.HEADER_SIZE),
            GoldenVectors.indexEncBytes().copyOfRange(0, Dbe1.HEADER_SIZE)
        )
    }

    @Test
    fun `parameter sheet matches the inputs used`() {
        assertEquals(text("vectors.json"), GoldenVectors.parameters())
    }

    @Test
    fun `every described golden file is committed`() {
        for (name in GoldenVectors.files().keys) {
            assertTrue(GoldenVectors.resource(name).isNotEmpty() || name == "dbe1-empty.plain", name)
        }
    }
}
