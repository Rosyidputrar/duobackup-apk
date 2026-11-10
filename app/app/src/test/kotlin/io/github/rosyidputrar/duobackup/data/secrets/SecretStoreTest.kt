package io.github.rosyidputrar.duobackup.data.secrets

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Android Keystore does not exist on the JVM, so these tests use a real Tink AES-256-GCM key
 * held in memory. Everything else is the production code path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecretStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun newAead(): Aead {
        AeadConfig.register()
        val entry = KeysetHandle.generateEntryFromParametersName("AES256_GCM").withRandomId().makePrimary()
        return KeysetHandle.newBuilder().addEntry(entry).build()
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private fun withStore(block: suspend (SecretStore, DataStore<Preferences>, Aead) -> Unit) = runTest {
        val job = Job()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val data = PreferenceDataStoreFactory.create(scope = CoroutineScope(dispatcher + job)) {
            File(temp.root, "secrets.preferences_pb")
        }
        val aead = newAead()
        block(SecretStore(data, dispatcher) { aead }, data, aead)
        job.cancelAndJoin()
    }

    @Test
    fun `a secret round trips and is not stored in the clear`() = withStore { store, data, _ ->
        val secret = "0123456789abcdef0123456789abcdef".toByteArray()
        store.put(SecretStore.TELEGRAM_API_HASH, secret)
        assertArrayEquals(secret, store.get(SecretStore.TELEGRAM_API_HASH))
        val stored = data.data.first()[byteArrayPreferencesKey(SecretStore.TELEGRAM_API_HASH)]!!
        assertFalse(stored.contentEquals(secret))
        assertFalse(String(stored, Charsets.ISO_8859_1).contains("0123456789abcdef"))
        assertTrue(stored.size > secret.size)
    }

    @Test
    fun `missing secrets read as null and values can be replaced`() = withStore { store, _, _ ->
        assertNull(store.get("nothing"))
        store.put("name", byteArrayOf(1))
        store.put("name", byteArrayOf(2, 3))
        assertArrayEquals(byteArrayOf(2, 3), store.get("name"))
        store.put("empty", ByteArray(0))
        assertEquals(0, store.get("empty")!!.size)
    }

    @Test
    fun `deleting a secret removes it`() = withStore { store, data, _ ->
        store.put("a", byteArrayOf(1))
        store.put("b", byteArrayOf(2))
        store.delete("a")
        assertNull(store.get("a"))
        assertArrayEquals(byteArrayOf(2), store.get("b"))
        assertNull(data.data.first()[byteArrayPreferencesKey("a")])
        store.delete("never-existed")
    }

    @Test
    fun `a modified ciphertext is detected`() = withStore { store, data, _ ->
        store.put("name", "secret".toByteArray())
        data.edit { prefs ->
            val key = byteArrayPreferencesKey("name")
            val bytes = prefs[key]!!.clone()
            bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
            prefs[key] = bytes
        }
        expectCorrupted { store.get("name") }
    }

    @Test
    fun `a ciphertext moved under another name is rejected`() = withStore { store, data, _ ->
        store.put("api_id", "12345".toByteArray())
        data.edit { it[byteArrayPreferencesKey("api_hash")] = it[byteArrayPreferencesKey("api_id")]!! }
        expectCorrupted { store.get("api_hash") }
        assertArrayEquals("12345".toByteArray(), store.get("api_id"))
    }

    @Test
    fun `a different device key cannot read the secret`() = withStore { store, data, _ ->
        store.put("name", "secret".toByteArray())
        val other = newAead()
        val stranger = SecretStore(data) { other }
        expectCorrupted { stranger.get("name") }
    }

    @Test
    fun `the key is not touched until a secret is used`() = withStore { _, data, aead ->
        var created = 0
        val lazyStore = SecretStore(data) {
            created++
            aead
        }
        assertNull(lazyStore.get("absent"))
        lazyStore.delete("absent")
        assertEquals(0, created)
        lazyStore.put("name", byteArrayOf(1))
        lazyStore.get("name")
        assertEquals(1, created)
    }

    private suspend fun expectCorrupted(block: suspend () -> Unit) {
        try {
            block()
            fail("expected SecretCorruptedException")
        } catch (e: SecretCorruptedException) {
            assertFalse(e.message!!.contains("secret\""))
        }
    }
}
