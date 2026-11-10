package io.github.rosyidputrar.duobackup.core.crypto

import io.github.rosyidputrar.duobackup.core.util.Hex

/** Remote object ids (plan §4.1). */
object Oid {
    /**
     * The id a hash is known by remotely: the hash itself when content is not encrypted, or
     * `HMAC-SHA256(idKey, hash bytes)` when it is, so plaintext hashes stay off the server.
     * Used for content ids and for the part hash stored in Drive properties.
     */
    fun of(sha256Hex: String, idKey: ByteArray?): String {
        require(sha256Hex.length == 64) { "not a SHA-256 hex string" }
        val bytes = Hex.decode(sha256Hex)
        return if (idKey == null) Hex.encode(bytes) else Hex.encode(Hkdf.hmac(idKey, bytes))
    }
}
