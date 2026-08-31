package com.spaceboy.ridebuddy.core.tft

import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.spaceboy.ridebuddy.ble.BleCharacteristics
import com.spaceboy.ridebuddy.data.AppSettings
import com.spaceboy.ridebuddy.data.TftTextMode
import com.spaceboy.ridebuddy.domain.BikeConnection
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeWrite
import com.spaceboy.ridebuddy.domain.BikeWriteMode
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Whether the last guidance update should be redrawn on the cluster.
 *
 * All three conditions matter: replaying while navigation is not running would put a turn
 * on a display that should be blank, replaying with output disabled would defeat the
 * rider's choice, and there is nothing to replay without a stored update.
 */
internal fun shouldReplayTftNavigation(
    navigationStarted: Boolean,
    outputEnabled: Boolean,
    hasLastInfo: Boolean,
): Boolean = navigationStarted && outputEnabled && hasLastInfo

/**
 * Drives the cluster's navigation display from the turn-by-turn guidance feed.
 *
 * The problem this solves is a mismatch of rates and reliability. Guidance updates arrive
 * roughly once a second as a complete snapshot, while each display field is a separate GATT
 * write that takes real time and is paced 200 ms from the last. Sending every field of every
 * update would queue faster than it drains and show the rider a turn they passed several
 * seconds ago.
 *
 * So writes go through two queues. `controlBatches` is FIFO and carries ordered sequences
 * that only make sense in order — entering a session, an alert, the teardown. `latestData`
 * is a coalescing map keyed by display field, where a newer value for a field replaces the
 * pending older one. Under load the rider gets the current distance rather than a backlog.
 * A batch records which queue it came from, so a failed one is retried where it belongs.
 *
 * Correctness across those queues rests on generation counters, one each for the session,
 * the active alert, and ownership of the text rows. Every batch records what it was built
 * for and is discarded rather than written once that has moved on — which is what stops a
 * batch queued for a route that has since ended from painting stale guidance over a display
 * that has moved on. Currency is rechecked *between* frames of a batch as well as before it,
 * since a pacing delay is long enough for ownership to change underneath one. Text rows are
 * additionally keyed by row number, so a two-row destination replacing a one-row destination
 * does not leave the old second row on screen, and a row whose exact bytes were already
 * acknowledged is not rewritten at all.
 *
 * The state is shared between the guidance feed, the connection, settings, and alert
 * timers, so it is lock-guarded. Suspending work never happens under the lock: a batch is
 * taken, released, and only then written.
 */
class TftNavigationBridge(
    private val connection: BikeConnection,
    private val settings: StateFlow<AppSettings>,
    private val scope: CoroutineScope,
) {
    private val queueLock = Any()
    private val wakeWorker = Channel<Unit>(Channel.CONFLATED)
    private val controlBatches = ArrayDeque<WriteBatch>()
    private val latestData = LinkedHashMap<FrameKey, Frame>()
    private var acceptingUpdates = false
    private var sessionActive = false
    private var textAlertActive = false
    private var textAlertMessage: String? = null
    private var transportReady = false
    private var outputEnabled = settings.value.tftNavigationOutputEnabled
    private var textOwnerGeneration = 0L
    private val acknowledgedText = mutableMapOf<Int, ByteArray>()
    private var lastPacedWriteAtNanos: Long? = null
    private var outputSuspended = false
    private var consecutiveWriteFailures = 0
    private var pendingClear = false
    private var pendingSession: Int? = null
    private var pendingStatus: Int? = null
    private var routeStatusMarked = false
    private var sessionGeneration = 0L
    private var arrivalPendingGeneration: Long? = null
    private var textAlertGeneration = 0L
    private var lastInfo: NavInfo? = null
    private var destinationLabel: String = ""
    private var previewActive = false

    init {
        scope.launch {
            // BleDiagnostics is republished on every telemetry frame; only the readiness edge
            // matters here, so the snapshot is reduced before it can wake this bridge.
            combine(connection.connectionState, connection.diagnostics) { state, diagnostics ->
                state is BikeConnectionState.Connected && diagnostics.authenticated
            }
                .distinctUntilChanged()
                .collect(::refreshTransportAvailability)
        }
        // Single worker draining both queues. Control batches go first: they are ordered
        // sequences whose steps depend on each other, while data frames are independent
        // snapshots that are correct whenever they land.
        scope.launch {
            for (ignored in wakeWorker) {
                while (true) {
                    val batch = synchronized(queueLock) {
                        if (!transportReady || outputSuspended) {
                            null
                        } else {
                            // Cluster-driver precedence: the owed control state first, then ordered
                            // text sequences, then whatever coalesced data is freshest.
                            nextControlStateBatchLocked()
                                ?: controlBatches.pollFirst()
                                ?: nextDataBatchLocked()
                        }
                    } ?: break
                    if (!isCurrent(batch)) continue
                    val result = writeBatch(batch)
                    when (result) {
                        BatchWriteResult.Stale -> continue
                        BatchWriteResult.Failed -> {
                            val failures = synchronized(queueLock) { ++consecutiveWriteFailures }
                            // A cluster that keeps rejecting writes must not be retried forever:
                            // each attempt costs a full GATT operation timeout and can retire the
                            // link. Past the limit, output is suspended outright rather than
                            // dropped-and-retried, which would rebuild the same failing sequence
                            // on the next guidance update.
                            if (failures >= MaxConsecutiveWriteFailures) {
                                synchronized(queueLock) { suspendOutputLocked() }
                                break
                            }
                            restore(batch)
                            delay((FailedWriteRetryMillis * failures).milliseconds)
                            wakeWorker.trySend(Unit)
                            break
                        }
                        BatchWriteResult.Completed ->
                            synchronized(queueLock) { consecutiveWriteFailures = 0 }
                    }
                    // The arrival display only starts counting down once the bike has it.
                    batch.arrivalGeneration
                        ?.takeIf { result == BatchWriteResult.Completed }
                        ?.let(::scheduleArrivalReset)
                }
            }
        }
        scope.launch {
            settings
                .map { appSettings -> appSettings.tftNavigationOutputEnabled }
                .distinctUntilChanged()
                .collect(::setOutputEnabled)
        }
    }

    /**
     * Begins accepting guidance for a new route.
     *
     * A route started while the arrival display is still up gets an explicit clear first,
     * because the cluster would otherwise draw the new route's first turn underneath the
     * previous route's "Arrived" banner.
     */
    fun start(destination: String = "") {
        val queuedReset = synchronized(queueLock) {
            destinationLabel = destination
            val wasShowingArrival = arrivalPendingGeneration != null
            arrivalPendingGeneration = null
            if (wasShowingArrival) {
                sessionActive = false
                controlBatches.clear()
                latestData.clear()
                markClearLocked()
            }
            sessionGeneration++
            acceptingUpdates = true
            previewActive = false
            // A route has been requested. The status word is emitted once for it; the transitional
            // session may well be overwritten by 87 before the worker drains, which is the same
            // coalescing the cluster's own driver does. Nothing is marked while output is off —
            // the rider's opt-in gates the display, not just the guidance updates.
            if (outputEnabled) {
                markSessionLocked(SessionRouteRequested)
                markRouteStatusOnceLocked()
            }
            outputEnabled && transportReady
        }
        if (queuedReset) wakeWorker.trySend(Unit)
    }

    /**
     * Turns one guidance snapshot into display writes.
     *
     * The first update of a route also carries the session-entry sequence, since there is
     * no separate signal that guidance has begun. The session generation is captured before
     * the packets are built and rechecked before they are queued: building them is not
     * instantaneous, and the route can end underneath it.
     */
    fun accept(info: NavInfo) {
        val generation = synchronized(queueLock) {
            if (!acceptingUpdates) return
            lastInfo = info
            if (!outputEnabled) return
            if (!sessionActive) {
                // Guidance is running: the session becomes 87 outright. No 83 is generated here —
                // that value belongs to the preview screen, and a route started without one simply
                // never passes through it.
                markRouteStatusOnceLocked()
                markSessionLocked(SessionGuidanceActive)
                sessionActive = true
            }
            sessionGeneration
        }

        val dataFrames = mutableListOf<Frame>()
        val current = info.currentStep
        if (current != null) {
            val next = info.remainingSteps.firstOrNull()
            val maneuverDistance = info.distanceToCurrentStepMeters ?: 0
            dataFrames += Frame(
                BleCharacteristics.NavigationManeuver,
                TftPacketEncoder.maneuver(
                    current = current.maneuver,
                    next = next?.maneuver ?: 0,
                    roundaboutExit = current.roundaboutTurnNumber ?: 0,
                    distanceMetres = maneuverDistance,
                ),
            )
            val destinationSeconds = info.timeToFinalDestinationSeconds ?: 0
            dataFrames += Frame(
                BleCharacteristics.NavigationTrip,
                TftPacketEncoder.trip(
                    arrivalEpochMillis = System.currentTimeMillis() + destinationSeconds * 1_000L,
                    destinationDistanceMetres = info.distanceToFinalDestinationMeters ?: 0,
                    maneuverDistanceMetres = maneuverDistance,
                ),
            )
            // The road name, not the instruction sentence. The row is sixteen characters and the
            // pictogram already says which way to turn, so spending it on "Head east on Gar"
            // truncates away the only thing the rider cannot read off the arrow. The OEM sends
            // the step's road name here for the same reason.
            val instruction = current.fullRoadName?.takeUnless(String::isBlank)
                ?: current.fullInstructionText.orEmpty().roadNameOrSelf()
            // Compact keeps the instruction banner and drops the destination lines, which is the
            // half a rider glances at.
            val compact = settings.value.tftTextMode == TftTextMode.Compact
            val destinationText = if (compact) "" else synchronized(queueLock) { destinationLabel }
            if (!synchronized(queueLock) { textAlertActive }) {
                TftPacketEncoder.guidanceTextRows(destinationText, instruction).forEach { payload ->
                    val frame = Frame(BleCharacteristics.NavigationText, payload)
                    // The cluster holds what it was last given, so a row whose bytes are already
                    // on screen is a write with nothing to say. Rows 0 and 1 carry the
                    // destination, which does not change for the whole route.
                    val unchanged = synchronized(queueLock) {
                        acknowledgedText[frame.key().row]?.contentEquals(payload) == true
                    }
                    if (!unchanged) dataFrames += frame
                }
            }
        }

        synchronized(queueLock) {
            if (!acceptingUpdates || !sessionActive || sessionGeneration != generation ||
                !outputEnabled
            ) return
            // Suspended: the phase, session and latest guidance above stay current, but nothing is
            // enqueued, so resuming rebuilds the current state instead of draining a stale backlog.
            if (outputSuspended) return
            val framesToQueue = if (textAlertActive) {
                dataFrames.filterNot { it.characteristic == BleCharacteristics.NavigationText }
            } else dataFrames
            if (framesToQueue.any { it.characteristic == BleCharacteristics.NavigationText }) {
                latestData.keys.removeAll { it.characteristic == BleCharacteristics.NavigationText }
            }
            framesToQueue.forEach { frame ->
                val key = frame.key()
                latestData.remove(key)
                latestData[key] = frame
            }
        }
        wakeWorker.trySend(Unit)
    }

    /**
     * Stages a destination so the cluster draws its **GO** prompt and the rider can start
     * the route from the handlebar.
     *
     * The staged state is session [SessionRouteReady] carrying the destination text and
     * nothing else; guidance moves on to [SessionGuidanceActive] once it actually begins.
     * Passing a blank destination takes the cluster back out of the state.
     *
     * A route that is already running owns the display, so staging is refused rather than
     * allowed to disturb it.
     */
    fun previewDestination(destination: String) {
        val frames = if (destination.isBlank()) {
            emptyList()
        } else {
            TftPacketEncoder.guidanceTextRows(destination, "")
                .map { payload -> Frame(BleCharacteristics.NavigationText, payload) }
        }
        val queued = synchronized(queueLock) {
            if (!outputEnabled || outputSuspended) return
            // A route that is already running owns the display; a staged one must not disturb it.
            if (acceptingUpdates || sessionActive) return
            if (destination.isBlank()) {
                if (!previewActive) return
                previewActive = false
                // Session 0 alone only suppresses the write; the clear is what takes GO off.
                markSessionLocked(SessionNone)
                markClearLocked()
                transportReady
            } else {
                destinationLabel = destination
                previewActive = true
                markSessionLocked(SessionRouteReady)
                controlBatches += WriteBatch(
                    frames = frames,
                    priority = false,
                    textGeneration = textOwnerGeneration,
                )
                transportReady
            }
        }
        if (queued) wakeWorker.trySend(Unit)
    }

    /**
     * Shows that the route is being recalculated.
     *
     * This is a pictogram and banner change, not a session change: the cluster stays in
     * guidance, and moving it elsewhere would tear the display down and rebuild it for
     * something that resolves in a second or two.
     */
    fun rerouting() {
        val destination = synchronized(queueLock) { destinationLabel }
        val pictogram = Frame(
            BleCharacteristics.NavigationManeuver,
            TftPacketEncoder.pictogram(TftPacketEncoder.PictogramRecalculating),
        )
        val textFrames = TftPacketEncoder.guidanceTextRows(destination, RecalculatingBanner)
            .map { payload -> Frame(BleCharacteristics.NavigationText, payload) }
        val queued = synchronized(queueLock) {
            if (!sessionActive || !outputEnabled || outputSuspended) false else {
                // The pictogram carries no text ownership, so it stays visible even if an alert
                // takes the rows before the banner lands. Only the banner is discardable.
                controlBatches += WriteBatch(
                    frames = listOf(pictogram),
                    priority = false,
                    sessionGeneration = sessionGeneration,
                )
                // A route hazard is published immediately before rerouting(). Its alert owns the
                // text rows for the next few seconds; only the recalculation pictogram may join it.
                if (!textAlertActive) {
                    controlBatches += WriteBatch(
                        frames = textFrames,
                        priority = false,
                        sessionGeneration = sessionGeneration,
                        textGeneration = textOwnerGeneration,
                    )
                }
                true
            }
        }
        if (queued) wakeWorker.trySend(Unit)
    }

    /** Shows the arrival state briefly, then clears the cluster without racing a newer route. */
    fun arrivedAndStop() {
        val generation = synchronized(queueLock) {
            if (!sessionActive || !outputEnabled) return@synchronized null
            sessionGeneration++
            val arrivalGeneration = sessionGeneration
            acceptingUpdates = false
            textAlertGeneration++
            textAlertActive = false
            textAlertMessage = null
            lastInfo = null
            latestData.clear()
            controlBatches.clear()
            arrivalPendingGeneration = arrivalGeneration
            // Arrival stays in the guidance session rather than moving the cluster to some
            // other state; only the banner changes. The banner is what makes this
            // self-clearing: the display says "Arrived", then wipes itself a couple of
            // seconds later, so a rider who has just pulled up has nothing to dismiss.
            controlBatches += WriteBatch(
                frames = TftPacketEncoder.guidanceTextRows(destinationLabel, ArrivedBanner)
                    .map { payload -> Frame(BleCharacteristics.NavigationText, payload) },
                priority = true,
                sessionGeneration = arrivalGeneration,
                arrivalGeneration = arrivalGeneration,
                textGeneration = textOwnerGeneration,
            )
            arrivalGeneration
        }
        if (generation == null) {
            stop()
            return
        }
        wakeWorker.trySend(Unit)
    }

    /**
     * Updates the posted speed limit. Coalescing, so a burst of updates leaves only the
     * newest value queued.
     */
    fun speedLimit(kph: Int) {
        if (synchronized(queueLock) { sessionActive && acceptingUpdates }) {
            queueLatest(Frame(BleCharacteristics.NavigationSpeedLimit, TftPacketEncoder.speedLimit(kph)))
        }
    }

    /**
     * Redraws the most recent guidance update. Used to restore the display after something
     * transient — an alert, a call — has overwritten it.
     */
    fun republishLast() {
        // A forced restoration: the caller is telling us the display no longer holds what we think
        // it does, so suppression must not decide the text is already there.
        val info = synchronized(queueLock) {
            invalidateTextCacheLocked()
            lastInfo
        }
        info?.let(::accept)
    }

    /**
     * Shows a short alert on the navigation text rows, returning whether it was queued.
     *
     * With no route running, this opens a temporary session purely to have somewhere to
     * draw, and [dismissTextAlert] closes it again. While a route *is* running the alert
     * borrows its text rows: guidance text frames are dropped for the duration, so the two
     * do not fight over the same rows, and guidance is republished on dismissal.
     */
    fun presentTextAlert(message: String): Boolean {
        if (message.isBlank()) return false
        if (connection.connectionState.value !is BikeConnectionState.Connected || !connection.diagnostics.value.authenticated) {
            return false
        }
        synchronized(queueLock) {
            if (!outputEnabled || outputSuspended) return false
            val startsTemporarySession = !sessionActive
            if (startsTemporarySession) sessionActive = true
            textAlertGeneration++
            textOwnerGeneration++
            invalidateTextCacheLocked()
            val alertGeneration = textAlertGeneration
            textAlertActive = true
            textAlertMessage = message
            controlBatches.removeAll { it.alertGeneration != null }
            latestData.keys.removeAll { it.characteristic == BleCharacteristics.NavigationText }
            if (startsTemporarySession) {
                // The alert needs somewhere to draw, which means a live guidance session.
                markRouteStatusOnceLocked()
                markSessionLocked(SessionGuidanceActive)
            }
            controlBatches.addLast(
                WriteBatch(
                    frames = TftPacketEncoder.displayTextRows(message)
                        .map { payload -> Frame(BleCharacteristics.NavigationText, payload) },
                    priority = true,
                    alertGeneration = alertGeneration,
                    sessionGeneration = sessionGeneration,
                    textGeneration = textOwnerGeneration,
                ),
            )
        }
        wakeWorker.trySend(Unit)
        return true
    }

    /** Removes an alert. The priority coordinator republishes guidance when appropriate. */
    fun dismissTextAlert() {
        val dismissal = synchronized(queueLock) {
            if (!textAlertActive) return
            textAlertGeneration++
            textOwnerGeneration++
            invalidateTextCacheLocked()
            textAlertActive = false
            textAlertMessage = null
            controlBatches.removeAll { it.alertGeneration != null }
            latestData.keys.removeAll { it.characteristic == BleCharacteristics.NavigationText }
            if (!acceptingUpdates && sessionActive) {
                sessionActive = false
                lastInfo = null
                markClearLocked()
                TextAlertDismissal(queuedShutdown = true)
            } else {
                TextAlertDismissal(guidanceToRepublish = lastInfo)
            }
        }
        dismissal.guidanceToRepublish?.let(::accept)
        if (dismissal.queuedShutdown || dismissal.guidanceToRepublish != null) wakeWorker.trySend(Unit)
    }

    /** Ends the session and clears the cluster. */
    fun stop() {
        if (synchronized(queueLock) { stopLocked() }) wakeWorker.trySend(Unit)
    }

    /**
     * Clears the arrival display, unless a new route has started in the meantime — the
     * generation check is what makes the delayed clear safe against that race.
     */
    private fun stopAfterArrival(generation: Long) {
        val shutdown = synchronized(queueLock) {
            if (arrivalPendingGeneration != generation ||
                sessionGeneration != generation || acceptingUpdates
            ) return
            stopLocked()
        }
        if (shutdown) wakeWorker.trySend(Unit)
    }

    private fun scheduleArrivalReset(generation: Long) {
        scope.launch {
            delay(ArrivalDisplayMillis.milliseconds)
            stopAfterArrival(generation)
        }
    }

    /**
     * Shared teardown. Invalidates both generations, drops every queue, discards any pending
     * session or status so nothing lands after the clear, and marks the clear if anything could
     * still be showing. Returns whether the worker has something to drain.
     */
    private fun stopLocked(): Boolean {
        // Session 80/status 132 can already be on the cluster before the first NavInfo makes
        // sessionActive true. A route cancelled or rejected during that window still needs clear.
        val shouldShutdown = sessionActive || (acceptingUpdates && outputEnabled && transportReady)
        sessionGeneration++
        arrivalPendingGeneration = null
        acceptingUpdates = false
        textAlertGeneration++
        sessionActive = false
        textAlertActive = false
        textAlertMessage = null
        lastInfo = null
        latestData.clear()
        controlBatches.clear()
        pendingSession = null
        pendingStatus = null
        routeStatusMarked = false
        if (shouldShutdown) markClearLocked()
        return pendingClear && transportReady
    }

    private fun queueLatest(frame: Frame) {
        val queued = synchronized(queueLock) {
            if (!sessionActive || !outputEnabled || outputSuspended) false else {
                val key = frame.key()
                latestData.remove(key)
                latestData[key] = frame
                true
            }
        }
        if (queued) wakeWorker.trySend(Unit)
    }

    /**
     * Applies the rider's opt-in setting.
     *
     * Turning output off must clear the display: the cluster holds whatever was last
     * written to it indefinitely, so simply stopping would leave a frozen turn on screen.
     * Turning it back on mid-route replays the latest guidance rather than waiting for the
     * next update.
     */
    private fun setOutputEnabled(enabled: Boolean) {
        var replay: NavInfo? = null
        val queuedReset = synchronized(queueLock) {
            if (outputEnabled == enabled) return
            outputEnabled = enabled
            if (!enabled) {
                sessionGeneration++
                arrivalPendingGeneration = null
                textAlertGeneration++
                // Anything that could still be showing on the cluster has to be cleared.
                if (sessionActive || textAlertActive || lastInfo != null) markClearLocked()
                // Anything marked before the rider switched output off must not still go out.
                pendingSession = null
                pendingStatus = null
                routeStatusMarked = false
                sessionActive = false
                textAlertActive = false
                textAlertMessage = null
                controlBatches.clear()
                latestData.clear()
                pendingClear && transportReady
            } else {
                // Switching output back on is the clearest retry request a rider can give, so it
                // lifts a suspension that would otherwise wait for a whole new transport.
                outputSuspended = false
                consecutiveWriteFailures = 0
                // outputEnabled is true throughout this branch, so only the other two decide.
                if (acceptingUpdates && lastInfo != null) replay = lastInfo
                pendingClear && transportReady
            }
        }
        replay?.let(::accept)
        if (queuedReset || replay != null) wakeWorker.trySend(Unit)
    }

    /**
     * Reacts to the link coming up or going down.
     *
     * The cluster forgets its navigation session on disconnect, so a reconnect cannot
     * resume: queued frames are discarded, the session is marked inactive, and the whole
     * sequence is rebuilt from scratch. Recovery work is collected under the lock and
     * performed after it, since both paths re-enter methods that take the same lock.
     */
    private fun refreshTransportAvailability(ready: Boolean) {
        val recovery = synchronized(queueLock) {
            when {
                ready && !transportReady -> {
                    transportReady = true
                    // A new transport generation: the failure budget and any suspension belong to
                    // the link that earned them, not to this one.
                    outputSuspended = false
                    consecutiveWriteFailures = 0
                    invalidateTextCacheLocked()
                    lastPacedWriteAtNanos = null
                    sessionActive = false
                    controlBatches.clear()
                    latestData.clear()
                    val pendingArrival = arrivalPendingGeneration
                    if (pendingArrival != null && outputEnabled) {
                        sessionActive = true
                        markRouteStatusOnceLocked()
                        markSessionLocked(SessionGuidanceActive)
                        controlBatches += WriteBatch(
                            frames = TftPacketEncoder.guidanceTextRows(destinationLabel, ArrivedBanner)
                                .map { payload -> Frame(BleCharacteristics.NavigationText, payload) },
                            priority = true,
                            sessionGeneration = pendingArrival,
                            arrivalGeneration = pendingArrival,
                            textGeneration = textOwnerGeneration,
                        )
                    }
                    ConnectionRecovery(
                        lastInfo = lastInfo.takeIf {
                            pendingArrival == null && shouldReplayTftNavigation(
                                navigationStarted = acceptingUpdates,
                                outputEnabled = outputEnabled,
                                hasLastInfo = lastInfo != null,
                            )
                        },
                        textAlertMessage = textAlertMessage.takeIf { outputEnabled },
                        previewDestination = destinationLabel.takeIf {
                            pendingArrival == null && previewActive && !acceptingUpdates && outputEnabled
                        },
                    )
                }

                !ready && transportReady -> {
                    transportReady = false
                    sessionActive = false
                    controlBatches.clear()
                    latestData.clear()
                    if (arrivalPendingGeneration != null) {
                        sessionGeneration++
                        arrivalPendingGeneration = sessionGeneration
                    }
                    null
                }

                else -> null
            }
        }
        recovery?.previewDestination?.let(::previewDestination)
        recovery?.textAlertMessage?.let(::presentTextAlert)
        recovery?.lastInfo?.let(::accept)
        if (ready) wakeWorker.trySend(Unit)
    }

    /**
     * Stops writing to the cluster after repeated failures, until a fresh transport generation.
     *
     * The queues are dropped and the session is forgotten, so nothing half-written is believed to
     * be on screen; the clear is marked so the display is tidied the moment output resumes. State
     * marking continues while suspended — the phase, session, destination and latest guidance all
     * stay current — but nothing is enqueued, so a resume reconstructs the *current* phase rather
     * than replaying a backlog that accumulated while the link was failing.
     */
    private fun suspendOutputLocked() {
        outputSuspended = true
        invalidateTextCacheLocked()
        consecutiveWriteFailures = 0
        controlBatches.clear()
        latestData.clear()
        sessionActive = false
        markClearLocked()
    }

    /** The freshest pending value for one display field, or null when the map is empty. */
    private fun nextDataBatchLocked(): WriteBatch? {
        val entry = latestData.entries.firstOrNull() ?: return null
        latestData.remove(entry.key)
        return WriteBatch(
            frames = listOf(entry.value),
            priority = false,
            coalescing = true,
            sessionGeneration = sessionGeneration,
            textGeneration = textOwnerGeneration
                .takeIf { entry.value.characteristic == BleCharacteristics.NavigationText },
        )
    }

    /**
     * Marks the session the cluster should be showing. Last write wins: a value assigned and
     * superseded before the worker drains is simply never sent, which is what the cluster's own
     * driver does. `0` is a sentinel meaning "no session to send" — it suppresses the write rather
     * than being transmitted, so cancelling a preview needs [markClearLocked] as well to actually
     * take GO off the display.
     */
    private fun markSessionLocked(value: Int) {
        pendingSession = value
    }

    /**
     * Marks the status word. Independent of session coalescing: a superseded session value must not
     * take a pending status with it, because they are separate dirty flags and the status is
     * emitted once per route request.
     */
    private fun markStatusLocked(value: Int) {
        pendingStatus = value
    }

    /** Marks the status for this route, if it has not already been marked. */
    private fun markRouteStatusOnceLocked() {
        if (routeStatusMarked) return
        routeStatusMarked = true
        markStatusLocked(StatusNavigationActive)
    }

    /**
     * Marks the display teardown: the clear packet plus a zeroed speed limit.
     *
     * Both pending controls are dropped first, so no delayed session or status write can land
     * after the clear and put the cluster back into a state the route has left. The speed limit
     * needs zeroing separately — the clear packet does not touch it, and a limit is only ever
     * learned while the rider is over it, so the last one would otherwise persist.
     */
    private fun markClearLocked() {
        invalidateTextCacheLocked()
        pendingClear = true
        pendingSession = null
        pendingStatus = null
        routeStatusMarked = false
    }

    /**
     * The next control write owed to the cluster, in the cluster driver's own precedence: clear,
     * then session, then status, at most one per pass.
     */
    private fun nextControlStateBatchLocked(): WriteBatch? {
        if (pendingClear) {
            pendingClear = false
            return WriteBatch(
                frames = listOf(
                    Frame(BleCharacteristics.NavigationClear, TftPacketEncoder.clear()),
                    Frame(BleCharacteristics.NavigationSpeedLimit, TftPacketEncoder.speedLimit(0)),
                ),
                priority = true,
                clearsCluster = true,
            )
        }
        pendingSession?.let { value ->
            pendingSession = null
            if (value != 0) {
                // Deliberately carries no session generation. These writes are what *establish*
                // a session, so gating them on one already being active would discard the entry
                // write itself. Staleness is handled by the mark being overwritten or dropped.
                return WriteBatch(
                    frames = listOf(
                        Frame(BleCharacteristics.NavigationSession, TftPacketEncoder.session(value)),
                    ),
                    priority = true,
                    sessionValue = value,
                )
            }
        }
        pendingStatus?.let { value ->
            pendingStatus = null
            return WriteBatch(
                frames = listOf(
                    Frame(BleCharacteristics.NavigationStatus, TftPacketEncoder.status(value)),
                ),
                priority = true,
                statusValue = value,
            )
        }
        return null
    }

    /**
     * Puts a failed batch back for another attempt, in the queue it came from.
     *
     * Coalesced data frames use `putIfAbsent`, so a newer value queued while the write was in
     * flight wins — re-queuing the stale one would put an outdated distance back on the display.
     * Ordered control batches keep their order; a priority one goes back to the front.
     */
    private fun restore(batch: WriteBatch) {
        synchronized(queueLock) {
            if (!isCurrentLocked(batch)) return
            if (batch.clearsCluster) {
                pendingClear = true
                return
            }
            // Only re-mark when nothing newer has been marked meanwhile: a superseded value must
            // not be resurrected by the failure of the write it replaced.
            batch.sessionValue?.let { value ->
                if (pendingSession == null) pendingSession = value
                return
            }
            batch.statusValue?.let { value ->
                if (pendingStatus == null) pendingStatus = value
                return
            }
            when {
                batch.coalescing -> batch.frames.forEach { frame ->
                    latestData.putIfAbsent(frame.key(), frame)
                }
                batch.priority -> controlBatches.addFirst(batch)
                else -> controlBatches.addLast(batch)
            }
        }
    }

    /**
     * Writes a batch frame by frame, rechecking currency between frames: a batch can be
     * invalidated partway through by a route ending or an alert being replaced, and the
     * remaining frames would then be painting a display state that no longer applies.
     *
     * **No unconditional second pass.** On the successful path each logical frame is
     * dispatched once; GATT-level retries and the bridge's own bounded recovery after a
     * reported failure are unaffected. The protocol is conventionally driven with a second
     * copy of every data and text field — not reproducing it is deliberate, and the
     * reasoning and reversal criteria are in `docs/cluster-link-decisions.md` (D1).
     */
    private suspend fun writeBatch(batch: WriteBatch): BatchWriteResult {
        for (frame in batch.frames) {
            if (!isCurrent(batch)) return BatchWriteResult.Stale
            pace(frame)
            // Rechecked after the wait, not only before it: ownership of the text rows can change
            // during a pacing delay, and the batch that owned them a moment ago must not go on to
            // write its next row over whatever replaced it.
            if (!isCurrent(batch)) return BatchWriteResult.Stale
            if (!connection.writeAndAwait(frame.toBikeWrite())) return BatchWriteResult.Failed
            onFrameWritten(frame)
        }
        return BatchWriteResult.Completed
    }

    /**
     * Holds the minimum spacing between consecutive data and text writes, across batch boundaries.
     *
     * Measured completion-to-next-dispatch rather than start-to-start. The bridge cannot implement
     * true start-to-start: `writeAndAwait` enters the shared GATT operation queue first, so the
     * moment the write actually reaches the peer is not ours to observe, and pacing from our own
     * dispatch would under-space whenever that queue is contended. Over-spacing slightly is the
     * safe direction, and it is cheap here — the three data fields are unacknowledged writes whose
     * callbacks return without a peer round trip.
     *
     * Control frames are not paced; the cluster's own driver spaces only its data and text loop.
     */
    private suspend fun pace(frame: Frame) {
        if (frame.characteristic !in PacedCharacteristics) return
        val waitMillis = synchronized(queueLock) {
            val last = lastPacedWriteAtNanos ?: return@synchronized 0L
            val elapsed = (System.nanoTime() - last) / NanosPerMilli
            (MinimumWriteIntervalMillis - elapsed).coerceAtLeast(0L)
        }
        if (waitMillis > 0) delay(waitMillis.milliseconds)
    }

    /** Records what actually reached the cluster, once the peer has confirmed it. */
    private fun onFrameWritten(frame: Frame) {
        synchronized(queueLock) {
            if (frame.characteristic in PacedCharacteristics) lastPacedWriteAtNanos = System.nanoTime()
            // Only acknowledged text is remembered. Caching at enqueue time would let a failed or
            // superseded write mark a row as delivered and suppress every future attempt at it.
            if (frame.characteristic == BleCharacteristics.NavigationText) {
                acknowledgedText[frame.key().row] = frame.payload
            }
        }
    }

    /**
     * Forgets what the cluster is believed to be showing, so the next update rewrites it in full.
     *
     * Needed wherever the display's contents stop being knowable from here: a reconnect, a session
     * reset, an alert taking or releasing the rows, and the cluster announcing it has come up and
     * asking for its state again.
     */
    private fun invalidateTextCacheLocked() {
        acknowledgedText.clear()
    }

    private fun isCurrent(batch: WriteBatch): Boolean = synchronized(queueLock) {
        isCurrentLocked(batch)
    }

    /**
     * Whether a batch still describes the current display state. A batch with no generation
     * recorded — the cluster clear — is always current: it is correct regardless of what
     * has happened since.
     */
    private fun isCurrentLocked(batch: WriteBatch): Boolean =
        (batch.textGeneration == null || batch.textGeneration == textOwnerGeneration) &&
                (batch.alertGeneration == null ||
                (textAlertActive && batch.alertGeneration == textAlertGeneration)) &&
                (batch.sessionGeneration == null ||
                        (sessionActive && batch.sessionGeneration == sessionGeneration))

    private fun Frame.toBikeWrite(): BikeWrite = BikeWrite(
        characteristic = characteristic,
        payload = payload,
        mode = if (characteristic in NoResponseNavigationWrites) {
            BikeWriteMode.NoResponsePreferred
        } else {
            BikeWriteMode.Default
        },
    )

    /**
     * Coalescing key. Text frames additionally key on their row number, which is byte 1 of
     * the packet, so the three rows coalesce independently instead of the newest row
     * evicting the other two.
     */
    private fun Frame.key(): FrameKey = FrameKey(
        characteristic,
        if (characteristic == BleCharacteristics.NavigationText) payload.getOrNull(1)?.toUByte()?.toInt() ?: 0 else 0,
    )

    /** One packet bound for one display field. */
    private data class Frame(val characteristic: UUID, val payload: ByteArray)

    private data class FrameKey(val characteristic: UUID, val row: Int)

    /**
     * A unit of queued work.
     *
     * The queue a batch came from is chosen by whoever built it. [coalescing] records which one
     * that was, so a failed batch is put back where it belongs, and [priority] then says whether
     * it goes to the front of the ordered queue. Conflating the two meant every non-priority
     * control batch was restored into the coalescing map instead — where an ordered sequence
     * loses its order, and a preview batch is undeliverable outright, since anything drawn from
     * that map is stamped with a session generation that preview does not have.
     *
     * The generation fields record what the batch was built for and are checked before
     * every write; a null generation means the batch is valid regardless.
     * [arrivalGeneration] additionally schedules the self-clearing arrival timer, and it
     * starts only once the write is confirmed, so the countdown begins when the rider can
     * actually see the banner.
     */
    private data class WriteBatch(
        val frames: List<Frame>,
        val priority: Boolean,
        val coalescing: Boolean = false,
        val alertGeneration: Long? = null,
        val sessionGeneration: Long? = null,
        val arrivalGeneration: Long? = null,
        val clearsCluster: Boolean = false,
        val sessionValue: Int? = null,
        val statusValue: Int? = null,
        val textGeneration: Long? = null,
    )

    private data class ConnectionRecovery(
        val lastInfo: NavInfo?,
        val textAlertMessage: String?,
        val previewDestination: String?,
    )
    private enum class BatchWriteResult { Completed, Failed, Stale }
    private data class TextAlertDismissal(
        val queuedShutdown: Boolean = false,
        val guidanceToRepublish: NavInfo? = null,
    )

    private companion object {
        /**
         * Fields sent unacknowledged. These three update continuously, and a dropped frame
         * is superseded by the next one within a second, so the round trip an acknowledged
         * write costs buys nothing. Everything else — session, status, text, clear — is
         * acknowledged, because those either change state or must not be silently lost.
         */
        val NoResponseNavigationWrites = setOf(
            BleCharacteristics.NavigationManeuver,
            BleCharacteristics.NavigationSpeedLimit,
            BleCharacteristics.NavigationTrip,
        )

        /** The fields the cluster's own driver paces. Control frames are deliberately excluded. */
        val PacedCharacteristics = NoResponseNavigationWrites + BleCharacteristics.NavigationText

        const val NanosPerMilli = 1_000_000L

        /**
         * Minimum spacing between writes. This is the cluster's own BLE interval; writing
         * faster is not reliably consumed.
         */
        const val MinimumWriteIntervalMillis = 200L

        /** Base backoff after a failed write; multiplied by the consecutive-failure count. */
        const val FailedWriteRetryMillis = 1_000L

        /** Failures after which pending output is dropped rather than retried; see the worker. */
        const val MaxConsecutiveWriteFailures = 3

        /** How long the arrival banner stays up before the display clears itself. */
        const val ArrivalDisplayMillis = 2_000L

        const val ArrivedBanner = "Arrived"
        const val RecalculatingBanner = "RECALCULATION"

        /**
         * Session values, confirmed on the wire.
         *
         * [SessionRouteReady] means a route is staged: the cluster draws its GO prompt and
         * waits for the handlebar. [SessionGuidanceActive] means guidance is running, which
         * is when it draws EXIT and starts rendering maneuvers. Getting these the wrong way
         * round tells the cluster to begin guidance at the moment guidance should end.
         *
         * There is no session value for ending — that is the clear packet alone — and none
         * for arrival, which stays in [SessionGuidanceActive] and changes only its banner.
         * A fourth value, 82, is guidance across more than one destination, which this app
         * never produces.
         */
        /** No session to send. A sentinel: it suppresses the write rather than being transmitted. */
        const val SessionNone = 0

        /** A route has been requested and is being prepared. Transitional. */
        const val SessionRouteRequested = 80

        const val SessionRouteReady = 83
        const val SessionGuidanceActive = 87

        /** The only status value the protocol uses; written once when navigation starts. */
        const val StatusNavigationActive = 132
    }
}

/**
 * Recovers the road name from a full guidance sentence.
 *
 * Only used when the navigation SDK gives no road name of its own. Its instruction text reads
 * "<maneuver> on <road>" or "<maneuver> onto <road>", and the cluster's sixteen-character row is
 * far better spent on the road than on a maneuver the pictogram is already drawing. Anything that
 * does not match — "Take the exit", "Arrive at your destination" — is returned unchanged, because
 * there is no road name in it to recover.
 */
internal fun String.roadNameOrSelf(): String {
    for (separator in RoadNameSeparators) {
        val index = lastIndexOf(separator)
        if (index >= 0) {
            val road = substring(index + separator.length).trim()
            if (road.isNotEmpty()) return road
        }
    }
    return this
}

private val RoadNameSeparators = listOf(" onto ", " on ")
