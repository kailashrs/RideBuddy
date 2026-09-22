# Ride storage — decision record

Why ride history is stored and backed up the way it is. This document owns *rationale* for the
local database, what is kept for how long, and what leaves the device through Android's backup
service. Code KDocs carry the local invariants and link back here rather than restating the
argument.

Measurements below are from the real schema under SQLite with realistic values, not estimates.

---

## S1 — Summaries are computed during the ride, not from stored samples

`ActiveRide` accumulates distance, RPM-milliseconds, throttle-milliseconds, fuel and maxima as
telemetry arrives, and `toRide()` derives every summary figure from those running totals. No
average is ever computed by reading samples back.

**Why it matters.** It means the samples are not kept in order to produce the figures, so the
question "could we keep samples in memory and persist only averages?" has already been answered
for the averages — they never touch the database. What the stored series is actually for is five
readers: the three telemetry charts, the route map, the ride-events list, and the CSV and GPX
exports.

**Consequence.** Retention can drop samples without touching a single number in History or
Insights. See [S4](#s4).

---

## S2 — Stored samples use fixed-point integers, relative time, and no surrogate key

Before: 98.1 bytes per sample, 1.35 MiB per riding hour, 492 MiB a year at an hour a day.

Three changes, none of which alter what is displayed:

- Every value is a scaled integer rather than a `REAL`. SQLite writes an integer as a
  variable-length value — one or two bytes for a speed — where a `REAL` is always eight. The
  scales in `RideSampleCodec` are finer than the source data: the vehicle reports whole km/h and
  tenths of a km/L, and no GPS fix resolves the centimetre that the coordinate scale preserves.
- Timestamps are offsets from the ride's start. An epoch millisecond needs a six-byte integer;
  an offset into a ride needs one or two.
- The table is `WITHOUT ROWID` keyed on `(ride_id, t)`. Every sample query already filtered by
  ride and ordered by time, so the old arrangement held that pair twice — once in the table and
  once in the index beside it — plus an `AUTOINCREMENT` id that nothing ever addressed a sample
  by.

**Result.** 37.8 bytes per sample, 0.52 MiB per riding hour: 39% of what it was.

**Cost.** Two samples in the same millisecond now collide on the primary key. At a 250 ms
telemetry rate that needs a burst, and `INSERT OR REPLACE` makes it cost one frame rather than
failing the ride's save.

---

## S3 — Stored at one sample a second, with acceleration carried as the interval's extreme

The vehicle sends roughly four frames a second. Every reader downsampled that immediately —
600 points for a chart, 1,000 for a route, at most 20 events — so four samples a second were
written to disk and then discarded on every read. One a second still gives an hour-long ride
3,600 points, several times what any reader uses, and is the rate GPS traces are conventionally
logged at.

**The exception is acceleration**, and it is why thinning is not a plain "keep every fourth
sample". A hard stop is a brief high-magnitude excursion; keeping whichever value landed on the
retained sample would flatten exactly the episodes the ride-events list exists to report. The
retained sample therefore carries the largest-magnitude acceleration seen anywhere in its
interval, which leaves `RideEventDetector` finding the same episodes at the same peaks it would
have found at full rate — asserted directly in `RideSampleCodecTest`.

Acceleration is stored rather than recomputed from adjacent speeds for the same reason: after
thinning it is no longer a difference between neighbours.

**Thinning happens at save time, not during the ride.** The recorder keeps the full-rate series
in memory, so the figures taken before storage — the 0–60 and 0–100 times, and the route
preview — are computed at the rate the vehicle sent.

**Result.** 39.8 bytes per sample at 1 Hz: **0.137 MiB per riding hour, 10% of the original**,
or about 50 MiB a year at an hour a day.

**Cost.** A CSV export is second-by-second rather than four times a second, and its acceleration
column is the interval's peak — hence `peak_acceleration_mps2`. Chart peaks can understate a
sub-second spike by a few km/h; the ride's true maximum speed is a stored summary figure taken at
full rate, so the headline number is unaffected.

**Migrated rides keep their original rate.** The version 5 to 6 migration rewrites existing
samples into the compact layout without thinning them, so nothing already recorded loses detail.

---

## S4 — Retention drops samples and keeps rides, and the window is the rider's

Nothing was ever deleted. The only delete was a wipe-everything button, so the database grew
monotonically for the life of the install; per-row savings postpone that without bounding it.

`SampleRetention` is a rider-chosen window — 30 days to Keep everything, defaulting to one year —
and `RideHistoryMaintenance` applies it. Only samples expire. The ride row, its summary figures,
its records and its route preview are kept indefinitely, which by [S1](#s1) means History,
Insights, weekly totals and the route thumbnail reach back as far as they ever did however short
the window is.

**Why a year by default.** A default of "keep everything" leaves the growth problem in place for
anyone who never opens settings, while a short default would delete a rider's data on upgrade
without being asked. A year bounds growth at roughly 50 MiB and deletes nothing anyone has
recorded so far.

**Pruning is driven by collecting the setting** rather than by a call at startup. The flow
replays its current value immediately, which is the startup pass, and emits again when the rider
shortens the window — which is when they expect the space back, not at some later launch.

**A pruned ride says so.** Three empty axes read as a recording fault; the detail screen replaces
the charts and events with a note naming the setting that governs them.

**Deleting a ride is a different action and reads as one.** Retention keeps the ride and drops its
detail; delete, from the ride's own screen, removes the ride entirely — so its distance and fuel
stop counting towards every total and record on Insights. The confirmation says that, because it
is the part a rider cannot see from the screen they are on, and the snapshot is rewritten so a
restore cannot bring a deleted ride back.

---

## S5 — Android's backup service carries a summary snapshot, not the database

The database cannot be backed up. Auto Backup allows an app 25 MB in total, and the sample series
is 0.137 MiB per riding hour — about 18 hours of riding at the old size, 180 at the new one, after
which backup fails entirely rather than degrading. A ride summary is 831 bytes, so 25 MB holds
roughly 31,000 of them: decades.

So `RideBackupStore` keeps `files/backup/rides.backup` current — every ride summary, rewritten on
each mutation — and the backup rules name that one file plus `app_settings.xml`. The snapshot is
written to a temporary file and renamed, because the backup service reads it on its own schedule
with no coordination with the app, and a half-written file would restore as a truncated history.

**Why a snapshot rather than splitting the database in two.** A separate summaries database would
restore automatically with no import step, but it would lose the foreign-key cascade that keeps
samples from outliving their ride, and it would still be a live SQLite file being copied without a
lock. A file the app writes atomically and controls the contents of has neither problem.

**Format: JSON, with named fields.** A snapshot outlives the build that wrote it, and names are
what make that survivable — a field added later is simply absent from an older file and picks up
its default, and an older build reading a newer file ignores what it does not recognise. A
positional format has to reject the whole file on a field count and lose the history instead. The
version number is reserved for changes names cannot absorb, such as a field changing units.

This was first written as a hand-rolled tab-separated format, on the grounds that `org.json` is a
stub in `android.jar` and could not be unit-tested. That was a testing limitation driving a
production decision, and the fix belonged in the tests: `testImplementation("org.json:json")` puts
the real artifact on the unit-test classpath, where it shadows the stub. It is test-only — on a
device the platform's implementation is used and nothing extra is packaged — and it also removed a
hand-rolled escaping routine, which was the format's largest surface for bugs.

A malformed entry costs that one ride. An unparseable file, or an unrecognised format or version,
restores nothing rather than misreading fields — the database stays empty and the app opens in its
first-run state.

**Restore imports only into an empty database.** That is the one state where importing cannot
destroy anything, so a rider who has ridden since the restore keeps what they recorded.

**What a restore does not bring back** is any ride's sample series, so restored rides have their
summary and route preview and no detail charts. There is no way around this inside a 25 MB budget.

---

## S6 — The backup rules are an allowlist

Naming even one `<include>` stops everything else being backed up. The Navigation API key lives in
shared preferences, and a rule that worked by listing exclusions would leak it the day somebody
added a preferences file and forgot to exclude it.

Listing the sensitive files as exclusions *as well* is not available as a safety net: lint rejects
an exclude that is not inside an included path, precisely because the include already excluded it.

Excluded by construction, and worth naming for the reader: `secure_navigation_settings` holds the
API key; `bike_identity`, `associated_bike`, `ble_protection_trust`, `bike_connection_demand` and
`connection_diagnostics` each describe one bond between this phone and one motorcycle, so
restoring them onto a different phone would describe a pairing that does not exist.

`disableIfNoEncryptionCapabilities` keeps ride history, which is location data, off any device
that cannot encrypt the backup with the rider's own lock screen.

**Not implemented: Drive, or any other cloud the app talks to itself.** Both `drive.appdata` and
`drive.file` are non-sensitive scopes, so they need only basic OAuth verification rather than a
security assessment — but an OAuth consent screen in Testing status issues refresh tokens that
expire after seven days, so an unattended backup would need re-consent weekly. Publishing it means
a privacy policy, a homepage and brand review, to gain automatic off-device storage of the sample
series and nothing else. Reopen if cross-device continuous sync of sample data is wanted.
