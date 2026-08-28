# Stationary motorcycle validation checklist

Run this checklist only with the motorcycle parked, on a battery maintainer or with adequate battery charge, and with the OEM app disconnected. Keep an immediate Bluetooth disconnect available.

## Read-only connection

1. Pairing runs through Android's CompanionDeviceManager — the system device picker handles scan and Bluetooth bond atomically. A phone without `FEATURE_COMPANION_DEVICE_SETUP` cannot pair with RideBuddy on Android 16+. Confirm the picker surfaces and completes the system flow before diagnostics report a GATT connection. Do not pair a different vehicle family until its telemetry layout is implemented and validated.
2. Record the advertised name, RSSI, service UUIDs, characteristic properties, and descriptors. The advertisement itself is already captured — see "Advertisement (captured)" in `aprilia-rs457-ble-protocol.md`; it can be re-read at any time without the bike present via `adb shell dumpsys companiondevice`, which stores the scan record the picker matched. Characteristic properties and descriptors still need the live connection.
3. Confirm diagnostics identify either the indicated-challenge or previously-accepted protection path. A fresh connection should subscribe to `8610` and write one known response; an accepted reconnect should bypass both operations. In both cases, confirm all six normal subscriptions complete before the companion link becomes ready, and live telemetry is plausible at standstill. Do not publish protection material in the validation record.
4. Disconnect/reconnect at least three times and confirm the previously verified path completes without a challenge wait, GATT timeout, or leaked callback.
5. If practical, reset the bike-side pairing while stationary and confirm Android bonding followed by the first-time `8610` challenge and known `8620` response path separately. Verify that RideBuddy sends no `8750` `LIVE` packet and performs no proactive characteristic reads on the `RS457_ID` path.

## TFT output

1. Leave **TFT navigation output**, **Caller display**, and **TFT call controls** off for the read-only phase.
2. At 0 km/h, run **Settings → Developer tools → Stationary TFT validation** and confirm that the cluster enters and then clears the navigation state.
3. Enable **TFT navigation output** and send one stationary navigation destination. Verify maneuver, trip, text, speed-limit, arrival, reroute, and clear states one at a time before using active guidance.
4. Watch for any field that fails to appear or updates late. RideBuddy writes each navigation field once where the OEM writes it twice, and `8210`, `8220` and `8230` are unacknowledged writes, so a cluster that drops one gives no error — a missing or stale field is the only symptom. If that happens, revert the commit that set `ClusterReplayCount` to 1 and retest.
5. Disable the setting while a test route is open, end navigation, and confirm no stale navigation data appears after the clear packet.

## Calls and background navigation

1. Run **Settings → Developer tools → Stationary call validation** before involving a second phone. It walks the caller display through ringing, answered, cleared, outgoing, and cleared again, and ends with nothing showing. Confirm each state appears.
2. Check the number on that test specifically. It is sent as `+919876543210` and the cluster should show `9876543210` — the trailing ten characters. Anything longer means the truncation rule is not reaching the display, and anything shorter means the field is smaller than the OEM's ten.
3. Only after that, enable caller display and call controls and test a real incoming call from a second phone; verify answer, reject, and end actions against the chosen default dialer.
4. Place an outgoing call and confirm the cluster distinguishes it from an answered incoming one. RideBuddy infers the direction from whether the notification was ever seen ringing, so a call already in progress when the app starts is reported as outgoing.
5. Watch for caller name or number that fails to appear, or appears late. The OEM writes each call packet two or three times with 200 ms gaps and RideBuddy writes it once. These are acknowledged writes, so the repetition should be unnecessary — but a missing or late field is what would say otherwise.
6. Start a route, turn the screen off or remove the task, and confirm the Navigation SDK service continues to deliver route updates. Explicitly select **End navigation** and confirm guidance and TFT output stop.

## Evidence to retain

Capture the Diagnostics export, cluster firmware version, Android version, device model, and a timestamped video or photo of each TFT state. Treat any unknown payload, warning lamp, implausible telemetry, or delayed callback as a stop condition and disable all vehicle-display outputs.
