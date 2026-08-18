# Stationary motorcycle validation checklist

Run this checklist only with the motorcycle parked, on a battery maintainer or with adequate battery charge, and with the OEM app disconnected. Keep an immediate Bluetooth disconnect available.

## Read-only connection

1. Pairing runs through Android's CompanionDeviceManager — the system device picker handles scan and Bluetooth bond atomically. A phone without `FEATURE_COMPANION_DEVICE_SETUP` cannot pair with RideBuddy on Android 16+. Confirm the picker surfaces and completes the system flow before diagnostics report a GATT connection. Do not pair a different vehicle family until its telemetry layout is implemented and validated.
2. Record the advertised name, RSSI, service UUIDs, characteristic properties, and descriptors.
3. Confirm diagnostics identify either the indicated-challenge or previously-accepted protection path. A fresh connection should subscribe to `8610` and write one known response; an accepted reconnect should bypass both operations. In both cases, confirm all six normal subscriptions complete before the companion link becomes ready, and live telemetry is plausible at standstill. Do not publish protection material in the validation record.
4. Disconnect/reconnect at least three times and confirm the previously verified path completes without a challenge wait, GATT timeout, or leaked callback.
5. If practical, reset the bike-side pairing while stationary and confirm Android bonding followed by the first-time `8610` challenge and known `8620` response path separately. Verify that RideBuddy sends no `8750` `LIVE` packet and performs no proactive characteristic reads on the `RS457_ID` path.

## TFT output

1. Leave **TFT navigation output**, **Caller display**, and **TFT call controls** off for the read-only phase.
2. At 0 km/h, run **Settings → Developer tools → Stationary TFT validation** and confirm that the cluster enters and then clears the navigation state.
3. Enable **TFT navigation output** and send one stationary navigation destination. Verify maneuver, trip, text, speed-limit, arrival, reroute, and clear states one at a time before using active guidance.
4. Disable the setting while a test route is open, end navigation, and confirm no stale navigation data appears after the clear packet.

## Calls and background navigation

1. Only after the TFT test, enable caller display and call controls separately. Test an incoming call from a second phone; verify answer, reject, and end actions against the chosen default dialer.
2. Start a route, turn the screen off or remove the task, and confirm the Navigation SDK service continues to deliver route updates. Explicitly select **End navigation** and confirm guidance and TFT output stop.

## Evidence to retain

Capture the Diagnostics export, cluster firmware version, Android version, device model, and a timestamped video or photo of each TFT state. Treat any unknown payload, warning lamp, implausible telemetry, or delayed callback as a stop condition and disable all vehicle-display outputs.
