package com.pramod.videoeditor.coresecurity

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Android Keystore-backed storage for user-supplied API keys.
 * Keys never go into logs, analytics or plain preferences.
 *
 * Honest limitation (documented in README): a key stored on-device cannot be
 * hidden from a determined device owner; for shared/public distribution use a
 * backend proxy instead of embedding keys.
 */
class SecureKeyStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "pramod_ai_keys",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun saveKey(provider: String, key: String) {
        prefs.edit().putString("key_$provider", key).apply()
    }

    fun getKey(provider: String): String? = prefs.getString("key_$provider", null)

    fun removeKey(provider: String) {
        prefs.edit().remove("key_$provider").apply()
    }

    fun hasKey(provider: String): Boolean = getKey(provider)?.isNotBlank() == true
}
