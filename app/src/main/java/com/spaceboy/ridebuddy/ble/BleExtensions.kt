package com.spaceboy.ridebuddy.ble

import java.util.UUID
import java.util.regex.Pattern

/**
 * Bike-name filter for the RS 457/Tuono 457 family. The OEM India app
 * (`com.piaggio.apriliaindia`, version 1.3) upper-cases the advertised name with
 * `Locale.ROOT` and checks for the `RS457_ID` substring in
 * `BleServerHelper.e(Context, BluetoothDevice)`. We do exactly that; SR-family devices
 * are intentionally not handled.
 */
private const val RsFamilyPrefix = "RS457_ID"

/**
 * CDM picker filter. Anchored to the `RS457_ID…` family so that unrelated peripherals
 * cannot show up in the picker. The picker is exposed for first-time pairing on a
 * fresh cache; once the bike is associated, `BikeCompanionManager.refresh()` resumes
 * from the cached MAC and skips the picker entirely.
 *
 * vivo X200 Ultra's CDM fork applies the regex with case-insensitive matching. The
 * trailing `[0-9A-F]{1,8}` absorbs the per-bike identifier suffix the firmware
 * appends (e.g. `RS457_IDE1B7`, `RS457_ID-AB`).
 */
val BikeNameFilter: Pattern = Pattern.compile(
    "$RsFamilyPrefix[-_]?[0-9A-F]{1,8}",
    Pattern.CASE_INSENSITIVE,
)

/**
 * Canonical string for the SIG-standard HID-over-GATT service UUID (`0x1812`),
 * stable since Bluetooth 4.0 (2010). Used **only** as a CDM picker scan filter.
 *
 * Confirmed against the bike, not assumed. The CDM association record for
 * `RS457_IDE1B7` holds the scan record the picker actually matched:
 *
 * ```
 * mAdvertiseFlags=6, mServiceUuids=[00001812-0000-1000-8000-00805f9b34fb],
 * mDeviceName=RS457_IDE1B7, rssi=-66, eventType=27
 * ```
 *
 * So the cluster advertises this UUID and no other, carries its name in the same
 * (merged, `eventType` bit 3) scan record, and sets flag bit 2 — BR/EDR Not
 * Supported. The bike is also registered in Android's HID host over
 * `BT_TRANSPORT_LE`, which is what makes it a HOGP peripheral in the first place.
 *
 * The filter earns its keep: the bike exposes a second, BR/EDR endpoint under the
 * *same* advertised name (`…C8:5C`, class-of-device 0x240418 — Audio/Video,
 * headphones) which Android classifies as DUAL. Name alone cannot separate them.
 *
 * Scan-filter use only. Do NOT reference this from anything GATT-side: Android
 * withholds the HID service from `BluetoothGatt.getServices()` for apps without
 * `BLUETOOTH_PRIVILEGED`, so adding `0x1812` to [BikeGattProfile]'s required
 * characteristics would make every connection fail as "profile is incomplete".
 * Scan filters read the advertisement, which is not subject to that restriction.
 *
 * Held as a String rather than a `ParcelUuid` constant to avoid loading
 * `android.os.ParcelUuid` from a Robolectric test classloader that has a partial
 * stub of the framework. `BikeCompanionManager.associate()` calls
 * `ParcelUuid.fromString(this)` at the call site, where the real Android runtime
 * is available.
 *
 * This is a SIG-standard UUID (assigned in Bluetooth 4.0) and not a vendor-defined
 * value. Hardcoding it cannot drift because SIG cannot reassign the 16-bit slot
 * without breaking conformance with every HID host on the planet.
 *
 * Note the OEM India app does none of this: it scans with an empty `ScanFilter`
 * list and matches `RS457_ID`/`SR_ID` as a substring of the name in its callback.
 * Our picker is deliberately stricter, and the record above is why that is safe.
 */
const val BikeHogpServiceUuidString: String =
    "00001812-0000-1000-8000-00805f9b34fb"

/** True when the name carries the OEM `RS457_ID` substring (case-insensitive). */
fun String.isApriliaBikeName(): Boolean = contains(RsFamilyPrefix, ignoreCase = true)

internal fun ByteArray.toHex(separator: String = ""): String =
    joinToString(separator) { "%02X".format(it.toInt() and 0xFF) }

internal fun UUID.shortName(): String = toString().takeLast(4)
