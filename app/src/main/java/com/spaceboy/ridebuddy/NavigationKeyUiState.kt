package com.spaceboy.ridebuddy

import com.spaceboy.ridebuddy.core.navigation.ConfigureResult
import com.spaceboy.ridebuddy.core.navigation.NavigationApiKeyPolicy

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

data class NavigationKeyUiState(
    val isConfigured: Boolean = false,
    val maskedKey: String? = null,
    val isSaving: Boolean = false,
    val restartRequired: Boolean = false,
    val errorMessage: String? = null,
)
