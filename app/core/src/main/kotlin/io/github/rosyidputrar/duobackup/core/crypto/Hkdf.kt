package io.github.rosyidputrar.duobackup.core.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF with HMAC-SHA256 (RFC 5869). */
object Hkdf {
    private const val HASH_LEN = 32

    fun sha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        require(len in 1..255 * HASH_LEN) { "invalid output length" }
        val prk = hmac(if (salt.isEmpty()) ByteArray(HASH_LEN) else salt, ikm)
        val okm = ByteArray(len)
        var block = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < len) {
            block = hmac(prk, block + info + counter.toByte())
            val take = minOf(HASH_LEN, len - written)
            block.copyInto(okm, written, 0, take)
            written += take
            counter++
        }
        return okm
    }

    internal fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
