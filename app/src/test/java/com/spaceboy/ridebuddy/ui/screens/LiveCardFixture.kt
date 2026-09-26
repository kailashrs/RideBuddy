package com.spaceboy.ridebuddy.ui.screens

import androidx.compose.runtime.Composable
import com.spaceboy.ridebuddy.ble.TelemetryFrame
import com.spaceboy.ridebuddy.core.navigation.GuidanceState
import com.spaceboy.ridebuddy.data.ActiveRide
import com.spaceboy.ridebuddy.data.DistanceUnits
import com.spaceboy.ridebuddy.data.LiveRideMetrics
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BleDiagnostics
import com.spaceboy.ridebuddy.ui.LiveTelemetryStreams
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A connected bike mid-ride, shared by the live screen's behaviour and render tests.
 *
 * One fixture rather than two so the picture that gets looked at is the same composition the
 * assertions run against.
 */
internal object LiveCardFixture {
    val Frame = TelemetryFrame(
        speedKilometresPerHour = 64.0,
        throttlePercent = 38,
        instantaneousMileageKilometresPerLitre = 22.4,
        engineRpm = 5_420L,
    )

    /** Three kilometres in, which is what the rider's own screenshot showed. */
    fun recording(): ActiveRide =
        ActiveRide.started(startedAtMillis = 0L, receivedAtElapsedRealtime = 0L, frame = Frame)
            .copy(distanceKilometres = 3.0)

    @Composable
    fun LiveScreenUnderTest(
        ride: ActiveRide?,
        onEndRide: () -> Unit = {},
    ) {
        LiveScreen(
            sharedDestination = null,
            sharedDestinationError = null,
            isNavigationStarting = false,
            connectionState = BikeConnectionState.Connected("RS457_IDE1B7", rssi = -60),
            live = LiveTelemetryStreams(
                telemetry = MutableStateFlow(Frame),
                diagnostics = MutableStateFlow(BleDiagnostics()),
                activeRide = MutableStateFlow(ride),
                saveFailed = MutableStateFlow(false),
                rideSamples = MutableStateFlow(emptyList()),
                rideMetrics = MutableStateFlow(LiveRideMetrics()),
            ),
            lastRide = null,
            guidance = GuidanceState(),
            units = DistanceUnits.Metric,
            onConnectBike = {},
            onDisconnectBike = {},
            onEndRide = onEndRide,
            onRetryRideSave = {},
            onStartNavigation = {},
            onOpenActiveNavigation = {},
            onStopNavigation = {},
            onSharedDestinationHandled = {},
            onCancelNavigationStart = {},
        )
    }
}
