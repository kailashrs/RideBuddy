# RideBuddy

RideBuddy is a native Android companion app for supported motorcycles. The
project is built with Kotlin and Jetpack Compose Material 3, with a
vehicle-aware Bluetooth transport layer and safety-gated vehicle integration.

## Features

- **Material You UI**: Adaptive layouts, light/dark modes, and dynamic color. Features Live, History, Insights, Info, and Settings destinations.
- **BLE Telemetry**: Automatic background reconnection. Live speed, RPM, throttle, and mileage metrics with automatic ride recording.
- **Google Navigation**: Share destinations directly from Google Maps. Full turn-by-turn routing via the Google Navigation SDK.
- **Ride History**: Local SQLite history with weekly summaries, performance records, and long-term insights. Includes GPX/CSV export capabilities. Rides, records and insights are kept for good; each ride's second-by-second telemetry is kept for a rider-chosen window, one year by default.
- **Backup**: Ride summaries and preferences ride along with Android's own backup, encrypted with the device lock screen and free of the rider's Drive quota. Telemetry detail stays on the device.
- **TFT Integration**: (Opt-in) Bridges turn-by-turn maneuvers, caller presentation, and handlebar call controls directly to the motorcycle's display. Calls come from Telecom, so they work with any dialler.
- **Alerts & Priorities**: Handles competing phone notifications, imminent turns, and weather warnings without obscuring critical driving information.

Vehicle writes are implemented behind opt-in controls and are disabled by
default until parked validation confirms compatibility. Navigation and
call-state display output are also off by default. The stationary display test
is an optional parked diagnostic that waits for expected GATT completions and
asks the rider to visually confirm the response; it is not a substitute for
broader live-vehicle validation.

The current vehicle-display integration exposes fixed notification icons, not
arbitrary notification or media text. Weather alerts use the Open-Meteo
forecast endpoint when a current riding location is available.
Road-hazard alerts remain available for navigation providers that supply hazard
events because turn-by-turn data alone does not expose a general hazard feed.

Weather data is provided by [Open-Meteo.com](https://open-meteo.com/) under CC BY 4.0. The public endpoint's applicable usage tier must be reviewed before commercial distribution.

## Toolchain

- Android Gradle Plugin 9.4.0
- Gradle 9.7.1
- Kotlin / Compose compiler 2.4.20
- Jetpack Compose BOM 2026.09.00
- compile and target SDK 37, minimum SDK 36 (Android 16); pairing requires `FEATURE_COMPANION_DEVICE_SETUP`
- Google Navigation SDK 7.9.0

The checked-in Gradle wrapper remains the authoritative build entry point.

## Build

Open the project in Android Studio or run:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
./gradlew :app:compileDebugAndroidTestKotlin
```

CI compiles the instrumentation suite. Run `./gradlew pixel2api36DebugAndroidTest`
locally to execute it on the checked-in Gradle managed device when the Android
emulator and hardware acceleration are available.

### Release builds

Local release packaging uses signing values from user-level Gradle properties
or the environment (never commit them): `RELEASE_STORE_FILE`,
`RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD`.
Without all four values, any produced release APK is not a distributable signed
release.

Run `./gradlew assembleRelease` to build the release variant.

GitHub release jobs require `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`,
`RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`, and `RELEASE_CERT_SHA256` (the
expected release-certificate SHA-256 digest).
They reject a mismatched signer, sanitize ref names used in artifact filenames,
and require a `v…` tag to match the app's `versionName` exactly. Ordinary CI
verification never receives release secrets or builds a signed release.

## Navigation setup

1. Create a Google Cloud project and enable Navigation SDK for Android.
2. Create an Android-restricted API key for package `com.spaceboy.ridebuddy` and the signing certificate used for the build.
3. In the app, open **Settings → Navigation** and paste the key.
4. Share a Google Maps destination to RideBuddy, or paste a **Google Maps link** into **Live → Navigate**. Review the calculated route, then tap **Go** on the phone or use the bike's **GO** action. **Start shared destinations** can start guidance immediately after routing succeeds.
5. Optionally set Location to **Allow all the time** under **Settings → Navigation with screen off** for the most accurate guidance when the app is backgrounded. Foreground navigation remains available without this optional grant.

## Bike and call setup

1. Open **Settings → Motorcycle Connection** and associate the motorcycle in Android's system companion-device picker.
2. Enable notification access under **Settings → Alerts & notifications** for the per-app icons on the display. Calls do not need it: they arrive through Telecom.
3. Before relying on **TFT navigation output**, **Caller display**, or **TFT call controls**, run **Settings → Developer tools → Stationary TFT validation** and confirm the visible display states. Keep the motorcycle parked and never perform first protocol validation while riding.

The key is supplied programmatically and is intentionally absent from source files and `AndroidManifest.xml`. Replacing an active key requires restarting the app.

Calls are read from Telecom through an `InCallService`, bound because the app holds the normal
`CALL_COMPANION_APP` permission. That works with whatever dialler is installed, needs no runtime
prompt, does not make RideBuddy the default dialler, and does not take calls away from one.
Caller identity, call state and the handlebar answer/end controls all come from the call itself
rather than from a notification, so a dialler that posts unusual notifications — or none — makes
no difference.

Text-message icons follow Android's default-SMS role rather than a list of app names, so whichever
app the rider uses for texts is covered. Because an app holding that role is routinely a dialler
too, only message-shaped notifications from it light the icon; its caller-ID, missed-call and
promotional cards do not.

**End ride** finishes and saves the ride in progress without ending the session: the bike stays
connected, and recording resumes once the bike has come to a stop and sets off again. Automatic
connection attempts are limited to three per cycle; failure saves the ongoing ride and clears
pending display output. Tap **Connect** to deliberately start another session.

## Ride data, storage and backup

A ride is stored as a summary plus a telemetry series. The summary is under a kilobyte and is
what History, Insights, weekly totals, records and the route thumbnail are built from. The series
is what makes history grow, at roughly 0.14 MB for every hour ridden, and it backs the per-ride
speed, engine and throttle charts, the ride-events list, and the detailed CSV and GPX exports.

**Settings → Ride Data & Export → Keep detailed telemetry** chooses how long each ride keeps its
series — 30 days to Keep everything, one year by default. Only the series expires: rides, records
and insights are kept indefinitely whatever the window, and a ride past it shows its summary and
route with a note in place of the charts.

To remove a ride outright rather than just its detail, open it from History and use **Delete** in
the top bar. That takes its distance and fuel out of your totals and records as well, so it is
confirmed first.

Exports carry the raw values, not your display units, so a file means the same thing whatever the
app was set to when you took it. A GPX track is named for where the ride went — "Koramangala to
Electronic City" — falling back to the ride's number when the geocoder resolved nothing.

**Backup** is Android's own. The app keeps a summary snapshot current and the backup rules name
that file and the rider's preferences, nothing else — the Navigation API key and everything
describing the pairing with a specific motorcycle are outside the backup set by construction.
Backups are free, do not count against Drive quota, and are encrypted with the device lock screen
on Android 9 and above; a device with no lock screen is not backed up at all. Restoring onto a new
phone brings back every ride summary and the rider's settings. It does not bring back telemetry
detail, which is far past the 25 MB the platform allows an app, so restored rides have no charts.
The app never uploads anything itself.

Decisions and measurements behind all of this are in
[docs/ride-storage-decisions.md](docs/ride-storage-decisions.md).

## Project references

- [Stationary vehicle validation checklist](docs/hardware-validation.md)
- [Ride storage and backup decisions](docs/ride-storage-decisions.md)
- [Material You product design](docs/app-ui-design.md)
