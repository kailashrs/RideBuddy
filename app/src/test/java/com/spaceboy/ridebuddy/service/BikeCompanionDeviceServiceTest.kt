package com.spaceboy.ridebuddy.service

import android.companion.DevicePresenceEvent
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeCompanionDeviceServiceTest {
    @Test
    fun legacyCallbacksStopAtAndroid16WhereTypedCallbacksAreDelivered() {
        assertTrue(shouldHandleLegacyPresenceCallback(Build.VERSION_CODES.VANILLA_ICE_CREAM))
        assertFalse(shouldHandleLegacyPresenceCallback(Build.VERSION_CODES.BAKLAVA))
    }

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
