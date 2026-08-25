package com.spaceboy.ridebuddy.ble

import com.spaceboy.ridebuddy.domain.BikeConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRequestPolicyTest {
    private val firstAddress = requireNotNull(BluetoothAddress.parse("CC:B3:1E:C1:E1:B7"))
    private val secondAddress = requireNotNull(BluetoothAddress.parse("CC:B3:1E:C1:E1:B8"))
    private val currentTarget = BikeConnectionTarget(firstAddress, "RS457_IDE1B7")

    @Test
    fun `same target does not restart an active connection`() {
        val renamedTarget = BikeConnectionTarget(firstAddress, "Renamed bike")

        assertFalse(shouldStartConnection(currentTarget, renamedTarget, BikeConnectionState.Scanning))
        assertFalse(shouldStartConnection(currentTarget, renamedTarget, BikeConnectionState.Connecting("bike")))
        assertFalse(shouldStartConnection(currentTarget, renamedTarget, BikeConnectionState.Authenticating("bike")))
        assertFalse(shouldStartConnection(currentTarget, renamedTarget, BikeConnectionState.Connected("bike", -60)))
    }

    @Test
    fun `same target starts from terminal states`() {
        assertTrue(shouldStartConnection(currentTarget, currentTarget, BikeConnectionState.Disconnected))
        assertTrue(shouldStartConnection(currentTarget, currentTarget, BikeConnectionState.Failed("failed")))
    }

    @Test
    fun `different target always replaces current connection`() {
        val replacement = BikeConnectionTarget(secondAddress, "Replacement")

        assertTrue(shouldStartConnection(currentTarget, replacement, BikeConnectionState.Connected("bike", null)))
    }

    @Test
    fun `disconnect diagnostics explain common controller status codes`() {
        assertEquals("link supervision timeout", gattConnectionStatusLabel(0x08))
        assertEquals("peer terminated connection", gattConnectionStatusLabel(0x13))
        assertEquals("local host terminated connection", gattConnectionStatusLabel(0x16))
        assertEquals("connection failed to establish", gattConnectionStatusLabel(0x3E))
        assertEquals("generic GATT error", gattConnectionStatusLabel(0x85))
        assertEquals("unknown", gattConnectionStatusLabel(0x7F))
    }
}
