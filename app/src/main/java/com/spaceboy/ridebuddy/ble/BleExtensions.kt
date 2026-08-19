package com.spaceboy.ridebuddy.ble

import java.util.Locale
import java.util.regex.Pattern

/**
 * Bike-name filter that mirrors the OEM Aprilia India app
 * (`com.piaggio.apriliaindia`, version 1.3). The OEM upper-cases the advertised name with
 * `Locale.ROOT` and checks for the `RS457_ID` or `SR_ID` substring in
 * `BleServerHelper.e(Context, BluetoothDevice)` (and again in `viewModel.U/p0`). We do
 * exactly that: no regex, no speculative `TUONO` / `TN` / `MIA` / `APRILIA_MIA` branches,
 * no `[ _-]?` separators between letters and digits. RS 457 and Tuono 457 share the same
 * `RS457_ID*` advertised name on the firmware observed so far.
 */
internal object BikeNames {
    const val RsFamilyPrefix = "RS457_ID"
    const val SrFamilyPrefix = "SR_ID"

    fun matchesRsFamily(name: String): Boolean =
        name.uppercase(Locale.ROOT).contains(RsFamilyPrefix)

    fun matchesSrFamily(name: String): Boolean =
        name.uppercase(Locale.ROOT).contains(SrFamilyPrefix)
}

/**
 * Show-all filter for the CompanionDeviceManager device picker. vivo X200 Ultra's CDM fork
 * applies the regex with full-string semantics; an overly-strict pattern was leaving the
 * picker empty. Authoritative post-pick validation runs in
 * [com.spaceboy.ridebuddy.core.companion.BikeCompanionManager.isAcceptableAssociationName].
 */
val CdmAcceptAllBikeNames: Pattern = Pattern.compile(".*", Pattern.CASE_INSENSITIVE)

/** True when the name carries the OEM `RS457_ID` substring (case-insensitive). */
fun String.isApriliaBikeName(): Boolean = BikeNames.matchesRsFamily(this)

/** True when the name carries the OEM `SR_ID` substring (case-insensitive). */
internal fun String.hasUnsupportedTelemetryLayout(): Boolean = BikeNames.matchesSrFamily(this)

internal fun ByteArray.toHex(separator: String = ""): String =
    joinToString(separator) { "%02X".format(it.toInt() and 0xFF) }
