package com.spaceboy.ridebuddy.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureNavigationApiKeyStore(context: Context) {
    private val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    @Synchronized
    fun load(): String? {
        val encryptedValue = preferences.getString(EncryptedValueKey, null) ?: return null
        val initializationVector = preferences.getString(InitializationVectorKey, null) ?: return null

        return runCatching {
            val cipher = Cipher.getInstance(Transformation)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(AuthenticationTagLengthBits, initializationVector.decodeBase64()),
            )
            String(cipher.doFinal(encryptedValue.decodeBase64()), Charsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized
    fun save(apiKey: String) {
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encryptedValue = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))

        preferences.edit(commit = true) {
            putString(EncryptedValueKey, encryptedValue.encodeBase64())
            putString(InitializationVectorKey, cipher.iv.encodeBase64())
        }
    }

    @Synchronized
    fun clear() {
        preferences.edit(commit = true) {
            remove(EncryptedValueKey)
            remove(InitializationVectorKey)
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(AndroidKeyStore).apply { load(null) }
        val existingKey = keyStore.getKey(KeyAlias, null) as? SecretKey
        if (existingKey != null) return existingKey

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeyStore).run {
            init(
                KeyGenParameterSpec.Builder(
                    KeyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun ByteArray.encodeBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.decodeBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private companion object {
        const val PreferencesName = "secure_navigation_settings"
        const val EncryptedValueKey = "encrypted_api_key"
        const val InitializationVectorKey = "api_key_iv"
        const val AndroidKeyStore = "AndroidKeyStore"
        const val KeyAlias = "rs457_navigation_api_key"
        const val Transformation = "AES/GCM/NoPadding"
        const val AuthenticationTagLengthBits = 128
    }
}
