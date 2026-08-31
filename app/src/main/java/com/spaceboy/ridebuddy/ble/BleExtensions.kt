package com.spaceboy.ridebuddy.ble

import java.util.UUID
import java.util.regex.Pattern

/**
 * Advertised-name prefix shared by the RS 457/Tuono 457 family.
 *
 * The cluster advertises a name of the form `RS457_ID<suffix>`, where the suffix is a
 * short per-bike identifier. Matching is done case-insensitively because the exact case
 * of the advertised name is not guaranteed across firmware revisions.
 *
 * Other model families (notably the `SR_ID` scooters) advertise under their own prefix
 * and are deliberately not accepted: they use a different telemetry frame layout, so
 * connecting to one would produce plausible-looking but wrong ride data.
 */
private const val RsFamilyPrefix = "RS457_ID"

/**
 * Name pattern handed to the system companion-device picker.
 *
 * Anchoring to the `RS457_ID…` family keeps unrelated peripherals out of the picker.
 * The picker is only used for first-time pairing; once the motorcycle is associated,
 * [com.spaceboy.ridebuddy.core.companion.BikeCompanionManager.refresh] resumes from the
 * cached MAC address and never shows it again.
 *
 * The trailing `[0-9A-F]{1,8}` absorbs the per-bike identifier the firmware appends —
 * both the bare form (`RS457_IDE1B7`) and the separated form (`RS457_ID-AB`). The
 * pattern is compiled case-insensitively because some vendor forks of the companion
 * device manager match the regex against the name verbatim rather than upper-casing it
 * first.
 */
val BikeNameFilter: Pattern = Pattern.compile(
    "$RsFamilyPrefix[-_]?[0-9A-F]{1,8}",
    Pattern.CASE_INSENSITIVE,
)

/**
 * Canonical string form of the SIG-standard HID-over-GATT service UUID (`0x1812`),
 * stable since Bluetooth 4.0 (2010). Used **only** as a companion-picker scan filter.
 *
 * This is confirmed against hardware rather than assumed. The association record the
 * picker creates preserves the scan record it matched:
 *
 * ```
 * mAdvertiseFlags=6, mServiceUuids=[00001812-0000-1000-8000-00805f9b34fb],
 * mDeviceName=RS457_IDE1B7, rssi=-66, eventType=27
 * ```
 *
 * Three things follow from that record. The cluster advertises this UUID and no other,
 * so it is the only UUID a scan filter may key on. Its name travels in the same merged
 * scan record (`eventType` bit 3), so a name filter and a UUID filter can be ANDed into
 * one filter. And flag bit 2 — BR/EDR Not Supported — marks the advertising interface as
 * LE-only. The motorcycle is separately registered in Android's HID host over
 * `BT_TRANSPORT_LE`, which is what makes it a real HOGP peripheral rather than something
 * that merely advertises the UUID.
 *
 * The filter earns its keep because the motorcycle exposes a *second*, BR/EDR endpoint
 * under the same advertised name (class-of-device `0x240418` — Audio/Video, headphones)
 * which Android classifies as DUAL. That one is the audio endpoint and speaks no GATT.
 * Name alone cannot separate the two; the service UUID can.
 *
 * Scan-filter use only. Do NOT reference this from anything GATT-side: Android withholds
 * the HID service from `BluetoothGatt.getServices()` for apps without
 * `BLUETOOTH_PRIVILEGED`, so adding `0x1812` to [BikeGattProfile]'s required
 * characteristics would make every connection fail as "profile is incomplete". Scan
 * filters read the advertisement, which is not subject to that restriction.
 *
 * Held as a `String` rather than a `ParcelUuid` constant so that unit tests running on a
 * partial framework stub never have to load `android.os.ParcelUuid`. The conversion via
 * `ParcelUuid.fromString` happens at the call site, where the real runtime is available.
 *
 * Hardcoding the value cannot drift: SIG cannot reassign a 16-bit slot without breaking
 * conformance with every HID host in existence.
 */
const val BikeHogpServiceUuidString: String =
    "00001812-0000-1000-8000-00805f9b34fb"

/** True when an advertised name belongs to the RS 457 family (case-insensitive). */
fun String.isApriliaBikeName(): Boolean = contains(RsFamilyPrefix, ignoreCase = true)

/** Renders bytes as upper-case hex, for log lines and protocol lookup keys. */
internal fun ByteArray.toHex(separator: String = ""): String =
    joinToString(separator) { "%02X".format(it.toInt() and 0xFF) }

/**
 * The last four hex digits of a UUID — the characteristic suffix (`8410`, `8730`, …)
 * that the protocol map is written in terms of. Used to keep log lines readable.
 */
internal fun UUID.shortName(): String = toString().takeLast(4)
