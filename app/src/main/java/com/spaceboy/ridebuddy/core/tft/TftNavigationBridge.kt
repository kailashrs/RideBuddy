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

internal fun shouldReplayTftNavigation(
    navigationStarted: Boolean,
    outputEnabled: Boolean,
    hasLastInfo: Boolean,
): Boolean = navigationStarted && outputEnabled && hasLastInfo

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
    private var clusterResetNeeded = false
    private var sessionGeneration = 0L
    private var arrivalPendingGeneration: Long? = null
    private var textAlertGeneration = 0L
    private var lastInfo: NavInfo? = null
    private var destinationLabel: String = ""

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
        scope.launch {
            var consecutiveWriteFailures = 0
            for (ignored in wakeWorker) {
                while (true) {
                    val batch = synchronized(queueLock) {
                        when {
                            !transportReady -> null
                            controlBatches.isNotEmpty() -> controlBatches.removeFirst()
                            latestData.isNotEmpty() -> {
                                val entry = latestData.entries.first()
                                latestData.remove(entry.key)
                                WriteBatch(
                                    frames = listOf(entry.value),
                                    priority = false,
                                    replayForCluster = true,
                                    sessionGeneration = sessionGeneration,
                                )
                            }

                            else -> null
                        }
                    } ?: break
                    if (!isCurrent(batch)) continue
                    val result = writeBatch(batch)
                    when (result) {
                        BatchWriteResult.Stale -> continue
                        BatchWriteResult.Failed -> {
                            consecutiveWriteFailures++
                            // A cluster that keeps rejecting writes must not be retried forever:
                            // each attempt costs a full GATT operation timeout and can retire the
                            // link, so the pending output is dropped instead of livelocking.
                            if (consecutiveWriteFailures >= MaxConsecutiveWriteFailures) {
                                consecutiveWriteFailures = 0
                                synchronized(queueLock) {
                                    controlBatches.clear()
                                    latestData.clear()
                                }
                                break
                            }
                            restore(batch)
                            delay((FailedWriteRetryMillis * consecutiveWriteFailures).milliseconds)
                            wakeWorker.trySend(Unit)
                            break
                        }
                        BatchWriteResult.Completed -> consecutiveWriteFailures = 0
                    }
                    // The arrival display only starts counting down once the bike has it.
                    batch.arrivalGeneration
                        ?.takeIf { result == BatchWriteResult.Completed }
                        ?.let(::scheduleArrivalReset)
                    if (batch.clearsCluster) {
                        synchronized(queueLock) { clusterResetNeeded = false }
                    }
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

    fun start(destination: String = "") {
        val queuedReset = synchronized(queueLock) {
            destinationLabel = destination
            val wasShowingArrival = arrivalPendingGeneration != null
            arrivalPendingGeneration = null
            if (wasShowingArrival) {
                sessionActive = false
                clusterResetNeeded = true
                controlBatches.clear()
                latestData.clear()
                if (transportReady) queueClusterResetLocked()
            }
            sessionGeneration++
            acceptingUpdates = true
            wasShowingArrival && transportReady
        }
        if (queuedReset) wakeWorker.trySend(Unit)
    }

    fun accept(info: NavInfo) {
        val controlFrames = mutableListOf<Frame>()
        val generation = synchronized(queueLock) {
            if (!acceptingUpdates) return
            lastInfo = info
            if (!outputEnabled) return
            if (!sessionActive) {
                controlFrames += Frame(
                    BleCharacteristics.NavigationSession,
                    TftPacketEncoder.session(SessionRouteReady)
                )
                controlFrames += Frame(
                    BleCharacteristics.NavigationStatus,
                    TftPacketEncoder.status(StatusNavigationActive)
                )
                controlFrames += Frame(
                    BleCharacteristics.NavigationSession,
                    TftPacketEncoder.session(SessionGuidanceActive)
                )
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
            val instruction = current.fullInstructionText?.takeUnless(String::isBlank)
                ?: current.fullRoadName.orEmpty()
            // Compact keeps the instruction banner and drops the destination lines, which is the
            // half a rider glances at.
            val compact = settings.value.tftTextMode == TftTextMode.Compact
            val destinationText = if (compact) "" else synchronized(queueLock) { destinationLabel }
            if (!synchronized(queueLock) { textAlertActive }) {
                TftPacketEncoder.guidanceTextRows(destinationText, instruction).forEach { payload ->
                    dataFrames += Frame(BleCharacteristics.NavigationText, payload)
                }
            }
        }

        synchronized(queueLock) {
            if (!acceptingUpdates || !sessionActive || sessionGeneration != generation ||
                !outputEnabled
            ) return
            val framesToQueue = if (textAlertActive) {
                dataFrames.filterNot { it.characteristic == BleCharacteristics.NavigationText }
            } else dataFrames
            if (controlFrames.isNotEmpty()) {
                controlBatches += WriteBatch(
                    frames = controlFrames,
                    priority = false,
                    sessionGeneration = generation,
                )
            }
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
     * The OEM does not change the session state to reroute. It writes pictogram 203 and puts
     * "RECALCULATION" on the banner, leaving the session where it is; 82 is its multi-leg
     * guidance state, which is what RideBuddy had been sending here.
     */
    fun rerouting() {
        val destination = synchronized(queueLock) { destinationLabel }
        val frames = listOf(
            Frame(
                BleCharacteristics.NavigationManeuver,
                TftPacketEncoder.pictogram(TftPacketEncoder.PictogramRecalculating),
            ),
        ) + TftPacketEncoder.guidanceTextRows(destination, RecalculatingBanner)
            .map { payload -> Frame(BleCharacteristics.NavigationText, payload) }
        val queued = synchronized(queueLock) {
            if (!sessionActive || !outputEnabled) false else {
                controlBatches += WriteBatch(frames = frames, priority = false, sessionGeneration = sessionGeneration)
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
            // No capture covers an arrival session value, and 83 now means "route ready", which
            // would put the GO prompt back up. The banner is a surface the capture does cover.
            controlBatches += WriteBatch(
                frames = TftPacketEncoder.guidanceTextRows(destinationLabel, ArrivedBanner)
                    .map { payload -> Frame(BleCharacteristics.NavigationText, payload) },
                priority = true,
                sessionGeneration = arrivalGeneration,
                arrivalGeneration = arrivalGeneration,
            )
            arrivalGeneration
        }
        if (generation == null) {
            stop()
            return
        }
        wakeWorker.trySend(Unit)
    }

    fun speedLimit(kph: Int) {
        if (synchronized(queueLock) { sessionActive && acceptingUpdates }) {
            queueLatest(Frame(BleCharacteristics.NavigationSpeedLimit, TftPacketEncoder.speedLimit(kph)))
        }
    }

    fun republishLast() {
        synchronized(queueLock) { lastInfo }?.let(::accept)
    }

    /** Displays a short alert using the TFT navigation text surface. */
    fun presentTextAlert(message: String): Boolean {
        if (message.isBlank() || !synchronized(queueLock) { outputEnabled }) return false
        if (connection.connectionState.value !is BikeConnectionState.Connected || !connection.diagnostics.value.authenticated) {
            return false
        }
        synchronized(queueLock) {
            if (!outputEnabled) return false
            val startsTemporarySession = !sessionActive
            if (startsTemporarySession) sessionActive = true
            textAlertGeneration++
            val alertGeneration = textAlertGeneration
            textAlertActive = true
            textAlertMessage = message
            controlBatches.removeAll { it.alertGeneration != null }
            latestData.keys.removeAll { it.characteristic == BleCharacteristics.NavigationText }
            if (startsTemporarySession) {
                controlBatches.addLast(
                    WriteBatch(
                        frames = listOf(
                            Frame(
                                BleCharacteristics.NavigationSession,
                                TftPacketEncoder.session(SessionRouteReady)
                            ),
                            Frame(BleCharacteristics.NavigationStatus, TftPacketEncoder.status(StatusNavigationActive)),
                            Frame(
                                BleCharacteristics.NavigationSession,
                                TftPacketEncoder.session(SessionGuidanceActive)
                            ),
                        ),
                        priority = true,
                        alertGeneration = alertGeneration,
                        sessionGeneration = sessionGeneration,
                    ),
                )
            }
            controlBatches.addLast(
                WriteBatch(
                    frames = TftPacketEncoder.displayTextRows(message)
                        .map { payload -> Frame(BleCharacteristics.NavigationText, payload) },
                    priority = true,
                    replayForCluster = true,
                    alertGeneration = alertGeneration,
                    sessionGeneration = sessionGeneration,
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
            textAlertActive = false
            textAlertMessage = null
            controlBatches.removeAll { it.alertGeneration != null }
            latestData.keys.removeAll { it.characteristic == BleCharacteristics.NavigationText }
            if (!acceptingUpdates && sessionActive) {
                sessionActive = false
                lastInfo = null
                clusterResetNeeded = true
                queueClusterResetLocked()
                TextAlertDismissal(queuedShutdown = true)
            } else {
                TextAlertDismissal(guidanceToRepublish = lastInfo)
            }
        }
        dismissal.guidanceToRepublish?.let(::accept)
        if (dismissal.queuedShutdown || dismissal.guidanceToRepublish != null) wakeWorker.trySend(Unit)
    }

    fun stop() {
        val shutdown = synchronized(queueLock) { stopLocked() }
        if (shutdown != null) wakeWorker.trySend(Unit)
    }

    private fun stopAfterArrival(generation: Long) {
        val shutdown = synchronized(queueLock) {
            if (arrivalPendingGeneration != generation ||
                sessionGeneration != generation || acceptingUpdates
            ) return
            stopLocked()
        }
        if (shutdown != null) wakeWorker.trySend(Unit)
    }

    private fun scheduleArrivalReset(generation: Long) {
        scope.launch {
            delay(ArrivalDisplayMillis.milliseconds)
            stopAfterArrival(generation)
        }
    }

    private fun stopLocked(): WriteBatch? {
        sessionGeneration++
        arrivalPendingGeneration = null
        acceptingUpdates = false
        textAlertGeneration++
        val shouldShutdown = sessionActive
        sessionActive = false
        textAlertActive = false
        textAlertMessage = null
        lastInfo = null
        latestData.clear()
        controlBatches.clear()
        if (shouldShutdown) clusterResetNeeded = true
        if (!clusterResetNeeded || !transportReady) return null
        return queueClusterResetLocked()
    }

    private fun queueControl(frame: Frame) {
        val queued = synchronized(queueLock) {
            if (!sessionActive || !outputEnabled) false else {
                controlBatches += WriteBatch(
                    frames = listOf(frame),
                    priority = false,
                    sessionGeneration = sessionGeneration,
                )
                true
            }
        }
        if (queued) wakeWorker.trySend(Unit)
    }

    private fun queueLatest(frame: Frame) {
        val queued = synchronized(queueLock) {
            if (!sessionActive || !outputEnabled) false else {
                val key = frame.key()
                latestData.remove(key)
                latestData[key] = frame
                true
            }
        }
        if (queued) wakeWorker.trySend(Unit)
    }

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
                clusterResetNeeded = clusterResetNeeded ||
                    sessionActive || textAlertActive || lastInfo != null
                sessionActive = false
                textAlertActive = false
                textAlertMessage = null
                controlBatches.clear()
                latestData.clear()
                if (clusterResetNeeded && transportReady) queueClusterResetLocked() != null else false
            } else {
                if (clusterResetNeeded && transportReady) queueClusterResetLocked()
                if (shouldReplayTftNavigation(
                        navigationStarted = acceptingUpdates,
                        outputEnabled = outputEnabled,
                        hasLastInfo = lastInfo != null,
                    )
                ) {
                    replay = lastInfo
                }
                clusterResetNeeded && transportReady
            }
        }
        replay?.let(::accept)
        if (queuedReset || replay != null) wakeWorker.trySend(Unit)
    }

    private fun queueClusterResetLocked(): WriteBatch? {
        if (controlBatches.any(WriteBatch::clearsCluster)) return null
        return WriteBatch(
            frames = listOf(
                Frame(BleCharacteristics.NavigationClear, TftPacketEncoder.clear()),
                Frame(BleCharacteristics.NavigationStatus, TftPacketEncoder.status(0)),
            ),
            priority = true,
            clearsCluster = true,
        ).also(controlBatches::addLast)
    }

    /**
     * A cluster forgets the navigation session on disconnect. Discard stale queued frames and
     * republish the latest guidance only after the new connection is authenticated.
     */
    private fun refreshTransportAvailability(ready: Boolean) {
        val recovery = synchronized(queueLock) {
            when {
                ready && !transportReady -> {
                    transportReady = true
                    sessionActive = false
                    controlBatches.clear()
                    latestData.clear()
                    if (clusterResetNeeded) queueClusterResetLocked()
                    val pendingArrival = arrivalPendingGeneration
                    if (pendingArrival != null && outputEnabled) {
                        sessionActive = true
                        controlBatches += WriteBatch(
                            frames = listOf(
                                Frame(
                                    BleCharacteristics.NavigationSession,
                                    TftPacketEncoder.session(SessionRouteReady),
                                ),
                                Frame(
                                    BleCharacteristics.NavigationStatus,
                                    TftPacketEncoder.status(StatusNavigationActive),
                                ),
                                Frame(
                                    BleCharacteristics.NavigationSession,
                                    TftPacketEncoder.session(SessionGuidanceActive),
                                ),
                            ) + TftPacketEncoder.guidanceTextRows(destinationLabel, ArrivedBanner)
                                .map { payload -> Frame(BleCharacteristics.NavigationText, payload) },
                            priority = true,
                            sessionGeneration = pendingArrival,
                            arrivalGeneration = pendingArrival,
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
        recovery?.textAlertMessage?.let(::presentTextAlert)
        recovery?.lastInfo?.let(::accept)
        if (ready) wakeWorker.trySend(Unit)
    }

    private fun restore(batch: WriteBatch) {
        synchronized(queueLock) {
            if (!isCurrentLocked(batch)) return
            if (batch.priority) {
                controlBatches.addFirst(batch)
            } else {
                batch.frames.forEach { frame -> latestData.putIfAbsent(frame.key(), frame) }
            }
        }
    }

    private suspend fun writeBatch(batch: WriteBatch): BatchWriteResult {
        val passes = if (batch.replayForCluster) ClusterReplayCount else 1
        var firstWrite = true
        repeat(passes) {
            for (frame in batch.frames) {
                if (!isCurrent(batch)) return BatchWriteResult.Stale
                if (!firstWrite) delay(MinimumWriteIntervalMillis.milliseconds)
                firstWrite = false
                if (!connection.writeAndAwait(frame.toBikeWrite())) return BatchWriteResult.Failed
            }
        }
        return BatchWriteResult.Completed
    }

    private fun isCurrent(batch: WriteBatch): Boolean = synchronized(queueLock) {
        isCurrentLocked(batch)
    }

    private fun isCurrentLocked(batch: WriteBatch): Boolean =
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

    private fun Frame.key(): FrameKey = FrameKey(
        characteristic,
        if (characteristic == BleCharacteristics.NavigationText) payload.getOrNull(1)?.toUByte()?.toInt() ?: 0 else 0,
    )

    private data class Frame(val characteristic: UUID, val payload: ByteArray)
    private data class FrameKey(val characteristic: UUID, val row: Int)
    private data class WriteBatch(
        val frames: List<Frame>,
        val priority: Boolean,
        val replayForCluster: Boolean = false,
        val alertGeneration: Long? = null,
        val sessionGeneration: Long? = null,
        val arrivalGeneration: Long? = null,
        val clearsCluster: Boolean = false,
    )

    private data class ConnectionRecovery(val lastInfo: NavInfo?, val textAlertMessage: String?)
    private enum class BatchWriteResult { Completed, Failed, Stale }
    private data class TextAlertDismissal(
        val queuedShutdown: Boolean = false,
        val guidanceToRepublish: NavInfo? = null,
    )

    private companion object {
        val NoResponseNavigationWrites = setOf(
            BleCharacteristics.NavigationManeuver,
            BleCharacteristics.NavigationSpeedLimit,
            BleCharacteristics.NavigationTrip,
        )
        /**
         * The 200 ms spacing is the OEM's own BLE interval constant
         * (`data.bluetooth.looper.a.b = 200`) and should stay.
         *
         * The replay count is deliberately **1**, which is a departure from the OEM.
         * `DashboardActivity.o1()` writes each navigation data field inside a `for (i < 2)` loop —
         * 8210, 8220, 8230 and 8240, but not the session, status or clear packets. RideBuddy sends
         * each once instead, on the reasoning that its serialized operation queue, callback
         * correlation and failure classification make the OEM's blind repetition unnecessary.
         *
         * That reasoning is untested, and it does not obviously cover 8210, 8220 and 8230, which
         * are ATT Write Commands: nothing at any layer this app controls can observe whether the
         * cluster consumed them. The gain is responsiveness — at two passes a full guidance update
         * took about two seconds to drain against a roughly 1 Hz feed, so distance-to-turn lagged.
         *
         * If a parked test shows maneuver, distance, speed limit or text failing to appear or
         * updating late, restore the OEM behaviour by reverting the commit that set this to 1.
         */
        const val MinimumWriteIntervalMillis = 200L
        const val ClusterReplayCount = 1
        const val FailedWriteRetryMillis = 1_000L
        const val MaxConsecutiveWriteFailures = 3
        const val ArrivalDisplayMillis = 2_000L
        const val ArrivedBanner = "Arrived"
        const val RecalculatingBanner = "RECALCULATION"
        /**
         * Session values as the capture shows the OEM using them, which is not what static
         * analysis suggested. It writes 83 when a route is ready — the cluster then draws the GO
         * prompt — and 87 once guidance is running, which is when it draws EXIT and starts
         * rendering maneuvers. RideBuddy had been writing 80 to start and 87 to shut down, so it
         * was telling the cluster to begin guidance at the exact moment it meant to end it.
         *
         * Ending is just the clear packet; the OEM sends no session value with it. No capture
         * covers arrival or rerouting, so those keep their previously assumed values and are
         * used only where the display can tolerate being wrong.
         */
        const val SessionRouteReady = 83
        const val SessionGuidanceActive = 87
        const val StatusNavigationActive = 132
    }
}
