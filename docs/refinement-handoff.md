# Refinement handoff

Working notes from the remote session that started the twelve-point refinement pass.
Written so the work can be picked up in a local session without re-deriving anything.

Base commit at session start: `fe90ec1` (`main` == `origin/main` == the feature branch;
the previous session's 50 commits were already merged, and the tree was clean — there
were no local uncommitted changes to carry over).

Branch: `claude/aprilia-app-refinements-eundsc`.

---

## Environment constraints hit in the remote container

These are the reason some items were verified by reading rather than by building.
They do not apply on a local machine.

- **No Android SDK, and no way to install one.** `dl.google.com` is refused by the
  container's network policy (`CONNECT tunnel failed, response 403`), which is the only
  host serving the command-line tools and platform packages. `repo.maven.apache.org`,
  `services.gradle.org` and `maven.google.com` are reachable, but without `android.jar`
  for API 36.1 nothing compiles. **Nothing in this handoff has been compiled or tested.**
  Run `./gradlew testDebugUnitTest lintDebug assembleDebug` locally before trusting any
  of it.
- **`Artifacts/` is absent.** It is gitignored (`Artifacts/*`, only `README.md` exempted)
  and was not in the fresh clone, so the decompiled OEM app could not be consulted
  directly. Everything about the protocol below comes from `docs/aprilia-rs457-ble-protocol.md`
  and `docs/cluster-link-decisions.md`, which the earlier session wrote *from* those
  artifacts. Both are detailed enough for items 1 and 2; re-verify against the decompiled
  source locally if you want belt and braces.

---

## Status of the twelve items

| # | Item | Status |
|---|------|--------|
| 1 | Navigation glyph mapping | Audited; one refinement identified, not applied |
| 2 | Route staging screen + bike GO | **Designed in full below, not implemented** |
| 3 | Max 3 connection attempts + cleanup | **Done** (`19846bf`) |
| 4 | End ride button | **Done** (`ce5a10c`) |
| 5 | Interactive route in ride details | Designed below, not implemented |
| 6 | Telemetry calculation validity | Audited; findings below |
| 7 | Rounding of displayed values | Two real defects found, not fixed |
| 8 | Drop the history line map | Designed below, not implemented |
| 9 | Charts render correctly | Root cause found, not fixed |
| 10 | Navigation text fits the protocol | Audited; already sound |
| 11 | Truecaller as default dialer | Three concrete defects found, not fixed |
| 12 | Label the field "Google Maps link" | **Done** (`ce5a10c`) |

---

## What landed

### Item 3 — `19846bf`

`MaxReconnectAttempts = 6` became `MaxConnectionAttempts = 3` in
`app/src/main/java/com/spaceboy/ridebuddy/ble/BleConnectionPolicy.kt`. The old constant
counted *reconnects* but was rendered to the rider as an attempt number, so the
diagnostics screen showed `Reconnecting (1/6)` for the second of seven tries. The budget
is now three attempts in total — first attempt plus two retries, at 1 s and 2 s —
and `AndroidBikeConnection.scheduleReconnect()` publishes `reconnectAttempt + 1` against it.

On `Failed(retriesExhausted = true)`, `AppContainer` now also calls
`tftPriorityCoordinator.dropDisplayedAlerts()` and `tftNavigationBridge.stop()` before
stopping the route. Rationale: the GATT queue is already dropped by `disconnectInternal`
(via `operationCoordinator.clear()`), but the alert timers and the bridge's replay state
outlive it and would surface on the next link. The ride was already ended and persisted
by `RideRecorder`'s own collector on that same state, with the foreground-service barrier
added in `af51072`, so ride handling was left alone.

**Latent defect fixed on the way:** `TftNavigationBridge.stopLocked()` cleared every field
it holds for replay *except* `previewActive`, so `refreshTransportAvailability(ready = true)`
could redraw a GO prompt for a destination dropped with the route.

### Item 4 + 12 — `ce5a10c`

`RideRecorder.endRideNow()` closes the ride at its last telemetry (using `stopCandidate`
when one exists, exactly as the disconnect path does) and sets `awaitStopBeforeNextRide`,
which blocks a new ride until speed falls back under the stop threshold. Without that the
next 4 Hz frame opens a second ride and the button looks inert. The flag is cleared on
`Disconnected`/`Failed` — the next session is a new outing.

Wired through `MainViewModel.endRide()` → `MainScreenActions.onEndRide` → `LiveScreen`,
where the button sits in a row with the recording distance and `Live details`, shown only
while `activeRide != null`.

The navigate field is now labelled `Google Maps link` with placeholder
`Paste or share a link from Google Maps` and a link icon. Note `DestinationParser` *does*
still geocode — a Maps link of the `?api=1&query=Some+Place` shape carries no coordinates —
so `geocode()` is **not** dead code; only the promise of free-text place search was wrong.

---

## Item 2 — route staging (designed, not built)

This is the largest remaining piece. The design below was settled against
`docs/aprilia-rs457-ble-protocol.md` and the existing bridge states.

### The key simplification

There are currently **two** staging concepts, and the requirement collapses them into one:

- **(a) Today:** `AppContainer.stagedDestination` stages raw *text* before any route exists.
  The cluster shows GO; handlebar GO fires `bringNavigationHostForward()`, a background
  activity launch through a `PendingIntent` into `MainActivity`, which re-parses the text
  and launches `NavigationActivity`. `LiveScreen` separately shows a "Navigate to?"
  confirmation `ModalBottomSheet`.
- **(b) Wanted:** a staging *screen* showing the calculated route, with GO at the bottom,
  mirrored to the cluster, startable from the handlebar.

Because (b) always precedes guidance, (a) becomes redundant. Deleting it removes the
background-activity-launch machinery entirely, which is the single most fragile thing in
the navigation path.

**Delete:** `AppContainer.stagedDestination` / `stageDestination` / `clearStagedDestination`
/ `StagedDestination` / `bringNavigationHostForward` / `ActionStartStagedNavigation` /
`ExtraStagedDestinationId` / `ExtraStagedDestination` / `HandlebarStartPendingIntentRequestCode`;
the `stagedDestination.collect { previewDestination(...) }` mirror in `init`;
`MainActivity.handleIncomingIntent`'s staged branch and `startStagedNavigation`;
`LiveScreen`'s `showSharedConfirmation` sheet. Then prune the now-unused imports in
`AppContainer` (`ActivityOptions`, `PendingIntent`, `Intent`, `AtomicLong`, `update`,
`MutableStateFlow`, `StateFlow`, `asStateFlow`) and the `appContext` field, which exists
only for the pending intent. `MutableSharedFlow`/`SharedFlow`/`asSharedFlow` appear to be
unused already — check before deleting.

I began this edit in the session and reverted it, because `MainActivity` still depends on
the removed API and the tree would not have compiled. Start from `AppContainer` and work
outwards.

### Replacement in `AppContainer`

```kotlin
/**
 * The calculated route waiting on GO, if any.
 *
 * Registered by the staging screen and invoked from the handlebar, so neither has to know
 * about the other and the process keeps one answer to "is a route staged". Tagged with the
 * staging screen's session id, so a departing screen cannot clear a newer screen's staging.
 */
private val stagedRoute = AtomicReference<StagedRoute?>(null)

internal fun stageRoute(sessionId: Long, start: () -> Unit) {
    val staged = StagedRoute(sessionId, start)
    stagedRoute.updateAndGet { current ->
        if (current != null && current.sessionId > sessionId) current else staged
    }
}

internal fun clearStagedRoute(sessionId: Long) {
    stagedRoute.updateAndGet { current -> current?.takeIf { it.sessionId != sessionId } }
}

private data class StagedRoute(val sessionId: Long, val start: () -> Unit)
```

and in the existing controls collector:

```kotlin
if (event is BikeControlEvent.StartNavigation) {
    val staged = stagedRoute.get()
    if (staged == null) {
        connectionEventJournal.record("Handlebar GO ignored; no route is staged")
    } else {
        connectionEventJournal.record("Handlebar GO; starting the staged route")
        staged.start()
    }
}
```

`start()` runs on the application scope's `Dispatchers.Default`, so the callback registered
by `NavigationActivity` must hop with `runOnUiThread`.

No background activity launch is needed any more: the staged route lives inside a
`NavigationActivity` whose `lifecycleScope` keeps collecting while merely stopped, so
handlebar GO works with the phone stowed. If that Activity is destroyed the staging is
gone, which is correct — the rider backed out of the route.

### `NavigationActivity` restructuring

The ordering problem: `prepareNewRoute()` currently calls `tftNavigationBridge.start(title)`
and `registerServiceForNavUpdates` *before* `calculateRoute`, and
`TftNavigationBridge.previewDestination()` refuses to stage while `acceptingUpdates ||
sessionActive`. So both must move to the GO step.

1. `prepareNewRoute(navigator)` keeps only the "stop a route that is already running" block
   and then calls `calculateRoute(navigator)`.
2. In `calculateRoute`'s `RouteStatus.OK` branch: if `intent.getBooleanExtra(ExtraStartImmediately, false)`
   call `beginGuidance(navigator)`, else `stageRoute(navigator)`.
3. New `stageRoute(navigator)`: set `stagedRouteTitleState.value = title`, call
   `runCatching(navigationView::showRouteOverview)` (that API is already used at the
   `SkipManeuver` branch, so it is confirmed), call
   `appContainer.tftNavigationBridge.previewDestination(title)` — this is cluster session
   `83` / `SessionRouteReady`, the OEM's GO state — and register with
   `appContainer.stageRoute(navigationSessionId) { runOnUiThread { startStagedRoute() } }`.
4. New `startStagedRoute()`: no-op unless still staged, still the session owner and holding
   a navigator; then `clearStagedRoute()` and `beginGuidance(navigator)`.
5. New `clearStagedRoute()`: clears `stagedRouteTitleState`, calls
   `appContainer.clearStagedRoute(navigationSessionId)` and `previewDestination("")`.
   **Must** be called first in `onDestroy` — see the trap below.
6. New `beginGuidance(navigator)`: `bridge.start(title)` → `registerServiceForNavUpdates`
   (toast + `bridge.stop()` if it fails) → `startGuidance()` → on success
   `guidanceStarted = true` and `navigationGuidanceLifecycle.markGuidanceStarted(...)`.
7. `NavigationActivity.intent(...)` gains `startImmediately: Boolean` →
   `ExtraStartImmediately`.

**`tftRouteRequestNeedsRestart` becomes dead** and should go. It existed only because
`bridge.start()` ran before the route was known, so a rejected route needed the TFT request
restarted while keeping the SDK registration. With `start()` moved after `RouteStatus.OK`
that case cannot arise, and the retry button's `onClick` collapses to: not configured →
`awaitNavigationKeyAndInitialize()`; no permission → `requestLocationOrInitialize()`;
attach requested → re-init; else `navigator?.let(::calculateRoute) ?: initializeNavigation()`.

**The trap:** `TftNavigationBridge.stopLocked()` computes
`shouldShutdown = sessionActive || (acceptingUpdates && outputEnabled && transportReady)`.
While staged, both `sessionActive` and `acceptingUpdates` are false, so `stop()` marks *no*
clear packet and the GO prompt stays lit on the cluster. Two options, and doing both is
cheapest: call `previewDestination("")` explicitly in `clearStagedRoute()` (which does mark
session `0` plus the clear), **and** add `previewActive` to that `shouldShutdown` expression.
While you are there, `setOutputEnabled(false)` should also clear `previewActive` and include
it in its own `markClearLocked()` condition.

### Bypass rule

`autoStartSharedDestinations` stops meaning "launch without confirmation" and starts meaning
"skip the staging screen". In `MainActivity.handleShareIntent`, always
`viewModel.queueAutoStartSharedDestination(destination)`; then at the `startActivity` call
in `startNavigation(rawDestination, autoStartRequestId)`:

```kotlin
startImmediately = autoStartRequestId != null &&
    viewModel.settings.value.autoStartSharedDestinations,
```

`autoStartRequestId != null` is precisely "this came from a share", so a typed link always
stages. `withRestoredAutoStartSharedDestination` still hands a failed share back to the
field with its error, so the manual `sharedDestination` state stays live and is worth keeping.

### The GO bar

Deliberately minimal: destination name plus a GO button, in an `ElevatedCard` pinned to
`Alignment.BottomCenter` of a `Box(Modifier.fillMaxSize().safeDrawingPadding())` inside the
existing `composeOverlay`. **Do not** try to show distance/ETA on it — that would mean
`Navigator.getCurrentTimeAndDistance()` / `TimeAndDistance`, whose exact accessor names
(`getMeters()`/`getSeconds()` vs `getDistanceMeters()`) I could not verify without the SDK.
`showRouteOverview()` draws the route and the SDK's own header carries those figures anyway.

The strings were drafted and reverted (they would have been unused resources):

```xml
<string name="navigation_default_destination">Destination</string>
<string name="navigation_staging_title">Route ready</string>
<string name="navigation_staging_go">Go</string>
```

`Icons.Outlined.Navigation` is already imported in `NavigationActivity`. New imports needed:
`fillMaxWidth`, `safeDrawingPadding`, and `TextOverflow` if you ellipsise the title. Use
`getString(...)` rather than `stringResource(...)` — that is the existing idiom inside this
file's `setContent`.

---

## Item 1 — glyph mapping (audited)

`TftPacketEncoder.clusterManeuver` was checked line by line against the Mappls→pictogram
table at `docs/aprilia-rs457-ble-protocol.md:327`. Every entry agrees: 1 straight, 2/3 U-turn
cw/ccw, 4/9 keep right/left, 5/10 slight, 6/11 turn, 7/12 sharp, 8 merge, 15/16 exit
right/left, 151–157 roundabout Nth exit, 158 plain roundabout, 200 ferry, 201 destination.
The left/right mirroring called out in the doc (pictogram `6` is a *right* turn, confirmed on
the wire against a `Turn right onto` banner) is correctly oriented. Ids 13/14 have no known
artwork and nothing emits them, which is right.

**One refinement worth making:** `Maneuver.ON_RAMP_KEEP_LEFT` and `ON_RAMP_KEEP_RIGHT`
currently map to `8` (merge). The cluster has dedicated keep-left/keep-right glyphs (`9`
and `4`), and those maneuvers do name a side, so `4`/`9` carry strictly more information.
Leave `MERGE_UNSPECIFIED`, `MERGE_LEFT`, `MERGE_RIGHT` and `ON_RAMP_UNSPECIFIED` on `8`.

`OFF_RAMP_UNSPECIFIED → 8` is *not* right either (an exit is not a merge), but there is no
side-less exit glyph in the vocabulary, and guessing a side is exactly the class of bug the
`057bd60` "stop mirroring left and right" commit fixed. Recommend leaving it and keeping the
existing comment.

## Item 10 — navigation text (audited, already sound)

`guidanceTextRows` splits rows 0/1 for the destination and row 2 for the instruction banner,
16 UTF-8 bytes each, walked by code point so a multi-byte character is never split across
rows. `TftNavigationBridge.accept()` already sends `current.fullRoadName` in preference to
the instruction sentence, falling back to `roadNameOrSelf()`, so the 16 characters are spent
on the thing the pictogram cannot convey. `TftTextMode.Compact` drops the destination rows.
Nothing to do here; the only possible improvement is truncating on a word boundary rather
than a byte boundary, which is cosmetic.

## Item 6 — telemetry calculations (audited)

Checked and **correct**: `distanceDeltaKilometres` (trapezoidal, with the 2.5 s gap rule that
contributes nothing across dropped frames rather than inventing distance); `fuelDeltaLitres`
(reciprocal of km/L averaged over the interval, null when either endpoint is missing);
`accelerationTime` (interpolates the crossing between straddling samples, rejects runs with
mid-run gaps); `RideEventDetector` (collapses consecutive over-threshold samples into one
episode at its peak); `InsightsCalculator` duration-weighted averages; `combinedMileageKilometresPerLitre`
(total distance over total fuel, not a mean of means).

`Ride.averageSpeedKph` is `speedSum / sampleCount` — a *sample* mean, not a time mean, so it
can disagree with `distance / duration`. I considered accumulating a `measuredMillis` on
`ActiveRide` and deriving `distance / measuredHours` instead, and concluded **it is not worth
the churn**: telemetry is uniform at ~4 Hz and stationary samples are included in the sum, so
the two agree to within half a sample at each end. The only real divergence was reconnect
gaps inflating `durationMillis`, and item 3 has now bounded those to a few seconds. Noting
it so the question is not re-opened from scratch.

## Item 7 — rounding (two real defects, not fixed)

1. **`HistoryScreen.kt:104`** — `"… • ${ride.averageRpm} RPM"` interpolates a raw `Double`.
   A real ride renders as `4231.578947368421 RPM`. Needs `"%.0f".format(locale, ride.averageRpm)`.
   (`ride.maximumRpm` on the same screen is a `Long` and is fine, as is `InsightsScreen.kt:122`
   which already formats.)
2. **`RideDetailActivity.TelemetryChart`** — `"Peak %.1f %s"` and `LiveScreen.LiveChart`'s
   `"Latest %.1f %s"` give one decimal to every series, so RPM reads `Peak 9800.0 rpm`.
   Give both a `decimals` parameter, 0 for rpm and throttle, 1 for speed.

Also minor: `RideDetailActivity.kt:450` uses `"%+.1f m/s²".format(...)` with no explicit
locale, unlike everything else in that file.

## Item 9 — charts (root cause found, not fixed)

The 1D insights chart renders as **a single dot in the middle of an empty card**. In
`lineChartSegments` (`ui/components/LineChart.kt`), when `validValues.size == 1` the y is
forced to `height / 2f` and, with `values.size == 1`, x to `width / 2f`; `toRenderedSegment`
then returns a bare `point` drawn as a `drawCircle` of radius ~3. "Today" usually contains
one ride, so that is the normal case, not an edge case.

Recommended fix: the distance trend is per-ride categorical data, so a **bar chart** is the
correct representation and renders correctly at n = 1. Replace `DistanceTrend`'s `LineChart`
with a small bar component. The time-series charts (`LiveChart`, `TelemetryChart`) are
genuinely line data and should stay as they are — they were checked and are fine, including
the gap-splitting on null/non-finite values and the non-overshooting cubic smoothing.

## Item 8 — history line map (designed, not built)

`HistoryScreen.RoutePreview` (lines 135–204) draws a normalised, aspect-distorted sketch on
four hairlines. Every route fills the card regardless of real extent, so a 2 km loop and a
200 km tour look identical — which is the "doesn't add a lot of value" observation.

Recommended: drop it, and give the card `startArea → endArea` plus the existing figures in a
cleaner layout. That makes `Ride.routePreview` dead, and with it: `RoutePoint`,
`RideRecorder.routePreview()`, the `encode()`/`decodeRoute()` helpers and the `route_preview`
column read/write in `RideRepository` (lines 115, 163, 281, 304, 350–357). Leave the column
in existing databases; just stop reading and writing it. Check
`app/src/androidTest/.../RideRepositoryMigrationTest.kt` before touching the schema.

## Item 5 — interactive route (designed, not built)

`RideDetailActivity.RouteCard` disables every gesture (`scrollGesturesEnabled`,
`zoomGesturesEnabled`, `rotationGesturesEnabled`, `tiltGesturesEnabled` all false) because
the map lives inside a `LazyColumn` and would fight it for vertical drags. That is also why
`NoOpLazyListPrefetchStrategy` exists.

Enabling gestures in place would reintroduce the nested-scroll conflict. Recommended instead:
make the card tappable and open a **full-screen** route view — full gestures, start/end
markers, and a scrub slider that walks a marker along the samples showing speed and time at
that point. A full-screen Composable swapped in for the list inside the same Activity avoids
both a new Activity and the `LazyColumn` conflict; the inline card stays a static preview and
the prefetch workaround stays justified.

## Item 11 — Truecaller (three defects found, not fixed)

Call notifications are **not** package-filtered — `BikeNotificationListenerService.onNotificationPosted`
hands every notification to `callNotificationBridge.onNotificationPosted` before consulting
`SupportedNotificationAppsByPackage` — so Truecaller's calls are already seen. The problems
are elsewhere:

1. **`isRideBuddyCallNotification()` is too loose.** It accepts anything with
   `category == CATEGORY_CALL`, which Truecaller's after-call cards (caller ID, spam report)
   can carry. Those would put a stale call screen on the cluster. Tighten to: a CallStyle
   notification (`EXTRA_CALL_TYPE != CALL_TYPE_UNKNOWN`), **or** one carrying answer/decline/
   hang-up intents, **or** `CATEGORY_CALL` *and* `FLAG_ONGOING_EVENT`.
2. **The caller-number heuristic can print a call duration.** `onNotificationPosted` falls
   back to `EXTRA_TEXT`, strips to digits and `+`, and accepts anything with ≥ 5 digits.
   Truecaller's ongoing-call text is a duration, so `01:02:03` yields `010203` — six digits —
   and a bogus "number" reaches the cluster. Require the text to look like a phone number
   before stripping (reject anything containing `:`; `PhoneNumberUtils.isGlobalPhoneNumber`
   on the trimmed string is the cleaner check).
3. **Truecaller's texts get no icon.** It is commonly the default SMS app too, but
   `com.truecaller` is not in `SupportedNotificationApps`. Add it under
   `NotificationAlertCategory.Messages` with events `6`/`7`, alongside Google/Samsung
   Messages and WhatsApp. Its call notifications are already intercepted before the icon
   path, and `onNotificationPosted` explicitly drops any tracked icon for a package whose
   notification turns out to be a call, so the two do not collide. To keep Truecaller's
   promo notifications from lighting the message icon, also skip `FLAG_ONGOING_EVENT` and
   group-summary notifications on the icon path — a worthwhile general filter.

The English-only action-label fallback in `extractCallIntents` is a known, documented
limitation and should be fine for Truecaller, which labels its actions "Answer"/"Decline".
Android 16 (`minSdk 36`) requires `CallStyle` for `phoneCall` foreground services anyway, so
the exact-intent path should be the one that runs.

---

## Suggested order for the rest

1. Item 7 and item 9 — small, isolated, immediately visible.
2. Item 8, then item 5 — both touch ride presentation, and 8 removes code that 5 would
   otherwise have to be reconciled with.
3. Item 11 — self-contained in `CallNotificationBridge` / `SupportedNotificationApps`.
4. Item 1 — a two-line change once you have decided on the on-ramp question.
5. Item 2 last — largest, and it deletes code the others do not touch.

Build after each: `./gradlew testDebugUnitTest lintDebug assembleDebug`.
