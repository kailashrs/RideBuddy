package com.spaceboy.ridebuddy

import com.spaceboy.ridebuddy.core.navigation.NavigationKeyUiState

import androidx.lifecycle.SavedStateHandle

// A destination shared from Google Maps is the only way to name a new place, so a share is
// always queued straight into the route preview. Whether the preview's Go is skipped is a
// setting, applied when the preview opens.

/** Queues a shared destination for the route preview. */
internal fun MainUiState.withPendingShare(share: PendingShare): MainUiState = copy(
    selectedDestination = TopLevelDestination.Live,
    isNavigationSettingsOpen = false,
    isDiagnosticsOpen = false,
    pendingShare = share,
    failedShare = null,
    isNavigationStarting = false,
    navigationStartAttemptId = null,
)

/**
 * Clears a share once it has been handed to the preview. Id-matched, so a late callback for a
 * share the rider has already replaced cannot clear the newer one.
 */
internal fun MainUiState.withCompletedShare(requestId: Long): MainUiState =
    if (pendingShare?.requestId == requestId) {
        copy(pendingShare = null, isNavigationStarting = false, navigationStartAttemptId = null)
    } else {
        this
    }

/**
 * A share that could not be turned into a route. It is kept, with the reason as the message,
 * so the snackbar can offer to try it again rather than the share being lost.
 */
internal fun MainUiState.withFailedShare(requestId: Long, message: String): MainUiState {
    val share = pendingShare?.takeIf { it.requestId == requestId } ?: return this
    return copy(pendingShare = null, failedShare = share.destination, transientMessage = message)
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
 * Persists a pending share across process death.
 *
 * A destination shared from another app can arrive while this app is not running, and the
 * system may then kill the process before it is handled. Without this, the share would simply
 * be lost. Restored values are re-validated rather than trusted — saved state survives an
 * upgrade, and an over-long or blank value should not come back.
 */
internal class SharedDestinationStateStore(
    private val savedStateHandle: SavedStateHandle,
) {
    fun restore(): MainUiState {
        val requestId = savedStateHandle.get<Long>(RequestIdKey)?.takeIf { it > 0L }
        val destination = savedStateHandle.get<String>(DestinationKey)?.normalizedDestinationInput()
        return MainUiState(
            pendingShare = if (requestId != null && destination != null) PendingShare(requestId, destination) else null,
        )
    }

    fun persist(state: MainUiState) {
        savedStateHandle[RequestIdKey] = state.pendingShare?.requestId
        savedStateHandle[DestinationKey] = state.pendingShare?.destination
    }

    private companion object {
        // Unchanged from when this was called the auto-start share, so a pending one survives the update.
        const val RequestIdKey = "shared_destination.auto_start.request_id"
        const val DestinationKey = "shared_destination.auto_start.destination"
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
    val pendingShare: PendingShare? = null,
    /** A share that failed, kept so the snackbar reporting it can offer Retry. */
    val failedShare: String? = null,
    val transientMessage: String? = null,
    val isNavigationStarting: Boolean = false,
    val navigationStartAttemptId: Long? = null,
    /** Body of the parked TFT-validation prompt, or null when nothing is awaiting confirmation. */
    val tftTestConfirmation: String? = null,
)

/**
 * A shared destination on its way to the route preview. The id makes each share distinct, so
 * the same place shared twice is two requests and a stale completion is recognisable.
 */
data class PendingShare(
    val requestId: Long,
    val destination: String,
)

/** The app's top-level navigation destinations, in bar order. */
enum class TopLevelDestination {
    Live,
    Insights,
    History,
    Settings,
}
