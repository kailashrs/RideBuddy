package com.spaceboy.ridebuddy.core.tft

import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.spaceboy.ridebuddy.ble.BleCharacteristics
import com.spaceboy.ridebuddy.core.calls.TftCallState
import com.spaceboy.ridebuddy.core.calls.TrackedCall
import com.spaceboy.ridebuddy.core.navigation.GuidanceOutput
import com.spaceboy.ridebuddy.core.navigation.NavigationDestination
import com.spaceboy.ridebuddy.data.AppSettings
import com.spaceboy.ridebuddy.data.TftTextMode
import com.spaceboy.ridebuddy.domain.BikeConnection
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeControlEvent
import com.spaceboy.ridebuddy.domain.BikeWrite
import com.spaceboy.ridebuddy.domain.BikeWriteMode
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What the route side of the display should show. */
internal sealed interface RouteDisplay {
    data object None : RouteDisplay
    data class Preview(val title: String, val trip: ByteArray?) : RouteDisplay
    data class Guiding(val title: String, val info: NavInfo? = null, val rerouting: Boolean = false) : RouteDisplay
    data class Arrived(val title: String) : RouteDisplay
}

/** One field on the cluster: a characteristic, and for the text rows, which row. */
internal data class ClusterField(val characteristic: UUID, val row: Int = 0)

/** The navigation screen the cluster should be on. */
internal data class NavigationScreen(val session: Int, val fields: Map<ClusterField, ByteArray>)

/**
 * Everything the cluster should show. A null [call] means no call; [callReady] is false until
 * the cluster has shown its side is up, before which call writes are dropped by the cluster.
 */
internal data class DesiredDisplay(
    val navigation: NavigationScreen?,
    val call: Map<ClusterField, ByteArray>?,
    val callReady: Boolean,
)

/** What the cluster has acknowledged. Anything not recorded here is written again. */
internal data class ShownDisplay(
    val navigation: ShownNavigation? = null,
    val call: Map<ClusterField, ByteArray> = emptyMap(),
)

internal data class ShownNavigation(
    val session: Int? = null,
    val statusSent: Boolean = false,
    val fields: Map<ClusterField, ByteArray> = emptyMap(),
)

/** One write owed to the cluster, and what the cluster shows once it is acknowledged. */
internal class ClusterWrite(
    val frames: List<BikeWrite>,
    val paced: Boolean,
    val after: (ShownDisplay) -> ShownDisplay,
)

/**
 * The screen for a route, an alert and the rider's text setting.
 *
 * An alert borrows the text rows and needs a guidance session to draw in, so it opens one when
 * no route is running. The route's other fields stay as they are, and the rows return to the
 * route's own text once the alert ends.
 */
internal fun navigationScreen(
    route: RouteDisplay,
    alert: String?,
    compactText: Boolean,
    nowMillis: Long,
): NavigationScreen? {
    val screen = when (route) {
        RouteDisplay.None -> null
        is RouteDisplay.Preview -> NavigationScreen(
            SessionRouteReady,
            textFields(TftPacketEncoder.guidanceTextRows(route.title, "")) +
                listOfNotNull(route.trip?.let { ClusterField(BleCharacteristics.NavigationTrip) to it }),
        )
        is RouteDisplay.Guiding -> guidingScreen(route, compactText, nowMillis)
        is RouteDisplay.Arrived -> NavigationScreen(
            SessionGuidanceActive,
            textFields(TftPacketEncoder.guidanceTextRows(route.title, ArrivedBanner)),
        )
    }
    if (alert == null) return screen
    val routeFields = screen?.fields.orEmpty().filterKeys { it.characteristic != BleCharacteristics.NavigationText }
    return NavigationScreen(SessionGuidanceActive, routeFields + textFields(TftPacketEncoder.displayTextRows(alert)))
}

private fun guidingScreen(route: RouteDisplay.Guiding, compactText: Boolean, nowMillis: Long): NavigationScreen {
    // A route is requested and guidance has not reported its first step yet.
    val info = route.info ?: return NavigationScreen(SessionRouteRequested, emptyMap())
    val destination = if (compactText) "" else route.title
    val current = info.currentStep ?: return NavigationScreen(SessionGuidanceActive, emptyMap())
    val trip = TftPacketEncoder.trip(
        arrivalEpochMillis = nowMillis + (info.timeToFinalDestinationSeconds ?: 0) * 1_000L,
        destinationDistanceMetres = info.distanceToFinalDestinationMeters ?: 0,
        maneuverDistanceMetres = info.distanceToCurrentStepMeters ?: 0,
    )
    if (route.rerouting) {
        return NavigationScreen(
            SessionGuidanceActive,
            mapOf(
                ClusterField(BleCharacteristics.NavigationManeuver) to
                    TftPacketEncoder.pictogram(TftPacketEncoder.PictogramRecalculating),
                ClusterField(BleCharacteristics.NavigationTrip) to trip,
            ) + textFields(TftPacketEncoder.guidanceTextRows(destination, RecalculatingBanner)),
        )
    }
    val next = info.remainingSteps.firstOrNull()
    val maneuver = TftPacketEncoder.maneuver(
        current = current.maneuver,
        next = next?.maneuver ?: 0,
        roundaboutExit = current.roundaboutTurnNumber ?: 0,
        // The next step's length is the gap between this maneuver and that one.
        nextManeuverDistanceMetres = next?.distanceFromPrevStepMeters ?: 0,
    )
    // The road name, not the instruction sentence: the row is sixteen characters and the
    // pictogram already says which way to turn. The OEM sends the road name here too.
    val road = current.simpleRoadName?.takeUnless(String::isBlank)
        ?: current.fullRoadName?.takeUnless(String::isBlank)
        ?: current.fullInstructionText.orEmpty().roadNameOrSelf()
    return NavigationScreen(
        SessionGuidanceActive,
        mapOf(
            ClusterField(BleCharacteristics.NavigationManeuver) to maneuver,
            ClusterField(BleCharacteristics.NavigationTrip) to trip,
        ) + textFields(TftPacketEncoder.guidanceTextRows(destination, road)),
    )
}

private fun textFields(rows: List<ByteArray>) =
    rows.mapIndexed { row, payload -> ClusterField(BleCharacteristics.NavigationText, row) to payload }.toMap()

/**
 * The call screen. The caller's name and number only when the rider shows callers; the state
 * whenever either call feature is on, because handlebar controls need the cluster to believe a
 * call is up.
 */
internal fun callScreen(call: TrackedCall?, settings: AppSettings): Map<ClusterField, ByteArray>? {
    if (call == null || !(settings.callerDisplay || settings.tftCallControls)) return null
    val state = when (call.state) {
        TftCallState.Ringing -> TftCallEncoder.ringing()
        TftCallState.Answered -> TftCallEncoder.accepted()
        TftCallState.Outgoing -> TftCallEncoder.outgoing()
    }
    return buildMap {
        if (settings.callerDisplay) {
            put(ClusterField(BleCharacteristics.CallerName), TftCallEncoder.callerName(call.callerName ?: call.callerNumber ?: "Unknown caller"))
            put(ClusterField(BleCharacteristics.CallerNumber), TftCallEncoder.callerNumber(call.callerNumber.orEmpty()))
        }
        put(ClusterField(BleCharacteristics.CallState), state)
    }
}

/**
 * The next write that brings the cluster closer to [desired], or null when it is there.
 *
 * The call screen goes first: a ringing phone is the most urgent thing on the display. The
 * navigation screen follows the cluster driver's own precedence — clear, then session, then
 * status once per route — and then each field whose acknowledged bytes differ.
 */
internal fun nextClusterWrite(desired: DesiredDisplay, shown: ShownDisplay): ClusterWrite? {
    if (desired.callReady) callWrite(desired.call, shown)?.let { return it }
    val navigation = desired.navigation
    if (navigation == null) {
        if (shown.navigation == null) return null
        return ClusterWrite(
            // The clear leaves the speed limit alone, so it is zeroed with it.
            frames = listOf(
                write(BleCharacteristics.NavigationClear, TftPacketEncoder.clear()),
                write(BleCharacteristics.NavigationSpeedLimit, TftPacketEncoder.speedLimit(0)),
            ),
            paced = false,
        ) { it.copy(navigation = null) }
    }
    val current = shown.navigation ?: ShownNavigation()
    if (current.session != navigation.session) {
        return ClusterWrite(listOf(write(BleCharacteristics.NavigationSession, TftPacketEncoder.session(navigation.session))), false) {
            it.copy(navigation = current.copy(session = navigation.session))
        }
    }
    if (!current.statusSent) {
        return ClusterWrite(listOf(write(BleCharacteristics.NavigationStatus, TftPacketEncoder.status(StatusNavigationActive))), false) {
            it.copy(navigation = current.copy(statusSent = true))
        }
    }
    val (field, payload) = navigation.fields.entries.firstOrNull { (field, payload) ->
        !(current.fields[field] contentEquals payload)
    } ?: return null
    return ClusterWrite(listOf(write(field.characteristic, payload)), paced = true) {
        it.copy(navigation = current.copy(fields = current.fields + (field to payload)))
    }
}

private fun callWrite(call: Map<ClusterField, ByteArray>?, shown: ShownDisplay): ClusterWrite? {
    if (call == null) {
        // No call to show; end one the cluster is still showing.
        val state = shown.call[ClusterField(BleCharacteristics.CallState)] ?: return null
        if (state contentEquals TftCallEncoder.ended()) return null
        return ClusterWrite(listOf(write(BleCharacteristics.CallState, TftCallEncoder.ended())), false) {
            it.copy(call = mapOf(ClusterField(BleCharacteristics.CallState) to TftCallEncoder.ended()))
        }
    }
    val (field, payload) = call.entries.firstOrNull { (field, payload) -> !(shown.call[field] contentEquals payload) }
        ?: return null
    return ClusterWrite(listOf(write(field.characteristic, payload)), false) { it.copy(call = it.call + (field to payload)) }
}

/** The three continuously updated fields go unacknowledged; the next update supersedes a drop. */
private fun write(characteristic: UUID, payload: ByteArray) = BikeWrite(
    characteristic,
    payload,
    if (characteristic in UnacknowledgedFields) BikeWriteMode.NoResponsePreferred else BikeWriteMode.Default,
)

private val UnacknowledgedFields = setOf(
    BleCharacteristics.NavigationManeuver,
    BleCharacteristics.NavigationSpeedLimit,
    BleCharacteristics.NavigationTrip,
)

/**
 * Within this distance a turn owns the display and no alert may cover it. An unknown distance
 * counts as far away, or alerts would be blocked for as long as guidance lacked one.
 */
internal fun RouteDisplay.turnIsImminent(): Boolean =
    this is RouteDisplay.Guiding && (info?.distanceToCurrentStepMeters ?: Int.MAX_VALUE) <= ImminentTurnMetres

/**
 * The cluster's navigation area, call screen and alerts, driven from what they should show.
 *
 * Inputs describe the desired display; one writer brings the cluster to it a write at a time,
 * rereading the desired state between writes so nothing stale is ever sent, and records only
 * what the cluster acknowledged. Whenever the cluster's contents stop being knowable — a new
 * link, the cluster restarting, a call screen covering the route — the record is dropped and
 * the next pass rewrites it.
 */
class ClusterDisplay(
    private val connection: BikeConnection,
    private val settings: StateFlow<AppSettings>,
    private val scope: CoroutineScope,
) : GuidanceOutput {
    private val route = MutableStateFlow<RouteDisplay>(RouteDisplay.None)
    private val alert = MutableStateFlow<String?>(null)
    private val call = MutableStateFlow<TrackedCall?>(null)
    private val callReady = MutableStateFlow(false)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()
    private var shown = ShownDisplay()
    private var shownGeneration = 0L
    private var suspended = false
    private var alertJob: Job? = null
    private var arrivalJob: Job? = null

    private val transportReady: Boolean
        get() = connection.connectionState.value is BikeConnectionState.Connected && connection.diagnostics.value.authenticated

    init {
        scope.launch {
            combine(route, alert, call, callReady, settings) { _, _, _, _, _ -> Unit }.collect { wake.trySend(Unit) }
        }
        scope.launch {
            combine(connection.connectionState, connection.diagnostics) { _, _ -> transportReady }
                .distinctUntilChanged()
                .collect { ready ->
                    // A new link: the cluster remembers nothing of what it was shown.
                    forget { ShownDisplay() }
                    if (!ready) {
                        callReady.value = false
                        alert.value = null
                    }
                }
        }
        scope.launch {
            connection.controls.collect { event ->
                when (event) {
                    // Restarted: it wants everything again.
                    BikeControlEvent.ClusterReady -> {
                        callReady.value = transportReady
                        forget { ShownDisplay() }
                    }
                    // It believes a call is up; republishing reconciles that with the phone's view.
                    BikeControlEvent.ClusterCallActive -> {
                        callReady.value = transportReady
                        forget { it.copy(call = emptyMap()) }
                    }
                    // Handlebar answer (1) and reject (0), only while a call is tracked here: a press
                    // just after the call ended must not act on whatever comes next.
                    is BikeControlEvent.CallAction -> if (settings.value.tftCallControls) {
                        val current = call.value ?: return@collect
                        when (event.code) {
                            1 -> current.answer()
                            0 -> current.hangUp()
                        }
                    }
                    else -> Unit
                }
            }
        }
        // The first telemetry frame is the other sign the cluster's side is up.
        scope.launch {
            connection.latestReading.collect { reading -> if (reading != null && transportReady) callReady.value = true }
        }
        scope.launch {
            var wasActive = false
            call.map { it != null }.distinctUntilChanged().collect { active ->
                // A call takes the display outright, so alerts end; once it ends the call screen
                // has covered the route, which is written again.
                if (active) clearAlert()
                if (wasActive && !active) forget { it.copy(navigation = it.navigation?.copy(fields = emptyMap())) }
                wasActive = active
            }
        }
        scope.launch {
            route.map { it.turnIsImminent() }.distinctUntilChanged().collect { imminent -> if (imminent) clearAlert() }
        }
        scope.launch { writeLoop() }
    }

    /** The call Telecom is reporting, or null. */
    internal fun onTelecomCallChanged(tracked: TrackedCall?) {
        call.value = tracked
    }

    /**
     * Shows a short alert on the text rows for a few seconds, returning whether it was shown.
     * A call or an imminent turn outranks it.
     */
    fun presentTextAlert(message: String): Boolean {
        if (!transportReady || !settings.value.tftNavigationOutputEnabled) return false
        if (call.value != null || route.value.turnIsImminent()) return false
        alertJob?.cancel()
        alert.value = message
        alertJob = scope.launch {
            delay(AlertDurationMillis.milliseconds)
            alert.value = null
        }
        return true
    }

    override fun preview(destination: NavigationDestination, distanceMetres: Int?, durationSeconds: Int?) {
        val trip = if (distanceMetres != null && durationSeconds != null) {
            TftPacketEncoder.trip(System.currentTimeMillis() + durationSeconds * 1_000L, distanceMetres, 0)
        } else {
            null
        }
        setRoute(RouteDisplay.Preview(destination.title, trip))
    }

    override fun started(destination: NavigationDestination) = setRoute(RouteDisplay.Guiding(destination.title))

    override fun update(info: NavInfo) {
        val title = (route.value as? RouteDisplay.Guiding)?.title ?: return
        route.value = RouteDisplay.Guiding(title, info)
    }

    override fun rerouting() {
        val guiding = route.value as? RouteDisplay.Guiding ?: return
        route.value = guiding.copy(rerouting = true)
    }

    override fun arrived() {
        val title = (route.value as? RouteDisplay.Guiding)?.title ?: return
        setRoute(RouteDisplay.Arrived(title))
    }

    override fun stopped() = setRoute(RouteDisplay.None)

    private fun setRoute(value: RouteDisplay) {
        arrivalJob?.cancel()
        route.value = value
    }

    private fun clearAlert() {
        alertJob?.cancel()
        alert.value = null
    }

    private fun desired() = settings.value.let { preferences ->
        DesiredDisplay(
            navigation = if (preferences.tftNavigationOutputEnabled) {
                navigationScreen(route.value, alert.value, preferences.tftTextMode == TftTextMode.Compact, System.currentTimeMillis())
            } else {
                null
            },
            call = callScreen(call.value, preferences),
            callReady = callReady.value,
        )
    }

    private fun forget(transform: (ShownDisplay) -> ShownDisplay) {
        synchronized(lock) {
            shown = transform(shown)
            shownGeneration++
            suspended = false
        }
        wake.trySend(Unit)
    }

    private suspend fun writeLoop() {
        var failures = 0
        var lastPacedAt = 0L
        for (ignored in wake) {
            while (transportReady) {
                val (write, generation) = synchronized(lock) {
                    if (suspended) return@synchronized null to shownGeneration
                    nextClusterWrite(desired(), shown) to shownGeneration
                }
                if (write == null) {
                    scheduleArrivalClear()
                    break
                }
                if (write.paced) {
                    val wait = MinimumWriteIntervalMillis - (System.currentTimeMillis() - lastPacedAt)
                    if (wait > 0) delay(wait.milliseconds)
                }
                val delivered = write.frames.all { frame -> connection.writeAndAwait(frame) }
                if (write.paced) lastPacedAt = System.currentTimeMillis()
                if (delivered) {
                    failures = 0
                    synchronized(lock) { if (generation == shownGeneration) shown = write.after(shown) }
                    continue
                }
                // A cluster that keeps refusing writes is left alone until the link or output changes;
                // each attempt costs a GATT operation timeout and can retire the link.
                if (++failures >= MaxConsecutiveWriteFailures) {
                    synchronized(lock) { suspended = true }
                    failures = 0
                    break
                }
                delay((FailedWriteRetryMillis * failures).milliseconds)
            }
        }
    }

    /** The arrival banner clears itself a moment after the cluster has it. */
    private fun scheduleArrivalClear() {
        val arrived = route.value as? RouteDisplay.Arrived ?: return
        if (arrivalJob?.isActive == true) return
        arrivalJob = scope.launch {
            delay(ArrivalDisplayMillis.milliseconds)
            route.compareAndSet(arrived, RouteDisplay.None)
        }
    }

    private companion object {
        const val AlertDurationMillis = 8_000L
        const val ArrivalDisplayMillis = 2_000L
        /** The cluster's own BLE interval for data and text; faster is not reliably consumed. */
        const val MinimumWriteIntervalMillis = 200L
        const val FailedWriteRetryMillis = 1_000L
        const val MaxConsecutiveWriteFailures = 3
    }
}

/**
 * Session values, confirmed on the wire. 83 stages a route and draws GO; 87 is guidance running,
 * which draws EXIT; 80 is a route requested but not yet reporting steps. There is no session for
 * ending — that is the clear packet — nor for arrival, which stays in 87 with its own banner.
 */
internal const val SessionRouteRequested = 80
internal const val SessionRouteReady = 83
internal const val SessionGuidanceActive = 87

/** The only status value; sent once per route. */
internal const val StatusNavigationActive = 132

private const val ArrivedBanner = "Arrived"
private const val RecalculatingBanner = "RECALCULATION"
private const val ImminentTurnMetres = 500

/**
 * Recovers the road name from a guidance sentence ("<maneuver> onto <road>") when the SDK gives
 * no road name of its own. Anything else is returned unchanged.
 */
internal fun String.roadNameOrSelf(): String {
    for (separator in listOf(" onto ", " on ")) {
        val index = lastIndexOf(separator)
        if (index >= 0) substring(index + separator.length).trim().takeIf(String::isNotEmpty)?.let { return it }
    }
    return this
}
