package com.spaceboy.ridebuddy.ui.screens

import androidx.compose.runtime.Composable
import com.spaceboy.ridebuddy.domain.TelemetryFrame
import com.spaceboy.ridebuddy.core.navigation.GuidanceState
import com.spaceboy.ridebuddy.data.ActiveRide
import com.spaceboy.ridebuddy.data.DistanceUnits
import com.spaceboy.ridebuddy.data.RideSample
import com.spaceboy.ridebuddy.data.calculateLiveRideMetrics
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BleDiagnostics
import com.spaceboy.ridebuddy.ui.LiveTelemetryStreams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A connected bike mid-ride, shared by the live screen's behaviour and render tests.
 *
 * One fixture rather than two so the picture that gets looked at is the same composition the
 * assertions run against.
 */
internal object LiveCardFixture {
    /** Eleven minutes, matching the sample series below. */
    const val RideMillis = 11 * 60_000L

    val Frame = TelemetryFrame(
        speedKilometresPerHour = 64.0,
        throttlePercent = 38,
        instantaneousMileageKilometresPerLitre = 22.4,
        engineRpm = 5_420L,
    )

    /**
     * Three kilometres in, which is what the rider's own screenshot showed, eleven minutes
     * after setting off. The elapsed figure has to be real: a render showing "0m" would look
     * like a defect in the sheet rather than an unset field in the fixture.
     */
    fun recording(): ActiveRide =
        ActiveRide.started(startedAtMillis = 0L, receivedAtElapsedRealtime = 0L, frame = Frame)
            .copy(
                distanceKilometres = 3.0,
                telemetryDurationMillis = RideMillis,
                lastSampleAtElapsedRealtime = RideMillis,
            )

    /**
     * Eleven minutes of 1 Hz telemetry with a couple of overtakes in it, so the sheet's charts
     * draw a shape rather than a flat line. Deterministic: a render that moves between runs
     * cannot be reviewed by looking at it.
     */
    fun samples(): List<RideSample> = List((RideMillis / 1_000L).toInt()) { index ->
        val seconds = index.toDouble()
        val cruise = 58.0 + 14.0 * sin(seconds / 70.0)
        val overtake = if (index in 230..300 || index in 470..520) 22.0 else 0.0
        val speed = (cruise + overtake).coerceAtLeast(0.0)
        RideSample(
            timestampMillis = index * 1_000L,
            speedKph = speed,
            rpm = (speed * 78).toLong().coerceIn(1_200, 10_500),
            throttlePercent = ((speed - 30) * 1.4).roundToInt().coerceIn(0, 100),
            mileageKilometresPerLitre = 24.0 - speed / 14.0,
            accelerationMetresPerSecondSquared = if (index == 0) 0.0 else (speed - (58.0 + 14.0 * sin((seconds - 1) / 70.0))) / 3.6,
        )
    }

    @Composable
    fun LiveScreenUnderTest(
        ride: ActiveRide?,
        frame: TelemetryFrame = Frame,
        guidance: GuidanceState = GuidanceState(),
        onStopNavigation: () -> Unit = {},
        onOpenActiveNavigation: () -> Unit = {},
        onEndRide: () -> Unit = {},
        samples: List<RideSample> = emptyList(),
    ) {
        LiveScreen(
            isNavigationStarting = false,
            connectionState = BikeConnectionState.Connected("RS457_IDE1B7", rssi = -60),
            bikeAssociated = true,
            pairingInProgress = false,
            live = LiveTelemetryStreams(
                telemetry = MutableStateFlow(frame),
                diagnostics = MutableStateFlow(BleDiagnostics(rssi = -64)),
                activeRide = MutableStateFlow(ride),
                rideSamples = MutableStateFlow(samples),
                rideMetrics = MutableStateFlow(calculateLiveRideMetrics(samples)),
                accelerationG = MutableStateFlow(-0.35),
            ),
            lastRide = null,
            guidance = guidance,
            units = DistanceUnits.Metric,
            onConnectBike = {},
            onDisconnectBike = {},
            onEndRide = onEndRide,
            destinations = emptyList(),
            onNavigateTo = {},
            onOpenGoogleMaps = {},
            onRenameDestination = { _, _ -> },
            onDeleteDestination = {},
            onOpenActiveNavigation = onOpenActiveNavigation,
            onStopNavigation = onStopNavigation,
            onCancelNavigationStart = {},
            onRideSelected = {},
        )
    }
}
