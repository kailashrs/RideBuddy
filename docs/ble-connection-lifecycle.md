# BLE connection lifecycle

Rules the connection stack is built around. Each one exists because breaking it produced a real
failure mode: an unbounded reconnect loop, a double-closed GATT handle, a lost pairing, or a
diagnostics screen that said "Last error: none" after a link drop.

## 1. One owner of the GATT link

`AndroidBikeConnection` is the only component that opens, retries, or retires a GATT session.

| Component | May do | May not do |
| --- | --- | --- |
| `AndroidBikeConnection` | open, retry, retire, schedule backoff | — |
| `BikeConnectionService` | route commands, publish the notification | call `disconnect()` on `Failed` |
| `BikeCompanionDeviceService` | report BLE presence edges | start a second GATT attempt |
| `MainActivity` | request one launch-time attempt | resume after the retry budget is spent |

The backoff schedule is 1, 2, 4, 8, 16, 30 seconds and then stops. Stopping publishes
`BikeConnectionState.Failed(retriesExhausted = true)`. Only a fresh `BLE_APPEARED` edge or an
explicit user retry may start again — `shouldAutoConnectOnLaunch` is what keeps an app relaunch
from silently handing the stack a new budget.

`EVENT_BT_CONNECTED` is classic-Bluetooth/HID connectivity. It is journaled and otherwise ignored.

`ConnectionAttemptTrigger` names which of these paths started an attempt, and the launch-time
attempt has its own value (`AppLaunch`). It is automatic in the sense that a manual disconnect
suppresses it, but no BLE appearance produced it, so it is not reported as `PresenceAppearance`.

## 2. A session is closed exactly once

Android keeps delivering callbacks from a `BluetoothGatt` after the app stops using it, so the
handle cannot be a nullable field. `GattSessionRegistry` owns the live session and remembers every
retired one; `GattSession.close()` is idempotent and reports whether it did the work. A callback
from a retired instance is recognised and dropped, never closed a second time.

The retired history is held as weak references rather than capped at a fixed length. A cap is not
safe here: once an entry is evicted, a late callback from that instance is no longer recognised, and
the fallback for an unrecognised handle is to close it — the second close the rule forbids. A weak
entry lasts exactly as long as the instance that could still call back.

## 3. Failures are classified before they are acted on

`gattFailureAction` decides what happens; `gattFailureCategory` decides what it is called.

| Condition | Action | Category |
| --- | --- | --- |
| Callback timeout | retire the link | `LinkLost` |
| `GATT_ERROR` (133), `GATT_FAILURE`, status 8 | retire the link | `LinkLost` |
| `GATT_INSUFFICIENT_AUTHENTICATION` / `_ENCRYPTION` / key size | retire the link | `AuthenticationRejected` |
| `GATT_CONNECTION_CONGESTED`, `GATT_BUSY` | bounded retry on the same link | `Transient` |
| Other ATT statuses (write/read not permitted, invalid length) | fail the operation | `Deterministic` |
| Synchronous `ERROR_GATT_WRITE_NOT_ALLOWED` | fail the operation, no retry | `Deterministic` |
| Synchronous `ERROR_GATT_WRITE_REQUEST_BUSY` | bounded retry | `Transient` |
| Synchronous `ERROR_DEVICE_NOT_BONDED`, adapter off | retire the link | `LocalPrecondition` |

Status 8 is deliberately *not* an authentication failure: Android reuses it for both
`GATT_INSUFFICIENT_AUTHORIZATION` and `GATT_CONNECTION_TIMEOUT`, and reporting a dropped link as an
auth failure is the confusion this table exists to prevent.

No callback timeout is tolerated. Android clears `mDeviceBusy` only from the callback, so an
operation that never answers leaves the GATT unable to perform any further characteristic or
descriptor work — completing it locally would only move the failure onto whatever runs next. The
identity reads that this exemption once existed for are gone entirely; see
`cluster-link-decisions.md` (D4).

## 4. Diagnostics keep the real failure

`BleDiagnosticsRecorder` is the only writer of `BleDiagnostics`. Teardown clears live link state but
never `lastFailure` or `lastSuccessfulLink`. A new automatic attempt against the same bike does not
overwrite the failure that caused it; only a new failure, a successful authentication, or a switch
to a different bike does. Reaching the end of the backoff records `suppressionReason` instead of
replacing the failure, so the screen never degrades to "Last error: none".

Every `ConnectionFailure` carries the attempt trigger, session id, retry count, operation and its
duration, link age, and the bond state read at failure time.

## 5. Stored protection acceptance is hard to lose

Acceptance is cleared only when:

- the bond is confirmed `BOND_NONE` or `BOND_BONDING` at connect time (a new pairing epoch), or
- the bike itself rejects the protocol (`ProtectionFailurePolicy.ClearAcceptance`).

Ordinary link loss, a controller timeout, status 133, a stale or duplicated protection callback, and
a required-profile failure all preserve it. There is no automatic `removeBond()` path anywhere.
