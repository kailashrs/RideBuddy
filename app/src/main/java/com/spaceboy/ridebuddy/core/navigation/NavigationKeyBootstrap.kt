package com.spaceboy.ridebuddy.core.navigation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

/**
 * Outcome of loading and applying the stored key. Carries only the masked key, so the
 * secret itself does not spread through the UI layer.
 */
internal data class NavigationKeyBootstrapResult(
    val maskedKey: String?,
    val isConfigured: Boolean,
    val restartRequired: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Loads and applies the stored Navigation SDK key exactly once per process.
 *
 * The SDK only accepts a key once, and several Activities and ViewModels need to know
 * whether one is configured. An `async` started at construction gives every caller the
 * same result without any of them racing to apply it — the first `await` does the work and
 * the rest join it.
 *
 * Later saves and removals overwrite the cached result through the `record…` methods rather
 * than re-running the load, so the UI reflects a change immediately even though the SDK
 * itself will not pick a replacement key up until the next process.
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

    /**
     * The current key state, waiting for the initial load if it has not finished.
     *
     * A `record…` call that lands while the load is still in flight wins: the load's own
     * result is only cached if nothing has been recorded, so a save is not overwritten by
     * the stale value the load started before it.
     */
    suspend fun await(): Result<NavigationKeyBootstrapResult> {
        val initialized = result.await()
        return synchronized(lock) {
            latestResult ?: initialized.also { latestResult = it }
        }
    }

    /** Notes a key the rider has just saved, without re-reading storage. */
    fun recordSavedKey(apiKey: String, configureResult: ConfigureResult) {
        synchronized(lock) {
            latestResult = Result.success(apiKey.toBootstrapResult(configureResult))
        }
    }

    /**
     * Notes a key the rider has just removed. [restartRequired] is true when the removed key
     * had already been applied to this process, which cannot be undone without a restart.
     */
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
