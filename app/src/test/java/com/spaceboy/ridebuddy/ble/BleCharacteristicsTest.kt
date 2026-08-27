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

    @Test
    fun `identity snapshots preserve the OEM subscription profile`() {
        assertEquals(
            listOf("8810", "8910"),
            BleCharacteristics.PostAuthenticationIdentityReads.map { it.toString().takeLast(4) },
        )
        assertTrue(
            BleCharacteristics.PostAuthenticationSubscriptions.containsAll(
                BleCharacteristics.PostAuthenticationIdentityReads,
            ),
        )
    }
}
