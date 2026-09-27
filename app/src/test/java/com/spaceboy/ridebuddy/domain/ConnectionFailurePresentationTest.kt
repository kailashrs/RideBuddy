package com.spaceboy.ridebuddy.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionFailurePresentationTest {
    @Test
    fun localFailuresUseShortCorrectiveCopy() {
        assertEquals(
            "Allow Nearby devices access.",
            riderFacingConnectionFailure(
                "Allow Nearby devices to connect to the motorcycle",
                ConnectionFailureCategory.LocalPrecondition,
            ),
        )
        assertEquals(
            "Turn on Bluetooth.",
            riderFacingConnectionFailure(
                "Turn on Bluetooth to connect to the motorcycle",
                ConnectionFailureCategory.LocalPrecondition,
            ),
        )
    }

    @Test
    fun linkErrorsDoNotExposeGattDetailsToUserCopy() {
        assertEquals(
            "Couldn't connect.",
            riderFacingConnectionFailure(
                "Link lost while starting service discovery: status 133",
                ConnectionFailureCategory.LinkLost,
            ),
        )
    }
}
