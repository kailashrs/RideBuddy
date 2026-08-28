# Aprilia RS 457 BLE protocol map

Status: static analysis of `apriliaindia.apk` (package `com.piaggio.apriliaindia`, version 1.3), plus the captured advertisement below. The motorcycle-side behavior, GATT service UUID, characteristic properties, firmware-version differences, and safe write rates still require a stationary live-bike capture — the advertisement is the only part confirmed against hardware so far.

## Executive summary

The OEM app is a conventional Android BLE central. It scans for devices whose names contain the substring `RS457_ID` or `SR_ID` (case-insensitive via `Locale.ROOT` upper-casing, then `String.contains`), connects with LE transport, discovers all services, and locates characteristics by UUID suffix rather than by a hardcoded service UUID.

RideBuddy intentionally accepts only the RS 457/Tuono 457 name family for now. The OEM app uses a separate SR telemetry parser, so accepting SR family advertisements without a model-specific decoder could record plausible-looking but incorrect ride data.

The application contains a protection handshake, but the inspected build does not contain a general-purpose key derivation algorithm. It stores ten fixed six-byte challenge/response pairs. The dashboard first requires an Android Bluetooth bond. For a first-time protected connection it enables indications on `8610`, handles the challenge in its characteristic-change callback, writes the corresponding six-byte response to `8620`, and persists a protection-accepted flag. Later connections with that flag bypass `8610` and enable the normal subscription set directly. Generic read callback code exists elsewhere in the APK, but the India dashboard connection path does not proactively read `8610`, the VIN, or the software version.

The protocol exposes useful read-only telemetry and a fixed-function TFT interface. It does not expose a general framebuffer or any confirmed ECU control surface.

## Advertisement (captured)

Recorded from the dev bike on the vivo X200 Ultra (Android 16), read back out of the
CompanionDeviceManager association record that the picker created:

```text
mAdvertiseFlags=6, mServiceUuids=[00001812-0000-1000-8000-00805f9b34fb],
mServiceSolicitationUuids=[], mManufacturerSpecificData={}, mServiceData={},
mTxPowerLevel=<none>, mDeviceName=RS457_IDE1B7, rssi=-66, eventType=27
```

What this pins down:

| Field | Value | Consequence |
| --- | --- | --- |
| Service UUIDs | `0x1812` (HOGP) only | The one UUID a scan filter may key on. No vendor UUID is advertised — `d6328aea…` is discoverable only after connecting. |
| Flags | `6` = LE General Discoverable + **BR/EDR Not Supported** | The advertising interface is LE-only. |
| `eventType` | `27` — legacy, connectable, scannable, **scan response** | Android merged the advertisement and scan response, so name and UUID arrive in one `ScanRecord` and may be ANDed in a single filter. |
| Name | `RS457_IDE1B7` | Present in the merged record, so `setNamePattern` sees it. |

The bike also appears in Android's HID host as
`addr:…e1:b7[public][BT_TRANSPORT_LE]` with `HOGP connection state`, confirming it is a
real HID-over-GATT peripheral rather than merely advertising the UUID.

Two bonded records share the name `RS457_IDE1B7`:

| Address | Transport | Class of device | Role |
| --- | --- | --- | --- |
| `…E1:B7` | LE | `0x001F00` uncategorized | The GATT/HOGP interface this app talks to |
| `…C8:5C` | BR/EDR (typed DUAL) | `0x240418` Audio/Video, headphones | The bike's audio endpoint |

Because the two share an advertised name, the picker cannot be scoped by name alone —
this is what the `0x1812` scan filter in `BikeCompanionManager.associate()` is for. Note
the OEM app does no UUID filtering at all: it calls `startScan` with an **empty**
`ScanFilter` list and `SCAN_MODE_LOW_LATENCY`, then substring-matches the name inside its
own callback.

## UUIDs

All application characteristics use this UUID family:

```text
d6328aea-d630-4a83-b51b-1da8e8daXXXX
```

The OEM code searches every discovered service for a characteristic whose UUID contains the expected full UUID. It does not embed the service UUID. A test app should record the service UUID and characteristic properties from `BluetoothGatt.getServices()` rather than guessing them.

The CCCD used for notification/indication subscription is the standard descriptor:

```text
00002902-0000-1000-8000-00805f9b34fb
```

## Characteristic map

| Suffix | Direction in OEM flow | Static-analysis purpose | Notes |
|---|---|---|---|
| `8110` | phone → bike | notification/app-event state | Payload is `[0x0B, event, phoneBatteryPercent, 0x00]`. |
| `8210` | phone → bike | current/next navigation pictogram | Payload builder is `[0x01, currentIcon, roundaboutExit, 0xFF, nextIcon, distanceLE[3], 0x2E]` (9 bytes total). The `0xFF` is a fixed wire delimiter separating `roundaboutExit` from `nextIcon`, not a "no next icon" placeholder. The distance is **little-endian** — see "24-bit distance fields" below. |
| `8220` | phone → bike | navigation speed limit | Payload builder is `[0x02, speedLimit, 0x2E]`. |
| `8230` | phone → bike | navigation time/distance | Payload builder is `[0x03, minute, hour, destinationDistanceLE[3], maneuverDistanceLE[3], 0x2E]`. Both distances are **little-endian**. `minute`/`hour` come from a `Calendar` over an arrival timestamp in the OEM code, but whether the cluster renders them as an arrival clock or a remaining duration still needs TFT validation. |
| `8240` | phone → bike | navigation text rows | Payload builder is `[0x04, rowId, totalPacketLength, ASCII bytes..., 0x2E]`. `totalPacketLength` is the ASCII byte count plus 4 (i.e. the total packet size including the four framing bytes), capped at 20 for the 16-character row limit. |
| `8250` | phone → bike | navigation clear/reset | OEM clear packet is `[0xFF, 0x2E]`. |
| `8260` | phone → bike | navigation/session state | Common builder is `[0x05, 0xFF, state, 0x2E]`; observed state values include 80, 82, 83, and 87. |
| `8270` | phone → bike | navigation status/command | Payload builder is `[0x06, value, 0x2E]`; `132` is emitted by the OEM flow for a status-style update. |
| `8280` | bike → phone | TFT navigation control | OEM handles a three-byte event; value 2 skips a waypoint and value 3 exits navigation. |
| `8310` | unknown | declared UUID only | No meaningful use found in the inspected app code. |
| `8410` | bike → phone | live vehicle telemetry | The OEM parser consumes bytes 0–8; details are below. |
| `8420` | unknown | declared UUID only | No meaningful use found in the inspected app code. |
| `8510` | unknown | declared UUID only | No meaningful use found in the inspected app code. |
| `8610` | bike → phone | protection challenge/request | Subscribed first when the stored protection flag is not satisfied. |
| `8620` | phone → bike | protection response | Six-byte response selected from the hardcoded lookup table. |
| `8710` | phone → bike | caller name | OEM sends a 20-byte, zero-padded, sanitized ASCII-style buffer beginning with `0x0A`. |
| `8720` | bike → phone | unknown/call-flow related | Declared and subscribed by the generic notification set, but no clear consumer was found. |
| `8730` | phone → bike | call state | Used by the OEM call-management path; exact state payload needs live confirmation. |
| `8740` | bike → phone | call/TFT control event | OEM handles values 0–3 in connection/call flow; 1 is answer and 0 is reject/end in the observed path. |
| `8750` | phone → bike | SR-family mobile status | The inspected India app schedules its `LIVE` packet only for the `SR_ID` model family. The `RS457_ID` dashboard path does not write it. |
| `8760` | phone → bike | caller number | OEM sends up to 20 byte values derived from the phone number. |
| `8810` | bike → phone | cluster software version | OEM treats the value as a byte string. |
| `8910` | bike → phone | VIN | OEM expects a framed value and strips the first and last byte before decoding text. |

The OEM's generic post-auth subscription set contains `8280`, `8720`, `8740`, `8410`, `8810`, and `8910`. Its helper scans the GATT services and enables characteristics that belong to that set, so the decompiled implementation does not establish a stable order across devices. RideBuddy uses the same membership in a deterministic queue order. The protected handshake separately subscribes to `8610`. The app's notification callback additionally recognizes telemetry, VIN, software version, and the navigation-control event.

## Authentication

### Sequence

1. Resolve the selected `BluetoothDevice`. If it is not bonded, call Android's `createBond()` and wait for `ACTION_BOND_STATE_CHANGED` to report `BOND_BONDED` before opening GATT.
2. Connect with LE transport, discover services, and verify that the protection endpoints and the six normal notification endpoints are present. The inspected OEM path does not request a larger MTU first.
3. If RideBuddy previously verified protection for this still-bonded address, skip `8610` and enable the normal post-auth subscriptions.
4. Otherwise, enable indications on `8610` by calling `setCharacteristicNotification()` and writing `ENABLE_INDICATION_VALUE` to its CCCD.
5. Receive the challenge through `onCharacteristicChanged`. RideBuddy deliberately does not substitute a characteristic read or infer that an Android bond means application protection succeeded.
6. Canonicalize the challenge as unsigned decimal bytes joined with commas, including a trailing comma. For example, bytes `0x63 0x75 0xA3` become `99,117,163,`.
7. Look up the full six-byte challenge in the table below and write the mapped response to `8620` with an acknowledged write.
8. After a successful `8620` write callback, enable the six normal subscription endpoints. RideBuddy deterministically queues `8280`, `8720`, `8740`, `8410`, `8810`, and `8910`, requires every CCCD write to succeed, and waits for valid post-auth profile evidence before presenting the session as connected. This queue order is a RideBuddy implementation choice; only the set membership is confirmed by the OEM code.

### Hardcoded pairs in APK

The values below are shown in hexadecimal for readability. They are the unsigned decimal arrays used by the OEM code after conversion to bytes.

| Challenge | Response |
|---|---|
| `63 75 A3 A4 63 3B` | `E9 77 97 5C C3 45` |
| `D9 EA DE F2 F9 A1` | `95 C0 F8 B8 D7 AE` |
| `D6 CC AA BA 9D 55` | `A5 B8 5F 19 73 36` |
| `95 6D 6E 55 13 7C` | `EB 1D DA ED 59 A8` |
| `0A 74 F6 52 B0 90` | `FF E5 50 3D EB 79` |
| `96 CE C9 8C E4 19` | `5D C0 23 3B A6 A1` |
| `BD 7D C2 27 82 05` | `97 A5 E5 1A 9D 95` |
| `FB 01 0C D2 D1 B6` | `31 1B EB 84 2A 20` |
| `06 71 41 BB 65 06` | `1B 55 DB 85 7E 10` |
| `32 B2 08 EE 86 03` | `CC 6E C3 09 28 88` |

This table is a compatibility observation, not proof that every cluster firmware uses the same table. An unknown challenge is not handled usefully by the OEM app; it does not derive a fallback response.

## Live telemetry (`8410`)

The RS 457/Tuono parser checks the `0x10` header and then consumes at least nine bytes:

```text
byte 0       0x10 header
bytes 1..2   front-wheel speed, little-endian, raw * 0.01 km/h
byte 3       throttle/gas opening, percentage-like value
byte 4       instantaneous mileage, raw * 0.2 km/L
bytes 5..8   engine RPM, little-endian unsigned integer
bytes 9..n   ignored by the inspected parser
```

Observed samples may contain `0x23` at byte 9, but the India OEM parser does not validate that byte or require an exact ten-byte length. RideBuddy therefore rejects frames shorter than nine bytes or with the wrong header, while tolerating trailing firmware-specific bytes.

The OEM applies an exponential moving average with an alpha of `0.2` to this km/L value for its live presentation, then takes the reciprocal when it needs L/100 km. RideBuddy treats an encoded zero as unavailable because it has no valid reciprocal, preserves positive km/L values in raw telemetry and stored samples, applies that filter only to the sampled live frame, and accumulates estimated litres from distance divided by mileage. RPM is parsed and logged but is not prominently displayed by the OEM app. The parser has a separate variant for the SR Motard family; the custom app should key decoding by detected model/firmware rather than assuming all Piaggio clusters share one frame.

## Navigation packet builders

The OEM's navigation state is a set of fixed TFT fields rather than a drawing API:

- `8210`: current and next maneuver icon, roundabout exit, and distance-to-current maneuver.
- `8220`: speed-limit value.
- `8230`: arrival/time and remaining-distance fields.
- `8240`: three short text rows. The first two are populated by splitting one string at 16 characters; the third is independently truncated to 16 characters. UTF-8 is used by the app, but ASCII/Latin text is the safest first live-test assumption.
- `8250`: clear/reset.
- `8260` and `8270`: session/status transitions, including recalculation, signal loss, and arrival-related states.

### 24-bit distance fields

`8210` and `8230` carry their distances as three bytes, **least-significant byte first**. This is
easy to misread from the decompiled builder, so the derivation is recorded here rather than
re-established each time.

`q(int)` in `ui/btconnect/support/a.java` renders the value as a hex string, chunks it into bytes,
reverses, and fills a three-slot array from index 2 downward — producing a right-aligned
*big-endian array*:

```text
q(500) -> q(0x0001F4) -> ["01","f4"] -> reversed -> arr[2]=0xF4, arr[1]=0x01, arr[0]=0x00
```

Every packet builder then emits that array **in reverse**, which is what puts the low byte on the
wire first:

```java
// maneuver — p()                      // trip — f()
int[] iArrQ = q((int) this.g);         {this.s, l(), k(),
iArr[5] = iArrQ[2];  // LSB             iArrQ2[2], iArrQ2[1], iArrQ2[0],  // destination distance
iArr[6] = iArrQ[1];                     iArrQ[2],  iArrQ[1],  iArrQ[0],   // maneuver distance
iArr[7] = iArrQ[0];  // MSB             this.N}
```

The two obfuscated stdlib calls inside `q()` resolve as `kotlin.collections.x.L` = `reversed()`
(it delegates to `Collections.reverse`) and `x.W` = `withIndex()`.

So 500 m goes out as `F4 01 00`. Sending it big-endian (`00 01 F4`) is read by the cluster as
roughly 16 million metres, and any distance under 256 m collapses to zero — RideBuddy shipped that
inversion until it was corrected against this derivation.

The bundled `assets/ble_characteristic.json` lists 42 fixed maneuver labels with IDs 0–41. Production code maps the Mappls maneuver identifiers to TFT values including 1–16, 101–107, 151–158, and 200–205. The label `YEZDI Logo` in the asset is likely copied or stale and should not be treated as an RS 457-specific behavior.

## Phone notifications and calls

`8110` only communicates fixed application-event icons. The OEM mapping is:

| Application | hidden/off | shown/on |
|---|---:|---:|
| Facebook/Lite | 10 | 11 |
| Instagram/Lite | 12 | 13 |
| Gmail | 14 | 15 |
| Twitter/Lite | 32 | 33 |

It does not carry arbitrary message text. A custom app wanting previews would need to reuse the navigation text rows and must arbitrate them against imminent turns.

The OEM uses a notification listener for notification events and a legacy phone-state receiver for calls. Its call path writes caller name/number and state packets, listens for TFT control events, and invokes deprecated `TelecomManager` answer/end methods. The custom app instead treats the default phone app's `Notification.CallStyle` actions as the primary control contract and offers the OEM-style Telecom path only as an explicit compatibility fallback. It does not declare an `InCallService`, request the default-dialer role, or misclassify the motorcycle as a wearable companion.

## What is not confirmed exposed

Static analysis found no BLE access for actual gear, tank level, coolant temperature, odometer, tire pressure, lean angle, ABS/traction-control intervention, ride mode, ECU DTCs, or ECU/ABS/traction-control/ride-mode commands. Database column names alone are not evidence of a transmitted feature.

## Google Navigation SDK bridge

The clean integration boundary is:

```text
Google Maps share intent
  -> extract place/coordinates from the shared URL
  -> set a destination in the Google Navigation SDK
  -> consume NavInfo/StepInfo turn-by-turn feed
  -> map Google maneuvers to the 42-entry TFT pictogram vocabulary
  -> serialize TFT packets and enqueue rate-limited BLE writes
```

Important product distinction: the consumer Google Maps app and the Google Navigation SDK are not the same navigation session. If Google Maps must remain the live navigation UI, the custom app cannot assume it can read its private internal turn stream. The robust option is to keep Google Maps as the destination-selection surface, then run the route/guidance session in the companion app. The official Navigation SDK also exposes a turn-by-turn feed with current/remaining steps, maneuver data, distance, time, and navigation state; its current TBT classes are documented as preview APIs and may change.

The SDK replaces the Maps SDK in an app that uses it, so the new companion app should choose one Google map stack rather than combining both. The current official setup requirements, API-key configuration, billing/terms, attribution, and SDK version must be checked at implementation time.

## Safe next test

Build a small Android BLE test app that:

1. Scans and logs device name, address, RSSI, advertisement data, service UUIDs, characteristic UUIDs, properties, and descriptors.
2. Connects only to the user-selected bike while stationary.
3. Reproduces both the first-time challenge/response path and the already-protected reconnect path, recording every descriptor, read, write, and notification callback with timestamps.
4. Enables `8410` notifications and validates frame rate, framing, and parser values without writing navigation data.
5. Sends only one known navigation field at a time, with conservative delays and a visible emergency disconnect control.

Do not fuzz unknown writable characteristics or test navigation updates while moving. The OEM app should not be connected at the same time during protocol experiments because competing GATT centrals may change connection behavior.
