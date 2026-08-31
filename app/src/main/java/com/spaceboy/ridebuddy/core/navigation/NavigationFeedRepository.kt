package com.spaceboy.ridebuddy.core.navigation

import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The current turn, flattened out of the SDK's guidance snapshot.
 *
 * Distances and times are nullable because the SDK does not always have them — early in a
 * route, or while rerouting — and a null has to stay distinguishable from a genuine zero.
 */
data class GuidanceState(
    val active: Boolean = false,
    val instruction: String = "",
    val roadName: String = "",
    val distanceToManeuverMetres: Int? = null,
    val distanceToDestinationMetres: Int? = null,
    val timeToDestinationSeconds: Int? = null,
    val maneuver: Int = 0,
    val nextManeuver: Int = 0,
    val roundaboutExit: Int = 0,
)

/**
 * Single fan-out point for turn-by-turn guidance.
 *
 * The SDK delivers guidance to one listener, but several things need it: the live screen,
 * the cluster display bridge, and the alert priority rules. This holds the latest state as
 * a flow for the UI and forwards the raw update through [onNavInfo] for consumers that
 * need fields the flattened [GuidanceState] does not carry.
 */
class NavigationFeedRepository {
    private val mutableGuidance = MutableStateFlow(GuidanceState())
    val guidance: StateFlow<GuidanceState> = mutableGuidance.asStateFlow()

    /** Raw pass-through for consumers needing the full update. Set by the app container. */
    internal var onNavInfo: ((NavInfo) -> Unit)? = null

    /**
     * Veto for terminal states. The SDK emits them on its own teardown as well as on real
     * arrival, and acting on the former would clear guidance that is still running.
     */
    internal var acceptTerminalNavInfo: (() -> Boolean)? = null

    /**
     * Handles one guidance update.
     *
     * Rerouting keeps the previous state and only overlays a banner, because the SDK
     * publishes no step while it recalculates and blanking the screen for a second or two
     * is worse than showing the last known turn.
     */
    fun accept(info: NavInfo) {
        if (info.navState == NavState.REROUTING) {
            mutableGuidance.value = mutableGuidance.value.asRerouting(
                distanceToDestinationMetres = info.distanceToFinalDestinationMeters,
                timeToDestinationSeconds = info.timeToFinalDestinationSeconds,
            )
            onNavInfo?.invoke(info)
            return
        }
        if (info.navState != NavState.ENROUTE) {
            if (acceptTerminalNavInfo?.invoke() == false) return
            clear()
            onNavInfo?.invoke(info)
            return
        }

        // Enroute but with no step resolved yet: say guidance is running rather than
        // showing an empty card that reads as a failure.
        val current = info.currentStep ?: run {
            mutableGuidance.value = GuidanceState(active = true, instruction = "Guidance active")
            onNavInfo?.invoke(info)
            return
        }
        val next = info.remainingSteps.firstOrNull()
        mutableGuidance.value = GuidanceState(
            active = true,
            instruction = current.fullInstructionText.orEmpty(),
            roadName = current.fullRoadName.orEmpty(),
            distanceToManeuverMetres = info.distanceToCurrentStepMeters,
            distanceToDestinationMetres = info.distanceToFinalDestinationMeters,
            timeToDestinationSeconds = info.timeToFinalDestinationSeconds,
            maneuver = current.maneuver,
            nextManeuver = next?.maneuver ?: 0,
            roundaboutExit = current.roundaboutTurnNumber ?: 0,
        )
        onNavInfo?.invoke(info)
    }

    fun clear() {
        mutableGuidance.value = GuidanceState()
    }
}

/** What a navigation state means for the cluster display. */
internal enum class NavigationFeedOutputAction {
    Guidance,
    Rerouting,

    /** Anything else — arrived, stopped, not navigating — clears the display. */
    Stop,
}

internal fun navigationFeedOutputAction(navState: Int): NavigationFeedOutputAction = when (navState) {
    NavState.ENROUTE -> NavigationFeedOutputAction.Guidance
    NavState.REROUTING -> NavigationFeedOutputAction.Rerouting
    else -> NavigationFeedOutputAction.Stop
}

/**
 * Overlays the rerouting banner while keeping the turn that was showing. Distance and time
 * are updated where the SDK still supplies them and otherwise retained.
 */
internal fun GuidanceState.asRerouting(
    distanceToDestinationMetres: Int?,
    timeToDestinationSeconds: Int?,
): GuidanceState = copy(
    active = true,
    instruction = "Rerouting…",
    distanceToDestinationMetres = distanceToDestinationMetres ?: this.distanceToDestinationMetres,
    timeToDestinationSeconds = timeToDestinationSeconds ?: this.timeToDestinationSeconds,
)
