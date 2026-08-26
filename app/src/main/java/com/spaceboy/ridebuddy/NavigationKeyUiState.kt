package com.spaceboy.ridebuddy

import com.spaceboy.ridebuddy.core.navigation.ConfigureResult
import com.spaceboy.ridebuddy.core.navigation.NavigationApiKeyPolicy
import com.spaceboy.ridebuddy.core.navigation.NavigationKeyBootstrapResult

internal class NavigationKeyOperationGuard {
    private var active = false

    @Synchronized
    fun tryAcquire(): Boolean {
        if (active) return false
        active = true
        return true
    }

    @Synchronized
    fun release() {
        check(active) { "Navigation key operation was not active" }
        active = false
    }
}

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

data class NavigationKeyUiState(
    val isConfigured: Boolean = false,
    val maskedKey: String? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val restartRequired: Boolean = false,
    val errorMessage: String? = null,
)
