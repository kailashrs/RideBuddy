# RideBuddy

RideBuddy is a native Android companion app for supported motorcycles. The
project is built with Kotlin and Jetpack Compose Material 3, with a
model-aware Bluetooth transport layer and safety-gated vehicle integration.

## Features

- **Material You UI**: Adaptive layouts, light/dark modes, and dynamic color. Features Live, History, Insights, Info, and Settings destinations.
- **BLE Telemetry**: Automatic background reconnection. Live speed, RPM, throttle, and consumption metrics with automatic ride recording.
- **Google Navigation**: Share destinations directly from Google Maps. Full turn-by-turn routing via the Google Navigation SDK.
- **Ride History**: Local SQLite history with weekly summaries, performance records, and long-term insights. Includes GPX/CSV export capabilities.
- **TFT Integration**: (Opt-in) Bridges turn-by-turn maneuvers, caller presentation, and standard phone app call controls directly to the motorcycle's display.
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

- Android Gradle Plugin 8.13.2
- Gradle 8.13
- Kotlin / Compose compiler 2.3.21
- Jetpack Compose BOM 2026.06.01
- compile SDK 36.1, target SDK 36, minimum SDK 31 (Android 12)
- Google Navigation SDK 7.8.0

The checked-in Gradle wrapper remains the authoritative build entry point.

## Build

Open the project in Android Studio or run:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleBenchmark
```

### Release builds

Release packaging deliberately fails without a signing key. Set these secrets in user-level Gradle properties or your CI environment (never commit them): `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. 

Run `./gradlew assembleRelease` to build the release variant.

CI automatically runs tests, lint, and builds debug/benchmark APKs, failing release builds if credentials are not provided.

## Navigation setup

1. Create a Google Cloud project and enable Navigation SDK for Android.
2. Create an Android-restricted API key for package `com.spaceboy.ridebuddy` and the signing certificate used for the build.
3. In the app, open **Settings → Navigation** and paste the key.
4. Share a Google Maps destination to RideBuddy, or paste a link/address into **Live → Navigate**.
5. Optionally set Location to **Allow all the time** under **Settings → Navigation with screen off** for the most accurate guidance when the app is backgrounded. Foreground navigation remains available without this optional grant.

## Bike and call setup

1. Open **Settings → Bike** and associate the motorcycle in Android's system companion-device picker.
2. Enable notification access under **Settings → Alerts & notifications** for TFT alerts, caller presentation, and standard call actions.
3. Enable **Legacy call compatibility** only if the installed phone app does not expose working answer/decline actions. This optional fallback uses deprecated Telecom controls and does not make RideBuddy the default dialer.
4. Before relying on **Experimental TFT navigation**, **Caller display**, or **TFT call controls**, consider running the stationary TFT test and confirming the visible display states. Keep the motorcycle parked and never perform first protocol validation while riding.

The key is supplied programmatically and is intentionally absent from source files and `AndroidManifest.xml`. Replacing an active key requires restarting the app.

## Project references

- [Stationary vehicle validation checklist](docs/hardware-validation.md)
- [Material You product design](docs/app-ui-design.md)
