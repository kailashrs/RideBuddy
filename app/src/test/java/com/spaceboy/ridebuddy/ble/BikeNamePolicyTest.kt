package com.spaceboy.ridebuddy.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeNamePolicyTest {
    @Test
    fun acceptsKnownRs457AndTuonoAdvertisements() {
        assertTrue("RS457_ID1234".isApriliaBikeName())
        assertTrue("TUONO457_ID_ABCD".isApriliaBikeName())
        assertTrue("tn457_id_demo".isApriliaBikeName())
    }

    @Test
    fun rejectsUnsupportedApriliaFamiliesAndUnrelatedDevices() {
        assertFalse("SR_ID_ABCD".isApriliaBikeName())
        assertFalse("APRILIA_MIA_1234".isApriliaBikeName())
        assertFalse("MIA_457_1234".isApriliaBikeName())
        assertTrue("SR_ID_ABCD".hasUnsupportedTelemetryLayout())
        assertTrue("APRILIA_MIA_1234".hasUnsupportedTelemetryLayout())
        assertFalse("HEADSET_RS457_ID".isApriliaBikeName())
    }
}
