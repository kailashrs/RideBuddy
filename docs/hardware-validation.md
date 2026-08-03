# Stationary bike validation checklist

Run this checklist only with the motorcycle parked, on a battery maintainer or with adequate battery charge, and with the OEM app disconnected. Keep an immediate Bluetooth disconnect available.

## Read-only connection

1. Associate the selected RS 457/Tuono 457 device and connect from the Diagnostics screen. Do not pair an SR/MIA-family device until its separate telemetry layout is implemented and validated.
2. Record the advertised name, RSSI, service UUIDs, characteristic properties, and descriptors.
3. Confirm the `8610` challenge and `8620` response sequence completes once and that `8410` telemetry is plausible at standstill.
4. Disconnect/reconnect at least three times and confirm no GATT operation timeouts or leaked connection callbacks appear in diagnostics.

## TFT output

1. Leave **Experimental TFT navigation**, **Caller display**, and **TFT call controls** off for the read-only phase.
2. At 0 km/h, run **More → Stationary TFT test** and confirm that the cluster enters and then clears the navigation state.
3. Enable **Experimental TFT navigation** and send one stationary navigation destination. Verify maneuver, trip, text, speed-limit, arrival, reroute, and clear states one at a time before using active guidance.
4. Disable the setting while a test route is open, end navigation, and confirm no stale navigation data appears after the clear packet.

## Calls and background navigation

1. Only after the TFT test, enable caller display and call controls separately. Test an incoming call from a second phone; verify answer, reject, and end actions against the chosen default dialer.
2. Start a route, turn the screen off or remove the task, and confirm the Navigation SDK service continues to deliver route updates. Explicitly select **End navigation** and confirm guidance and TFT output stop.

## Evidence to retain

Capture the Diagnostics export, cluster firmware version, Android version, device model, and a timestamped video or photo of each TFT state. Treat any unknown payload, warning lamp, implausible telemetry, or delayed callback as a stop condition and disable the experimental outputs.
