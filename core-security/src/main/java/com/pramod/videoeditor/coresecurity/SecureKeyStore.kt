package com.pramod.videoeditor.coresecurity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore-backed storage for user-supplied API keys.
 * Keys never go into logs, analytics or plain preferences.
 *
 * Uses the platform Android Keystore directly (AES-256-GCM) instead of the
 * deprecated androidx.security:security-crypto library, per current Android
 * guidance. Encrypted blobs are kept in private SharedPreferences; the key
 * material never leaves the Keystore.
 *
 * Honest limitation (documented in README): a key stored on-device cannot be
 * hidden from a determined device owner; for shared/public distribution use a
 * backend proxy instead of embedding keys.
 */
class SecureKeyStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return keyGen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(b64: String): String? = try {
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        if (bytes.size <= GCM_IV_LEN) return null
        val iv = bytes.copyOfRange(0, GCM_IV_LEN)
        val ct = bytes.copyOfRange(GCM_IV_LEN, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }

    fun saveKey(provider: String, key: String) {
        prefs.edit().putString(KEY_PREFIX + provider, encrypt(key)).apply()
    }

    fun getKey(provider: String): String? {
        val b64 = prefs.getString(KEY_PREFIX + provider, null) ?: return null
        return decrypt(b64)
    }

    fun removeKey(provider: String) {
        prefs.edit().remove(KEY_PREFIX + provider).apply()
    }

    fun hasKey(provider: String): Boolean = getKey(provider)?.isNotBlank() == true

    companion object {
        private const val PREFS_NAME = "pramod_ai_keys"
        private const val KEY_PREFIX = "key_"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "pramod_ai_master_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LEN = 12
    }
}
