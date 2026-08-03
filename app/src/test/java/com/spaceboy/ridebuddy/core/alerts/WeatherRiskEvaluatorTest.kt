package com.spaceboy.ridebuddy.core.alerts

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherRiskEvaluatorTest {
    @Test
    fun warnsForThunderstormAndStrongGusts() {
        assertNotNull(WeatherSnapshot(95, 0.0, 10.0, 0).riskMessage)
        assertNotNull(WeatherSnapshot(1, 0.0, 55.0, 0).riskMessage)
    }

    @Test
    fun warnsForLikelyUpcomingRain() {
        assertNotNull(WeatherSnapshot(1, 0.0, 10.0, 75).riskMessage)
    }

    @Test
    fun staysQuietForBenignConditions() {
        assertNull(WeatherSnapshot(1, 0.0, 10.0, 20).riskMessage)
    }
}
