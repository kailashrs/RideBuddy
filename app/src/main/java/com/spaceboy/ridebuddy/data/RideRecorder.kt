package com.spaceboy.ridebuddy.data

import android.util.Log
import com.spaceboy.ridebuddy.ble.TelemetryFrame
import com.spaceboy.ridebuddy.domain.BikeConnection
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.TelemetryReading
import com.spaceboy.ridebuddy.core.location.RideLocationLabeler
import com.spaceboy.ridebuddy.core.location.RideLocationTracker
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Detects rides from telemetry and records them, with no rider action required.
 *
 * A ride starts when speed crosses the configured threshold and ends when it falls back
 * below it and stays there — the delay matters, because a traffic light is not the end of a
 * ride. Distance is integrated from wheel speed rather than taken from GPS, so it is
 * recorded correctly with no location permission and no satellite fix; location, when
 * available, only adds the route trace and place labels.
 *
 * Two sample streams are kept for different purposes. The live window is a bounded recent
 * history for the UI, published on a throttle. The stored list is the full ride, thinned as
 * it grows so a long ride cannot grow without bound. All recording state is confined to a
 * single-threaded dispatcher, which is why none of it is otherwise guarded.
 */
class RideRecorder(
    private val bikeConnection: BikeConnection,
    private val repository: RideRepository,
    private val scope: CoroutineScope,
    private val locationTracker: RideLocationTracker,
    private val settingsRepository: AppSettingsRepository,
    private val locationLabeler: RideLocationLabeler,
) {
    private val mutableActiveRide = MutableStateFlow<ActiveRide?>(null)
    val activeRide: StateFlow<ActiveRide?> = mutableActiveRide.asStateFlow()
    private var stopJob: Job? = null
    // record(), finishRide(), the connection-state collector and the stop job all run on
    // RecordingDispatcher, a single-threaded dispatcher, so these need no further guarding.
    private val samples = mutableListOf<RideSample>()
    private val liveWindow = ArrayDeque<RideSample>()
    private val mutableLiveSamples = MutableStateFlow<List<RideSample>>(emptyList())
    val liveSamples: StateFlow<List<RideSample>> = mutableLiveSamples.asStateFlow()
    private val mutableLiveSampleEvents = MutableSharedFlow<RideSample>(
        extraBufferCapacity = LiveSampleEventBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val liveSampleEvents: SharedFlow<RideSample> = mutableLiveSampleEvents.asSharedFlow()
    private var lastLiveFrame: TelemetryFrame? = null
    private var lastLiveAtElapsedRealtime: Long? = null
    private var lastLiveEmitAtElapsedRealtime: Long = 0L
    private var stopCandidate: StopCandidate? = null
    private var resumePending = false
    private val pendingWrites = MutableStateFlow(0)
    private var lastTelemetryAtMillis: Long? = null

    /** Loads history and begins watching telemetry. Called once, at app start. */
    fun start() {
        scope.launch { refreshHistory() }
        scope.launch(RecordingDispatcher) {
            bikeConnection.rawTelemetry.collect(::record)
        }
        // A ride ends when the link is genuinely over — not while it is being re-established.
        // Automatic reconnection passes through the transient states below, so finalizing on
        // anything other than Connected would split a ride on every momentary drop.
        scope.launch(RecordingDispatcher) {
            bikeConnection.connectionState.collect { state ->
                when (state) {
                    is BikeConnectionState.Connected -> Unit

                    BikeConnectionState.Disconnected,
                    is BikeConnectionState.Failed,
                    -> {
                        if (mutableActiveRide.value != null) finishRide(stopCandidate)
                        clearLiveTelemetryState()
                    }

                    is BikeConnectionState.Connecting,
                    is BikeConnectionState.Authenticating,
                    -> pauseForReconnect()
                }
            }
        }
    }

    /**
     * Handles one telemetry frame: updates the live window, and starts, extends or ends the
     * ride as its speed dictates.
     */
    /**
     * Holds an active ride open while the link is re-established.
     *
     * A stop cannot be confirmed without telemetry, so the pending confirmation is abandoned
     * rather than allowed to fire blind. Running totals and stored samples are kept; only the
     * live view and the derivation baselines are dropped, and [resumePending] makes the first
     * frame after the gap rebuild those baselines instead of measuring across it.
     */
    private fun pauseForReconnect() {
        if (mutableActiveRide.value != null) {
            stopJob?.cancel()
            stopJob = null
            stopCandidate = null
            resumePending = true
        }
        clearLiveTelemetryState()
    }

    private fun clearLiveTelemetryState() {
        lastLiveFrame = null
        lastLiveAtElapsedRealtime = null
        liveWindow.clear()
        mutableLiveSamples.value = emptyList()
    }

    private fun record(reading: TelemetryReading) {
        if (bikeConnection.connectionState.value !is BikeConnectionState.Connected) return
        val frame = reading.frame
        val now = reading.receivedAtMillis
        lastTelemetryAtMillis = now
        val nowElapsedRealtime = reading.receivedAtElapsedRealtime
        val previousFrame = lastLiveFrame
        val previousAt = lastLiveAtElapsedRealtime
        val liveElapsedMillis = previousAt?.let { nowElapsedRealtime - it } ?: 0L
        // Acceleration from the speed difference over the monotonic interval. Skipped
        // across a long gap: dropped frames would otherwise show as a huge spike, since the
        // speed change is real but the elapsed time is not what it appears.
        val liveAcceleration = if (previousFrame != null && liveElapsedMillis in 1..MaxAccelerationSampleGapMillis) {
            ((frame.speedKilometresPerHour - previousFrame.speedKilometresPerHour) / 3.6) / (liveElapsedMillis / 1_000.0)
        } else 0.0
        val liveSample = sample(frame, now, liveAcceleration.coerceIn(-20.0, 20.0))
        lastLiveFrame = frame
        lastLiveAtElapsedRealtime = nowElapsedRealtime
        mutableLiveSampleEvents.tryEmit(liveSample)

        liveWindow.addLast(liveSample)
        if (liveWindow.size > MaxLiveSamples) liveWindow.removeFirst()
        val dueForEmit = nowElapsedRealtime - lastLiveEmitAtElapsedRealtime >= LiveSampleEmitIntervalMillis
        if (dueForEmit || liveWindow.size <= 1) {
            lastLiveEmitAtElapsedRealtime = nowElapsedRealtime
            mutableLiveSamples.value = liveWindow.toList()
        }

        val current = mutableActiveRide.value
        if (current == null) {
            if (frame.speedKilometresPerHour >= settingsRepository.settings.value.rideStartSpeedKph) {
                samples.clear()
                // Seed with the last few seconds of pre-threshold samples. A standing-start
                // acceleration time is measured from a stop, and by the time speed crosses
                // the start threshold the launch itself has already happened.
                liveWindow.toList().performancePreRoll(now).forEach(::appendStoredSample)
                stopCandidate = null
                mutableActiveRide.value = ActiveRide.started(now, nowElapsedRealtime, frame)
            }
            return
        }

        // The first frame after a reconnect rebases the integration baselines onto now, so the
        // gap contributes no distance. Telemetry across it was never measured, and estimating it
        // from two readings either side would invent a figure rather than under-report one.
        val resumed = resumePending
        if (resumed) resumePending = false
        val baseline = if (resumed) current.copy(lastSampleAtElapsedRealtime = nowElapsedRealtime) else current
        val elapsedMillis = nowElapsedRealtime - baseline.lastSampleAtElapsedRealtime
        val distanceDelta = distanceDeltaKilometres(baseline.lastSpeedKph, frame.speedKilometresPerHour, elapsedMillis)
        val updated = baseline.add(frame, nowElapsedRealtime, distanceDelta)
        mutableActiveRide.value = updated
        appendStoredSample(liveSample)

        // Stopping is provisional. The ride's totals and samples are snapshotted at the
        // moment speed dropped, and a delayed job confirms it; moving again cancels the job
        // and discards the snapshot. Snapshotting rather than reading current state at
        // confirmation time is what keeps the recorded end time honest — it is when the
        // bike actually stopped, not when the delay expired.
        val settings = settingsRepository.settings.value
        if (shouldStopRide(frame.speedKilometresPerHour, settings.rideStopSpeedKph)) {
            if (stopJob == null) {
                stopCandidate = StopCandidate(
                    endedAtMillis = now,
                    activeRide = updated,
                    samples = samples.toList(),
                )
                stopJob = scope.launch(RecordingDispatcher) {
                    delay(((settings.rideStopDelaySeconds.coerceIn(10, 600) * 1_000L)).milliseconds)
                    val lastSpeed = mutableActiveRide.value?.lastSpeedKph ?: settings.rideStartSpeedKph
                    val confirmedStop = stopCandidate
                    if (confirmedStop != null && shouldStopRide(lastSpeed, settings.rideStopSpeedKph)) {
                        finishRide(confirmedStop)
                    }
                }
            }
        } else {
            stopJob?.cancel()
            stopJob = null
            stopCandidate = null
        }
    }

    /**
     * Closes out a ride and saves it.
     *
     * Rides under [MinimumSavedDistanceKm] are dropped rather than stored: they are sensor
     * noise or a bike rolled a few metres, and they would distort every average in history.
     *
     * Saving happens in two stages. The ride is persisted first, then reverse geocoding
     * fills in the place labels — that needs a network and must not delay or endanger the
     * write of the ride itself.
     */
    private fun finishRide(confirmedStop: StopCandidate? = null) {
        stopJob?.cancel()
        stopJob = null
        stopCandidate = null
        resumePending = false
        val latestActive = mutableActiveRide.getAndUpdate { null } ?: return
        val active = confirmedStop?.activeRide ?: latestActive
        val completedSamples = (confirmedStop?.samples ?: samples.toList()).also { samples.clear() }
        if (active.distanceKilometres < MinimumSavedDistanceKm) return
        val start = completedSamples.firstOrNull { it.latitude != null && it.longitude != null }
        val end = completedSamples.lastOrNull { it.latitude != null && it.longitude != null }
        val route = completedSamples.routePreview()
        val zeroToSixty = completedSamples.accelerationTime(60.0)
        val zeroToHundred = completedSamples.accelerationTime(100.0)
        val completedRide = active.toRide(
            // Without a confirmed stop, the ride ended when telemetry did — not when the app
            // noticed. Using the wall clock here would add the whole reconnect schedule to the
            // duration of every ride that ends in a failed reconnect.
            confirmedStop?.endedAtMillis ?: lastTelemetryAtMillis ?: System.currentTimeMillis(),
        ).copy(
            startLatitude = start?.latitude,
            startLongitude = start?.longitude,
            endLatitude = end?.latitude,
            endLongitude = end?.longitude,
            routePreview = route,
            zeroToSixtyMillis = zeroToSixty,
            zeroToHundredMillis = zeroToHundred,
        )
        // Counted around the whole write, because the connection state that finalises a ride is
        // the same one that stops the foreground service. Without this the app can drop its
        // foreground notification — and with it the process's protection from being killed —
        // while the ride it just finished is still being written.
        pendingWrites.update { it + 1 }
        scope.launch {
            try {
                val rideId = persistRide(completedRide, completedSamples) ?: return@launch
                val startArea = locationLabeler.label(start?.latitude, start?.longitude)
                val endArea = locationLabeler.label(end?.latitude, end?.longitude)
                updateRideAreas(rideId, startArea, endArea)
            } finally {
                pendingWrites.update { it - 1 }
            }
        }
    }

    /**
     * Suspends until every finished ride has been written.
     *
     * For the foreground service to hold the process up until the last ride is safely stored.
     */
    suspend fun awaitPendingWrites() {
        pendingWrites.first { it == 0 }
    }

    private suspend fun refreshHistory() {
        try {
            repository.refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(LogTag, "Could not load ride history", error)
        }
    }

    private suspend fun persistRide(ride: Ride, completedSamples: List<RideSample>): Long? = try {
        repository.insert(ride, completedSamples)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.e(LogTag, "Could not save completed ride", error)
        null
    }

    private suspend fun updateRideAreas(rideId: Long, startArea: String?, endArea: String?) {
        if (startArea == null && endArea == null) return
        try {
            repository.updateAreas(rideId, startArea, endArea)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(LogTag, "Ride saved without location labels", error)
        }
    }

    private fun sample(frame: TelemetryFrame, now: Long, acceleration: Double): RideSample {
        val location = locationTracker.freshLocation()
        return RideSample(
            timestampMillis = now,
            speedKph = frame.speedKilometresPerHour,
            rpm = frame.engineRpm,
            throttlePercent = frame.throttlePercent,
            mileageKilometresPerLitre = frame.instantaneousMileageKilometresPerLitre,
            accelerationMetresPerSecondSquared = acceleration,
            latitude = location?.latitude,
            longitude = location?.longitude,
            accuracyMetres = location?.accuracyMetres,
            altitudeMetres = location?.altitudeMetres,
        )
    }

    /**
     * Appends a sample, halving the resolution of the whole ride once the cap is reached.
     *
     * Dropping every other sample rather than the oldest keeps the entire ride represented
     * at progressively coarser resolution, instead of keeping a sharp record of the end and
     * losing the beginning.
     */
    private fun appendStoredSample(sample: RideSample) {
        samples += sample
        if (samples.size <= MaxStoredSamples) return
        // Compacted in place: allocating a new list of this size on a hot path is wasteful.
        var writeIndex = 0
        for (readIndex in samples.indices step 2) {
            samples[writeIndex++] = samples[readIndex]
        }
        samples.subList(writeIndex, samples.size).clear()
    }

    companion object {
        /** Below this, it was not a ride. Keeps noise out of history and its averages. */
        const val MinimumSavedDistanceKm = 0.1

        /** Live window: about two and a half minutes at the telemetry rate. */
        const val MaxLiveSamples = 600

        /** Stored samples before the ride is thinned. Several hours at full resolution. */
        const val MaxStoredSamples = 36_000

        /** Beyond this gap, an acceleration figure would be an artefact of the gap itself. */
        const val MaxAccelerationSampleGapMillis = 2_500L
        // Telemetry arrives about every 250 ms, so these are sized against that rate rather
        // than against a fast stream: a 250 ms throttle would have let every single frame
        // through and copied the whole live window four times a second for nothing.
        private const val LiveSampleEventBufferCapacity = 8
        private const val LiveSampleEmitIntervalMillis = 1_000L
        private const val LogTag = "RideRecorder"
        private val RecordingDispatcher = Dispatchers.Default.limitedParallelism(1)
    }
}

/**
 * The ride as it stood when speed first dropped, held while the stop is confirmed. Nothing
 * recorded after that moment belongs to the ride.
 */
private data class StopCandidate(
    val endedAtMillis: Long,
    val activeRide: ActiveRide,
    val samples: List<RideSample>,
)

internal fun shouldStopRide(speedKph: Double, stopSpeedKph: Double): Boolean = speedKph <= stopSpeedKph

/**
 * Distance covered between two samples, by trapezoidal integration of speed — the mean of
 * the two speeds over the interval, which tracks acceleration far better than either
 * endpoint alone.
 *
 * A gap longer than [MaxDistanceIntegrationGapMillis] contributes nothing. Frames were
 * dropped, and the bike's speed across that gap is unknown; assuming it held the average of
 * two distant readings would silently invent distance.
 */
internal fun distanceDeltaKilometres(lastSpeedKph: Double, currentSpeedKph: Double, elapsedMillis: Long): Double =
    if (elapsedMillis !in 1..MaxDistanceIntegrationGapMillis) 0.0
    else ((lastSpeedKph + currentSpeedKph) / 2.0) * elapsedMillis / 3_600_000.0

internal const val MaxDistanceIntegrationGapMillis = 2_500L

/**
 * Fuel used over one interval, or null when it cannot be known.
 *
 * The vehicle reports km/L, so consumption is its reciprocal — L/km — and the two endpoints
 * are averaged over the interval, matching how distance is integrated. Both readings are
 * required: a missing one is "no data", and treating it as zero consumption would
 * understate a ride's fuel use for as long as the gap lasted.
 */
internal fun fuelDeltaLitres(
    distanceKilometres: Double,
    previousMileageKilometresPerLitre: Double?,
    currentMileageKilometresPerLitre: Double?,
): Double? {
    if (!distanceKilometres.isFinite() || distanceKilometres <= 0.0) return null
    val previousFuelLitresPerKilometre = previousMileageKilometresPerLitre
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?.let { 1.0 / it }
        ?: return null
    val currentFuelLitresPerKilometre = currentMileageKilometresPerLitre
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?.let { 1.0 / it }
        ?: return null
    return distanceKilometres * (previousFuelLitresPerKilometre + currentFuelLitresPerKilometre) / 2.0
}

/**
 * Time from a standing start to [targetKph], or null when the ride contains no clean run.
 *
 * The crossing time is interpolated between the two samples straddling the target rather
 * than taken as the first sample above it. At a few samples a second, that rounding alone
 * would be a meaningful share of the measurement.
 *
 * Runs are rejected when they are implausibly short or long, or when frames were dropped
 * mid-run — a gap makes the elapsed time real but the acceleration unverifiable.
 */
internal fun List<RideSample>.accelerationTime(targetKph: Double): Long? {
    if (size < 2) return null
    var launchAtMillis: Long? = null
    var previous: RideSample? = null
    for (sample in this) {
        val prior = previous
        if (prior != null && sample.timestampMillis <= prior.timestampMillis) continue
        if (prior != null && sample.timestampMillis - prior.timestampMillis > MaxPerformanceSampleGapMillis) {
            launchAtMillis = null
        }

        if (sample.speedKph <= LaunchSpeedKph) {
            launchAtMillis = sample.timestampMillis
        } else if (launchAtMillis != null && sample.speedKph >= targetKph) {
            val crossingAt = if (prior != null && prior.speedKph < targetKph && sample.speedKph > prior.speedKph) {
                val fraction = ((targetKph - prior.speedKph) / (sample.speedKph - prior.speedKph)).coerceIn(0.0, 1.0)
                prior.timestampMillis + ((sample.timestampMillis - prior.timestampMillis) * fraction).toLong()
            } else sample.timestampMillis
            return (crossingAt - launchAtMillis).takeIf { it in MinimumPerformanceMillis..MaximumPerformanceMillis }
        }
        previous = sample
    }
    return null
}

/** Treated as a standing start. Not zero: wheel speed idles noisily just above it. */
private const val LaunchSpeedKph = 5.0
private const val MaxPerformanceSampleGapMillis = 2_500L
private const val MinimumPerformanceMillis = 500L
private const val MaximumPerformanceMillis = 60_000L

/**
 * The trailing samples worth carrying into a new ride, so a launch that began before the
 * start threshold is still measurable. See [accelerationTime].
 */
internal fun List<RideSample>.performancePreRoll(
    nowMillis: Long,
    maximumAgeMillis: Long = MaximumPerformancePreRollMillis,
): List<RideSample> = takeLastWhile { sample ->
    nowMillis - sample.timestampMillis in 0..maximumAgeMillis
}

private const val MaximumPerformancePreRollMillis = 10_000L

/**
 * Thins a route to at most [maxPoints] evenly spaced points for the history preview.
 *
 * Evenly spaced by index rather than distance: cheap, and it preserves the route's overall
 * shape, which is all a thumbnail needs. The full trace lives in the stored samples.
 */
private fun List<RideSample>.routePreview(maxPoints: Int = 32): List<RoutePoint> {
    val points = mapNotNull { sample ->
        val latitude = sample.latitude ?: return@mapNotNull null
        val longitude = sample.longitude ?: return@mapNotNull null
        RoutePoint(latitude, longitude)
    }
    if (points.size <= maxPoints) return points
    val step = (points.lastIndex.toDouble() / (maxPoints - 1)).coerceAtLeast(1.0)
    return List(maxPoints) { index -> points[(index * step).toInt().coerceAtMost(points.lastIndex)] }
}

/**
 * A ride in progress, as running totals rather than a sample list.
 *
 * Accumulating sums and maxima keeps the update cost constant per frame and independent of
 * ride length — the sample list exists for the route and the charts, not for the averages.
 * Timing uses the monotonic clock so a mid-ride time correction cannot distort distance.
 */
data class ActiveRide(
    val startedAtMillis: Long,
    val lastSampleAtElapsedRealtime: Long,
    val lastSpeedKph: Double,
    val distanceKilometres: Double,
    val sampleCount: Long,
    val speedSum: Double,
    val maximumSpeedKph: Double,
    val rpmSum: Double,
    val maximumRpm: Long,
    val throttleSum: Double,
    val lastMileageKilometresPerLitre: Double?,
    val estimatedFuelLitres: Double?,
) {
    /** Folds one frame into the totals. Fuel only accumulates while mileage is reported. */
    fun add(frame: TelemetryFrame, receivedAtElapsedRealtime: Long, distanceDelta: Double): ActiveRide {
        val currentMileage = frame.instantaneousMileageKilometresPerLitre
        val fuelDelta = fuelDeltaLitres(
            distanceDelta,
            lastMileageKilometresPerLitre,
            currentMileage,
        )
        return copy(
            lastSampleAtElapsedRealtime = receivedAtElapsedRealtime,
            lastSpeedKph = frame.speedKilometresPerHour,
            distanceKilometres = distanceKilometres + distanceDelta,
            sampleCount = sampleCount + 1,
            speedSum = speedSum + frame.speedKilometresPerHour,
            maximumSpeedKph = maxOf(maximumSpeedKph, frame.speedKilometresPerHour),
            rpmSum = rpmSum + frame.engineRpm,
            maximumRpm = maxOf(maximumRpm, frame.engineRpm),
            throttleSum = throttleSum + frame.throttlePercent,
            lastMileageKilometresPerLitre = currentMileage,
            estimatedFuelLitres = fuelDelta?.let { (estimatedFuelLitres ?: 0.0) + it }
                ?: estimatedFuelLitres,
        )
    }

    /**
     * Converts the running totals into a stored ride. `id` is left at zero; the database
     * assigns the real one on insert.
     */
    fun toRide(endedAtMillis: Long): Ride {
        val divisor = sampleCount.coerceAtLeast(1).toDouble()
        return Ride(
            id = 0,
            startedAtMillis = startedAtMillis,
            endedAtMillis = endedAtMillis,
            distanceKilometres = distanceKilometres,
            averageSpeedKph = speedSum / divisor,
            maximumSpeedKph = maximumSpeedKph,
            averageRpm = rpmSum / divisor,
            maximumRpm = maximumRpm,
            averageThrottlePercent = throttleSum / divisor,
            estimatedFuelLitres = estimatedFuelLitres,
        )
    }

    companion object {
        fun started(startedAtMillis: Long, receivedAtElapsedRealtime: Long, frame: TelemetryFrame) = ActiveRide(
            startedAtMillis = startedAtMillis,
            lastSampleAtElapsedRealtime = receivedAtElapsedRealtime,
            lastSpeedKph = frame.speedKilometresPerHour,
            distanceKilometres = 0.0,
            sampleCount = 1,
            speedSum = frame.speedKilometresPerHour,
            maximumSpeedKph = frame.speedKilometresPerHour,
            rpmSum = frame.engineRpm.toDouble(),
            maximumRpm = frame.engineRpm,
            throttleSum = frame.throttlePercent.toDouble(),
            lastMileageKilometresPerLitre = frame.instantaneousMileageKilometresPerLitre,
            estimatedFuelLitres = null,
        )
    }
}
