package com.spaceboy.ridebuddy.core.tft

import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavState
import com.google.android.libraries.mapsplatform.turnbyturn.model.StepInfo
import com.spaceboy.ridebuddy.FakeBikeConnection
import com.spaceboy.ridebuddy.ble.BleCharacteristics
import com.spaceboy.ridebuddy.core.calls.TftCallState
import com.spaceboy.ridebuddy.core.calls.TrackedCall
import com.spaceboy.ridebuddy.core.navigation.NavigationDestination
import com.spaceboy.ridebuddy.data.AppSettings
import com.spaceboy.ridebuddy.domain.BikeControlEvent
import com.spaceboy.ridebuddy.domain.BikeWrite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterDisplayTest {
    private val now = 1_700_000_000_000L
    private val title = "Marina Beach"

    private fun screen(route: RouteDisplay, alert: String? = null, compact: Boolean = false) =
        navigationScreen(route, alert, compact, now)

    /** Applies writes until the cluster matches, returning what was sent and what is shown. */
    private fun drive(desired: DesiredDisplay, from: ShownDisplay = ShownDisplay()): Pair<List<BikeWrite>, ShownDisplay> {
        var shown = from
        val sent = mutableListOf<BikeWrite>()
        while (true) {
            val write = nextClusterWrite(desired, shown) ?: return sent to shown
            sent += write.frames
            shown = write.after(shown)
        }
    }

    private fun nav(route: RouteDisplay, alert: String? = null) = DesiredDisplay(screen(route, alert), null, callReady = true)

    private fun List<BikeWrite>.characteristics() = map { it.characteristic }

    private fun guidance(distanceToTurn: Int = 900, road: String = "Beach Road") = NavInfo.builder()
        .setNavState(NavState.ENROUTE)
        .setCurrentStep(StepInfo.builder().setManeuver(Maneuver.TURN_RIGHT).setSimpleRoadName(road).build())
        .setRemainingSteps(arrayOf(StepInfo.builder().setManeuver(Maneuver.TURN_LEFT).setDistanceFromPrevStepMeters(300).build()))
        .setDistanceToCurrentStepMeters(distanceToTurn)
        .setDistanceToFinalDestinationMeters(5_000)
        .setTimeToFinalDestinationSeconds(600)
        .build()

    private val text = BleCharacteristics.NavigationText

    @Test
    fun `a staged route draws GO once with session 83, status, destination rows and trip`() {
        val trip = TftPacketEncoder.trip(now + 600_000, 5_000, 0)
        val (sent, _) = drive(nav(RouteDisplay.Preview(title, trip)))

        assertEquals(
            listOf(BleCharacteristics.NavigationSession, BleCharacteristics.NavigationStatus, text, text, text, BleCharacteristics.NavigationTrip),
            sent.characteristics(),
        )
        assertEquals(TftPacketEncoder.session(SessionRouteReady).toList(), sent[0].payload.toList())
        assertEquals(TftPacketEncoder.status(StatusNavigationActive).toList(), sent[1].payload.toList())
    }

    @Test
    fun `starting from the preview moves to 80 then 87 without resending the status`() {
        val (_, staged) = drive(nav(RouteDisplay.Preview(title, null)))

        val (requested, afterRequest) = drive(nav(RouteDisplay.Guiding(title)), staged)
        assertEquals(listOf(TftPacketEncoder.session(SessionRouteRequested).toList()), requested.map { it.payload.toList() })

        val (guiding, _) = drive(nav(RouteDisplay.Guiding(title, guidance())), afterRequest)
        assertEquals(TftPacketEncoder.session(SessionGuidanceActive).toList(), guiding.first().payload.toList())
        assertTrue(BleCharacteristics.NavigationStatus !in guiding.characteristics())
        assertTrue(BleCharacteristics.NavigationManeuver in guiding.characteristics())
    }

    @Test
    fun `an update that changes only the distance rewrites only the fields that changed`() {
        val (_, shown) = drive(nav(RouteDisplay.Guiding(title, guidance(distanceToTurn = 900))))

        val (sent, _) = drive(nav(RouteDisplay.Guiding(title, guidance(distanceToTurn = 400))), shown)

        assertEquals(listOf(BleCharacteristics.NavigationTrip), sent.characteristics())
    }

    @Test
    fun `stopping clears the display and zeroes the speed limit, once`() {
        val (_, shown) = drive(nav(RouteDisplay.Guiding(title, guidance())))

        val (sent, cleared) = drive(nav(RouteDisplay.None), shown)

        assertEquals(listOf(BleCharacteristics.NavigationClear, BleCharacteristics.NavigationSpeedLimit), sent.characteristics())
        assertEquals(emptyList<BikeWrite>(), drive(nav(RouteDisplay.None), cleared).first)
    }

    @Test
    fun `an alert with no route opens a session and clears it again when it ends`() {
        val (sent, shown) = drive(nav(RouteDisplay.None, alert = "WEATHER ALERT. Heavy rain"))
        assertEquals(TftPacketEncoder.session(SessionGuidanceActive).toList(), sent.first().payload.toList())
        assertEquals(3, sent.count { it.characteristic == text })

        val (ended, _) = drive(nav(RouteDisplay.None), shown)
        assertEquals(BleCharacteristics.NavigationClear, ended.first().characteristic)
    }

    @Test
    fun `an alert borrows only the text rows and guidance takes them back after`() {
        val route = RouteDisplay.Guiding(title, guidance())
        val (_, guiding) = drive(nav(route))

        val (alerting, shown) = drive(nav(route, alert = "ROUTE ALERT"), guiding)
        assertTrue(alerting.all { it.characteristic == text })

        val (restored, _) = drive(nav(route), shown)
        assertTrue(restored.isNotEmpty() && restored.all { it.characteristic == text })
    }

    @Test
    fun `rerouting shows the recalculating pictogram and banner`() {
        val screen = screen(RouteDisplay.Guiding(title, guidance(), rerouting = true))!!

        assertEquals(
            TftPacketEncoder.pictogram(TftPacketEncoder.PictogramRecalculating).toList(),
            screen.fields.getValue(ClusterField(BleCharacteristics.NavigationManeuver)).toList(),
        )
    }

    @Test
    fun `arrival stays in the guidance session with its banner`() {
        val screen = screen(RouteDisplay.Arrived(title))!!

        assertEquals(SessionGuidanceActive, screen.session)
        assertEquals(TftPacketEncoder.guidanceTextRows(title, "Arrived")[2].toList(), screen.fields.getValue(ClusterField(text, 2)).toList())
    }

    @Test
    fun `compact text drops the destination rows`() {
        val screen = screen(RouteDisplay.Guiding(title, guidance()), compact = true)!!

        assertEquals(TftPacketEncoder.guidanceTextRows("", "Beach Road")[0].toList(), screen.fields.getValue(ClusterField(text, 0)).toList())
    }

    private val call = TrackedCall("1", "Asha", "+919876543210", TftCallState.Ringing)

    @Test
    fun `a call goes first, state before the caller as the OEM sends it, with the caller only when shown`() {
        val shownCallers = callScreen(call, AppSettings(callerDisplay = true))
        val (sent, _) = drive(DesiredDisplay(screen(RouteDisplay.Guiding(title, guidance())), shownCallers, callReady = true))
        assertEquals(
            listOf(BleCharacteristics.CallState, BleCharacteristics.CallerName, BleCharacteristics.CallerNumber),
            sent.take(3).characteristics(),
        )

        val controlsOnly = callScreen(call, AppSettings(tftCallControls = true))!!
        assertEquals(setOf(ClusterField(BleCharacteristics.CallState)), controlsOnly.keys)
    }

    @Test
    fun `an ended call is written once, and never for a call that was not shown`() {
        val (_, ringing) = drive(DesiredDisplay(null, callScreen(call, AppSettings(callerDisplay = true)), callReady = true))

        val (ended, afterEnd) = drive(DesiredDisplay(null, null, callReady = true), ringing)
        assertEquals(listOf(TftCallEncoder.ended().toList()), ended.map { it.payload.toList() })
        assertEquals(emptyList<BikeWrite>(), drive(DesiredDisplay(null, null, callReady = true), afterEnd).first)
        assertEquals(emptyList<BikeWrite>(), drive(DesiredDisplay(null, null, callReady = true)).first)
    }

    @Test
    fun `nothing about a call is written before the cluster is ready for it`() {
        val (sent, _) = drive(DesiredDisplay(null, callScreen(call, AppSettings(callerDisplay = true)), callReady = false))

        assertEquals(emptyList<BikeWrite>(), sent)
    }

    @Test
    fun `an alert gives way to an imminent turn`() {
        assertTrue(RouteDisplay.Guiding(title, guidance(distanceToTurn = 400)).turnIsImminent())
        assertTrue(!RouteDisplay.Guiding(title, guidance(distanceToTurn = 900)).turnIsImminent())
        assertTrue(!RouteDisplay.Guiding(title).turnIsImminent())
    }

    @Test
    fun `the writer brings the cluster to a staged route and clears it when the route ends`() = withDisplay { display, connection ->
        display.preview(NavigationDestination(12.97, 77.59, title), 5_000, 600)
        awaitWrites(connection) { it.any { write -> write.characteristic == BleCharacteristics.NavigationTrip } }

        display.stopped()
        awaitWrites(connection) { it.last().characteristic == BleCharacteristics.NavigationSpeedLimit }
    }

    @Test
    fun `nothing reaches the cluster while navigation output is off`() =
        withDisplay(AppSettings(tftNavigationOutputEnabled = false)) { display, connection ->
            display.preview(NavigationDestination(12.97, 77.59, title), 5_000, 600)
            delay(300)

            assertEquals(emptyList<BikeWrite>(), synchronized(connection.writes) { connection.writes.toList() })
        }

    @Test
    fun `a restarted cluster is sent everything again`() = withDisplay { display, connection ->
        display.preview(NavigationDestination(12.97, 77.59, title), null, null)
        awaitWrites(connection) { it.count { write -> write.characteristic == text } == 3 }

        connection.controls.tryEmit(BikeControlEvent.ClusterReady)
        awaitWrites(connection) { it.count { write -> write.characteristic == text } == 6 }
    }

    @Test
    fun `the handlebar answers only a call that is being tracked`() = withDisplay(AppSettings(tftCallControls = true)) { display, connection ->
        var answered = 0
        connection.controls.tryEmit(BikeControlEvent.CallAction(1))
        delay(200)
        display.onTelecomCallChanged(call.copy(answer = { answered++ }))
        delay(50)
        connection.controls.tryEmit(BikeControlEvent.CallAction(1))
        withTimeout(2_000) { while (answered == 0) delay(10) }

        assertEquals(1, answered)
    }

    private fun withDisplay(
        settings: AppSettings = AppSettings(tftNavigationOutputEnabled = true),
        block: suspend (ClusterDisplay, FakeBikeConnection) -> Unit,
    ) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connection = FakeBikeConnection()
        val display = ClusterDisplay(connection, MutableStateFlow(settings), scope)
        try {
            delay(50)
            block(display, connection)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun awaitWrites(connection: FakeBikeConnection, condition: (List<BikeWrite>) -> Boolean) {
        withTimeout(5_000) {
            while (!synchronized(connection.writes) { connection.writes.toList() }.let { it.isNotEmpty() && condition(it) }) delay(20)
        }
    }
}
