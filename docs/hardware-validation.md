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
2. At 0 km/h, run **Settings → Developer tools → Stationary TFT validation**. It walks navigation first and asks what the cluster showed, then does the same for the caller display. Confirm the cluster enters and then clears the navigation state.
3. Enable **TFT navigation output** and send one stationary navigation destination. Verify maneuver, trip, text, arrival, reroute, and clear states one at a time before using active guidance. The speed-limit field is only ever zeroed, so the check is that it stays blank rather than that it shows a figure.
   Check both distance readings in the next-maneuver panel against the phone. They are different quantities — `8210` carries the gap to the maneuver *after* the current one, `8230` the distance to the one being approached — so if the panel shows the same number twice, one of them is being fed the wrong value.
4. Watch for any field that fails to appear or updates late. `8210`, `8220` and `8230` are unacknowledged writes, so a cluster that drops one gives no error — a missing or stale field is the only symptom. Record which field, at what point in the route, and whether it recovered on the next update.
5. With guidance running, press **EXIT** on the handlebar and confirm navigation stops. Do it twice: once with the navigation screen open, and once with the app backgrounded or the navigation screen closed. The second case is the one that used to fail — guidance keeps running in the background and the exit is handled at process scope now, not by the screen.
6. Share a destination to RideBuddy. With **Start shared destinations** off it stops on the route preview: confirm the whole route is framed on the phone, the cluster shows **GO** with the destination, distance and ETA, and that either **Go** on the phone or the handlebar starts the same route. Turn the setting on and confirm the preview is skipped entirely. Then, once guidance is running, confirm the same handlebar button skips a waypoint on a multi-stop route and exits on a single-destination one.
7. Disable the setting while a test route is open, end navigation, and confirm no stale navigation data appears after the clear packet.

## Calls and background navigation

1. The stationary validation above covers the caller display too, as its second phase: ringing, answered, cleared, outgoing, and cleared again, ending with nothing showing. Answer its second prompt before involving a second phone — a "no" there points at the caller display alone, since navigation was confirmed separately a moment earlier.
2. Check the number on that test specifically. It is sent as `+919876543210` and the cluster should show `9876543210` — the trailing ten characters. Anything longer means the truncation rule is not reaching the display, and anything shorter means the field is smaller than the OEM's ten.
3. Only after that, enable caller display and call controls and test a real incoming call from a second phone; verify answer, reject, and end actions. These act on the call through Telecom, so the result should not depend on which dialler is installed — test with a third-party dialler as well as the stock one.
4. Answer a call **on the phone** rather than the handlebar and confirm the cluster shows it as answered. This is the case that used to report "call ended" at the moment of answering, when call state was inferred from which notification a dialler happened to post.
5. Place an outgoing call and confirm the cluster distinguishes it from an answered incoming one. The direction now comes from `Call.Details.getCallDirection()`, so a call already in progress when the app starts should still be reported correctly.
6. Take a call where the dialler shows only a full-screen call UI and posts no usable notification. The cluster should still show it.
5. Watch for caller name or number that fails to appear, or appears late. The OEM writes each call packet two or three times with 200 ms gaps and RideBuddy writes it once. These are acknowledged writes, so the repetition should be unnecessary — but a missing or late field is what would say otherwise.
8. Start a route, turn the screen off or remove the task, and confirm the Navigation SDK service continues to deliver route updates. Explicitly select **End navigation** and confirm guidance and TFT output stop.

## Known gaps

The cluster software version usually reads "Not reported". It is not a defect in the app: the
cluster answers a read of `8810` with a zero-filled buffer and volunteers the value only as an
indication, on no schedule anyone has identified. It was seen once, `1.3.6`, about ten minutes into
a capture; 118 minutes of connected time across eleven later sessions — the longest 31 minutes —
produced it zero times, while the VIN on the adjacent characteristic arrived twenty times. If it
appears during a validation run, record when, and what had happened just before.

## Evidence to retain

Capture the Diagnostics export, Android version, device model, and a timestamped video or photo of each TFT state. Treat any unknown payload, warning lamp, implausible telemetry, or delayed callback as a stop condition and disable all vehicle-display outputs.
