# Architecture (1.1)

The backend is a flat set of process-scoped objects built in `AppContainer`. Each has one job;
they talk through `StateFlow`s and a few constructor-injected interfaces.

| Area | Owner | What it does |
|---|---|---|
| Bike link | `ble/AndroidBikeConnection` | Bonds (before GATT, as the OEM app does), connects through Nordic's `BleManager`, answers the protection challenge or uses the stored acceptance, subscribes, and reports `Connected` only once the cluster sends something. Three attempts per cycle, 1 s then 2 s apart. |
| Pairing | `core/companion/BikeCompanionManager` | The companion association is the only record of the paired bike. Presence events arrive in `BikeCompanionDeviceService`; `BikeConnectionDemandController` decides whether they may reconnect. |
| Cluster output | `core/tft/ClusterDisplay` | Computes the navigation and call screens the cluster should show, and one writer sends whatever differs from what the cluster acknowledged, in the OEM driver's order. |
| Notification icons | `service/NotificationIcons` | OEM behaviour: shown on every post, hidden when the last notification behind the icon goes, relit on connect and on battery change. |
| Navigation | `core/navigation/NavigationController` | Owns the Navigation SDK navigator and the route: prepare, start (phone Go or handlebar GO), skip, stop (phone or handlebar EXIT), arrival, and stop on link loss. The map screen is a view of it. |
| Calls | `service/RideBuddyInCallService` | Reads calls from Telecom and hands them to `ClusterDisplay`, which also acts on the handlebar answer/reject. |
| Rides | `data/RideRecorder`, `data/RideRepository` | Detects and records rides from telemetry. Summaries live in `ride_history.db` (backed up); each ride's samples are one gzipped ProtoBuf blob in `ride_samples.db` (not backed up). |
| Settings | `data/AppSettingsRepository` | One `@Serializable` value in a DataStore (`settings.json`, backed up). Link state — protection acceptance, bike identity, connection demand — is a second DataStore (`link_state.json`, not backed up). |

## Upgrading from 1.0

`LegacyRideImporter` reads the 1.0 `rides.db` (schema version 6 only) once, keeps ride ids,
verifies counts, and renames the file to `rides.db.migrated`. DataStore's
`SharedPreferencesMigration` carries the 1.0 preference files over once. Both go in a later
release, once 1.1 has been confirmed on the bike.

## Sources of truth

Protocol behaviour is checked against the decompiled OEM Aprilia India app, and platform
behaviour against AOSP. The decision records in this folder explain *why*; where they disagree
with those sources, the sources win.
