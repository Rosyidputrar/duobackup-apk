package io.github.rosyidputrar.duobackup.core.format

import io.github.rosyidputrar.duobackup.core.crypto.CaptionCipher
import java.util.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Part captions: `db1:` + compact JSON, or `dbe1:` + Base64url (no padding) of the sealed JSON.
 */
object CaptionCodec {
    const val PLAIN_PREFIX: String = "db1:"
    const val ENCRYPTED_PREFIX: String = "dbe1:"

    /**
     * Encodes `meta` so that it fits `budget` UTF-16 code units, which is how Telegram counts
     * caption length. When it does not fit, the path is shortened from the front, keeping its
     * tail, and marked with `pt = 1`. Nothing else is ever shortened.
     *
     * @throws FormatException.CaptionTooLong when even a one-character path does not fit
     */
    fun encode(meta: PartCaption, budget: Int, cipher: CaptionCipher?): String {
        val encrypted = cipher != null
        if (encodedLength(meta, encrypted) <= budget) return render(meta, cipher)
        var low = 1
        var high = meta.path.length - 1
        var best: PartCaption? = null
        while (low <= high) {
            val mid = (low + high) ushr 1
            val candidate = meta.copy(path = tail(meta.path, mid), pathTruncated = 1)
            if (encodedLength(candidate, encrypted) <= budget) {
                best = candidate
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return render(best ?: throw FormatException.CaptionTooLong(budget), cipher)
    }

    /**
     * Decodes a caption. Returns null for text that is not a DuoBackup part caption, so foreign
     * messages in the channel are skipped rather than treated as errors.
     */
    fun decode(text: String, cipher: CaptionCipher? = null): PartCaption? {
        val json = when {
            text.startsWith(PLAIN_PREFIX) -> text.substring(PLAIN_PREFIX.length)
            text.startsWith(ENCRYPTED_PREFIX) -> {
                cipher ?: throw FormatException.KeyRequired("encrypted caption needs the backup key")
                val sealed = try {
                    Base64.getUrlDecoder().decode(text.substring(ENCRYPTED_PREFIX.length))
                } catch (e: IllegalArgumentException) {
                    throw FormatException.Malformed("caption is not valid Base64url", e)
                }
                String(cipher.open(sealed), Charsets.UTF_8)
            }
            else -> return null
        }
        try {
            val element = FormatJson.parseToJsonElement(json)
            val root = element as? JsonObject ?: throw FormatException.Malformed("caption is not a JSON object")
            val version = (root["v"] as? JsonPrimitive)?.intOrNull
                ?: throw FormatException.Malformed("caption has no version")
            if (version != PartCaption.VERSION) {
                throw FormatException.UnsupportedVersion("caption", version.toString())
            }
            return FormatJson.decodeFromJsonElement(PartCaption.serializer(), root)
        } catch (e: SerializationException) {
            throw FormatException.Malformed("caption JSON is malformed", e)
        }
    }

    private fun render(meta: PartCaption, cipher: CaptionCipher?): String {
        val json = FormatJson.encodeToString(PartCaption.serializer(), meta)
        if (cipher == null) return PLAIN_PREFIX + json
        val sealed = cipher.seal(json.toByteArray(Charsets.UTF_8))
        return ENCRYPTED_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(sealed)
    }

    /** Length in UTF-16 code units, computed without drawing a nonce. */
    private fun encodedLength(meta: PartCaption, encrypted: Boolean): Int {
        val json = FormatJson.encodeToString(PartCaption.serializer(), meta)
        if (!encrypted) return PLAIN_PREFIX.length + json.length
        val sealedSize = json.toByteArray(Charsets.UTF_8).size + CaptionCipher.OVERHEAD
        return ENCRYPTED_PREFIX.length + (sealedSize * 4 + 2) / 3
    }

    /** The last `length` code units of `path`, minus one when that would split a surrogate pair. */
    private fun tail(path: String, length: Int): String {
        val start = path.length - length
        return if (Character.isLowSurrogate(path[start])) path.substring(start + 1) else path.substring(start)
    }
}
