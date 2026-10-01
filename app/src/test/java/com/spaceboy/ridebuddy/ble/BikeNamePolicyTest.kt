package com.spaceboy.ridebuddy.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.regex.Pattern

/** The picker's name and service filters, which are the only gate a bike passes through. */
class BikeNamePolicyTest {
    @Test
    fun cdmPickerPatternAcceptsRs457FamilyAndRejectsEverythingElse() {
        // The CDM `setNamePattern` filter must scope the picker to the RS 457 name
        // family so that the BR/EDR `RS457_IDE1B7` audio endpoint can't masquerade
        // as the bike. The service-UUID filter is ANDed with this one during the
        // picker scan itself, not after pairing (see BikeCompanionManager.associate()).
        val pattern: Pattern = BikeNameFilter
        // Family names with the per-bike hex suffix. The hex suffix is required —
        // a bare `RS457_ID` is not a real advertisement and never reaches the picker.
        assertTrue(pattern.matcher("RS457_IDE1B7").matches())
        assertTrue(pattern.matcher("rs457_id1234abcd").matches())
        assertTrue(pattern.matcher("RS457_ID-AB").matches())
        // Anything that doesn't start with `RS457_ID` is excluded.
        assertFalse(pattern.matcher("SR_ID_ABCD").matches())
        assertFalse(pattern.matcher("Galaxy Buds2 Pro").matches())
        assertFalse(pattern.matcher("AB Shutter3").matches())
        assertFalse(pattern.matcher("").matches())
        // Same-name peripheral: classic-BT headset sharing the displayed name on
        // vivo does not advertise its name through this pattern — the CDM regex
        // is anchored to the family prefix.
        assertFalse(pattern.matcher("HEADSET_RS457_ID").matches())
    }

    @Test
    fun hogpScanFilterUuidMatchesTheAdvertisementTheBikeActuallySends() {
        // The other half of the picker filter. A typo here does not fail loudly —
        // it produces a picker that silently lists nothing, so the exact value is
        // pinned. Taken verbatim from the CDM association record for the dev bike:
        //   mServiceUuids=[00001812-0000-1000-8000-00805f9b34fb]
        assertEquals("00001812-0000-1000-8000-00805f9b34fb", BikeHogpServiceUuidString)
        // SIG 16-bit 0x1812 expanded against the Bluetooth Base UUID.
        assertEquals(
            UUID.fromString("00001812-0000-1000-8000-00805f9b34fb"),
            UUID.fromString(BikeHogpServiceUuidString),
        )
    }
}
