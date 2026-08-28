package com.spaceboy.ridebuddy.core.companion

import com.spaceboy.ridebuddy.ble.BluetoothAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeCompanionPolicyTest {
    private val storedBike = AssociatedBike(
        bluetoothAddress = requireNotNull(BluetoothAddress.parse("AA:BB:CC:DD:EE:FF")),
        name = "Motorcycle",
        associationId = 42,
    )

    @Test
    fun localAssociationClearsOnlyAfterRequiredSystemDisassociationSucceeds() {
        assertFalse(
            canClearLocalAssociation(
                hasStoredAssociation = true,
                companionSupported = true,
                managerAvailable = true,
                disassociationSucceeded = false,
            ),
        )
        assertTrue(
            canClearLocalAssociation(
                hasStoredAssociation = true,
                companionSupported = true,
                managerAvailable = true,
                disassociationSucceeded = true,
            ),
        )
        assertFalse(
            canClearLocalAssociation(
                hasStoredAssociation = true,
                companionSupported = true,
                managerAvailable = false,
                disassociationSucceeded = false,
            ),
        )
    }

    @Test
    fun unsupportedPhonesMayForgetAStoreOnlyLegacyBike() {
        assertTrue(
            canClearLocalAssociation(
                hasStoredAssociation = true,
                companionSupported = false,
                managerAvailable = false,
                disassociationSucceeded = false,
            ),
        )
    }

    @Test
    fun replacingAssociationClearsAcceptanceForThePreviousAddress() {
        val replacement = storedBike.copy(
            bluetoothAddress = requireNotNull(BluetoothAddress.parse("11:22:33:44:55:66")),
        )

        assertEquals(
            storedBike.bluetoothAddress,
            protectionAcceptanceToClear(storedBike, replacement),
        )
        assertEquals(null, protectionAcceptanceToClear(storedBike, storedBike.copy(name = "Renamed")))
    }
}
