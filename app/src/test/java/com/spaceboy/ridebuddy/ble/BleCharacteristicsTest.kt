package com.spaceboy.ridebuddy.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleCharacteristicsTest {
    /**
     * The set is the OEM's; the order is RideBuddy's own choice, so both are pinned here. Anything
     * outside this list — the SR-only mobile status packet in particular — must stay unsubscribed.
     */
    @Test
    fun `post-authentication subscriptions match the OEM profile in a deterministic order`() {
        assertEquals(
            listOf("8280", "8720", "8740", "8410", "8810", "8910"),
            BleCharacteristics.PostAuthenticationSubscriptions.map { it.toString().takeLast(4) },
        )
    }

    /**
     * Indications are the only way the identity values ever arrive. A capture shows both
     * characteristics answering a read promptly with a zero-filled buffer and delivering the real
     * value later as an indication, so dropping either subscription would mean the cluster
     * software version and VIN are never learned at all.
     */
    @Test
    fun `both identity values stay subscribed, since nothing else delivers them`() {
        assertTrue(
            BleCharacteristics.PostAuthenticationSubscriptions.containsAll(
                listOf(BleCharacteristics.ClusterSoftwareVersion, BleCharacteristics.Vin),
            ),
        )
    }
}
