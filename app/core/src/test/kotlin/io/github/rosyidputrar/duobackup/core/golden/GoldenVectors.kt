package io.github.rosyidputrar.duobackup.core.golden

import io.github.rosyidputrar.duobackup.core.Limits
import io.github.rosyidputrar.duobackup.core.crypto.CaptionCipher
import io.github.rosyidputrar.duobackup.core.crypto.Dbe1
import io.github.rosyidputrar.duobackup.core.crypto.Dbe1Cipher
import io.github.rosyidputrar.duobackup.core.crypto.KeyFile
import io.github.rosyidputrar.duobackup.core.crypto.KeyMaterial
import io.github.rosyidputrar.duobackup.core.crypto.Oid
import io.github.rosyidputrar.duobackup.core.format.CaptionCodec
import io.github.rosyidputrar.duobackup.core.format.IndexSnapshot
import io.github.rosyidputrar.duobackup.core.format.IndexSnapshotCodec
import io.github.rosyidputrar.duobackup.core.format.PartCaption
import io.github.rosyidputrar.duobackup.core.model.DestinationId
import io.github.rosyidputrar.duobackup.core.testing.CountingRandom
import io.github.rosyidputrar.duobackup.core.util.Hex
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The fixed inputs behind every golden file. Randomness is a byte counter starting at a
 * documented value, so each file can be reproduced exactly by any implementation.
 */
internal object GoldenVectors {
    val masterKey = ByteArray(32) { it.toByte() }
    val keys = KeyMaterial(masterKey)

    const val PASSPHRASE = "correct horse battery staple"
    const val KEY_FILE_ITERATIONS = 1000
    const val KEY_FILE_RANDOM_START = 0x40
    const val CAPTION_RANDOM_START = 0x60
    const val INDEX_RANDOM_START = 0x70
    const val CREATED_AT = 1_762_758_000_000

    /** A DBE1 stream under the content key: `<name>.plain` encrypts to `<name>.bin`. */
    class Stream(val name: String, val randomStart: Int, val plain: ByteArray) {
        fun encrypt(): ByteArray = ByteArrayOutputStream().also { out ->
            Dbe1.newWriter(out, keys.contentKey, CountingRandom(randomStart)).use { it.write(plain) }
        }.toByteArray()
    }

    val streams = listOf(
        Stream("dbe1-empty", 0x10, ByteArray(0)),
        Stream("dbe1-small", 0x20, "DuoBackup golden vector\n".toByteArray(Charsets.US_ASCII)),
        Stream("dbe1-two-frames", 0x30, ByteArray(Limits.FRAME + 7) { (it % 251).toByte() })
    )

    /** SHA-256 of "abc" and of the empty string, standing in for content and part hashes. */
    private const val HASH_A = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private const val HASH_B = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    val caption = PartCaption(
        oid = HASH_A,
        contentSha256 = HASH_A,
        contentSize = 734_003_200,
        contentPartSize = 536_870_912,
        partCount = 2,
        partIndex = 1,
        plainOffset = 536_870_912,
        plainSize = 197_132_288,
        partSha256 = HASH_B,
        encrypted = 0,
        path = "DCIM/Camera/Liburan ke Bali ✈ 2026.mp4",
        volume = "primary",
        mtime = 1_762_700_000_000,
        mime = "video/mp4"
    )

    val captionEncrypted = caption.copy(oid = Oid.of(HASH_A, keys.idKey), encrypted = 1)

    fun keyFile(): String = KeyFile.create(
        masterKey,
        PASSPHRASE.toCharArray(),
        CountingRandom(KEY_FILE_RANDOM_START),
        CREATED_AT,
        KEY_FILE_ITERATIONS
    )

    fun captionPlain(): String = CaptionCodec.encode(caption, 1024, null)

    fun captionCipher() = CaptionCipher(keys.metaKey, CountingRandom(CAPTION_RANDOM_START))

    fun captionEnc(): String = CaptionCodec.encode(captionEncrypted, 1024, captionCipher())

    val index = IndexSnapshot(
        generatedAt = CREATED_AT,
        device = "Pixel 9",
        install = "0b9d5c2e-6a1f-4c1e-9b7a-3f2d1e0c9b8a",
        encryption = IndexSnapshot.Encryption(enabled = false),
        session = IndexSnapshot.Session(
            runId = 42,
            result = "PARTIAL",
            pendingParts = 1,
            bytesUploaded = mapOf(DestinationId.DRIVE to 734_003_203, DestinationId.TELEGRAM to 536_870_915)
        ),
        quota = mapOf(DestinationId.DRIVE to IndexSnapshot.Quota(limit = 429_496_729_600, usage = 734_003_203)),
        contents = listOf(
            IndexSnapshot.Content(
                sha256 = HASH_A,
                oid = HASH_A,
                size = 734_003_200,
                partSize = 536_870_912,
                encrypted = 0,
                parts = listOf(
                    IndexSnapshot.Part(
                        index = 0,
                        sha256 = HASH_A,
                        telegram = IndexSnapshot.TelegramRef(-1_001_234_567_890, 456, "BQACAgUAAxkBAAIBgolden"),
                        driveFileId = "1AbCdEfGhIjKlMnOpQrStUvWxYz012345"
                    ),
                    IndexSnapshot.Part(index = 1, sha256 = HASH_B, driveFileId = "1ZyXwVuTsRqPoNmLkJiHgFeDcBa543210")
                )
            ),
            IndexSnapshot.Content(sha256 = HASH_B, oid = HASH_B, size = 0, partSize = 536_870_912, encrypted = 0)
        ),
        files = listOf(
            IndexSnapshot.FileEntry("primary", caption.path, HASH_A, 734_003_200, 1_762_700_000_000, "video/mp4"),
            IndexSnapshot.FileEntry("primary", "Documents/empty.txt", HASH_B, 0, 1_762_600_000_000, "text/plain"),
            IndexSnapshot.FileEntry("primary", "Documents/no-extension", HASH_B, 0, 1_762_600_000_001)
        )
    )

    val indexEncrypted = index.copy(
        encryption = IndexSnapshot.Encryption(enabled = true, keyId = keys.keyId),
        contents = index.contents.map { it.copy(oid = Oid.of(it.sha256, keys.idKey), encrypted = 1) }
    )

    fun indexCipher() = Dbe1Cipher(keys.metaKey, CountingRandom(INDEX_RANDOM_START))

    fun indexPlainBytes(): ByteArray = ByteArrayOutputStream().also {
        IndexSnapshotCodec.write(index, it)
    }.toByteArray()

    fun indexEncBytes(): ByteArray =
        ByteArrayOutputStream().also { IndexSnapshotCodec.write(indexEncrypted, it, indexCipher()) }.toByteArray()

    /** Everything another implementation needs to reproduce the golden files. */
    fun parameters(): String = buildJsonObject {
        put("masterKey", Hex.encode(masterKey))
        put("contentKey", Hex.encode(keys.contentKey))
        put("metaKey", Hex.encode(keys.metaKey))
        put("idKey", Hex.encode(keys.idKey))
        put("keyId", keys.keyId)
        put("passphrase", PASSPHRASE)
        put("random", "byte counter starting at randomStart, wrapping at 256")
        putJsonObject("randomStart") {
            streams.forEach { put("${it.name}.bin", it.randomStart) }
            put("keyfile-v1.json", KEY_FILE_RANDOM_START)
            put("caption-enc.txt", CAPTION_RANDOM_START)
            put("index-v1-enc.json.gz.dbe", INDEX_RANDOM_START)
        }
        putJsonObject("keys") {
            streams.forEach { put("${it.name}.bin", "contentKey") }
            put("caption-enc.txt", "metaKey")
            put("index-v1-enc.json.gz.dbe", "metaKey")
        }
        put("captionBudget", 1024)
    }.toString()

    /** Every golden file and the bytes it must hold. Gzip-based files are compared after inflating. */
    fun files(): Map<String, ByteArray> = buildMap {
        streams.forEach {
            put("${it.name}.plain", it.plain)
            put("${it.name}.bin", it.encrypt())
        }
        put("keyfile-v1.json", keyFile().toByteArray(Charsets.UTF_8))
        put("caption-plain.txt", captionPlain().toByteArray(Charsets.UTF_8))
        put("caption-enc.txt", captionEnc().toByteArray(Charsets.UTF_8))
        put("index-v1.json.gz", indexPlainBytes())
        put("index-v1-enc.json.gz.dbe", indexEncBytes())
        put("vectors.json", parameters().toByteArray(Charsets.UTF_8))
    }

    fun resource(name: String): ByteArray = checkNotNull(javaClass.getResourceAsStream("/golden/$name")) {
        "missing golden file $name"
    }.use { it.readBytes() }
}
