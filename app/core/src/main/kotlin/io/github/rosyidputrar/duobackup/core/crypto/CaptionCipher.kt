package io.github.rosyidputrar.duobackup.core.crypto

import io.github.rosyidputrar.duobackup.core.util.SecureRandomSource
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Seals Telegram captions: `nonce (12) ‖ AES-256-GCM(metaKey, plaintext, fixed AAD)`. */
class CaptionCipher(metaKey: ByteArray, private val random: SecureRandomSource) {
    private val key: SecretKeySpec

    init {
        require(metaKey.size == KeyMaterial.KEY_SIZE) { "key must be ${KeyMaterial.KEY_SIZE} bytes" }
        key = SecretKeySpec(metaKey, "AES")
    }

    fun seal(plaintext: ByteArray): ByteArray {
        val nonce = random.nextBytes(NONCE_SIZE)
        check(nonce.size == NONCE_SIZE) { "random source returned a wrong nonce size" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(AAD)
        return nonce + cipher.doFinal(plaintext)
    }

    fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < OVERHEAD) throw CryptoException.AuthFailed("sealed caption is too short")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_SIZE))
        cipher.updateAAD(AAD)
        return try {
            cipher.doFinal(sealed, NONCE_SIZE, sealed.size - NONCE_SIZE)
        } catch (e: AEADBadTagException) {
            throw CryptoException.AuthFailed().apply { initCause(e) }
        }
    }

    companion object {
        const val NONCE_SIZE: Int = 12

        /** Bytes added to the plaintext: nonce plus tag. */
        const val OVERHEAD: Int = NONCE_SIZE + 16

        private const val TAG_BITS = 128
        private val AAD = "duobackup/caption/v1".toByteArray(Charsets.US_ASCII)
    }
}
