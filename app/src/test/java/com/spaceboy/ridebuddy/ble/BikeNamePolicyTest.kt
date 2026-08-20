package com.spaceboy.ridebuddy.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/**
 * Locks in bike-name policy parity with the OEM Aprilia India app
 * (`com.piaggio.apriliaindia`, version 1.3). The OEM upper-cases the advertised name
 * with `Locale.ROOT` and asks `String.contains("RS457_ID")`; we mirror that primitive
 * exactly. If a future change tightens the filter (e.g. `startsWith` instead of
 * `contains`), update these tests alongside the implementation rather than letting
 * them drift.
 *
 * SR-family support was intentionally dropped — the app only knows the RS 457 /
 * Tuono 457 telemetry layout.
 */
class BikeNamePolicyTest {
    @Test
    fun acceptsAdvertisedRs457Names() {
        // The actual advertisement captured from the dev bike on vivo X200 Ultra.
        assertTrue("RS457_IDE1B7".isApriliaBikeName())
        // Mixed-case variants; the OEM does not care because the name is upper-cased first.
        assertTrue("rs457_ide1b7".isApriliaBikeName())
        assertTrue("Rs457_Id1234".isApriliaBikeName())
        // Suffix lengths and content can vary; only the prefix matters.
        assertTrue("RS457_ID1234".isApriliaBikeName())
        assertTrue("RS457_ID_AB".isApriliaBikeName())
    }

    @Test
    fun rejectsNonRs457NameVariants() {
        // Speculative variants the OEM does not pair with because the bike firmware
        // does not broadcast them.
        assertFalse("RS 457".isApriliaBikeName())
        assertFalse("RS457".isApriliaBikeName())
        assertFalse("RS-457".isApriliaBikeName())
        assertFalse("Tuono 457".isApriliaBikeName())
        assertFalse("tuono457".isApriliaBikeName())
        assertFalse("TUONO457_ID_ABCD".isApriliaBikeName())
        assertFalse("TUONO_457_ID_ABCD".isApriliaBikeName())
        assertFalse("tn457_id_demo".isApriliaBikeName())
        // No `_ID` suffix, so the OEM substring check fails.
        assertFalse("MIA_457".isApriliaBikeName())
    }

    @Test
    fun rejectsSrFamilyAndOtherApriliaNames() {
        // The OEM never recognised `SR_*`; matching the app's policy, SR names must
        // not be associated. The same goes for MIA (which the OEM never matches either).
        assertFalse("SR_ID_ABCD".isApriliaBikeName())
        assertFalse("APRILIA_MIA_1234".isApriliaBikeName())
        assertFalse("MIA_457_1234".isApriliaBikeName())
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
    fun cdmPickerPatternAcceptsRs457FamilyAndRejectsEverythingElse() {
        // The CDM `setNamePattern` filter must scope the picker to the RS 457 name
        // family so that the classic-BT `RS457_IDE1B7` headset can't masquerade
        // as the bike. Authoritative service-UUID filtering happens after a
        // successful pair (see BikeCompanionManager.associate()).
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
}
