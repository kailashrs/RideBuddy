package com.spaceboy.ridebuddy.core.navigation

import com.google.android.libraries.navigation.NavigationApi
import java.security.MessageDigest

/**
 * Applies the Navigation SDK key, which can only be set once per process.
 *
 * That one-shot limitation is the whole reason this class exists: a rider replacing their
 * key needs to be told to restart the app, not left with a silently ignored change. The
 * key is fingerprinted rather than retained so that re-applying the same key is recognised
 * as a no-op without holding the secret in memory for the life of the process.
 */
class GoogleNavigationSdkGateway {
    private var configuredKeyFingerprint: String? = null

    val isConfiguredInProcess: Boolean
        @Synchronized get() = configuredKeyFingerprint != null

    /** Applies [apiKey] if nothing has been applied yet; otherwise reports what stands. */
    @Synchronized
    fun configureIfNeeded(apiKey: String): ConfigureResult {
        val fingerprint = apiKey.fingerprint()
        val currentFingerprint = configuredKeyFingerprint

        if (currentFingerprint == fingerprint) {
            return ConfigureResult.AlreadyConfigured
        }
        if (currentFingerprint != null) {
            return ConfigureResult.RestartRequired
        }

        return runCatching {
            NavigationApi.setApiKey(apiKey)
            configuredKeyFingerprint = fingerprint
            ConfigureResult.Configured
        }.getOrElse { error ->
            ConfigureResult.Failed(error.message ?: "Navigation SDK rejected the API key")
        }
    }

    private fun String.fingerprint(): String = MessageDigest
        .getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

sealed interface ConfigureResult {
    /** The key was applied to this process. */
    data object Configured : ConfigureResult

    /** The same key was already applied; nothing to do. */
    data object AlreadyConfigured : ConfigureResult

    /** A different key is already applied. The new one takes effect only after a restart. */
    data object RestartRequired : ConfigureResult

    /** The SDK rejected the key. */
    data class Failed(val message: String) : ConfigureResult
}
