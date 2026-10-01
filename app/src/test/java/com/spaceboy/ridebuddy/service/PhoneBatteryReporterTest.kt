package com.spaceboy.ridebuddy.service

import androidx.test.core.app.ApplicationProvider
import com.spaceboy.ridebuddy.FakeBikeConnection
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeControlEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhoneBatteryReporterTest {
    private val connection = FakeBikeConnection(BikeConnectionState.Disconnected)
    private val reporter = PhoneBatteryReporter(connection, batteryPercent = { 55 })

    private fun sent() = connection.writes.map { it.payload.map(Byte::toInt) }

    @Test
    fun `the packet is the OEM app-event packet with no event`() {
        assertEquals(listOf(11, 0, 55, 0), phoneBatteryPacket(55).map(Byte::toInt))
        assertEquals(listOf(11, 0, 100, 0), phoneBatteryPacket(140).map(Byte::toInt))
    }

    @Test
    fun `the level is sent when the link comes up and when the cluster restarts, never while away`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            reporter.start(ApplicationProvider.getApplicationContext(), scope)
            reporter.send()
            assertEquals(emptyList<List<Int>>(), sent())

            connection.connectionState.value = BikeConnectionState.Connected("RS 457", null)
            connection.controls.tryEmit(BikeControlEvent.ClusterReady)

            assertEquals(listOf(listOf(11, 0, 55, 0), listOf(11, 0, 55, 0)), sent())
        } finally {
            scope.cancel()
        }
    }
}
