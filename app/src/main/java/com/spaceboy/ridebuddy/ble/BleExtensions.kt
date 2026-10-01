package com.spaceboy.ridebuddy.ble

import java.util.HexFormat
import java.util.UUID
import java.util.regex.Pattern

/**
 * The RS 457 / Tuono 457 advertised-name prefix, `RS457_ID<per-bike suffix>`. Other families,
 * such as the `SR_ID` scooters, use a different telemetry layout and are deliberately excluded.
 */
private const val RsFamilyPrefix = "RS457_ID"

/**
 * The companion picker's name filter. Case-insensitive, because some vendor builds of the
 * companion device manager match the name verbatim rather than upper-casing it first.
 */
val BikeNameFilter: Pattern = Pattern.compile("$RsFamilyPrefix[-_]?[0-9A-F]{1,8}", Pattern.CASE_INSENSITIVE)

/**
 * HID over GATT (`0x1812`), the only service UUID the cluster advertises. It is what separates
 * the bike's LE interface from its BR/EDR audio endpoint, which advertises under the same name,
 * so the picker filters on it. Picker only: Android hides this service from `getServices()` for
 * unprivileged apps, so requiring it GATT-side would fail every connection.
 */
const val BikeHogpServiceUuidString: String = "00001812-0000-1000-8000-00805f9b34fb"

private val Hex = HexFormat.of().withUpperCase()
private val SpacedHex = HexFormat.ofDelimiter(" ").withUpperCase()

internal fun ByteArray.toHex(): String = Hex.formatHex(this)

internal fun ByteArray.toSpacedHex(): String = SpacedHex.formatHex(this)

internal fun String.hexToBytes(): ByteArray = Hex.parseHex(this)

/** The characteristic suffix (`8410`, `8730`, …) the protocol is written in terms of. */
internal fun UUID.shortName(): String = toString().takeLast(4)
