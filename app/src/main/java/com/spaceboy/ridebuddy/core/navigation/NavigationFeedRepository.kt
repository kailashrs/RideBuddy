package com.spaceboy.ridebuddy.core.navigation

import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

class NavigationFeedRepository {
    private val mutableGuidance = MutableStateFlow(GuidanceState())
    val guidance: StateFlow<GuidanceState> = mutableGuidance.asStateFlow()
    internal var onNavInfo: ((NavInfo) -> Unit)? = null
    internal var acceptTerminalNavInfo: (() -> Boolean)? = null

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

internal enum class NavigationFeedOutputAction {
    Guidance,
    Rerouting,
    Stop,
}

internal fun navigationFeedOutputAction(navState: Int): NavigationFeedOutputAction = when (navState) {
    NavState.ENROUTE -> NavigationFeedOutputAction.Guidance
    NavState.REROUTING -> NavigationFeedOutputAction.Rerouting
    else -> NavigationFeedOutputAction.Stop
}

internal fun GuidanceState.asRerouting(
    distanceToDestinationMetres: Int?,
    timeToDestinationSeconds: Int?,
): GuidanceState = copy(
    active = true,
    instruction = "Rerouting…",
    distanceToDestinationMetres = distanceToDestinationMetres ?: this.distanceToDestinationMetres,
    timeToDestinationSeconds = timeToDestinationSeconds ?: this.timeToDestinationSeconds,
)
