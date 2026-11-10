package io.github.rosyidputrar.duobackup.data.secrets

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import com.google.crypto.tink.Aead
import java.security.GeneralSecurityException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** A stored secret could not be decrypted: it was modified, or the device key is gone. */
class SecretCorruptedException(name: String, cause: Throwable) :
    Exception("stored secret '$name' cannot be decrypted", cause)

/**
 * Small secrets (API credentials, database keys, the wrapped master key), encrypted with an AEAD
 * whose key lives in the Android Keystore. Each value is bound to its name, so a ciphertext
 * moved under another name does not decrypt.
 *
 * @param aead creates or loads the key on first use; called off the main thread
 */
class SecretStore(
    private val store: DataStore<Preferences>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    aead: () -> Aead
) {
    private val aead: Aead by lazy(aead)

    suspend fun put(name: String, value: ByteArray) {
        val sealed = withContext(io) { aead.encrypt(value, name.toByteArray(Charsets.UTF_8)) }
        store.edit { it[byteArrayPreferencesKey(name)] = sealed }
    }

    /** Returns null when nothing is stored under `name`. */
    suspend fun get(name: String): ByteArray? {
        val sealed = store.data.first()[byteArrayPreferencesKey(name)] ?: return null
        return withContext(io) {
            try {
                aead.decrypt(sealed, name.toByteArray(Charsets.UTF_8))
            } catch (e: GeneralSecurityException) {
                throw SecretCorruptedException(name, e)
            }
        }
    }

    suspend fun delete(name: String) {
        store.edit { it.remove(byteArrayPreferencesKey(name)) }
    }

    companion object {
        const val TELEGRAM_API_ID: String = "telegram_api_id"
        const val TELEGRAM_API_HASH: String = "telegram_api_hash"
        const val TDLIB_DATABASE_KEY: String = "tdlib_database_key"
        const val WRAPPED_MASTER_KEY: String = "wrapped_master_key"
    }
}
