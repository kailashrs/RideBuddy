package com.spaceboy.ridebuddy.core.navigation

import android.app.Activity
import android.app.Application
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavState
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationUpdatesOptions
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoutingOptions
import com.google.android.libraries.navigation.SpeedAlertOptions
import com.google.android.libraries.navigation.Waypoint
import com.spaceboy.ridebuddy.data.AppSettings
import com.spaceboy.ridebuddy.domain.BikeConnection
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeControlEvent
import com.spaceboy.ridebuddy.service.NavInfoReceivingService
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The current turn, flattened out of the SDK's guidance snapshot. Distances and times are
 * nullable because the SDK does not always have them, and null must stay distinct from zero.
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
 * Keeps the last turn while the route is recalculated: the SDK publishes no step then, and
 * blanking the screen for a second or two is worse than showing the last known turn.
 */
internal fun GuidanceState.asRerouting(distanceToDestinationMetres: Int?, timeToDestinationSeconds: Int?) = copy(
    active = true,
    instruction = "Rerouting…",
    distanceToDestinationMetres = distanceToDestinationMetres ?: this.distanceToDestinationMetres,
    timeToDestinationSeconds = timeToDestinationSeconds ?: this.timeToDestinationSeconds,
)

internal fun NavInfo.toGuidanceState(): GuidanceState {
    // Enroute with no step resolved yet: say guidance is running rather than showing an empty card.
    val current = currentStep ?: return GuidanceState(active = true, instruction = "Guidance active")
    return GuidanceState(
        active = true,
        instruction = current.fullInstructionText.orEmpty(),
        roadName = current.fullRoadName.orEmpty(),
        distanceToManeuverMetres = distanceToCurrentStepMeters,
        distanceToDestinationMetres = distanceToFinalDestinationMeters,
        timeToDestinationSeconds = timeToFinalDestinationSeconds,
        maneuver = current.maneuver,
        nextManeuver = remainingSteps.firstOrNull()?.maneuver ?: 0,
        roundaboutExit = current.roundaboutTurnNumber ?: 0,
    )
}

/** Where a route is. [destination] is present in every state that has one. */
sealed interface NavigationSession {
    val destination: NavigationDestination? get() = null

    data object Idle : NavigationSession
    data class Preparing(override val destination: NavigationDestination) : NavigationSession
    data class Ready(
        override val destination: NavigationDestination,
        val distanceMetres: Int?,
        val durationSeconds: Int?,
    ) : NavigationSession
    data class Guiding(override val destination: NavigationDestination) : NavigationSession
    data class Arrived(override val destination: NavigationDestination) : NavigationSession
    data class Failed(override val destination: NavigationDestination?, val message: String) : NavigationSession
}

/** What the cluster is told about a route. Implemented where the cluster output lives. */
interface GuidanceOutput {
    fun preview(destination: NavigationDestination, distanceMetres: Int?, durationSeconds: Int?)
    fun started(destination: NavigationDestination)
    fun update(info: NavInfo)
    fun rerouting()
    fun arrived()
    fun stopped()
}

/**
 * Owns the process's single Navigation SDK [Navigator] and the route on it.
 *
 * Every change to the route goes through here, one at a time, so the map screen, the Live tab
 * and the handlebar all drive the same session. Closing the map does not stop guidance: the
 * SDK keeps its own foreground service and the cluster keeps drawing turns.
 */
class NavigationController(
    private val application: Application,
    private val connection: BikeConnection,
    private val settings: StateFlow<AppSettings>,
    private val output: GuidanceOutput,
    private val log: (String) -> Unit,
    scope: CoroutineScope,
    private val requestNavigator: suspend (Activity) -> Navigator? = { activity -> sdkNavigator(activity, log) },
) {
    private val mutex = Mutex()
    private var navigator: Navigator? = null
    private val mutableSession = MutableStateFlow<NavigationSession>(NavigationSession.Idle)
    private val mutableGuidance = MutableStateFlow(GuidanceState())

    val session: StateFlow<NavigationSession> = mutableSession.asStateFlow()
    val guidance: StateFlow<GuidanceState> = mutableGuidance.asStateFlow()

    /** The navigator, for the map view to follow. Null when no route is in play. */
    val currentNavigator: Navigator? get() = navigator

    private val arrivalListener = Navigator.ArrivalListener { event ->
        if (event.isFinalDestination) {
            scope.launch { arrive() }
        } else {
            runCatching { navigator?.continueToNextDestination() }
        }
    }

    init {
        scope.launch(Dispatchers.Main) {
            connection.controls.collect { event ->
                when (event) {
                    BikeControlEvent.StartNavigation -> startGuidance()
                    BikeControlEvent.ExitNavigation -> {
                        log("Handlebar exit; stopping navigation")
                        stop()
                    }
                    BikeControlEvent.SkipManeuver -> navigator?.takeIf { (it.timeAndDistanceList?.size ?: 0) > 1 }
                        ?.let { runCatching(it::continueToNextDestination) }
                    else -> Unit
                }
            }
        }
        scope.launch(Dispatchers.Main) {
            var sawConnection = false
            connection.connectionState.collect { state ->
                val ended = state is BikeConnectionState.Failed || state is BikeConnectionState.Disconnected
                if (!ended) sawConnection = true
                if (ended && sawConnection && mutableSession.value !is NavigationSession.Idle) {
                    sawConnection = false
                    log("Connection ended; stopping navigation")
                    stop()
                }
            }
        }
    }

    /**
     * Calculates a route to [destination] and waits on the preview for a Go. Any route already
     * running is replaced. [activity] is needed the first time, for the SDK's terms dialog.
     */
    suspend fun prepare(activity: Activity, destination: NavigationDestination) = serially {
        mutableSession.value = NavigationSession.Preparing(destination)
        val navigator = obtainNavigator(activity) ?: return@serially fail(destination, "Navigation could not start")
        endGuidance(navigator)
        val preferences = settings.value
        val waypoint = Waypoint.builder()
            .setLatLng(destination.latitude, destination.longitude)
            .setTitle(destination.title)
            .build()
        val routing = RoutingOptions()
            .travelMode(RoutingOptions.TravelMode.TWO_WHEELER)
            .avoidTolls(preferences.avoidTolls)
            .avoidHighways(preferences.avoidHighways)
            .avoidFerries(preferences.avoidFerries)
        val status = suspendCancellableCoroutine { continuation ->
            navigator.setDestination(waypoint, routing).setOnResultListener { status -> continuation.resume(status) }
        }
        if (mutableSession.value != NavigationSession.Preparing(destination)) return@serially
        if (status != Navigator.RouteStatus.OK) {
            log("Navigation route request failed: ${status.name}")
            return@serially fail(destination, "No route found to that destination")
        }
        val trip = navigator.currentTimeAndDistance
        mutableSession.value = NavigationSession.Ready(destination, trip?.meters, trip?.seconds)
        output.preview(destination, trip?.meters, trip?.seconds)
    }

    /** Starts guidance on a prepared route. Needs the bike, since the cluster is the point. */
    suspend fun startGuidance(): Boolean = serially {
        val ready = mutableSession.value as? NavigationSession.Ready ?: return@serially false
        val navigator = navigator ?: return@serially false
        if (connection.connectionState.value !is BikeConnectionState.Connected) return@serially false
        val registered = runCatching {
            navigator.registerServiceForNavUpdates(
                application.packageName,
                NavInfoReceivingService::class.java.name,
                NavigationUpdatesOptions.builder().setNumNextStepsToPreview(1).build(),
            )
        }.getOrDefault(false)
        if (!registered) {
            fail(ready.destination, "The bike display could not receive navigation updates")
            return@serially false
        }
        mutableSession.value = NavigationSession.Guiding(ready.destination)
        output.started(ready.destination)
        navigator.startGuidance()
        true
    }

    /** Ends the route, whatever state it is in. */
    suspend fun stop() = serially {
        if (mutableSession.value == NavigationSession.Idle && navigator == null) return@serially
        mutableSession.value = NavigationSession.Idle
        release()
        output.stopped()
    }

    /** One guidance update from the SDK's feed. Anything arriving outside guidance is stale. */
    fun onNavInfo(info: NavInfo) {
        if (mutableSession.value !is NavigationSession.Guiding) return
        when (info.navState) {
            NavState.ENROUTE -> {
                mutableGuidance.value = info.toGuidanceState()
                output.update(info)
            }
            NavState.REROUTING -> {
                mutableGuidance.value = mutableGuidance.value.asRerouting(
                    info.distanceToFinalDestinationMeters,
                    info.timeToFinalDestinationSeconds,
                )
                output.rerouting()
            }
            else -> Unit
        }
    }

    private suspend fun arrive() = serially {
        val guiding = mutableSession.value as? NavigationSession.Guiding ?: return@serially
        // The session moves first, so the SDK's own terminal update on stopGuidance is ignored.
        mutableSession.value = NavigationSession.Arrived(guiding.destination)
        output.arrived()
        release()
    }

    private fun fail(destination: NavigationDestination?, message: String) {
        mutableSession.value = NavigationSession.Failed(destination, message)
        release()
        output.stopped()
    }

    private suspend fun obtainNavigator(activity: Activity): Navigator? {
        navigator?.let { return it }
        val obtained = requestNavigator(activity) ?: return null
        val preferences = settings.value
        obtained.setTaskRemovedBehavior(Navigator.TaskRemovedBehavior.CONTINUE_SERVICE)
        obtained.setAudioGuidance(
            if (preferences.voiceGuidance) Navigator.AudioGuidance.VOICE_ALERTS_AND_GUIDANCE
            else Navigator.AudioGuidance.SILENT,
        )
        obtained.setSpeedAlertOptions(SpeedAlertOptions(0.05f, 0.15f, 5.0))
        obtained.addArrivalListener(arrivalListener)
        navigator = obtained
        return obtained
    }

    private fun endGuidance(navigator: Navigator) {
        runCatching(navigator::stopGuidance)
        runCatching(navigator::unregisterServiceForNavUpdates)
        mutableGuidance.value = GuidanceState()
    }

    /** Stops guidance and returns the navigator to the SDK; the next route asks for a fresh one. */
    private fun release() {
        val current = navigator ?: run {
            mutableGuidance.value = GuidanceState()
            return
        }
        navigator = null
        current.removeArrivalListener(arrivalListener)
        endGuidance(current)
        runCatching(current::clearDestinations)
        runCatching(current::cleanup)
    }

    /** One route change at a time, on the main thread the SDK requires. */
    private suspend fun <T> serially(block: suspend () -> T): T =
        withContext(Dispatchers.Main.immediate) { mutex.withLock { block() } }

}

/** The SDK's navigator. The Activity is what lets it show its terms dialog on first use. */
private suspend fun sdkNavigator(activity: Activity, log: (String) -> Unit): Navigator? = withTimeoutOrNull(10.seconds) {
    suspendCancellableCoroutine { continuation ->
        runCatching {
            NavigationApi.getNavigator(activity, object : NavigationApi.NavigatorListener {
                override fun onNavigatorReady(navigator: Navigator) {
                    if (continuation.isActive) continuation.resume(navigator)
                }

                override fun onError(errorCode: Int) {
                    log("Navigation SDK initialization failed: $errorCode")
                    if (continuation.isActive) continuation.resume(null)
                }
            })
        }.onFailure { if (continuation.isActive) continuation.resume(null) }
    }
}
