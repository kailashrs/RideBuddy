package com.spaceboy.ridebuddy

import com.spaceboy.ridebuddy.core.navigation.ConfigureResult
import com.spaceboy.ridebuddy.core.navigation.NavigationApiKeyPolicy
import com.spaceboy.ridebuddy.core.navigation.NavigationKeyBootstrapResult

/**
 * UI state after the rider saves a key.
 *
 * Only an outright rejection keeps the previous state and shows an error. "Restart
 * required" still counts as configured — the key is stored and valid, it simply will not
 * take effect until the next process — and the flag is what prompts the rider about that.
 */
internal fun navigationKeyStateFor(
    result: ConfigureResult,
    apiKey: String,
    current: NavigationKeyUiState,
): NavigationKeyUiState = when (result) {
    is ConfigureResult.Failed -> current.copy(isSaving = false, errorMessage = result.message)
    ConfigureResult.Configured,
    ConfigureResult.AlreadyConfigured,
    ConfigureResult.RestartRequired,
    -> NavigationKeyUiState(
        isConfigured = true,
        maskedKey = NavigationApiKeyPolicy.mask(apiKey),
        restartRequired = result is ConfigureResult.RestartRequired,
    )
}

/** UI state from the process-wide key load; a load failure surfaces as an error message. */
internal fun navigationKeyStateForBootstrap(
    result: Result<NavigationKeyBootstrapResult>,
): NavigationKeyUiState = result.fold(
    onSuccess = { bootstrap -> NavigationKeyUiState(
        isConfigured = bootstrap.isConfigured,
        maskedKey = bootstrap.maskedKey,
        restartRequired = bootstrap.restartRequired,
        errorMessage = bootstrap.errorMessage,
    ) },
    onFailure = { error ->
        NavigationKeyUiState(errorMessage = error.message ?: "Could not load navigation setup")
    },
)

/**
 * Navigation-key state for the settings screen. Only the masked key is held; the key itself
 * never leaves the secure store.
 */
data class NavigationKeyUiState(
    val isConfigured: Boolean = false,
    val maskedKey: String? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val restartRequired: Boolean = false,
    val errorMessage: String? = null,
)
