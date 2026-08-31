package com.spaceboy.ridebuddy

import androidx.lifecycle.SavedStateHandle

// A shared destination arrives in one of two shapes and they are mutually exclusive.
// "Manual" is put in the field for the rider to review and start; "auto-start" launches
// navigation without further input, and is only used when the rider has opted into it.
// Each transition below clears the other's state, so the two can never both be live.

/** Shows a shared destination for review on the Live screen. */
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

/** Queues a shared destination to start navigation on its own. */
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

/**
 * Clears a completed auto-start. Id-matched, so a late callback for a request the rider has
 * already replaced cannot clear the newer one.
 */
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

/**
 * Falls back from a failed auto-start to the manual field, so the destination is still there
 * for the rider to retry rather than silently lost.
 */
internal fun MainUiState.withRestoredAutoStartSharedDestination(
    requestId: Long,
    errorMessage: String? = null,
): MainUiState {
    val request = autoStartSharedDestination?.takeIf { it.requestId == requestId } ?: return this
    return withManualSharedDestination(request.destination).copy(sharedDestinationError = errorMessage)
}

/**
 * Marks a navigation start in flight. The attempt id is what lets a slow start that has
 * since been superseded be ignored when it finally reports back.
 */
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

/**
 * Persists a pending shared destination across process death.
 *
 * A destination shared from another app can arrive while this app is not running, and the
 * system may then kill the process before the rider acts on it. Without this, the share
 * would simply be lost. Restored values are re-validated rather than trusted — saved state
 * survives an upgrade, and an over-long or blank value should not come back.
 */
internal class SharedDestinationStateStore(
    private val savedStateHandle: SavedStateHandle,
) {
    /** Rebuilds the pending destination. Auto-start wins; the two are mutually exclusive. */
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

/** Far longer than any address or Maps link, short enough to bound what is stored. */
internal const val MaxDestinationInputLength = 4_096

/** Trims and length-checks destination input; null when there is nothing usable. */
internal fun String.normalizedDestinationInput(): String? = trim()
    .takeIf { it.isNotEmpty() && it.length <= MaxDestinationInputLength }

/** Everything the main screen renders from, as one immutable value. */
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

/**
 * A destination to start navigating without asking. The id makes each request distinct, so
 * the same destination shared twice is two requests and a stale completion is recognisable.
 */
data class AutoStartSharedDestinationRequest(
    val requestId: Long,
    val destination: String,
)

/** The app's top-level navigation destinations, in bar order. */
enum class TopLevelDestination {
    Live,
    History,
    Insights,
    Info,
    More,
}
