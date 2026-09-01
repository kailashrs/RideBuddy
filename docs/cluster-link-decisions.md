# Cluster link — decision record

Why RideBuddy's BLE and TFT behaviour is what it is: the evidence behind each choice, the
divergences from the OEM app that are deliberate, the alternatives that were rejected, and what
would reopen each decision.

This document owns *rationale*. It summarises wire facts only where a decision turns on them,
and deliberately does not duplicate exhaustive definitions from:

- `aprilia-rs457-ble-protocol.md` — wire facts: characteristics, payload layouts, observed traffic.
- `hardware-validation.md` — observable acceptance procedures for a parked bike.
- Code KDocs — local invariants, with a link back to a section here rather than a restated argument.

When a decision changes, change it here first. Comments that restate an argument in full are how
the two drift apart.

---

## Evidence base

| Source | What it settles |
| --- | --- |
| `apriliaindia` 1.3, decompiled (jadx) | OEM write types, replay loops, pacing, loop cadence, dirty-flag semantics, session/status values |
| AOSP `android-36.1` platform sources | `BluetoothGatt` busy semantics, `readRemoteRssi` behaviour, `CompanionDeviceService` callback contract |
| 18.2 s wire capture, live guidance | Observed cycle period, byte-identical duplicate pairs, text burst behaviour |
| App diagnostics export, 27 Aug 2026 | VIN persisted, cluster software never acquired |
| Stationary capture, two RideBuddy sessions and one OEM session | `8810`/`8910` read responses, indication timing, absence of OEM reads |

Anything below asserted without one of these behind it is marked as inference.

---

## OEM findings

Detail lives in the protocol document. Summarised here only where a decision depends on it.

**Write types are per characteristic, and RideBuddy matches them.** `8210`, `8230` and `8220` go
out as `WRITE_TYPE_NO_RESPONSE` via `r() → I()`; `8240` is `WRITE_TYPE_DEFAULT` via `t()/s() →
K()/J()`; session, status and clear are `DEFAULT` via `G()`. Confirmed from `model.b`'s fourth
constructor argument, which is passed straight to `writeCharacteristic(char, value, writeType)`.

**Pacing is 200 ms after every transmission inside `o1()`** — a `runBlocking { delay(200) }` in each
loop iteration, sourced from `looper.a.b = 200`. Control writes are *not* paced by it; they sit in
the outer `else if` chain at one per tick.

**The loop is scheduled at 1 Hz**, `postDelayed(this, 1000L)`. The ~2 s cycle measured on the wire is
emergent: `o1()` blocks for roughly 1.2 s before the reschedule runs.

**Dirty flags are event-driven, not value-driven** — except text. Across 18.2 s each of
`8210`/`8220`/`8230` carried exactly one distinct payload, re-asserted every cycle. `8240` is gated
on the instruction string changing, and appeared as a single burst.

**Session and status are coalesced state, not queued packets.** At most one control is written per
tick from an `else if` chain reading whatever `m()` currently holds, so a later `V(83)` silently
overwrites an unsent `V(80)`. `S(132)` has one call site, invoked per route request. `V(0)` is never
transmitted: the branch is guarded on `m() != 0`, so it suppresses the write, and preview teardown
pairs it with `W(true)` to raise the clear separately.

**`8750` is SR-family only.** `Y1()` gates its runnable behind `aVar.a().equals("SR_ID")`.

---

## Android framework findings

**A read with no callback wedges the connection.** `readCharacteristic()` sets `mDeviceBusy = true`
and clears it only in `onCharacteristicRead`, on an immediate `RemoteException`, or on a
connection-state change. Until one of those, every subsequent characteristic and descriptor
operation is refused. This is why a callback timeout must retire the GATT rather than be "tolerated"
locally, and why a hanging cosmetic read is not a cosmetic problem.

**`readRemoteRssi()` is outside that guard.** It touches `mDeviceBusy` nowhere; it checks
`mService`/`mClientRegistered` and forwards. Overlapping RSSI requests therefore do not contend
through Android's ATT guard. Contention at other layers has not been examined.

**Companion presence callbacks are edge-triggered.** `onDevicePresenceEvent` fires when presence
*changes*, not repeatedly while a device is in range.

**Blank identity values were already discarded.** `decodeBikeVin` rejects any non-printable byte,
and `updateIdentity` drops a blank version. A zero-filled read could therefore never overwrite a
good value — which is why the useless reads produced no visible symptom.

**Background activity launches need both opt-ins** — creator mode when constructing the
`PendingIntent`, sender mode on the options bundle passed to `send()`. `ActivityOptions` does not
exempt a direct `startActivity()`.

---

## Decisions

<a id="d1"></a>

### D1 — Single-pass writes, no replay

**Decision.** No unconditional second pass. On the successful path each logical frame is dispatched
once; GATT-level retries and the bridge's bounded recovery after a reported failure remain. The
replay machinery has been removed rather than left as a dormant switch.

**Rationale.** Single-pass halves navigation-write occupancy of the shared operation queue. That is
the factual benefit; whether it improves link reliability is unproven. Adopting the OEM's second
pass would be an argument from fidelity, not from an observed failure — nothing has shown a dropped
field a second copy would have rescued. The machinery was inert before removal (`ClusterReplayCount`
was 1, so both branches of the pass count agreed), so it read as a live tuning knob while doing
nothing.

**Weakest for the unacknowledged fields.** `8210`, `8220` and `8230` are ATT Write Commands, so
nothing at any layer the phone controls can observe whether the cluster consumed them. `8240` is
acknowledged in both implementations, so the argument is stronger there.

**Reversal criteria.** A parked test showing maneuver, distance, speed limit or text failing to
appear or updating late. Reintroduce for the unacknowledged three first; only extend to `8240` if
text still drops despite being acknowledged.

<a id="d2"></a>

### D2 — Conservative pacing floor

**Decision.** 200 ms as a completion-to-next-dispatch floor across batch boundaries, applied only to
`8210`/`8220`/`8230`/`8240`.

**Rationale.** True start-to-start is not implementable in the bridge: `writeAndAwait()` enters the
shared operation queue first, so the bridge does not control when the GATT write actually starts and
would under-space whenever the queue is contended. The floor over-spaces slightly instead, which is
the safe direction. The cost is small — the three data fields are unacknowledged, so their callbacks
return without a peer round trip.

**Revisit when.** A capture shows effective spacing materially above 200 ms with a field visibly
updating late as a result.

**Not adopted.** The OEM's ~2.2 s emergent full-cycle cadence. That comes from its 1 Hz timer plus a
blocking burst; our drain is guidance-driven and single-pass writing does not reproduce it either
way.

<a id="d3"></a>

### D3 — Session and status as coalesced state

**Decision.** Dirty state variables with phase currency, not queued immutable frames. Preview is
`83` only; route request marks `80` and `132` with `132` once per request; guidance replaces with
`87` and never regenerates `83`; preview cancellation sets `0` *and* enqueues the clear; guidance
stop is clear only.

**Rationale.** Modelling session entry as an ordered packet sequence is what creates the
`FrameKey(8260, 0)` collision, where two session values share a coalescing key and the second is
discarded. State with currency means two session values are never queued together.

**Status is independent of session coalescing.** A superseded `80` must still emit its pending `132`
once — they are separate dirty flags in the OEM (`R` and `U`) and the else-if chain merely defers
status a tick.

**Deliberately stricter than the OEM.** Teardown clears both flags before the clear packet, so no
delayed `132` or `87` lands afterwards. The OEM never clears `R` on preview teardown.

**Revisit when.** A parked test shows the cluster requires a distinct preview-to-guidance transition,
or that one-control-per-tick entry behaves differently from a batched one.

<a id="d4"></a>

### D4 — Identity reads are deleted; the values arrive only as indications

**Decision.** No reads of `8810` or `8910`. Both stay subscribed, and their values are taken from
indications whenever the cluster sends them. The suppression policy and the Diagnostics probe built
to make the reads safe are deleted with them.

**Evidence.** A stationary capture settles this on the wire. Both RideBuddy sessions did
CCCD-enable then an immediate read, and both characteristics answered promptly with a zero-filled
buffer — `8810` eight bytes of zeros, `8910` nineteen. The real values arrived later and only ever
as indications: `@MET0004BB6F002938#` on `8910`, `1.3.6` on `8810`. The OEM app, in its own window,
wrote both CCCDs and never issued a `READ_REQ` at all; across the whole capture not one of the
eighteen read responses is an OEM read of either characteristic.

**The reads were inert rather than harmful, which is why nothing looked broken.** A zero-filled
`8910` is rejected by `decodeBikeVin` as non-printable, and a zero-filled `8810` decodes to an empty
string that `updateIdentity` discards. Their entire cost was an ATT round trip per connection plus
the risk that gave rise to all the machinery now removed: a read whose callback never arrives leaves
Android's `mDeviceBusy` set and wedges the connection.

**This also settles the commit history that motivated them.** `0d066c2` added the reads because VIN
and cluster software were not populating, and `ac1e466` restored a hybrid after `7e06f68` removed
them. Neither can have worked as believed: the reads return nothing. What populates the values is
the indication arriving, on the cluster's own schedule — `8910` about seven seconds after subscribe
in this capture, `8810` roughly ten minutes in — though a later 955-second session on the same
motorcycle ended with the software version still unread, so ten minutes is a lower bound and not a
period.

**Authentication never depended on them.** Valid telemetry is accepted as verification evidence and
arrives at about 4 Hz, well inside the 8 s deadline. Verification therefore arms as soon as the
required subscriptions are ready and needs nothing from the identity characteristics.

**Accepted consequence.** The cluster software version can be absent from diagnostics for minutes
into a session, until its indication arrives. The reads never shortened that wait — the diagnostics
export recording "cluster software never acquired" is explained by the indication simply not having
arrived in that window.

**Reversal criteria.** A capture showing a `READ_RSP` on either characteristic that carries a real
value. One zero-filled response is not a firmware quirk to design around; a populated one would mean
the read is worth issuing after all.

<a id="d5"></a>

### D5 — Ride recording pauses across non-terminal states

**Decision.** Finalize only on `Disconnected` and `Failed`. Pause on `Connecting` and
`Authenticating`. The first resumed frame contributes zero distance, and `finishRide`'s fallback
timestamp is the last telemetry wall-clock time rather than "now".

**Rationale.** Reconnection passes through `Connecting` *and* `Authenticating`, so `!is Connected`
splits a ride on any transient drop. Never interpolating across missing telemetry under-reports
rather than inventing distance, consistent with how gaps over 2.5 s are already handled. The
timestamp matters because the fallback applies on every path without a confirmed stop — otherwise a
pause through the ~61 s backoff budget adds a minute of phantom duration.

**`Scanning` has been removed.** It was carried here and in five other `when` branches purely
defensively, and nothing in the codebase ever assigned it — a state no code path can produce is not
a case to handle. The branches went with it.

**Revisit when.** Recorded rides show implausible distance or duration around a reconnect.

<a id="d6"></a>

### D6 — No RSSI in-flight guard

**Decision.** Log the `readRemoteRssi()` return value and non-success callback status. Do **not** add
an in-flight guard.

**Rationale.** A guard without a stale timeout turns one lost callback into permanently stopped
polling, and the pair is more machinery than the problem warrants. The guard's value is low anyway,
since RSSI requests do not contend through Android's `mDeviceBusy` ATT guard. (That is the proven
statement; contention at other layers has not been examined either way.)

**Revisit when.** Diagnostics show RSSI callbacks routinely failing or never arriving.

---

## Rejected claims

Raised during review and dismissed with reasons. Recorded so they are not re-raised.

| Claim | Why rejected |
| --- | --- |
| The RSSI bypass interferes with characteristic operations | The bypass is real, but `readRemoteRssi()` touches `mDeviceBusy` nowhere in AOSP — it does not contend through Android's ATT guard |
| A failed boolean read misclassified as Busy | A documented deliberate choice with bounded consequences |
| Speed/RPM/throttle should be time-weighted | Telemetry is near-uniform at ~4 Hz; sample and time-weighted means agree well under display precision |
| CSV formula injection | The per-sample export has no text fields; only geocoder area names could carry one |
| Hazard settings copy overstates support | Hedged as "supported route warnings", and the README documents the limitation |
| Protocol doc's "1 Hz loop" is wrong | It is correct — the ~2 s cycle is emergent from a blocking burst |
| Protocol doc's "8750 not written" is wrong | It is correct — `Y1()` gates it behind `SR_ID` |
| Identity values already arrive via subscriptions | Correct, but not for the reason first given. It was withdrawn as unevidenced structural inference; the stationary capture later established it on the wire — see [D4](#d4) |
| The identity reads were fixing a real gap | The commit history says so, but the reads return zero-filled buffers. Whatever populated the values was the indication, not the read |

---

## Hardware unknowns

Questions the source cannot answer. Each needs a parked bike.

- Does the cluster require passing through preview `83` before `87` on a direct start?
- Does one-control-per-tick session entry behave differently from a batched entry?
- Do any fields go missing under single-pass writing? (See [D1](#d1) reversal criteria.)
- What prompts the `8810` indication? It arrived about ten minutes into one capture and not at all
  in a later 955-second session, so it is not on a fixed timer. Far later than
  `8910`, and nothing establishes whether that is a timer, an event, or coincidence.
