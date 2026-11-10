package io.github.rosyidputrar.duobackup.core.crypto

import io.github.rosyidputrar.duobackup.core.util.SecureRandomSource
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** The passphrase-wrapped master key stored on every destination as `duobackup-key.json`. */
object KeyFile {
    const val DEFAULT_ITERATIONS: Int = 600_000

    private const val FORMAT = "duobackup-key"
    private const val VERSION = 1
    private const val KDF_ALG = "PBKDF2-HMAC-SHA256"
    private const val WRAP_ALG = "AES-256-GCM"
    private const val SALT_SIZE = 16
    private const val NONCE_SIZE = 12
    private const val TAG_BITS = 128
    private const val AAD_PREFIX = "duobackup/key/v1"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    private data class Document(
        val format: String = FORMAT,
        val v: Int = VERSION,
        val keyId: String,
        val kdf: Kdf,
        val wrap: Wrap,
        val createdAt: Long
    )

    @Serializable
    private data class Kdf(val alg: String = KDF_ALG, val iter: Int, val salt: String)

    @Serializable
    private data class Wrap(val alg: String = WRAP_ALG, val nonce: String, val ct: String)

    @Serializable
    private data class Header(val format: String? = null, val v: Int? = null)

    /** Wraps `masterKey` under `passphrase` and returns the key file as compact JSON. */
    fun create(
        masterKey: ByteArray,
        passphrase: CharArray,
        random: SecureRandomSource,
        createdAt: Long,
        iterations: Int = DEFAULT_ITERATIONS
    ): String {
        require(masterKey.size == KeyMaterial.KEY_SIZE) { "master key must be ${KeyMaterial.KEY_SIZE} bytes" }
        require(iterations > 0) { "iterations must be positive" }
        require(passphrase.isNotEmpty()) { "passphrase must not be empty" }
        val keyId = KeyMaterial.keyIdOf(masterKey)
        val salt = random.nextBytes(SALT_SIZE)
        val nonce = random.nextBytes(NONCE_SIZE)
        check(salt.size == SALT_SIZE && nonce.size == NONCE_SIZE) { "random source returned a wrong size" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(keyId))
        val encoder = Base64.getEncoder()
        val document = Document(
            keyId = keyId,
            kdf = Kdf(iter = iterations, salt = encoder.encodeToString(salt)),
            wrap = Wrap(
                nonce = encoder.encodeToString(nonce),
                ct = encoder.encodeToString(cipher.doFinal(masterKey))
            ),
            createdAt = createdAt
        )
        return json.encodeToString(Document.serializer(), document)
    }

    /** The key id a key file claims, without unlocking it. */
    fun keyIdOf(text: String): String = parse(text).keyId

    /**
     * Returns the master key. Throws [CryptoException.WrongPassphrase] when the passphrase is
     * wrong or the file was modified, and other [CryptoException]s for unusable files.
     */
    fun unlock(text: String, passphrase: CharArray): ByteArray {
        if (passphrase.isEmpty()) throw CryptoException.WrongPassphrase()
        val document = parse(text)
        val decoder = Base64.getDecoder()
        val salt: ByteArray
        val nonce: ByteArray
        val wrapped: ByteArray
        try {
            salt = decoder.decode(document.kdf.salt)
            nonce = decoder.decode(document.wrap.nonce)
            wrapped = decoder.decode(document.wrap.ct)
        } catch (e: IllegalArgumentException) {
            throw CryptoException.BadKeyFile("invalid Base64 in key file").apply { initCause(e) }
        }
        if (nonce.size != NONCE_SIZE || salt.isEmpty() || document.kdf.iter <= 0) {
            throw CryptoException.BadKeyFile("invalid key file parameters")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            wrappingKey(passphrase, salt, document.kdf.iter),
            GCMParameterSpec(TAG_BITS, nonce)
        )
        cipher.updateAAD(aad(document.keyId))
        val masterKey = try {
            cipher.doFinal(wrapped)
        } catch (e: AEADBadTagException) {
            throw CryptoException.WrongPassphrase().apply { initCause(e) }
        }
        if (masterKey.size != KeyMaterial.KEY_SIZE || KeyMaterial.keyIdOf(masterKey) != document.keyId) {
            throw CryptoException.BadKeyFile("key file does not hold the key it names")
        }
        return masterKey
    }

    private fun parse(text: String): Document {
        try {
            val header = json.decodeFromString(Header.serializer(), text)
            if (header.format != FORMAT) throw CryptoException.BadKeyFile("not a DuoBackup key file")
            if (header.v !=
                VERSION
            ) {
                throw CryptoException.UnsupportedKeyFile("unsupported key file version ${header.v}")
            }
            val document = json.decodeFromString(Document.serializer(), text)
            if (document.kdf.alg != KDF_ALG || document.wrap.alg != WRAP_ALG) {
                throw CryptoException.UnsupportedKeyFile("unsupported key file algorithm")
            }
            return document
        } catch (e: SerializationException) {
            throw CryptoException.BadKeyFile("malformed key file").apply { initCause(e) }
        }
    }

    private fun wrappingKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KeyMaterial.KEY_SIZE * 8)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun aad(keyId: String): ByteArray = (AAD_PREFIX + keyId).toByteArray(Charsets.US_ASCII)
}
