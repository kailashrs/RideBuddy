package com.spaceboy.ridebuddy.service

import android.companion.DevicePresenceEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class BikeCompanionDeviceServiceTest {
    @Test
    fun blePresenceControlsConnectionWhileClassicDisconnectDoesNotMarkBikeAbsent() {
        assertEquals(
            CompanionPresenceAction.Reconnect,
            companionPresenceAction(DevicePresenceEvent.EVENT_BLE_APPEARED),
        )
        assertEquals(
            CompanionPresenceAction.MarkAbsent,
            companionPresenceAction(DevicePresenceEvent.EVENT_BLE_DISAPPEARED),
        )
        assertEquals(
            CompanionPresenceAction.Ignore,
            companionPresenceAction(DevicePresenceEvent.EVENT_BT_DISCONNECTED),
        )
    }
}
