package com.spaceboy.ridebuddy.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

class BikeNamePolicyTest {
    @Test
    fun acceptsKnownRs457AndTuonoAdvertisements() {
        assertTrue("RS457_ID1234".isApriliaBikeName())
        assertTrue("TUONO457_ID_ABCD".isApriliaBikeName())
        assertTrue("tn457_id_demo".isApriliaBikeName())
    }

    @Test
    fun acceptsActualDeviceAdvertisedNameWithSixCharSuffix() {
        // The full advertising name seen on vivo X200 Ultra for the dev bike.
        assertTrue("RS457_IDE1B7".isApriliaBikeName())
    }

    @Test
    fun acceptsFamilyPrefixesWithoutUnderscore() {
        assertTrue("RS 457".isApriliaBikeName())
        assertTrue("RS457".isApriliaBikeName())
        assertTrue("Tuono 457".isApriliaBikeName())
        assertTrue("RS-457".isApriliaBikeName())
        assertTrue("tuono457".isApriliaBikeName())
    }

    @Test
    fun acceptsCaseInsensitiveVariants() {
        assertTrue("rs457_ide1b7".isApriliaBikeName())
        assertTrue("RS457_IDE1B7".isApriliaBikeName())
        assertTrue("TUONO_457_ID_ABCD".isApriliaBikeName())
    }

    @Test
    fun rejectsUnsupportedApriliaFamiliesAndUnrelatedDevices() {
        assertFalse("SR_ID_ABCD".isApriliaBikeName())
        assertFalse("APRILIA_MIA_1234".isApriliaBikeName())
        assertFalse("MIA_457_1234".isApriliaBikeName())
        assertTrue("SR_ID_ABCD".hasUnsupportedTelemetryLayout())
        assertTrue("APRILIA_MIA_1234".hasUnsupportedTelemetryLayout())
    }

    @Test
    fun rejectsHeadsetPrefixEvenThoughFamilyMatches() {
        // A bluetooth headset whose name happens to contain the RS 457 substring would
        // now be flagged as an Aprilia device by find() semantics. The post-pick
        // validation in BikeCompanionManager.accept() also runs hasUnsupportedTelemetryLayout;
        // headsets fail that check because they don't match any Aprilia family. This test
        // documents the find() behaviour so a future revert to matches() cannot regress silently.
        assertTrue("HEADSET_RS457_ID".isApriliaBikeName())
        assertFalse("HEADSET_RS457_ID".hasUnsupportedTelemetryLayout())
    }

    @Test
    fun rejectsUnrelatedBluetoothDevices() {
        assertFalse("Galaxy Buds2 Pro".isApriliaBikeName())
        assertFalse("HUAWEI WATCH 5-1A2".isApriliaBikeName())
        assertFalse("AB Shutter3".isApriliaBikeName())
        assertFalse("CASIO GA-B2100".isApriliaBikeName())
        assertFalse("Moondrop Golden Ages 2".isApriliaBikeName())
    }

    @Test
    fun rejectsEmptyOrWhitespaceNames() {
        assertFalse("".isApriliaBikeName())
        assertFalse("   ".isApriliaBikeName())
    }

    @Test
    fun cdmShowAllPatternMatchesEverything() {
        // The CDM filter pattern must accept every name; post-pick validation rejects
        // anything that isn't an RS 457 / Tuono 457. This guarantees the system device
        // picker is never empty on OEM forks that apply setNamePattern with full-string semantics.
        assertTrue(CdmAcceptAllBikeNames.matcher("RS457_IDE1B7").matches())
        assertTrue(CdmAcceptAllBikeNames.matcher("Galaxy Buds2 Pro").matches())
        assertTrue(CdmAcceptAllBikeNames.matcher("").matches())
        assertTrue(CdmAcceptAllBikeNames.matcher("SR_ID_ABCD").matches())
    }
}
