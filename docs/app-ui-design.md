# RideBuddy — Material You UI design

## Product direction

The app should feel like a quiet riding companion rather than a dashboard full of controls. The rider should be able to glance at connection state, navigation, and a few live metrics immediately. Detailed analytics, configuration, and history belong in the parked/non-riding experience.

The UI uses Material 3 / Material You principles:

- Dynamic system colors when available, with a deep-red fallback theme.
- Tonal surfaces instead of heavy borders and gradients.
- Large touch targets, generous spacing, and clear hierarchy.
- Cards and bottom sheets for progressive disclosure.
- Light and dark themes, with dark theme optimized for night riding.
- System font scaling and screen-reader-friendly labels.

## Primary navigation

Use a five-item Material 3 navigation bar on compact screens when the bike is not actively navigating. Show both icon and text label for every destination:

| Destination | Purpose |
|---|---|
| Live | Connection status, live telemetry, current ride, quick status |
| History | Ride history, summaries, routes, performance trends |
| Insights | Long-term totals, averages, records, and period comparisons |
| Info | Bike identity, connection, firmware, protocol status |
| Settings | Settings, permissions, notifications, about |

Five peer destinations fit the Material 3 navigation bar limit on compact windows. On medium and expanded windows, adapt the same destinations to a navigation rail. Preserve each destination's state when switching tabs.

When navigation is active, switch to a dedicated full-screen navigation destination. Hide the normal navigation bar so the screen has one clear purpose, and restore the previous selected destination when navigation ends.

## First-run flow

Keep setup linear and explain why each permission is needed.

1. Welcome: “Your motorcycle, at a glance.”
2. Bluetooth permission and explanation.
3. Location permission and explanation for routes and ride recording.
4. Optional notification access and legacy call-compatibility permission.
5. Associate the bike via the system CompanionDeviceManager picker.
6. Confirm the detected bike name and last four address characters.
7. Pair and authenticate.
8. If no Google Navigation API key is configured, offer an optional “Set up navigation” step. Bike connection and telemetry remain usable without it.
9. Show a short “ready” screen with connection, telemetry, and navigation status.

Do not expose protocol terminology such as GATT, characteristics, or challenge-response in normal onboarding. Put technical diagnostics under Settings → Diagnostics.

## Live screen

The Live screen has two states.

### Disconnected state

```text
┌─────────────────────────────┐
│ RideBuddy              ⋮    │
│                             │
│       Bike not connected    │
│   Connect when your bike is │
│          nearby             │
│                             │
│       [ Find my bike ]      │
│                             │
│  Last ride                   │
│  42.8 km   1h 12m   38 km/h │
└─────────────────────────────┘
```

The main action is connection. Do not show empty telemetry gauges.

### Connected state

```text
┌─────────────────────────────┐
│ Motorcycle             ●    │
│ Connected                    │
│                             │
│           72                 │
│          km/h               │
│                             │
│  RPM       Throttle   Fuel  │
│  5,420     38%        5.8   │
│                             │
│  Navigation                  │
│  Share a destination from   │
│  Google Maps to begin       │
│                             │
│  [ Live details ]            │
└─────────────────────────────┘
```

Design rules:

- Speed is the largest value and uses the user-selected unit.
- RPM, throttle, and consumption are compact secondary metrics.
- The connection indicator is a semantic status pill, not a decorative Bluetooth icon.
- Do not require a manual “Start ride” action. A ride begins automatically when the bike is connected and moving.
- “Live details” opens a bottom sheet, not a new dense dashboard.

## Active navigation surface

Navigation is automatically activated after the app receives a shared destination and has a route. The app should not ask the rider to start a second navigation session.

```text
┌─────────────────────────────┐
│  Navigation       Connected │
│                             │
│       ┌─────────────┐       │
│       │     ↱       │       │
│       └─────────────┘       │
│          350 m              │
│       Turn right onto       │
│       Residency Road        │
│                             │
│  18 min       7.4 km        │
│  ETA 6:42 PM                │
│                             │
│  [ Route overview ]         │
└─────────────────────────────┘
```

The phone screen can show the Google Navigation SDK map and guidance UI, while the TFT receives the reduced fixed-widget representation:

- Current and following maneuver.
- Distance to maneuver.
- Road/destination text.
- ETA and remaining distance.
- Speed limit when available.

The app should automatically suppress notification and media cards near an imminent turn. Temporary alerts must expire and restore the navigation state.

## Share-to-navigate flow

The primary destination flow is:

```text
Google Maps → Share → RideBuddy → resolve destination → route → BLE/TFT guidance
```

The receiving screen should be a short confirmation sheet:

```text
Navigate to?
Koramangala, Bengaluru

[ Start automatically ]
```

If the product decision remains fully automatic, this sheet should briefly confirm the destination and proceed without requiring another tap. If route calculation fails, show a clear retry state and leave the existing navigation untouched.

## Live details bottom sheet

Use a three-level bottom sheet:

- Peek: speed, RPM, throttle, consumption.
- Half: current ride metrics and connection quality.
- Full: timestamped telemetry chart and raw-data diagnostics.

The full view may include:

- Speed chart.
- RPM chart.
- Throttle chart.
- Consumption chart.
- Acceleration/braking events.
- Telemetry frequency and packet-loss estimate.

Raw hexadecimal packets should only appear under the diagnostic mode.

## History screen

The History screen is a quiet ride-history surface, not a social feed.

Top content:

- This week distance.
- Ride count.
- Average ride duration.
- Average fuel consumption.

Each ride card shows:

- Date and start area.
- Distance and duration.
- Average and maximum speed.
- Estimated fuel used.
- Small route preview.

Ride detail contains:

- Route map.
- Speed/RPM/throttle charts.
- Performance events.
- Fuel-efficiency summary.
- Parking location.
- Share/export action.

Performance features such as 0–60 and 0–100 should appear as optional insight cards, never as the main ride metric.

## Info screen

The Info screen should make the bike and protocol feel dependable without exposing implementation details.

```text
Info
RideBuddy
Connected

Vehicle identity
VIN                  ********ABC
Cluster software     1.0.0
Last connected       Just now

Connection
Telemetry            Receiving
Navigation           Ready
Companion link       Ready

[ Reconnect ]
```

Technical diagnostics can expose:

- Device name/address.
- RSSI.
- Service/characteristic discovery.
- Notification state.
- Companion-link readiness and protection phase.
- Last error and timestamp.
- Telemetry frame rate.

Keep this behind an explicit diagnostics entry.

## Settings

Organize settings by user intent:

- Navigation: Google Navigation API key, units, voice guidance, route preferences, TFT text behavior.
- Ride recording: automatic start/stop thresholds, storage, export.
- Alerts: overspeed, RPM, acceleration, braking, weather, hazards.
- Notifications: supported apps, preview length, priority behavior.
- Calls: caller display and TFT call controls.
- Appearance: dynamic color, light/dark/system, contrast.
- Permissions: Bluetooth, location, notification listener, and optional legacy phone-call access.
- Background guidance: disclose and link to the optional "Allow all the time" location setting without blocking foreground navigation.
- Bike association: Android's generic Companion Device picker and nearby-presence status; never a watch profile.
- Diagnostics: protocol logs and test mode.

Avoid exposing unsupported settings for gear calibration, fuel level, ride mode, or ECU controls.

### Google Navigation API key

Place API-key setup at Settings → Navigation → Google Navigation API key.

Use a standard Material 3 settings flow:

- A `ListItem` shows Navigation status: Not configured, Ready, Invalid, or Restart required.
- Selecting it opens a dedicated settings screen with a single outlined text field.
- Mask the saved value and show only its final four characters after setup.
- Provide Paste, Save, Replace, Remove, and Test configuration actions with appropriate button hierarchy.
- Explain that the key must have Navigation SDK for Android enabled, billing configured, and Android application restrictions for this app's package and signing certificate.
- Keep the key out of logs, analytics, screenshots, exports, backups, and crash reports.
- Store it encrypted using Android Keystore-backed storage.
- Never initialize the Navigation SDK until a configured key has been loaded.

The current SDK supports runtime configuration, but requires an app restart if the active key is replaced. If the key is replaced or removed, change the status to "Restart required" and instruct the user to restart the app.

If the key is absent or invalid:

- Keep Live telemetry, History, Info, BLE connection, and diagnostics available.
- Disable route creation without disabling the rest of the app.
- Show one clear setup action in the navigation card.
- Preserve an active route until the user explicitly replaces the key and restarts.

## Material 3 visual system

### Structure and components

- Use `Scaffold` as the primary screen structure.
- Use `NavigationBar` with five `NavigationBarItem`s on compact windows.
- Use adaptive navigation rail/drawer layouts on wider windows.
- Use a small top app bar on Live, where vertical space is valuable.
- Use large top app bars on History, Info, and Settings, collapsing naturally while scrolling.
- Use `ListItem` and section headings for settings instead of nesting many cards.
- Use filled buttons for the single highest-priority action, tonal buttons for secondary actions, and text buttons for low-emphasis actions.
- Use modal bottom sheets for short contextual tasks; use full screens for API-key setup, permissions, diagnostics, and ride details.
- Use Material 3 snackbar messages for brief confirmations and inline supporting text for actionable errors.
- Respect system bars and window insets edge-to-edge.

### Color

- Use the system dynamic color scheme on Android versions that support it.
- Provide a deep-red fallback seed color rather than forcing red over dynamic colors.
- Use primary for actions and active navigation state.
- Use error only for safety-critical warnings or failed connection states.
- Use tertiary for telemetry emphasis and secondary for supporting information.
- Never encode state using color alone; pair color with text or an icon.

### Shape

- Medium rounded cards for ordinary content.
- Large rounded containers for hero metrics and navigation cards.
- Small shape for chips and status pills.
- Avoid excessive card nesting.

### Typography

- Display style for speed and the active maneuver distance.
- Headline style for screen titles and primary section names.
- Body style for road names, destinations, and ride summaries.
- Label style for units, timestamps, and status metadata.
- Keep labels short and avoid all-caps except for compact units.

### Motion

- Use short, calm transitions for connection and route state changes.
- Animate metric changes subtly; do not make speed or RPM bounce.
- Use a clear crossfade when a temporary alert replaces navigation.
- Respect reduced-motion system settings.

## Riding-mode behavior

When the bike is connected and moving:

- Prioritize speed, current maneuver, and connection state.
- Minimize interactive controls.
- Use large touch targets for any unavoidable action.
- Do not require manual trip start, fuel entry, calibration, page selection, or ride-mode selection.
- Suppress low-priority cards near turns.
- Keep notification previews short.
- Use phone audio/haptics for urgent alerts rather than adding dense TFT text.

Priority order:

1. Incoming call.
2. Imminent navigation maneuver.
3. Critical overspeed or hazard alert.
4. Reroute, closure, toll, or weather alert.
5. Notification preview.
6. Media update.
7. Normal navigation/ride information.

## Accessibility baseline

- Meet WCAG AA contrast targets.
- Support dynamic font scaling without clipping.
- Provide content descriptions for icons and charts.
- Use semantic headings and state announcements.
- Ensure every action is reachable without relying on color or gesture alone.
- Keep touch targets at least 48dp.
- Provide a high-contrast fallback theme.
- Test TalkBack, dark mode, large text, portrait orientation, and intermittent connectivity.

## Recommended implementation shape

Use Kotlin with Jetpack Compose and Material 3 for the new app. Keep these modules separate:

- `ble`: CDM-driven pairing, protection handshake, GATT scheduling, packet codecs.
- `telemetry`: frame parsing and derived ride metrics.
- `navigation`: destination sharing, Google Navigation SDK, maneuver mapping.
- `ride`: automatic lifecycle and local persistence.
- `priority`: TFT card arbitration and timeouts.
- `ui`: Material 3 screens and state rendering.

The UI should observe domain state and never construct BLE packets directly. Packet serialization belongs in the BLE/navigation layers so every write can be validated, rate-limited, logged, and tested.
