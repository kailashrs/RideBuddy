package com.spaceboy.ridebuddy.ble

import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
import android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
import android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
import com.spaceboy.ridebuddy.domain.BikeWriteMode
import org.junit.Assert.assertEquals
import org.junit.Test

class WriteTypeTest {
    @Test
    fun `the preference only decides when the characteristic accepts both`() {
        val both = PROPERTY_WRITE or PROPERTY_WRITE_NO_RESPONSE
        assertEquals(WRITE_TYPE_NO_RESPONSE, writeType(both, BikeWriteMode.NoResponsePreferred))
        assertEquals(WRITE_TYPE_DEFAULT, writeType(both, BikeWriteMode.Default))
    }

    @Test
    fun `whichever type the characteristic declares wins`() {
        assertEquals(WRITE_TYPE_DEFAULT, writeType(PROPERTY_WRITE, BikeWriteMode.NoResponsePreferred))
        assertEquals(WRITE_TYPE_NO_RESPONSE, writeType(PROPERTY_WRITE_NO_RESPONSE, BikeWriteMode.Default))
    }
}
