package com.spaceboy.ridebuddy.ble

import java.util.regex.Pattern

val ApriliaBikeNamePattern: Pattern = Pattern.compile(
    "(RS[_ -]?457|TUONO[_ -]?457|TN[_ -]?457)",
    Pattern.CASE_INSENSITIVE,
)
private val UnsupportedTelemetryBikeNamePattern: Pattern = Pattern.compile(
    "(SR[_ -]?ID|APRILIA[_ -]?MIA|MIA[_ -]?457)",
    Pattern.CASE_INSENSITIVE,
)

/**
 * Show-all filter for the CompanionDeviceManager device picker. vivo X200 Ultra's CDM fork
 * applies the regex with full-string semantics; an overly-strict pattern was leaving the
 * picker empty. Validation happens post-pick in [com.spaceboy.ridebuddy.core.companion.BikeCompanionManager.accept].
 */
val CdmAcceptAllBikeNames: Pattern = Pattern.compile(".*", Pattern.CASE_INSENSITIVE)

/** Uses [find] semantics so trailing suffixes and whitespace are tolerated. */
fun String.isApriliaBikeName(): Boolean = ApriliaBikeNamePattern.matcher(this).find()
internal fun String.hasUnsupportedTelemetryLayout(): Boolean = UnsupportedTelemetryBikeNamePattern.matcher(this).find()

internal fun ByteArray.toHex(separator: String = ""): String =
    joinToString(separator) { "%02X".format(it.toInt() and 0xFF) }
