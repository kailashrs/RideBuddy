package com.spaceboy.ridebuddy.ble

import com.spaceboy.ridebuddy.domain.BikeConnectionTarget
import android.net.MacAddress
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConnectionRequestPolicyTest {
    private val firstAddress = requireNotNull(MacAddress.fromString("CC:B3:1E:C1:E1:B7"))
    private val secondAddress = requireNotNull(MacAddress.fromString("CC:B3:1E:C1:E1:B8"))
    private val currentTarget = BikeConnectionTarget(firstAddress, "RS457_IDE1B7")

    @Test
    fun `same target does not restart an active connection`() {
        val renamedTarget = BikeConnectionTarget(firstAddress, "Renamed bike")

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
}
