package com.spaceboy.ridebuddy.core.security

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
 * Stores the Navigation SDK key encrypted under an Android Keystore key.
 *
 * The key is the rider's own billable credential, so it is never held in plain text at
 * rest. AES-GCM under an Android Keystore key means the secret never leaves the keystore
 * and cannot be recovered from a preferences backup alone; only the ciphertext and its IV
 * are stored here. Whether the keystore is hardware-backed is the platform's choice, not
 * something requested or checked here — the threat this addresses is a readable backup or
 * preferences file, which holds either way. A fresh IV per encryption is required — GCM loses its guarantees if one
 * is reused — which is what `setRandomizedEncryptionRequired` enforces.
 *
 * Every method is synchronized: settings and the bootstrap loader can touch this from
 * different threads, and an interleaved save would pair one operation's ciphertext with
 * another's IV.
 */
class SecureNavigationApiKeyStore(context: Context) {
    private val preferences = context.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    /**
     * The stored key, or null when there is none or it cannot be decrypted.
     *
     * Decryption failure is a real possibility rather than a defensive branch: the keystore
     * key is invalidated by events like a factory reset. Treating that as "no key stored"
     * prompts the rider to re-enter it, which is the only recovery available anyway.
     */
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

    /**
     * Encrypts and stores [apiKey], throwing if the write does not commit.
     *
     * Both values are written in one commit so a failure cannot leave ciphertext paired
     * with a stale IV — which would decrypt to nothing on the next launch.
     */
    @Synchronized
    @Suppress("UseKtx") // The KTX edit helper discards commit(), so its failure cannot be checked.
    fun save(apiKey: String) {
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encryptedValue = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))

        requirePreferenceCommit(
            preferences.edit()
                .putString(EncryptedValueKey, encryptedValue.encodeBase64())
                .putString(InitializationVectorKey, cipher.iv.encodeBase64())
                .commit(),
            "save the navigation API key",
        )
    }

    @Synchronized
    @Suppress("UseKtx") // The KTX edit helper discards commit(), so its failure cannot be checked.
    fun clear() {
        requirePreferenceCommit(
            preferences.edit()
                .remove(EncryptedValueKey)
                .remove(InitializationVectorKey)
                .commit(),
            "remove the navigation API key",
        )
    }

    /**
     * The keystore key, generated on first use. Lazily rather than eagerly, so an install
     * that never configures navigation never creates one.
     */
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

/**
 * Turns a failed commit into an exception. Silently ignoring one would leave the rider
 * believing a key was saved or removed when it was not.
 */
internal fun requirePreferenceCommit(committed: Boolean, operation: String) {
    check(committed) { "Could not $operation" }
}
