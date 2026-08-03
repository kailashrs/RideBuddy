package com.spaceboy.ridebuddy.data

import android.util.Log
import com.spaceboy.ridebuddy.ble.TelemetryFrame
import com.spaceboy.ridebuddy.domain.BikeConnection
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.TelemetryReading
import com.spaceboy.ridebuddy.core.location.RideLocationLabeler
import com.spaceboy.ridebuddy.core.location.RideLocationTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch

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
    private val samplesLock = Any()
    private val samples = mutableListOf<RideSample>()
    private val liveWindow = mutableListOf<RideSample>()
    private val mutableLiveSamples = MutableStateFlow<List<RideSample>>(emptyList())
    val liveSamples: StateFlow<List<RideSample>> = mutableLiveSamples.asStateFlow()
    private val recordingDispatcher = Dispatchers.Default.limitedParallelism(1)
    private var lastLiveFrame: TelemetryFrame? = null
    private var lastLiveAtElapsedRealtime: Long? = null
    private var lastLiveEmitAtElapsedRealtime: Long = 0L

    fun start() {
        scope.launch { refreshHistory() }
        scope.launch(recordingDispatcher) {
            bikeConnection.rawTelemetry.collect(::record)
        }
        scope.launch(recordingDispatcher) {
            bikeConnection.connectionState.collect { state ->
                if (state !is BikeConnectionState.Connected) {
                    if (mutableActiveRide.value != null) finishRide()
                    lastLiveFrame = null
                    lastLiveAtElapsedRealtime = null
                    synchronized(samplesLock) { liveWindow.clear() }
                    mutableLiveSamples.value = emptyList()
                }
            }
        }
    }

    private fun record(reading: TelemetryReading) {
        if (bikeConnection.connectionState.value !is BikeConnectionState.Connected) return
        val frame = reading.frame
        val now = reading.receivedAtMillis
        val nowElapsedRealtime = reading.receivedAtElapsedRealtime
        val previousFrame = lastLiveFrame
        val previousAt = lastLiveAtElapsedRealtime
        val liveElapsedMillis = previousAt?.let { nowElapsedRealtime - it } ?: 0L
        val liveAcceleration = if (previousFrame != null && liveElapsedMillis in 1..MaxAccelerationSampleGapMillis) {
            ((frame.speedKilometresPerHour - previousFrame.speedKilometresPerHour) / 3.6) / (liveElapsedMillis / 1_000.0)
        } else 0.0
        val liveSample = sample(frame, now, liveAcceleration.coerceIn(-20.0, 20.0))
        lastLiveFrame = frame
        lastLiveAtElapsedRealtime = nowElapsedRealtime

        val snapshotLive = synchronized(samplesLock) {
            liveWindow += liveSample
            if (liveWindow.size > MaxLiveSamples) liveWindow.removeAt(0)
            if (nowElapsedRealtime - lastLiveEmitAtElapsedRealtime >= LiveSampleEmitIntervalMillis || liveWindow.size <= 1) {
                lastLiveEmitAtElapsedRealtime = nowElapsedRealtime
                liveWindow.toList()
            } else null
        }
        if (snapshotLive != null) {
            mutableLiveSamples.value = snapshotLive
        }

        val current = mutableActiveRide.value
        if (current == null) {
            if (frame.speedKilometresPerHour >= settingsRepository.settings.value.rideStartSpeedKph) {
                synchronized(samplesLock) {
                    samples.clear()
                    appendStoredSampleLocked(liveSample)
                }
                mutableActiveRide.value = ActiveRide.started(now, nowElapsedRealtime, frame)
            }
            return
        }

        val elapsedMillis = nowElapsedRealtime - current.lastSampleAtElapsedRealtime
        val distanceDelta = distanceDeltaKilometres(current.lastSpeedKph, frame.speedKilometresPerHour, elapsedMillis)
        val updated = current.add(frame, nowElapsedRealtime, distanceDelta)
        mutableActiveRide.value = updated
        synchronized(samplesLock) { appendStoredSampleLocked(liveSample) }

        val settings = settingsRepository.settings.value
        if (shouldStopRide(frame.speedKilometresPerHour, settings.rideStopSpeedKph)) {
            if (stopJob == null) {
                stopJob = scope.launch(recordingDispatcher) {
                    delay(settings.rideStopDelaySeconds.coerceIn(10, 600) * 1_000L)
                    val lastSpeed = mutableActiveRide.value?.lastSpeedKph ?: settings.rideStartSpeedKph
                    if (shouldStopRide(lastSpeed, settings.rideStopSpeedKph)) finishRide()
                }
            }
        } else {
            stopJob?.cancel()
            stopJob = null
        }
    }

    private fun finishRide() {
        stopJob?.cancel()
        stopJob = null
        val active = mutableActiveRide.getAndUpdate { null } ?: return
        val completedSamples = synchronized(samplesLock) {
            val list = samples.toList()
            samples.clear()
            list
        }
        if (active.distanceKilometres < MinimumSavedDistanceKm) return
        val start = completedSamples.firstOrNull { it.latitude != null && it.longitude != null }
        val end = completedSamples.lastOrNull { it.latitude != null && it.longitude != null }
        val route = completedSamples.routePreview()
        val zeroToSixty = completedSamples.accelerationTime(60.0)
        val zeroToHundred = completedSamples.accelerationTime(100.0)
        val completedRide = active.toRide(System.currentTimeMillis()).copy(
            startLatitude = start?.latitude,
            startLongitude = start?.longitude,
            endLatitude = end?.latitude,
            endLongitude = end?.longitude,
            routePreview = route,
            zeroToSixtyMillis = zeroToSixty,
            zeroToHundredMillis = zeroToHundred,
        )
        scope.launch {
            val rideId = persistRide(completedRide, completedSamples) ?: return@launch
            val startArea = locationLabeler.label(start?.latitude, start?.longitude)
            val endArea = locationLabeler.label(end?.latitude, end?.longitude)
            updateRideAreas(rideId, startArea, endArea)
        }
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
        val location = locationTracker.location.value
        return RideSample(
            timestampMillis = now,
            speedKph = frame.speedKilometresPerHour,
            rpm = frame.engineRpm,
            throttlePercent = frame.throttlePercent,
            consumptionLPer100Km = frame.instantaneousConsumptionLitresPer100Km,
            accelerationMetresPerSecondSquared = acceleration,
            latitude = location?.latitude,
            longitude = location?.longitude,
            accuracyMetres = location?.accuracyMetres,
            altitudeMetres = location?.altitudeMetres,
        )
    }

    private fun appendStoredSampleLocked(sample: RideSample) {
        samples += sample
        if (samples.size <= MaxStoredSamples) return
        val compacted = samples.filterIndexed { index, _ -> index % 2 == 0 }
        samples.clear()
        samples += compacted
    }

    companion object {
        const val MinimumSavedDistanceKm = 0.1
        const val MaxLiveSamples = 600
        const val MaxStoredSamples = 36_000
        const val MaxAccelerationSampleGapMillis = 2_500L
        private const val LiveSampleEmitIntervalMillis = 250L
        private const val LogTag = "RideRecorder"
    }
}

internal fun shouldStopRide(speedKph: Double, stopSpeedKph: Double): Boolean = speedKph <= stopSpeedKph

internal fun distanceDeltaKilometres(lastSpeedKph: Double, currentSpeedKph: Double, elapsedMillis: Long): Double =
    if (elapsedMillis !in 1..MaxDistanceIntegrationGapMillis) 0.0
    else ((lastSpeedKph + currentSpeedKph) / 2.0) * elapsedMillis / 3_600_000.0

internal const val MaxDistanceIntegrationGapMillis = 2_500L

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

private const val LaunchSpeedKph = 5.0
private const val MaxPerformanceSampleGapMillis = 2_500L
private const val MinimumPerformanceMillis = 500L
private const val MaximumPerformanceMillis = 60_000L

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
    val consumptionSum: Double,
) {
    fun add(frame: TelemetryFrame, receivedAtElapsedRealtime: Long, distanceDelta: Double): ActiveRide = copy(
        lastSampleAtElapsedRealtime = receivedAtElapsedRealtime,
        lastSpeedKph = frame.speedKilometresPerHour,
        distanceKilometres = distanceKilometres + distanceDelta,
        sampleCount = sampleCount + 1,
        speedSum = speedSum + frame.speedKilometresPerHour,
        maximumSpeedKph = maxOf(maximumSpeedKph, frame.speedKilometresPerHour),
        rpmSum = rpmSum + frame.engineRpm,
        maximumRpm = maxOf(maximumRpm, frame.engineRpm),
        throttleSum = throttleSum + frame.throttlePercent,
        consumptionSum = consumptionSum + frame.instantaneousConsumptionLitresPer100Km,
    )

    fun toRide(endedAtMillis: Long): Ride {
        val divisor = sampleCount.coerceAtLeast(1).toDouble()
        val averageConsumption = consumptionSum / divisor
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
            averageConsumptionLPer100Km = averageConsumption,
            estimatedFuelLitres = distanceKilometres * averageConsumption / 100.0,
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
            consumptionSum = frame.instantaneousConsumptionLitresPer100Km,
        )
    }
}
