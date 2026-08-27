package com.spaceboy.ridebuddy

import androidx.lifecycle.SavedStateHandle

internal fun MainUiState.withManualSharedDestination(value: String): MainUiState = copy(
    selectedDestination = TopLevelDestination.Live,
    isNavigationSettingsOpen = false,
    isDiagnosticsOpen = false,
    sharedDestination = value,
    sharedDestinationError = null,
    autoStartSharedDestination = null,
    isNavigationStarting = false,
    navigationStartAttemptId = null,
)

internal fun MainUiState.withAutoStartSharedDestination(
    request: AutoStartSharedDestinationRequest,
): MainUiState = copy(
    selectedDestination = TopLevelDestination.Live,
    isNavigationSettingsOpen = false,
    isDiagnosticsOpen = false,
    sharedDestination = null,
    sharedDestinationError = null,
    autoStartSharedDestination = request,
    isNavigationStarting = false,
    navigationStartAttemptId = null,
)

internal fun MainUiState.withCompletedAutoStartSharedDestination(requestId: Long): MainUiState =
    if (autoStartSharedDestination?.requestId == requestId) {
        copy(
            autoStartSharedDestination = null,
            isNavigationStarting = false,
            navigationStartAttemptId = null,
        )
    } else {
        this
    }

internal fun MainUiState.withRestoredAutoStartSharedDestination(
    requestId: Long,
    errorMessage: String? = null,
): MainUiState {
    val request = autoStartSharedDestination?.takeIf { it.requestId == requestId } ?: return this
    return withManualSharedDestination(request.destination).copy(sharedDestinationError = errorMessage)
}

internal fun MainUiState.withNavigationStartAttempt(attemptId: Long): MainUiState = copy(
    isNavigationStarting = true,
    navigationStartAttemptId = attemptId,
)

internal fun MainUiState.withFinishedNavigationStartAttempt(attemptId: Long): MainUiState =
    if (navigationStartAttemptId == attemptId) {
        copy(isNavigationStarting = false, navigationStartAttemptId = null)
    } else {
        this
    }

internal class SharedDestinationStateStore(
    private val savedStateHandle: SavedStateHandle,
) {
    fun restore(): MainUiState {
        val autoStartRequestId = savedStateHandle.get<Long>(AutoStartRequestIdKey)
            ?.takeIf { it > 0L }
        val autoStartDestination = savedStateHandle.get<String>(AutoStartDestinationKey)
            ?.normalizedDestinationInput()
        val autoStartRequest = if (autoStartRequestId != null && autoStartDestination != null) {
            AutoStartSharedDestinationRequest(autoStartRequestId, autoStartDestination)
        } else {
            null
        }
        val manualDestination = if (autoStartRequest == null) {
            savedStateHandle.get<String>(ManualDestinationKey)?.normalizedDestinationInput()
        } else {
            null
        }
        return MainUiState(
            sharedDestination = manualDestination,
            sharedDestinationError = manualDestination?.let {
                savedStateHandle.get<String>(ManualDestinationErrorKey)?.takeIf(String::isNotBlank)
            },
            autoStartSharedDestination = autoStartRequest,
        )
    }

    fun persist(state: MainUiState) {
        savedStateHandle[AutoStartRequestIdKey] = state.autoStartSharedDestination?.requestId
        savedStateHandle[AutoStartDestinationKey] = state.autoStartSharedDestination?.destination
        savedStateHandle[ManualDestinationKey] = state.sharedDestination
        savedStateHandle[ManualDestinationErrorKey] = state.sharedDestinationError
    }

    private companion object {
        const val AutoStartRequestIdKey = "shared_destination.auto_start.request_id"
        const val AutoStartDestinationKey = "shared_destination.auto_start.destination"
        const val ManualDestinationKey = "shared_destination.manual.destination"
        const val ManualDestinationErrorKey = "shared_destination.manual.error"
    }
}

internal const val MaxDestinationInputLength = 4_096

internal fun String.normalizedDestinationInput(): String? = trim()
    .takeIf { it.isNotEmpty() && it.length <= MaxDestinationInputLength }

data class MainUiState(
    val selectedDestination: TopLevelDestination = TopLevelDestination.Live,
    val isNavigationSettingsOpen: Boolean = false,
    val isDiagnosticsOpen: Boolean = false,
    val navigationKey: NavigationKeyUiState = NavigationKeyUiState(),
    val sharedDestination: String? = null,
    val sharedDestinationError: String? = null,
    val autoStartSharedDestination: AutoStartSharedDestinationRequest? = null,
    val transientMessage: String? = null,
    val isNavigationStarting: Boolean = false,
    val navigationStartAttemptId: Long? = null,
    /** Body of the parked TFT-validation prompt, or null when nothing is awaiting confirmation. */
    val tftTestConfirmation: String? = null,
)

data class AutoStartSharedDestinationRequest(
    val requestId: Long,
    val destination: String,
)

enum class TopLevelDestination {
    Live,
    History,
    Insights,
    Info,
    More,
}
