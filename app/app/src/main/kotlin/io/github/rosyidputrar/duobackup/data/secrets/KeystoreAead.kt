package io.github.rosyidputrar.duobackup.data.secrets

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/** The Tink AEAD behind [SecretStore]: an AES-256-GCM keyset wrapped by an Android Keystore key. */
object KeystoreAead {
    private const val KEYSET_NAME = "duobackup_keyset"
    private const val KEYSET_PREFS = "duobackup_keyset_prefs"
    private const val MASTER_KEY_URI = "android-keystore://duobackup_master"

    fun create(context: Context): Aead {
        AeadConfig.register()
        return AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }
}
