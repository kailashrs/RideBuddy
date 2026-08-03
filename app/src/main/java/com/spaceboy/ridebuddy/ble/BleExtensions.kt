package com.spaceboy.ridebuddy.ble

import java.util.regex.Pattern

val ApriliaBikeNamePattern: Pattern = Pattern.compile(
    "^(RS457_ID|TUONO457_ID|TUONO_ID|TN457_ID).*",
    Pattern.CASE_INSENSITIVE,
)
private val UnsupportedTelemetryBikeNamePattern: Pattern = Pattern.compile(
    "^(SR_ID|APRILIA_MIA|MIA_457).*",
    Pattern.CASE_INSENSITIVE,
)

fun String.isApriliaBikeName(): Boolean = ApriliaBikeNamePattern.matcher(this).matches()
internal fun String.hasUnsupportedTelemetryLayout(): Boolean = UnsupportedTelemetryBikeNamePattern.matcher(this).matches()

internal fun ByteArray.toHex(separator: String = ""): String =
    joinToString(separator) { "%02X".format(it.toInt() and 0xFF) }
