package com.spaceboy.ridebuddy.core.navigation

import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
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
    var onNavInfo: ((NavInfo) -> Unit)? = null

    fun accept(info: NavInfo) {
        val current = info.currentStep ?: run {
            clear()
            return
        }
        val next = info.remainingSteps.firstOrNull()
        mutableGuidance.value = GuidanceState(
            active = info.navState == NavStateEnroute || info.navState == NavStateRerouting,
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

    private companion object {
        const val NavStateEnroute = 1
        const val NavStateRerouting = 2
    }
}
