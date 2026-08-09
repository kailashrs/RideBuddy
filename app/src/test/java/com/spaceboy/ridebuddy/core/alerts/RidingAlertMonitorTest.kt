package com.spaceboy.ridebuddy.core.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RidingAlertMonitorTest {
    @Test
    fun transientHardMotionSampleIsEvaluatedIndependently() {
        val alerts = listOf(0.0, -4.0, 0.0).mapNotNull { acceleration ->
            ridingMotionAlert(
                accelerationMetresPerSecondSquared = acceleration,
                accelerationAlertsEnabled = true,
                brakingAlertsEnabled = true,
            )
        }

        assertEquals(listOf(RidingMotionAlert.HardBraking), alerts)
    }

    @Test
    fun disabledMotionAlertsRemainSilent() {
        assertNull(ridingMotionAlert(4.0, accelerationAlertsEnabled = false, brakingAlertsEnabled = true))
        assertNull(ridingMotionAlert(-4.0, accelerationAlertsEnabled = true, brakingAlertsEnabled = false))
    }
}
