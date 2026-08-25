package com.spaceboy.ridebuddy.service

import android.companion.DevicePresenceEvent
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeCompanionDeviceServiceTest {
    @Test
    fun positivePresenceEnsuresConnectionWhileNegativeSourcesLeaveGattUnchanged() {
        assertEquals(
            CompanionPresenceAction.EnsureConnected,
            companionPresenceAction(DevicePresenceEvent.EVENT_BLE_APPEARED),
        )
        assertEquals(
            CompanionPresenceAction.EnsureConnected,
            companionPresenceAction(DevicePresenceEvent.EVENT_BT_CONNECTED),
        )
        assertEquals(
            CompanionPresenceAction.KeepConnection,
            companionPresenceAction(DevicePresenceEvent.EVENT_BLE_DISAPPEARED),
        )
        assertEquals(
            CompanionPresenceAction.KeepConnection,
            companionPresenceAction(DevicePresenceEvent.EVENT_BT_DISCONNECTED),
        )
    }

    @Test
    fun presenceOnlyRequestsAConnectionFromATerminalState() {
        assertTrue(shouldRequestPresenceReconnect(BikeConnectionState.Disconnected))
        assertTrue(shouldRequestPresenceReconnect(BikeConnectionState.Failed("failed")))
        assertFalse(shouldRequestPresenceReconnect(BikeConnectionState.Scanning))
        assertFalse(shouldRequestPresenceReconnect(BikeConnectionState.Connecting("bike")))
        assertFalse(shouldRequestPresenceReconnect(BikeConnectionState.Authenticating("bike")))
        assertFalse(shouldRequestPresenceReconnect(BikeConnectionState.Connected("bike", null)))
    }
}
