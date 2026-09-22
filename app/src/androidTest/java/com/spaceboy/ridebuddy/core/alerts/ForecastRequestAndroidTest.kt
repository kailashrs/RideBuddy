package com.spaceboy.ridebuddy.core.alerts

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.URL
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** `Uri.Builder` is a stub off-device, so the assembled request is asserted here. */
@RunWith(AndroidJUnit4::class)
class ForecastRequestAndroidTest {
    @Test
    fun theRequestCarriesEveryFieldTheResponseIsReadFor() {
        val url = URL(forecastUrl(12.97, 77.5945627))

        assertEquals("https", url.protocol)
        assertEquals("api.open-meteo.com", url.host)
        assertEquals("/v1/forecast", url.path)
        val query = url.query.split("&").associate {
            val (name, value) = it.split("=", limit = 2)
            name to URLDecoder.decode(value, "UTF-8")
        }
        assertEquals("12.970000", query["latitude"])
        assertEquals("77.594563", query["longitude"])
        // The commas arrive percent-encoded; the live service reads them the same either way.
        assertEquals("weather_code,precipitation,wind_gusts_10m", query["current"])
        assertEquals("precipitation_probability", query["hourly"])
        assertEquals("3", query["forecast_hours"])
        assertEquals("auto", query["timezone"])
    }
}
