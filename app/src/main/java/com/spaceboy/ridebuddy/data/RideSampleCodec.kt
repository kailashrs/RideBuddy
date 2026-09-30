package com.spaceboy.ridebuddy.data

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * A ride's samples as stored: offsets from the ride's start rather than epoch times, and
 * single precision for everything but coordinates, which is still finer than the source data.
 * ProtoBuf keeps absent readings absent, and gzip removes most of what repeats between rows.
 */
@Serializable
private class StoredSeries(val samples: List<StoredSample>)

@Serializable
private class StoredSample(
    val offsetMillis: Long,
    val speedKph: Float,
    val rpm: Long,
    val throttlePercent: Int,
    val accelerationMetresPerSecondSquared: Float,
    val mileageKilometresPerLitre: Float? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMetres: Float? = null,
    val altitudeMetres: Float? = null,
)

@OptIn(ExperimentalSerializationApi::class)
internal fun encodeSampleSeries(startedAtMillis: Long, samples: List<RideSample>): ByteArray {
    val series = StoredSeries(
        samples.map { sample ->
            StoredSample(
                offsetMillis = sample.timestampMillis - startedAtMillis,
                speedKph = sample.speedKph.toFloat(),
                rpm = sample.rpm,
                throttlePercent = sample.throttlePercent,
                accelerationMetresPerSecondSquared = sample.accelerationMetresPerSecondSquared.toFloat(),
                mileageKilometresPerLitre = sample.mileageKilometresPerLitre?.toFloat(),
                latitude = sample.latitude,
                longitude = sample.longitude,
                accuracyMetres = sample.accuracyMetres,
                altitudeMetres = sample.altitudeMetres?.toFloat(),
            )
        },
    )
    val bytes = ByteArrayOutputStream()
    GZIPOutputStream(bytes).use { it.write(ProtoBuf.encodeToByteArray(series)) }
    return bytes.toByteArray()
}

@OptIn(ExperimentalSerializationApi::class)
internal fun decodeSampleSeries(startedAtMillis: Long, data: ByteArray): List<RideSample> {
    val series = ProtoBuf.decodeFromByteArray<StoredSeries>(GZIPInputStream(data.inputStream()).use { it.readBytes() })
    return series.samples.map { stored ->
        RideSample(
            timestampMillis = startedAtMillis + stored.offsetMillis,
            speedKph = stored.speedKph.toDouble(),
            rpm = stored.rpm,
            throttlePercent = stored.throttlePercent,
            mileageKilometresPerLitre = stored.mileageKilometresPerLitre?.toDouble(),
            accelerationMetresPerSecondSquared = stored.accelerationMetresPerSecondSquared.toDouble(),
            latitude = stored.latitude,
            longitude = stored.longitude,
            accuracyMetres = stored.accuracyMetres,
            altitudeMetres = stored.altitudeMetres?.toDouble(),
        )
    }
}

/**
 * The interval stored samples are thinned to, from the roughly 250 ms the vehicle sends.
 *
 * Everything that reads the series back downsamples it much further — six hundred points for
 * a chart, a thousand for a route — so four samples a second were being written to disk and
 * then discarded on every read. One a second still gives an hour-long ride 3,600 points,
 * several times what any reader uses, and is the rate GPS traces are conventionally logged at.
 *
 * The exception is acceleration, which is why [RideSample.accelerationMetresPerSecondSquared]
 * is carried through thinning as the interval's extreme rather than the representative
 * sample's own value. See [decimatedForStorage].
 */
internal const val StoredSampleIntervalMillis = 1_000L

/**
 * Thins a completed ride's samples to one per [intervalMillis] for storage.
 *
 * The first sample of each interval is kept, so stored timestamps stay on the original
 * grid and a telemetry outage still shows as a gap rather than being closed up.
 *
 * Acceleration is treated differently from every other value. A hard stop is a brief,
 * high-magnitude excursion, and keeping whichever value happened to land on the retained
 * sample would flatten it — the ride-events list would lose exactly the episodes it exists
 * to report. The retained sample therefore carries the largest-magnitude acceleration seen
 * anywhere in its interval, which leaves [RideEventDetector] able to find the same episodes
 * at the same peaks it would have found at full rate.
 *
 * Runs on the ride's full-rate samples at save time, so the figures derived before storage —
 * the acceleration times and the route preview — are unaffected by any of this.
 */
internal fun List<RideSample>.decimatedForStorage(
    intervalMillis: Long = StoredSampleIntervalMillis,
): List<RideSample> {
    if (intervalMillis <= 0 || size < 2) return this
    val thinned = ArrayList<RideSample>(size / 4 + 1)
    var representative: RideSample? = null
    var peak = 0.0
    fun emit() {
        representative?.let { thinned += it.copy(accelerationMetresPerSecondSquared = peak) }
    }
    forEach { sample ->
        val current = representative
        if (current == null || sample.timestampMillis - current.timestampMillis >= intervalMillis) {
            emit()
            representative = sample
            peak = sample.accelerationMetresPerSecondSquared
        } else if (kotlin.math.abs(sample.accelerationMetresPerSecondSquared) > kotlin.math.abs(peak)) {
            peak = sample.accelerationMetresPerSecondSquared
        }
    }
    emit()
    return thinned
}
