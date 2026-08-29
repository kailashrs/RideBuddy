package com.spaceboy.ridebuddy.ble

import com.spaceboy.ridebuddy.domain.BikeControlEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 8740 is not call-only. The OEM switches on its value: 0 and 1 are reject and answer while a call
 * is up, and 2 is the cluster announcing it has come up and wants the phone's state again.
 */
class ClusterControlEventTest {
    @Test
    fun `zero and one are call actions`() {
        assertEquals(BikeControlEvent.CallAction(0), callControlEvent(byteArrayOf(0)))
        assertEquals(BikeControlEvent.CallAction(1), callControlEvent(byteArrayOf(1)))
    }

    @Test
    fun `two is the cluster announcing it is ready`() {
        assertEquals(BikeControlEvent.ClusterReady, callControlEvent(byteArrayOf(2)))
    }

    @Test
    fun `anything else is not guessed at`() {
        assertNull(callControlEvent(byteArrayOf(3)))
        assertNull(callControlEvent(byteArrayOf()))
    }

    @Test
    fun `the app event packet matches the OEM builder`() {
        // looper.b.l(i, helper) = {11, i, battery, 0}
        assertEquals(
            listOf(11, 7, 55, 0),
            com.spaceboy.ridebuddy.service.appEventPacket(7, 55).map { it.toInt() },
        )
    }
}

/** Mirrors the CallControl branch in AndroidBikeConnection.onNotification. */
private fun callControlEvent(value: ByteArray): BikeControlEvent? =
    when (value.firstOrNull()?.toInt()?.and(0xFF)) {
        0, 1 -> BikeControlEvent.CallAction(value.first().toInt() and 0xFF)
        2 -> BikeControlEvent.ClusterReady
        else -> null
    }
