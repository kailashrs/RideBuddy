package com.spaceboy.ridebuddy.core.navigation

import com.google.android.libraries.navigation.NavigationApi
import java.security.MessageDigest

class GoogleNavigationSdkGateway {
    private var configuredKeyFingerprint: String? = null

    val isConfiguredInProcess: Boolean
        @Synchronized get() = configuredKeyFingerprint != null

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
    data object Configured : ConfigureResult
    data object AlreadyConfigured : ConfigureResult
    data object RestartRequired : ConfigureResult
    data class Failed(val message: String) : ConfigureResult
}
