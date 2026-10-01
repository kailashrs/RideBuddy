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
| Live | Connection status, live ride data, current ride, quick status |
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
4. Associate the bike via the system CompanionDeviceManager picker. Calls arrive through Telecom, which needs the motorcycle paired with the watch device profile rather than any runtime prompt.
5. Confirm the detected bike name and last four address characters.
6. Pair and authenticate.
7. If no Google Navigation API key is configured, offer an optional “Set up navigation” step. Bike connection and ride data remain usable without it.
8. Show a short “ready” screen with connection, ride data, and navigation status.

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

The main action is connection. Do not show empty gauges.

### Connected state

```text
┌─────────────────────────────┐
│ ● Motorcycle    Disconnect  │
│                             │
│ ┌─────────────────────────┐ │
│ │  72 km/h        [LIVE]  │ │
│ │                         │ │
│ │  RPM            5,420   │ │
│ │  ████████░░░░░░░░░░░░░  │ │
│ │  Throttle          38%  │ │
│ │  ██████░░░░░░░░░░░░░░░  │ │
│ │  ─────────────────────  │ │
│ │  ● Recording     3.0 km │ │
│ │                         │ │
│ │  [End ride] [Live det…] │ │
│ └─────────────────────────┘ │
│                             │
│  Navigate                    │
│  Share a destination from   │
│  Google Maps to begin       │
└─────────────────────────────┘
```

Design rules:

- Speed is the largest value and uses the user-selected unit. Its unit sits on the number's
  own baseline rather than at a tuned offset, so the pair holds together at any display scale.
- Revs and throttle are bar gauges, not numbers: both are read as a position at riding pace,
  and giving them one shape makes the pair read as a single instrument.
- Instantaneous mileage is not on the card. It swings with every throttle movement and only
  means something averaged over a finished ride, so it lives in the details sheet and in the
  ride summary.
- The card carries three kinds of thing and keeps them apart: readings, then ride state, then
  actions. Ride state is a status line with a dot — never a control sharing a row with controls.
- Card actions sit at the bottom trailing edge, ordered by emphasis with the lower-emphasis
  “end” action first, matching the navigate card so no card trains a thumb onto the other's
  “end” button. They wrap to their own lines rather than clip at large display scales.
- The LIVE badge uses the secondary container, not the primary one. The app's seed colour is
  red, so in the dark scheme `primaryContainer` and `errorContainer` are the same value — a
  badge that is always on screen would wear the app's error colour and shout louder than the
  redline the RPM gauge turns red for. Anything permanent on this card stays off primary.
- The connection indicator is a semantic status pill, not a decorative Bluetooth icon.
- Do not require a manual “Start ride” action. A ride begins automatically when the bike is connected and moving.
- “Live details” opens a bottom sheet, not a new dense dashboard.

`./gradlew testDebugUnitTest` draws this card, and the details sheet at each of its three
levels, to `app/build/outputs/renders/` (see `LiveCardRenderTest` and
`LiveDetailsSheetRenderTest`). Review changes to either by looking at those, not by reading
the layout — the colour-token collision above was invisible in the source and obvious in the
picture. The sheet composes into its own window, which the screen's root never draws, so its
capture goes through the sheet's own node rather than the root.

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

The app should automatically suppress notification and media cards near an imminent turn. Temporary alerts must expire and restore the navigation state.

## Share-to-navigate flow

The primary destination flow is:

```text
Google Maps → Share → RideBuddy → resolve destination → route → BLE/TFT guidance
```

The route is calculated first, and the rider confirms against the route rather than against the
text they shared:

```text
        ( whole route drawn on the map )

  Koramangala, Bengaluru
  12.4 km • 21 min
  [            Go            ]
```

The preview frames every segment of the route — the SDK's own overview stops at 45 minutes — and
mirrors the destination, distance and ETA to the cluster as session `83`, so **Go** on the phone and
**GO** on the handlebar start the same prepared navigator.

There is deliberately no second prompt before this one. A confirmation sheet asking "Navigate to?"
ahead of the preview asked the same question twice, the first time without showing the route.
**Start shared destinations** now means "skip the preview as well", not "skip the sheet"; a typed
link always stops at the preview. If route calculation fails, show a clear retry state and leave the
existing navigation untouched.

## Wording

The rider's word for what the bike sends is **ride data**, never “telemetry”. It appears in
section headings, settings, dialogs and onboarding copy.

“Telemetry” survives in three places, all deliberate:

- Developer tools — the diagnostics screen, its shared report and the stationary TFT test.
  That audience wants the precise word, and the figures there (frame rate, packet-gap
  estimate) are not ride data in any useful sense.
- Code identifiers, log output and Compose list keys, which no rider reads.
- The stored backup key `telemetryDurationMillis` and the SQLite column `telemetry_duration`.
  Renaming either breaks every existing backup and would need a schema migration to buy
  nothing.

## Live details bottom sheet

One sheet, opened fully expanded, scrolled rather than levelled:

- Live now: speed, RPM, throttle, then mileage as a label/value row.
- **This ride**: distance, time, hard acceleration, hard braking.
- **Ride data**: speed, RPM and throttle charts, each timestamped.

Design rules:

- No detail-level selector. The three-level version asked the rider to choose how much they
  wanted before they could see any of it, and the choice was sticky — a rider who had once
  picked the smallest level silently stopped being shown the ride figures. Scrolling answers
  the same question without putting a decision in front of it.
- The sheet skips its partially expanded stop. The content is one scroll, so a half-height
  sheet is just a drag between the rider and what they opened it for.
- Figures are label left, value right, matching the live card's gauges, so the same number is
  found in the same place on both surfaces.
- No connection quality here. Signal strength is not ride information; it belongs to the
  diagnostics screen with the dBm value, the telemetry rate and the packet-gap estimate.
- Units are spelled one way per surface: "38%" and "Latest 39%", never "39 %".

Raw hexadecimal packets should only appear under the diagnostic mode.

## History screen

The History screen is a quiet ride-history surface, not a social feed.

Top content:

- This week distance.
- Ride count.
- Average ride duration.
- Average mileage.

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

Pick the control from the choice, not from habit. A segmented button is for two or three
exclusive options whose labels are a word long — Full/Compact, System/Light/Dark. Each segment
takes an equal share of the row whatever its label needs, so a longer or a fifth option wraps
inside its own segment, one character per line, rather than the row adapting. Those belong in a
list row that shows the current choice and opens a single-choice dialog, which is also how
Android's own settings present the same shape of decision. The row gets no trailing chevron: a
chevron points at another screen, and a dialog is not one.


- Navigation: Google Navigation API key, units, voice guidance, route preferences, TFT text behavior.
- Ride recording: automatic start/stop thresholds, export, and how long each ride keeps its detailed ride data. The retention choice sits with ride data rather than under storage or privacy, because what a rider is deciding is how much of a ride's detail to keep, not how many megabytes to spend. Say what survives it: rides, records and insights are kept for good.
- Alerts: overspeed, RPM, acceleration, braking, weather, hazards.
- Notifications: one toggle per app, grouped by kind. The kinds are headings, not switches — a category switch above per-app switches gave two controls for the same thing, either able to silently veto the other. The text-message entry is whichever app holds the default-SMS role, resolved rather than listed.
- Calls: caller display and TFT call controls.
- Appearance: dynamic color, light/dark/system, contrast.
- Permissions: Bluetooth, location, and phone notifications for riding alerts. Calls need no runtime prompt, but they do need the motorcycle paired with the watch device profile, which is consented to once in the system pairing dialog.
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
- Keep the key out of logs, analytics, screenshots, exports, backups, and crash reports. The backup rules are an allowlist naming two files, so the key is outside the backup set by construction rather than by an exclusion someone has to maintain.
- Store it encrypted using Android Keystore-backed storage.
- Never initialize the Navigation SDK until a configured key has been loaded.

The current SDK supports runtime configuration, but requires an app restart if the active key is replaced. If the key is replaced or removed, change the status to "Restart required" and instruct the user to restart the app.

If the key is absent or invalid:

- Keep Live ride data, History, Info, BLE connection, and diagnostics available.
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
- Use tertiary for ride-data emphasis and secondary for supporting information.
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
