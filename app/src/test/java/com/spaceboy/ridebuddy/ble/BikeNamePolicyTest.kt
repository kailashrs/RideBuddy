package com.spaceboy.ridebuddy.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

/**
 * Locks in bike-name policy parity with the OEM Aprilia India app
 * (`com.piaggio.apriliaindia`, version 1.3). The OEM upper-cases the advertised name
 * with `Locale.ROOT` and asks `String.contains("RS457_ID")` /
 * `String.contains("SR_ID")`; we mirror that primitive exactly. If a future change
 * tightens the filter (e.g. `startsWith` instead of `contains`), update these tests
 * alongside the implementation rather than letting them drift.
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
        // Speculative variants the old regex used to accept; the OEM does not pair
        // with these because the bike firmware does not broadcast them.
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
    fun acceptsSubstringMatchesIncludingHeadsets() {
        // The OEM uses `contains`, so anything that *contains* `RS457_ID` matches.
        // A headset named `HEADSET_RS457_ID` would pass the name check at the CDM
        // picker layer; downstream GATT service discovery (and the protection
        // handshake) is the gate that actually rejects non-bike devices. We pin
        // this substring semantics so a future tightening cannot regress silently.
        assertTrue("HEADSET_RS457_ID".isApriliaBikeName())
        assertFalse("HEADSET_RS457_ID".hasUnsupportedTelemetryLayout())
    }

    @Test
    fun rejectsSrFamilyButNotOtherApriliaNames() {
        // Only `SR_ID` triggers the unsupported-telemetry bail.
        assertFalse("SR_ID_ABCD".isApriliaBikeName())
        assertTrue("SR_ID_ABCD".hasUnsupportedTelemetryLayout())
        // The OEM never recognised `APRILIA_MIA_*` or `MIA_*`; we match that.
        assertFalse("APRILIA_MIA_1234".isApriliaBikeName())
        assertFalse("APRILIA_MIA_1234".hasUnsupportedTelemetryLayout())
        assertFalse("MIA_457_1234".isApriliaBikeName())
        assertFalse("MIA_457_1234".hasUnsupportedTelemetryLayout())
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
        // anything that isn't an RS 457 family name. This guarantees the system device
        // picker is never empty on OEM forks that apply setNamePattern with full-string semantics.
        val pattern: Pattern = CdmAcceptAllBikeNames
        assertTrue(pattern.matcher("RS457_IDE1B7").matches())
        assertTrue(pattern.matcher("Galaxy Buds2 Pro").matches())
        assertTrue(pattern.matcher("").matches())
        assertTrue(pattern.matcher("SR_ID_ABCD").matches())
    }
}
