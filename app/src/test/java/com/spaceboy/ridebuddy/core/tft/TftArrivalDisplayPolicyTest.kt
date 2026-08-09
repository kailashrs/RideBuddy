package com.spaceboy.ridebuddy.core.tft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TftArrivalDisplayPolicyTest {
    @Test
    fun `arrival display timer starts only after its write completes`() {
        assertNull(
            arrivalDisplayTimerGeneration(
                arrivalGeneration = 7L,
                writeCompletedSuccessfully = false,
            ),
        )
        assertEquals(
            7L,
            arrivalDisplayTimerGeneration(
                arrivalGeneration = 7L,
                writeCompletedSuccessfully = true,
            ),
        )
    }
}
