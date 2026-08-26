package com.spaceboy.ridebuddy.core.navigation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

internal data class NavigationKeyBootstrapResult(
    val maskedKey: String?,
    val isConfigured: Boolean,
    val restartRequired: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Loads and applies the stored Navigation SDK key once per process. The deferred result is shared
 * by every Activity and ViewModel, while retaining only the masked form of the key.
 */
internal class NavigationKeyBootstrap(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    loadKey: () -> String?,
    configureKey: (String) -> ConfigureResult,
) {
    private val lock = Any()
    private var latestResult: Result<NavigationKeyBootstrapResult>? = null
    private val result = scope.async(dispatcher) {
        try {
            val apiKey = loadKey()
            Result.success(apiKey.toBootstrapResult(apiKey?.let(configureKey)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun await(): Result<NavigationKeyBootstrapResult> {
        val initialized = result.await()
        return synchronized(lock) {
            latestResult ?: initialized.also { latestResult = it }
        }
    }

    fun recordSavedKey(apiKey: String, configureResult: ConfigureResult) {
        synchronized(lock) {
            latestResult = Result.success(apiKey.toBootstrapResult(configureResult))
        }
    }

    fun recordRemovedKey(restartRequired: Boolean) {
        synchronized(lock) {
            latestResult = Result.success(
                NavigationKeyBootstrapResult(
                    maskedKey = null,
                    isConfigured = false,
                    restartRequired = restartRequired,
                ),
            )
        }
    }
}

private fun String?.toBootstrapResult(configureResult: ConfigureResult?): NavigationKeyBootstrapResult =
    when (configureResult) {
        ConfigureResult.Configured,
        ConfigureResult.AlreadyConfigured,
        -> NavigationKeyBootstrapResult(
            maskedKey = this?.let(NavigationApiKeyPolicy::mask),
            isConfigured = true,
        )
        ConfigureResult.RestartRequired -> NavigationKeyBootstrapResult(
            maskedKey = this?.let(NavigationApiKeyPolicy::mask),
            isConfigured = true,
            restartRequired = true,
        )
        is ConfigureResult.Failed -> NavigationKeyBootstrapResult(
            maskedKey = this?.let(NavigationApiKeyPolicy::mask),
            isConfigured = false,
            errorMessage = configureResult.message,
        )
        null -> NavigationKeyBootstrapResult(maskedKey = null, isConfigured = false)
    }
