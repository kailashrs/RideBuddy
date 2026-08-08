package com.spaceboy.ridebuddy.core.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

class RideLocationLabeler(context: Context) {
    private val geocoder = Geocoder(context.applicationContext, Locale.getDefault())

    suspend fun label(latitude: Double?, longitude: Double?): String? {
        if (latitude == null || longitude == null) return null
        val address = try {
            withTimeoutOrNull(GeocoderTimeoutMillis.milliseconds) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCancellableCoroutine { continuation ->
                        geocoder.getFromLocation(latitude, longitude, 1) { results ->
                            if (continuation.isActive) continuation.resume(results.firstOrNull())
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    withContext(Dispatchers.IO) { geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull() }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        return address?.shortLabel() ?: "%.4f, %.4f".format(Locale.US, latitude, longitude)
    }

    private fun Address.shortLabel(): String? = listOfNotNull(subLocality, locality, adminArea)
        .distinct()
        .take(2)
        .joinToString(", ")
        .takeIf(String::isNotBlank)

    private companion object {
        const val GeocoderTimeoutMillis = 5_000L
    }
}
