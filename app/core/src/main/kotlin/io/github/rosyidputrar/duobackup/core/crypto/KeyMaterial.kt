package io.github.rosyidputrar.duobackup.core.crypto

import io.github.rosyidputrar.duobackup.core.util.Hex
import java.security.MessageDigest

/** The subkeys and key id derived from the 32-byte master key (plan §4.6). */
class KeyMaterial(masterKey: ByteArray) {
    init {
        require(masterKey.size == KEY_SIZE) { "master key must be $KEY_SIZE bytes" }
    }

    /** Encrypts part content. */
    val contentKey: ByteArray = derive(masterKey, "duobackup/content/v1")

    /** Encrypts captions and index snapshots. */
    val metaKey: ByteArray = derive(masterKey, "duobackup/meta/v1")

    /** Keys the HMAC that turns plaintext hashes into remote object ids. */
    val idKey: ByteArray = derive(masterKey, "duobackup/id/v1")

    val keyId: String = keyIdOf(masterKey)

    companion object {
        const val KEY_SIZE: Int = 32

        fun keyIdOf(masterKey: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update("duobackup/kid/v1".toByteArray(Charsets.US_ASCII))
            return Hex.encode(digest.digest(masterKey)).substring(0, 16)
        }

        private fun derive(masterKey: ByteArray, info: String): ByteArray =
            Hkdf.sha256(masterKey, ByteArray(0), info.toByteArray(Charsets.US_ASCII), KEY_SIZE)
    }
}
