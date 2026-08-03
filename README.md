# RS 457 Companion

An independent Android companion app for the Aprilia RS 457. The project is a native Kotlin and Jetpack Compose Material 3 app built around the BLE protocol mapped from the Aprilia India OEM application.

## Current implementation

- Material You app shell with **Live**, **History**, **Insights**, **Info**, and **Settings** destinations.
- Guided first-run setup for permissions, bike association, authentication status, optional alerts, and navigation.
- Adaptive bottom navigation / navigation rail layout.
- Generic Android Companion Device association for the RS 457/Tuono 457 advertisement families that share the verified telemetry layout, with no watch or automotive profile impersonation. SR/MIA-family devices remain excluded until their distinct telemetry decoder is implemented and tested.
- Companion presence callbacks and foreground GATT ownership for automatic nearby reconnection.
- Google Maps share target, coordinate/link parser, address geocoding, and two-wheeler Navigation SDK route session. External shares require confirmation by default; automatic start is an explicit opt-in.
- Runtime Google Navigation SDK API-key settings under **Settings → Navigation**.
- API keys encrypted with an Android Keystore AES-GCM key; raw keys are never read back into the UI.
- Google Navigation SDK 7.8.0 initialization through `NavigationApi.setApiKey()` before any Navigator is requested.
- Full serialized BLE GATT transport with MTU negotiation, authentication, subscriptions, reconnect, heartbeat, identity reads, bike controls, and diagnostics.
- Live speed, RPM, throttle, and consumption telemetry with automatic ride recording and local SQLite history.
- Three-level live-details sheet with glance metrics, current-ride/connection detail, and rolling speed/RPM/throttle/consumption charts even before ride recording starts.
- Configurable recording thresholds, named start/end areas, parking coordinates, compact route previews, and a real Google route map in ride details.
- Weekly History summaries, fuel summaries, hard acceleration/braking events, 0–60/0–100 records, parking launch, ride sharing, CSV/GPX export, and full-history CSV export.
- Long-term Insights for 7, 30, and 90 days or all time, including totals, averages, records, fuel estimates, and period comparison.
- Opt-in, rate-limited TFT bridge for maneuver, trip, road text, session, status, and clear packets.
- Opt-in notification-listener bridge for OEM-supported social, mail, message, and incoming-call presentation on the TFT.
- Per-app notification controls and reactive priority arbitration: calls win, approaching turns immediately clear lower-priority alerts, each simultaneous alert expires independently, and navigation is restored afterward.
- Call controls prefer the default phone app's standard `CallStyle` actions; an explicit `ANSWER_PHONE_CALLS` Telecom fallback is available for incompatible dialers.
- Optional phone-side overspeed, high-RPM, hard-acceleration and hard-braking alerts, plus location-based severe-weather and supported route alerts that can briefly appear on the TFT without obscuring calls or imminent turns.
- System/light/dark appearance modes, dynamic color, and high-contrast colors.
- Dedicated protocol diagnostics screen, stationary TFT test, diagnostics sharing, and in-place ride-database migrations.
- Unit tests for telemetry decoding, handshake lookup, API-key validation, aggregations, and TFT packet encoding.

Bike-side writes are implemented from the statically recovered OEM protocol. Inferred TFT navigation and call-state output are off by default. The stationary TFT test is an optional parked diagnostic that waits for expected GATT completions and asks the rider to visually confirm the TFT response; it is not a substitute for broader live vehicle validation.

The recovered TFT protocol exposes fixed notification icons, not arbitrary notification or media text. Accordingly, message preview-length and media-card controls are intentionally not fabricated. Weather alerts use the Open-Meteo forecast endpoint only while the preference is enabled and a current riding location is available. Checks are limited to once every 30 minutes unless the motorcycle moves at least 10 km; no weather API key is required. Road-hazard alerts remain available for navigation providers that supply hazard events because turn-by-turn data alone does not expose a general hazard feed.

Weather data is provided by [Open-Meteo.com](https://open-meteo.com/) under CC BY 4.0. The public endpoint's applicable usage tier must be reviewed before commercial distribution.

## Toolchain

- Android Gradle Plugin 9.3.1
- Gradle 9.6.1
- Kotlin / Compose compiler 2.3.21
- Jetpack Compose BOM 2026.06.01
- compile/target SDK 37, minimum SDK 31 (Android 12)
- Google Navigation SDK 7.8.0

AGP 9.3 requires Android Studio Quail 2 (2026.1.2) for full IDE support. The checked-in Gradle wrapper remains the authoritative build entry point.

## Build

Open the project in Android Studio or run:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleBenchmark
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
The `benchmark` variant is an optimized, minified local build with a `.benchmark`
application ID and `-benchmark` version suffix. It is debug-signed for local R8
validation only and is not a release artifact.

### Signed release builds

Release packaging deliberately fails without a signing key, so the project cannot accidentally produce a distributable unsigned APK. Set these secrets in user-level Gradle properties or your CI environment (never commit them):

```properties
RELEASE_STORE_FILE=/absolute/path/to/release.keystore
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

Then run `./gradlew assembleRelease` or `./gradlew bundleRelease`.

For local R8/resource-shrinker validation without release credentials, run
`./gradlew assembleBenchmark`. This produces a debug-signed, non-distributable
package with the distinct application ID `com.spaceboy.ridebuddy.benchmark` and
the version suffix `-benchmark`.

CI always runs unit tests, lint, a debug build, and the optimized benchmark
build. To enable its optional release-signing check, configure
`RELEASE_KEYSTORE_BASE64` with the base64 contents of the keystore plus the
three password/alias secrets shown above. CI then builds the release APK and
rejects any Android Debug signer.

## Navigation setup

1. Create a Google Cloud project and enable Navigation SDK for Android.
2. Create an Android-restricted API key for package `com.spaceboy.ridebuddy` and the signing certificate used for the build.
3. In the app, open **Settings → Navigation** and paste the key.
4. Share a Google Maps destination to RS 457 Companion, or paste a link/address into **Live → Navigate**.
5. Optionally set Location to **Allow all the time** under **Settings → Navigation with screen off** for the most accurate guidance when the app is backgrounded. Foreground navigation remains available without this optional grant.

## Bike and call setup

1. Open **Settings → Bike** and associate the motorcycle in Android's system companion-device picker.
2. Enable notification access under **Settings → Alerts & notifications** for TFT alerts, caller presentation, and standard call actions.
3. Enable **Legacy call compatibility** only if the installed phone app does not expose working answer/decline actions. This optional fallback uses the same deprecated Telecom controls observed in the OEM app and does not make RS 457 Companion the default dialer.
4. Before relying on **Experimental TFT navigation**, **Caller display**, or **TFT call controls**, consider running the stationary TFT test and confirming the visible display states. Keep the motorcycle parked and never perform first protocol validation while riding.

The key is supplied programmatically and is intentionally absent from source files and `AndroidManifest.xml`. Replacing a key after the SDK has been configured requires an app restart because Google permits `NavigationApi.setApiKey()` only once per process.

## Protocol references

- [BLE protocol map](docs/aprilia-rs457-ble-protocol.md)
- [Stationary bike validation checklist](docs/hardware-validation.md)
- [Material You product design](docs/app-ui-design.md)
