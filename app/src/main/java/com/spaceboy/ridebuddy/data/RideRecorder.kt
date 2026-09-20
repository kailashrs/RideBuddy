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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
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
    /**
     * Set when the rider ends a ride by hand, and cleared once speed falls back under the stop
     * threshold. Without it the next telemetry frame at road speed opens a fresh ride straight
     * away and the button looks inert.
     */
    private var awaitStopBeforeNextRide = false
    private val saveQueue = RideSaveQueue(
        insert = repository::insert,
        onSaved = { rideId, ride ->
            // Enrichment is optional and runs after the primary save barrier opens.
            scope.launch {
                val startArea = locationLabeler.label(ride.startLatitude, ride.startLongitude)
                val endArea = locationLabeler.label(ride.endLatitude, ride.endLongitude)
                updateRideAreas(rideId, startArea, endArea)
            }
        },
        onFailure = { error -> Log.e(LogTag, "Could not save completed ride; retained for retry", error) },
    )
    val saveFailed: StateFlow<Boolean> = saveQueue.saveFailed

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
                        if (mutableActiveRide.value != null) finishRide(stopCandidate, endOfSession = true)
                        // The next session is a new outing, so a hand-ended ride stops holding
                        // recording closed across it.
                        awaitStopBeforeNextRide = false
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
        val nowElapsedRealtime = reading.receivedAtElapsedRealtime
        val now = mutableActiveRide.value?.let {
            it.startedAtMillis + (nowElapsedRealtime - it.startedAtElapsedRealtime).coerceAtLeast(0L)
        } ?: reading.receivedAtMillis
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
            if (awaitStopBeforeNextRide) {
                if (shouldStopRide(
                        frame.speedKilometresPerHour,
                        settingsRepository.settings.value.rideStopSpeedKph,
                    )
                ) {
                    awaitStopBeforeNextRide = false
                }
                return
            }
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
        val updated = baseline.add(frame, nowElapsedRealtime)
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
     * Automatic stop detection discards rides below [MinimumSavedDistanceKm]. A terminal
     * session end always saves an already active ride, including a deliberately short one.
     *
     * Saving happens in two stages. The ride is persisted first, then reverse geocoding
     * fills in the place labels — that needs a network and must not delay or endanger the
     * write of the ride itself.
     */
    private fun finishRide(confirmedStop: StopCandidate? = null, endOfSession: Boolean = false) {
        stopJob?.cancel()
        stopJob = null
        stopCandidate = null
        resumePending = false
        val latestActive = mutableActiveRide.getAndUpdate { null } ?: return
        val active = confirmedStop?.activeRide ?: latestActive
        val completedSamples = (confirmedStop?.samples ?: samples.toList()).also { samples.clear() }
        if (!endOfSession && active.distanceKilometres < MinimumSavedDistanceKm) return
        val start = completedSamples.firstOrNull { it.latitude != null && it.longitude != null }
        val end = completedSamples.lastOrNull { it.latitude != null && it.longitude != null }
        val route = completedSamples.routePreview()
        val zeroToSixty = completedSamples.accelerationTime(60.0)
        val zeroToHundred = completedSamples.accelerationTime(100.0)
        val completedRide = active.toRide().copy(
            startLatitude = start?.latitude,
            startLongitude = start?.longitude,
            endLatitude = end?.latitude,
            endLongitude = end?.longitude,
            routePreview = route,
            zeroToSixtyMillis = zeroToSixty,
            zeroToHundredMillis = zeroToHundred,
        )
        saveQueue.enqueue(completedRide, completedSamples)
        scope.launch { saveQueue.flush() }
    }

    /**
     * Retries a save the disk refused. Needed on its own because a ride ended by hand fails
     * while the service is still running, so there is no shutdown pass to carry the retry.
     */
    fun retrySave() {
        scope.launch { saveQueue.flush() }
    }

    /**
     * Ends the ride the rider is on, at their request, without ending the session.
     *
     * The link, guidance and cluster notifications are separate features they have not asked to
     * give up — a rider who ends a ride at a stop is usually about to carry on. Recording stays
     * closed until the bike is actually stationary, so pressing this at road speed cannot be
     * undone by the very next frame.
     *
     * Runs on the recording dispatcher, which orders it against the telemetry collector rather
     * than racing it for [mutableActiveRide].
     */
    fun endRideNow() {
        scope.launch(RecordingDispatcher) {
            if (mutableActiveRide.value == null) return@launch
            finishRide(stopCandidate, endOfSession = true)
            awaitStopBeforeNextRide = true
        }
    }

    /**
     * Ends any active ride and waits for its primary insert. False retains it for Retry save.
     *
     * The service cannot do this by watching a counter. It collects the same connection state
     * this recorder does, on a different dispatcher, so it can reach the barrier before the
     * recorder has even seen the state that ends the ride — finding a count of zero and
     * shutting down while the ride is still un-finalised. Finalisation therefore happens *here*,
     * on the recorder's own single-threaded dispatcher, which orders it against the collector:
     * whichever runs first, the other finds the ride already ended and simply awaits the save.
     */
    suspend fun finalizeAndAwaitSave(): Boolean {
        withContext(RecordingDispatcher) {
            if (mutableActiveRide.value != null) finishRide(stopCandidate, endOfSession = true)
            clearLiveTelemetryState()
        }
        return saveQueue.flush()
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
    if (elapsedMillis !in 1..MaxDistanceIntegrationGapMillis ||
        !lastSpeedKph.isFinite() || !currentSpeedKph.isFinite() || lastSpeedKph < 0.0 || currentSpeedKph < 0.0
    ) 0.0
    else ((lastSpeedKph + currentSpeedKph) / 2.0) * elapsedMillis / 3_600_000.0

internal const val MaxDistanceIntegrationGapMillis = 2_500L

/**
 * Fuel used over one interval, or null when it cannot be known.
 *
 * The vehicle reports km/L, so speed divided by mileage gives L/hour. Trapezoidal
 * integration of that rate matches the speed integration used for distance. Both readings are
 * required: a missing one is "no data", and treating it as zero consumption would
 * understate a ride's fuel use for as long as the gap lasted.
 */
internal fun fuelDeltaLitres(
    distanceKilometres: Double,
    previousMileageKilometresPerLitre: Double?,
    currentMileageKilometresPerLitre: Double?,
    previousSpeedKph: Double,
    currentSpeedKph: Double,
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
    val speedSum = previousSpeedKph + currentSpeedKph
    if (!speedSum.isFinite() || previousSpeedKph < 0.0 || currentSpeedKph < 0.0 || speedSum <= 0.0) return null
    // Distance was integrated from speed, so weight endpoint L/km by endpoint speed.
    // Multiplying two independent endpoint averages introduces incorrect cross terms.
    return distanceKilometres *
        (previousSpeedKph * previousFuelLitresPerKilometre + currentSpeedKph * currentFuelLitresPerKilometre) / speedSum
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
    if (size < 2 || !targetKph.isFinite() || targetKph <= LaunchSpeedKph) return null
    var launchAtMillis: Long? = null
    var previous: RideSample? = null
    var bestMillis: Long? = null
    for (sample in this) {
        val prior = previous
        if (!sample.speedKph.isFinite() || sample.speedKph < 0.0 ||
            (prior != null && sample.timestampMillis - prior.timestampMillis !in 1..MaxPerformanceSampleGapMillis)
        ) {
            launchAtMillis = null
        }
        if (sample.speedKph in 0.0..LaunchSpeedKph) {
            launchAtMillis = sample.timestampMillis
        } else if (launchAtMillis != null && sample.speedKph >= targetKph) {
            val crossingAt = if (prior != null && prior.speedKph < targetKph && sample.speedKph > prior.speedKph) {
                val fraction = ((targetKph - prior.speedKph) / (sample.speedKph - prior.speedKph)).coerceIn(0.0, 1.0)
                prior.timestampMillis + ((sample.timestampMillis - prior.timestampMillis) * fraction).toLong()
            } else sample.timestampMillis
            val duration = (crossingAt - launchAtMillis).takeIf { it in MinimumPerformanceMillis..MaximumPerformanceMillis }
            if (duration != null) bestMillis = bestMillis?.let { minOf(it, duration) } ?: duration
            launchAtMillis = null
        }
        previous = sample
    }
    return bestMillis
}

/** Treated as a standing start. Not zero: wheel speed idles noisily just above it. */
private const val LaunchSpeedKph = 0.5
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
        RoutePoint(latitude, longitude).takeIf(RoutePoint::isValid)
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
    val startedAtElapsedRealtime: Long,
    val lastSampleAtElapsedRealtime: Long,
    val lastSpeedKph: Double,
    val lastRpm: Long,
    val lastThrottlePercent: Int,
    val distanceKilometres: Double,
    val telemetryDurationMillis: Long,
    val maximumSpeedKph: Double,
    val rpmMillis: Double,
    val maximumRpm: Long,
    val throttleMillis: Double,
    val lastMileageKilometresPerLitre: Double?,
    val estimatedFuelLitres: Double?,
) {
    /** Integrates only measured intervals. */
    fun add(frame: TelemetryFrame, receivedAtElapsedRealtime: Long): ActiveRide {
        val elapsed = (receivedAtElapsedRealtime - lastSampleAtElapsedRealtime)
            .takeIf { it in 1..MaxDistanceIntegrationGapMillis } ?: 0L
        val distanceDelta = distanceDeltaKilometres(lastSpeedKph, frame.speedKilometresPerHour, elapsed)
        val currentMileage = frame.instantaneousMileageKilometresPerLitre
        val fuelDelta = fuelDeltaLitres(distanceDelta, lastMileageKilometresPerLitre, currentMileage, lastSpeedKph, frame.speedKilometresPerHour)
        return copy(
            lastSampleAtElapsedRealtime = receivedAtElapsedRealtime,
            lastSpeedKph = frame.speedKilometresPerHour,
            lastRpm = frame.engineRpm,
            lastThrottlePercent = frame.throttlePercent,
            distanceKilometres = distanceKilometres + distanceDelta,
            telemetryDurationMillis = telemetryDurationMillis + elapsed,
            maximumSpeedKph = maxOf(maximumSpeedKph, frame.speedKilometresPerHour),
            rpmMillis = rpmMillis + (lastRpm / 2.0 + frame.engineRpm / 2.0) * elapsed,
            maximumRpm = maxOf(maximumRpm, frame.engineRpm),
            throttleMillis = throttleMillis + (lastThrottlePercent / 2.0 + frame.throttlePercent / 2.0) * elapsed,
            lastMileageKilometresPerLitre = currentMileage,
            // An interval without a mileage reading contributes nothing rather than voiding
            // the total. The bike encodes 0 km/L on every closed-throttle overrun, which parses
            // to "no reading", so demanding unbroken coverage threw the estimate away on every
            // real ride -- and those are the intervals burning least fuel anyway.
            estimatedFuelLitres = fuelDelta?.let { (estimatedFuelLitres ?: 0.0) + it }
                ?: estimatedFuelLitres,
        )
    }

    /** Wall-clock corrections cannot change duration: anchor the elapsed time to the start. */
    fun toRide(): Ride {
        val measuredMillis = telemetryDurationMillis.coerceAtLeast(1).toDouble()
        return Ride(
            id = 0,
            startedAtMillis = startedAtMillis,
            endedAtMillis = startedAtMillis + (lastSampleAtElapsedRealtime - startedAtElapsedRealtime).coerceAtLeast(0L),
            distanceKilometres = distanceKilometres,
            averageSpeedKph = distanceKilometres * 3_600_000.0 / measuredMillis,
            maximumSpeedKph = maximumSpeedKph,
            averageRpm = if (telemetryDurationMillis > 0) rpmMillis / measuredMillis else lastRpm.toDouble(),
            maximumRpm = maximumRpm,
            averageThrottlePercent = if (telemetryDurationMillis > 0) throttleMillis / measuredMillis else lastThrottlePercent.toDouble(),
            estimatedFuelLitres = estimatedFuelLitres,
            telemetryDurationMillis = telemetryDurationMillis,
        )
    }

    companion object {
        fun started(startedAtMillis: Long, receivedAtElapsedRealtime: Long, frame: TelemetryFrame) = ActiveRide(
            startedAtMillis = startedAtMillis,
            startedAtElapsedRealtime = receivedAtElapsedRealtime,
            lastSampleAtElapsedRealtime = receivedAtElapsedRealtime,
            lastSpeedKph = frame.speedKilometresPerHour,
            lastRpm = frame.engineRpm,
            lastThrottlePercent = frame.throttlePercent,
            distanceKilometres = 0.0,
            telemetryDurationMillis = 0L,
            maximumSpeedKph = frame.speedKilometresPerHour,
            rpmMillis = 0.0,
            maximumRpm = frame.engineRpm,
            throttleMillis = 0.0,
            lastMileageKilometresPerLitre = frame.instantaneousMileageKilometresPerLitre,
            estimatedFuelLitres = null,
        )
    }
}
