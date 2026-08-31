package com.spaceboy.ridebuddy.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.spaceboy.ridebuddy.domain.BikeConnection
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeControlEvent
import com.spaceboy.ridebuddy.domain.BikeWrite
import com.spaceboy.ridebuddy.domain.BleDiagnostics
import com.spaceboy.ridebuddy.domain.BondStateSnapshot
import com.spaceboy.ridebuddy.domain.ConnectionAttemptContext
import com.spaceboy.ridebuddy.domain.ConnectionAttemptTrigger
import com.spaceboy.ridebuddy.domain.ConnectionFailure
import com.spaceboy.ridebuddy.domain.ConnectionFailureCategory
import com.spaceboy.ridebuddy.domain.ProtectionPath
import com.spaceboy.ridebuddy.domain.ProtectionPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * Sole owner of the GATT link and of automatic reconnection.
 *
 * Nothing outside this class may start, retry, or tear down a GATT session: the foreground
 * service and the companion presence receiver express *demand*, and this class decides what
 * to do about it. Concentrating that here is what makes the retry budget meaningful —
 * anywhere else could hand the stack a fresh one just by asking again.
 *
 * The work is delegated in layers, each independently testable: [BluetoothBondCoordinator]
 * gets the device bonded, [ProtectionCoordinator] and [ProtectionSession] run the
 * handshake, [GattOperationCoordinator] serialises I/O and owns the retry policy, and
 * [BikeTelemetryStream] decodes and publishes what arrives. What is left here is the
 * connection lifecycle itself and the routing of framework callbacks into those parts.
 *
 * Two invariants run through it. All mutable state is confined to the main handler, and
 * every public entry point hops there before touching anything. And every framework
 * callback is checked against the session registry first: Android keeps delivering
 * callbacks from a `BluetoothGatt` long after the app has stopped using it, so an
 * unchecked callback would drive a link that no longer exists. Sessions are tracked
 * through [GattSessionRegistry] so each instance is closed exactly once, and every failure
 * is recorded as a structured [ConnectionFailure] rather than a bare string.
 */
@SuppressLint("MissingPermission")
internal class AndroidBikeConnection(
    context: Context,
    private val captureRecorder: BleCaptureRecorder,
    private val protectionAcceptanceStore: ProtectionAcceptanceStore,
    private val connectionEventJournal: ConnectionEventJournal,
    private val bikeIdentityRepository: BikeIdentityRepository,
) : BikeConnection {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bondCoordinator = BluetoothBondCoordinator(
        context = appContext,
        handler = mainHandler,
        isGenerationCurrent = { generation -> generation == connectionGeneration },
        onBondReady = ::onBondReady,
        onFailure = { message -> failLocally(message) },
        log = ::log,
    )
    private val profile = BikeGattProfile()
    private val sessions = GattSessionRegistry<BluetoothGatt>(
        // Logged so a leaked connection request shows up as an open with no matching close.
        { gatt, disconnectFirst ->
            log("Closing GATT transport (disconnectFirst=$disconnectFirst)")
            closeAndroidGatt(gatt, disconnectFirst)
        },
    )
    private var connectedDevice: BluetoothDevice? = null
    private var connectionTarget: BikeConnectionTarget? = null
    private var deviceName: String? = null
    private var connectedDeviceBonded = false
    private var intentionalDisconnect = false
    private var reconnectAttempt = 0
    private var consecutiveEncryptionStalls = 0
    private var reconnectScheduled = false
    private var connectionGeneration = 0L
    private var connectionMonitoringActive = false
    private var attemptTrigger: ConnectionAttemptTrigger? = null
    private var authenticatedAtMillis: Long? = null
    private val nextWriteRequestId = AtomicLong()
    private val telemetryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val telemetryStream = BikeTelemetryStream()

    private val mutableConnectionState = MutableStateFlow<BikeConnectionState>(BikeConnectionState.Disconnected)
    private val diagnosticsRecorder = BleDiagnosticsRecorder(connectionEventJournal.events.value)
    private val mutableControls = MutableSharedFlow<BikeControlEvent>(extraBufferCapacity = 8)
    private val operationCoordinator = GattOperationCoordinator(
        handler = mainHandler,
        executor = AndroidGattOperationExecutor(captureRecorder),
        currentGatt = { sessions.current()?.openTransport() },
        isChallengeResponsePending = ::isChallengeResponsePending,
        attemptContext = { attemptContext() },
        onActiveOperationChanged = diagnosticsRecorder::setActiveOperation,
        onFailureRecorded = ::recordConnectionFailure,
        onResetRequired = ::retireLinkAndReconnect,
        onOperationExhausted = ::handleExhaustedOperation,
        log = ::log,
    )
    private val protectionCoordinator = ProtectionCoordinator(
        handler = mainHandler,
        currentGeneration = { connectionGeneration },
        characteristic = { uuid -> profile[uuid] },
        enqueue = operationCoordinator::enqueue,
        enqueueAll = operationCoordinator::enqueueAll,
        isAuthenticated = { diagnosticsRecorder.value.authenticated },
        markAccepted = { connectionTarget?.address?.let(protectionAcceptanceStore::markAccepted) },
        clearAcceptance = { connectionTarget?.address?.let(protectionAcceptanceStore::clear) },
        onAuthenticated = ::completeAuthentication,
        onFailure = ::failWithCategory,
        onReconnectRequired = ::reconnectAfterProtectionFailure,
        updateDiagnostics = diagnosticsRecorder::setProtection,
        log = ::log,
    )

    init {
        // Mirror the journal into the diagnostics snapshot so the screen has one source to
        // read. The hop back onto the main handler keeps every diagnostics mutation on the
        // thread that owns it.
        telemetryScope.launch {
            connectionEventJournal.events.collect { events ->
                mainHandler.post { diagnosticsRecorder.recordEvents(events) }
            }
        }
    }

    override val connectionState: StateFlow<BikeConnectionState> = mutableConnectionState.asStateFlow()
    override val rawTelemetry = telemetryStream.rawTelemetry
    override val telemetry = telemetryStream.telemetry
    override val latestTelemetryReading = telemetryStream.latestReading
    override val identity = bikeIdentityRepository.identity
    override val diagnostics: StateFlow<BleDiagnostics> = diagnosticsRecorder.diagnostics
    override val controls: SharedFlow<BikeControlEvent> = mutableControls

    /**
     * Requests a connection to [target], tearing down whatever is in play first.
     *
     * A duplicate request for the bike already being connected is ignored rather than
     * restarting the attempt — see [shouldStartConnection]. Bumping the generation is what
     * makes every callback and timer belonging to the previous attempt inert.
     */
    override fun connect(target: BikeConnectionTarget) {
        runOnMain {
            if (!shouldStartConnection(connectionTarget, target, mutableConnectionState.value)) {
                log("Ignored duplicate connection request for ${target.deviceName}; connection already active")
                return@runOnMain
            }
            val replacingTarget = connectionTarget?.address != target.address
            connectionGeneration++
            intentionalDisconnect = true
            disconnectInternal(closeOnly = false)
            connectionTarget = target
            deviceName = target.deviceName
            bikeIdentityRepository.select(target.address)
            // A failure recorded against a different bike says nothing about this one.
            if (replacingTarget) diagnosticsRecorder.clearFailure()
            diagnosticsRecorder.setProtection(ProtectionPhase.Idle, null)
            diagnosticsRecorder.setActiveOperation(null)
            reconnectAttempt = 0
            attemptTrigger = target.trigger
            mainHandler.removeCallbacksAndMessages(ReconnectToken)
            // Cleared before connectGatt(), not after: on an already-bonded device the whole
            // chain below is synchronous, so a failure inside it reaches scheduleReconnect()
            // during this call. Leaving the flag set there suppressed the backoff and stranded
            // the state machine in Connecting with no retry and no timeout armed. Late callbacks
            // from the session just torn down are recognised by the session registry, not by
            // this flag, so clearing it early loses nothing.
            intentionalDisconnect = false
            connectGatt()
        }
    }

    /**
     * Tears the link down at the app's request. Sets [intentionalDisconnect] so the
     * resulting disconnect callback is understood as expected rather than as a lost link
     * that should be retried.
     */
    override fun disconnect() {
        runOnMain {
            connectionGeneration++
            intentionalDisconnect = true
            connectionMonitoringActive = false
            mainHandler.removeCallbacksAndMessages(ReconnectToken)
            disconnectInternal(closeOnly = false)
            mutableConnectionState.value = BikeConnectionState.Disconnected
            log("Disconnected by app request")
        }
    }

    override fun notifyStartFailed(message: String) {
        runOnMain { failLocally(message) }
    }

    /** Fire-and-forget write. Silently dropped when no authenticated session exists. */
    override fun enqueueWrite(characteristic: UUID, payload: ByteArray) {
        runOnMain { writeInternal(characteristic, payload) }
    }

    /**
     * Writes and waits for the framework to confirm it, returning false on any failure.
     *
     * Used where the caller needs to know a write actually landed — display sequences that
     * must not advance on an unconfirmed step. The timeout is a backstop for a stack that
     * accepts a write and never calls back; abandoning it is not free, which is what
     * [cancelAwaitedWrite] deals with.
     */
    override suspend fun writeAndAwait(write: BikeWrite): Boolean {
        val completion = CompletableDeferred<Boolean>()
        val requestId = nextWriteRequestId.incrementAndGet()
        mainHandler.post {
            if (!diagnosticsRecorder.value.authenticated) {
                completion.complete(false)
                return@post
            }
            val target = profile[write.characteristic]
            if (target == null) {
                completion.complete(false)
                return@post
            }
            operationCoordinator.enqueue(
                GattOperation.Write(
                    characteristic = target,
                    value = write.payload.copyOf(),
                    mode = write.mode,
                    completion = completion,
                    requestId = requestId,
                ),
            )
        }
        return try {
            withTimeoutOrNull(AwaitedWriteTimeoutMillis.milliseconds) { completion.await() }
                ?: cancelAwaitedWrite(requestId, completion)
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) { cancelAwaitedWrite(requestId, completion) }
            throw cancellation
        }
    }

    /**
     * A caller must never retry while its original write can still reach the cluster. Queued
     * requests can be removed directly; an in-flight request requires a link reset because GATT
     * has no per-operation cancellation API.
     */
    private suspend fun cancelAwaitedWrite(requestId: Long, completion: CompletableDeferred<Boolean>): Boolean {
        val cancellationComplete = CompletableDeferred<Unit>()
        mainHandler.post {
            if (!completion.isCompleted) {
                val activeWrite = operationCoordinator.activeWrite()
                when {
                    activeWrite?.requestId == requestId -> {
                        completion.complete(false)
                        retireLinkAndReconnect(
                            connectionFailure(
                                message = "Link lost while writing: awaited write was abandoned after " +
                                    "${AwaitedWriteTimeoutMillis}ms",
                                category = ConnectionFailureCategory.LinkLost,
                            ),
                        )
                    }

                    operationCoordinator.removeQueuedWrite(requestId) -> completion.complete(false)

                    else -> {
                        log("Timed-out write was no longer queued")
                        completion.complete(false)
                    }
                }
            }
            cancellationComplete.complete(Unit)
        }
        cancellationComplete.await()
        return completion.await()
    }

    private fun writeInternal(characteristic: UUID, payload: ByteArray) {
        if (!diagnosticsRecorder.value.authenticated) return
        val target = profile[characteristic] ?: return
        operationCoordinator.enqueue(GattOperation.Write(target, payload.copyOf()))
    }

    /**
     * Starts an attempt: checks the local preconditions, resolves the device, and hands off
     * to the bond coordinator. Also the reconnect entry point, so it runs once per attempt.
     */
    private fun connectGatt() {
        val target = connectionTarget ?: return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            failLocally("Allow Nearby devices to connect to the motorcycle")
            return
        }

        val adapter = bluetoothManager.adapter ?: run {
            failLocally("Bluetooth is unavailable on this phone")
            return
        }
        if (!adapter.isEnabled) {
            failLocally("Turn on Bluetooth to connect to the motorcycle")
            return
        }

        // BLUETOOTH_CONNECT is granted above and BluetoothAddress always yields six bytes, so
        // neither the permission nor the malformed-address rejection is reachable here.
        val device = adapter.getRemoteDevice(target.address.toByteArray())
        val bondState = device.bondState
        connectedDeviceBonded = bondState == BluetoothDevice.BOND_BONDED
        connectedDevice = device
        // A bond that is absent or still forming means the pairing epoch this acceptance was
        // recorded against is gone, so the stored shortcut must not survive into the new one.
        if (bondState == BluetoothDevice.BOND_NONE || bondState == BluetoothDevice.BOND_BONDING) {
            if (protectionAcceptanceStore.isAccepted(target.address)) {
                log("New pairing epoch (bond ${bondStateSnapshot(bondState)}); stored acceptance cleared")
            }
            protectionAcceptanceStore.clear(target.address)
        }
        mutableConnectionState.value = BikeConnectionState.Connecting(deviceName)
        telemetryStream.clearUiTelemetry()
        diagnosticsRecorder.beginConnectionAttempt(
            bonded = connectedDeviceBonded,
            context = attemptContext(),
        )

        bondCoordinator.prepare(
            device = device,
            initialBondState = bondState,
            generation = connectionGeneration,
            deviceName = deviceName.orEmpty(),
        )
    }

    private fun onBondReady(device: BluetoothDevice) {
        connectedDeviceBonded = true
        diagnosticsRecorder.markBonded()
        if (attemptTrigger == null) attemptTrigger = ConnectionAttemptTrigger.BondCompleted
        startGattConnection(device)
    }

    /**
     * Opens the GATT link on a bonded device and arms the overall connection timeout.
     *
     * The generation is captured before the framework call and rechecked after it: opening
     * a GATT is slow enough that the attempt can be superseded in the meantime, and the
     * instance produced would then be an orphan that still delivers callbacks. Closing it
     * as unadopted retires it without ever making it current.
     */
    private fun startGattConnection(device: BluetoothDevice) {
        val target = connectionTarget ?: return
        val requestedGeneration = connectionGeneration
        val address = target.address.toString()
        // The generation is logged so overlapping connection requests are visible: the stack
        // will happily hold more than one outstanding, and a leaked request re-establishes the
        // link the instant it drops, bypassing the backoff this class thinks it is applying.
        log(
            "Connecting to ${deviceName.orEmpty()} (${address.takeLast(5)}), " +
                "bonded=true, generation=$requestedGeneration",
        )
        val newGatt = try {
            device.connectGatt(
                appContext,
                false,
                callback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                mainHandler,
            )
        } catch (error: RuntimeException) {
            log("GATT start failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            failLocally("Android could not start the Bluetooth connection")
            return
        }
        if (newGatt == null) {
            recordConnectionFailure(
                connectionFailure(
                    message = "Android could not create a GATT connection",
                    category = ConnectionFailureCategory.LocalPrecondition,
                ),
            )
            scheduleReconnect()
            return
        }
        if (requestedGeneration != connectionGeneration) {
            sessions.closeUnadopted(newGatt, SystemClock.elapsedRealtime())
            return
        }
        val session = sessions.open(newGatt, SystemClock.elapsedRealtime())
        diagnosticsRecorder.updateAttempt(attemptContext())
        mainHandler.removeCallbacksAndMessages(ConnectionTimeoutToken)
        mainHandler.postAtTime({
            if (requestedGeneration == connectionGeneration && sessions.current() === session &&
                mutableConnectionState.value !is BikeConnectionState.Connected
            ) {
                retireLinkAndReconnect(
                    connectionFailure(
                        message = "Timed out connecting to the motorcycle after ${ConnectionTimeoutMillis}ms",
                        category = ConnectionFailureCategory.LinkLost,
                    ),
                )
            }
        }, ConnectionTimeoutToken, SystemClock.uptimeMillis() + ConnectionTimeoutMillis)
    }

    private val callback = AndroidBikeGattCallback(
        handleConnectionStateChanged = ::onConnectionStateChanged,
        handleMtuChanged = ::onMtuChanged,
        handleServicesDiscovered = ::onServicesDiscovered,
        handleNotification = { callbackGatt, characteristic, value ->
            onNotification(callbackGatt, characteristic.uuid, value)
        },
        handleDescriptorWrite = ::onDescriptorWrite,
        handleWrite = ::onCharacteristicWrite,
        handleRssiRead = ::onRssiRead,
    )

    /**
     * Link came up or went down.
     *
     * A connected state carrying a failure status is not a connection — the framework
     * reports the failure this way — so it is handled as a loss. Both branches route
     * through [intentionalDisconnect]: an expected teardown just closes, while an
     * unexpected one goes back to the reconnect backoff.
     */
    private fun onConnectionStateChanged(callbackGatt: BluetoothGatt, status: Int, newState: Int) {
        if (!isCurrent(callbackGatt)) {
            discardStaleCallback(callbackGatt, "connection state change")
            return
        }
        val session = sessions.current() ?: return
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    val failure = connectionFailure(
                        message = "GATT connection failed: " +
                            "${gattConnectionStatusLabel(status)} ($status)",
                        category = ConnectionFailureCategory.LinkLost,
                        statusCode = status,
                        statusName = gattConnectionStatusLabel(status),
                    )
                    if (intentionalDisconnect) {
                        recordConnectionFailure(failure)
                        disconnectInternal(closeOnly = true)
                    } else {
                        retireLinkAndReconnect(failure, updateConnectingState = false)
                    }
                    return
                }
                session.markConnected(SystemClock.elapsedRealtime())
                // No MTU is requested, and neither does the cluster's own app. Every packet is
                // built to fit the default ATT bearer rather than happening to fit it: the text
                // rows are sixteen bytes because that is what 23 - 3 header - 1 terminator
                // leaves, which is also the row width the OEM hardcodes. Negotiating a larger
                // one is an extra round trip and another failure mode in the connection
                // sequence, and would not widen the display. The default is recorded here so
                // diagnostics never shows a blank MTU; if the peer negotiates one anyway,
                // onMtuChanged overwrites it.
                diagnosticsRecorder.setAttMtu(DefaultAttMtu)
                log("GATT connected")
                discoverServices(callbackGatt)
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                val linkAge = session.linkAgeMillis(SystemClock.elapsedRealtime())
                val message = "Link lost: ${gattConnectionStatusLabel(status)} ($status)" +
                    linkAge?.let { ", link age ${it / 1_000}s" }.orEmpty()
                if (intentionalDisconnect) {
                    log(message)
                    disconnectInternal(closeOnly = true)
                } else if (isEncryptionStall(session, status)) {
                    reportEncryptionStall(message, linkAge)
                } else {
                    retireLinkAndReconnect(
                        connectionFailure(
                            message = message,
                            category = ConnectionFailureCategory.LinkLost,
                            statusCode = status,
                            statusName = gattConnectionStatusLabel(status),
                            linkAgeMillis = linkAge,
                        ),
                        updateConnectingState = false,
                    )
                }
            }
        }
    }

    private fun onMtuChanged(callbackGatt: BluetoothGatt, mtu: Int, status: Int) {
        if (!isCurrent(callbackGatt)) return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            diagnosticsRecorder.setAttMtu(mtu)
            log("MTU $mtu")
        }
    }

    /**
     * Discovery finished: index the profile, verify it is a compatible cluster, and start
     * the handshake. An incomplete profile is deterministic — a peer that is not this
     * vehicle, or a discovery that returned nothing usable — so it fails without retrying.
     */
    private fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
        if (!isCurrent(callbackGatt)) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            retireLinkAndReconnect(
                connectionFailure(
                    message = "Link lost while discovering services: " +
                        "${gattStatusName(status)} ($status)",
                    category = ConnectionFailureCategory.LinkLost,
                    statusCode = status,
                    statusName = gattStatusName(status),
                ),
            )
            return
        }
        val snapshot = profile.replace(callbackGatt.services)
        diagnosticsRecorder.setServices(snapshot.serviceCount, snapshot.characteristicLabels)
        if (snapshot.missingRequiredCharacteristics.isNotEmpty()) {
            failWithCategory(
                "Motorcycle companion profile is incomplete (missing ${
                    snapshot.missingRequiredCharacteristics.joinToString { it.shortName() }
                })",
                ConnectionFailureCategory.Deterministic,
            )
            return
        }
        mutableConnectionState.value = BikeConnectionState.Authenticating(deviceName ?: "Motorcycle")
        val address = connectionTarget?.address
        // Re-read the live bond state so a pair that completed between connectGatt() and the
        // service discovery callback is still recognised for the stored-acceptance shortcut.
        connectedDevice?.let { device -> connectedDeviceBonded = device.bondState == BluetoothDevice.BOND_BONDED }
        val previouslyAccepted =
            address != null && connectedDeviceBonded && protectionAcceptanceStore.isAccepted(address)
        protectionCoordinator.begin(previouslyAccepted)
    }

    /** A CCCD write completed, which is how a subscription reports success. */
    private fun onDescriptorWrite(
        callbackGatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int,
    ) {
        if (!isCurrent(callbackGatt)) return
        sessions.current()?.markOperationCompleted()
        val uuid = descriptor.characteristic.uuid
        operationCoordinator.complete(
            status = status,
            label = "subscribe ${uuid.shortName()}",
            matchesActiveOperation = { operation ->
                operation is GattOperation.Subscribe && operation.characteristic.uuid == uuid
            },
        ) { completed ->
            if (completed is GattOperation.Subscribe) {
                diagnosticsRecorder.countDescriptorWrite()
                protectionCoordinator.onSubscriptionCompleted(completed.characteristic.uuid)
            }
        }
    }

    private fun onCharacteristicWrite(
        callbackGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        if (!isCurrent(callbackGatt)) return
        sessions.current()?.markOperationCompleted()
        operationCoordinator.complete(
            status = status,
            label = "write ${characteristic.uuid.shortName()}",
            matchesActiveOperation = { operation ->
                operation is GattOperation.Write && operation.characteristic.uuid == characteristic.uuid
            },
        ) { completed ->
            if (completed is GattOperation.Write) onWriteCompleted(completed)
        }
    }

    /**
     * Records a signal-strength reading, or notes why there is not one.
     *
     * A non-success status leaves the last good value showing rather than blanking the meter —
     * one failed poll is not evidence the link has degraded. It is logged, because a reading
     * that never succeeds is otherwise indistinguishable from one that is simply not changing,
     * and the displayed value would sit frozen at whatever it last managed to read.
     *
     * Only the transition is logged. These poll every ten seconds for as long as the session is
     * up, so logging each failure would bury the rest of the journal on a link that is failing
     * exactly the way this is meant to reveal.
     */
    private fun onRssiRead(callbackGatt: BluetoothGatt, rssi: Int, status: Int) {
        if (!isCurrent(callbackGatt)) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            if (!rssiReadFailing) {
                rssiReadFailing = true
                log("RSSI reads are failing with status $status; the displayed value is stale")
            }
            return
        }
        if (rssiReadFailing) {
            rssiReadFailing = false
            log("RSSI reads recovered")
        }
        diagnosticsRecorder.setRssi(rssi)
        val current = mutableConnectionState.value
        if (current is BikeConnectionState.Connected) {
            mutableConnectionState.value = current.copy(rssi = rssi)
        }
    }

    private fun discoverServices(gatt: BluetoothGatt) {
        val started = try {
            gatt.discoverServices()
        } catch (error: RuntimeException) {
            // A GATT closed underneath this call can surface as an IllegalStateException
            // from native code on some Bluetooth stacks rather than a false return.
            log("Service discovery threw: ${error.message}")
            false
        }
        if (!started) {
            retireLinkAndReconnect(
                connectionFailure(
                    message = "Link lost while starting service discovery: rejected by the Bluetooth stack",
                    category = ConnectionFailureCategory.LinkLost,
                ),
            )
        }
    }

    /**
     * Routes an inbound notification by characteristic.
     *
     * Several branches call `acceptEvidence`: a notification the cluster only sends over an
     * established session is what promotes the handshake to authenticated, so each decoded
     * value doubles as proof the link is genuinely up.
     */
    private fun onNotification(callbackGatt: BluetoothGatt, uuid: UUID, value: ByteArray) {
        if (!isCurrent(callbackGatt)) return
        captureRecorder.record(BleCaptureDirection.Notification, uuid, value)
        val now = System.currentTimeMillis()
        val frameLine = "${uuid.shortName()} ${value.toHex(" ")}"
        if (uuid != BleCharacteristics.Telemetry) {
            diagnosticsRecorder.recordNotification(frameLine, now)
        }
        when (uuid) {
            BleCharacteristics.ProtectionChallenge -> protectionCoordinator.onChallenge(value)

            BleCharacteristics.Telemetry -> {
                val acceptance = telemetryStream.accept(
                    payload = value,
                    receivedAtMillis = now,
                    elapsedRealtime = SystemClock::elapsedRealtime,
                )
                diagnosticsRecorder.recordTelemetryNotification(
                    frameLine = frameLine,
                    receivedAtMillis = now,
                    telemetryHz = acceptance.telemetryHz,
                    droppedRawTelemetryFrames = acceptance.droppedRawTelemetryFrames,
                    malformed = !acceptance.valid,
                )
                if (acceptance.valid) protectionCoordinator.acceptEvidence("valid telemetry")
            }

            BleCharacteristics.Vin -> {
                val vin = value.decodeBikeVin()
                if (vin == null) {
                    log("Ignored malformed VIN frame (${value.size} bytes)")
                } else {
                    updateIdentity(vin = vin)
                    protectionCoordinator.acceptEvidence("VIN")
                }
            }

            BleCharacteristics.ClusterSoftwareVersion -> {
                val version = value.decodeClusterSoftwareVersion()
                updateIdentity(version = version)
                if (version.isNotBlank()) protectionCoordinator.acceptEvidence("cluster software version")
            }

            BleCharacteristics.NavigationControl -> {
                // The command is byte 1 of a three-byte event, not byte 0. Reading byte 0
                // instead is what once left the handlebar unable to skip a waypoint or exit;
                // what byte 0 itself carries has not been established.
                val command = value.takeIf { it.size >= 3 }?.get(1)?.toInt()?.and(0xFF)
                when (command) {
                    1 -> BikeControlEvent.StartNavigation
                    2 -> BikeControlEvent.SkipManeuver
                    3 -> BikeControlEvent.ExitNavigation
                    else -> {
                        log("Unhandled navigation control ${value.toHex(" ")}")
                        null
                    }
                }?.let(mutableControls::tryEmit)
            }

            // Despite the name, this characteristic is not call-only. It carries four
            // distinct meanings in a single byte: 0 and 1 are handlebar reject and answer,
            // 2 is the cluster announcing that it has come up and wants the phone's state
            // resent, and 3 is the cluster asserting that a call is live on its side.
            BleCharacteristics.CallControl -> when (value.firstOrNull()?.toInt()?.and(0xFF)) {
                0, 1 -> BikeControlEvent.CallAction(value.first().toInt() and 0xFF)
                2 -> BikeControlEvent.ClusterReady
                3 -> BikeControlEvent.ClusterCallActive
                else -> {
                    log("Unhandled call control ${value.toHex(" ")}")
                    null
                }
            }?.let(mutableControls::tryEmit)
        }
    }

    /**
     * The session is fully up. Resets the retry budget — this connection worked, so a later
     * failure starts its backoff from scratch — cancels the connection timeout, and starts
     * signal-strength polling.
     */
    private fun completeAuthentication(evidence: String, path: ProtectionPath?) {
        if (diagnosticsRecorder.value.authenticated) return
        reconnectAttempt = 0
        consecutiveEncryptionStalls = 0
        authenticatedAtMillis = System.currentTimeMillis()
        mainHandler.removeCallbacksAndMessages(ConnectionTimeoutToken)
        diagnosticsRecorder.markAuthenticated(path)
        diagnosticsRecorder.updateAttempt(attemptContext())
        connectionTarget?.address?.let { address ->
            bikeIdentityRepository.update(address) { identity ->
                identity.copy(lastConnectedAtMillis = System.currentTimeMillis())
            }
        }
        mutableConnectionState.value = BikeConnectionState.Connected(deviceName ?: "Motorcycle", null)
        log("Protected session verified by $evidence")
        connectionMonitoringActive = true
        scheduleRssiRead()
    }

    private fun reconnectAfterProtectionFailure(message: String) {
        retireLinkAndReconnect(
            connectionFailure(message, ConnectionFailureCategory.LinkLost),
        )
    }

    private fun onWriteCompleted(operation: GattOperation.Write) {
        diagnosticsRecorder.countWrite()
        if (operation.isProtectionResponseWrite()) {
            protectionCoordinator.onProtectionResponseWritten()
        }
        operation.completion?.complete(true)
    }

    /** The single path that retires a live GATT session and hands the link back to the backoff. */
    /**
     * Whether this disconnect is the motorcycle refusing the stored bonding key.
     *
     * The signature is narrow on purpose: a **bonded** peer whose link reached STATE_CONNECTED,
     * dropped with the timeout status, and completed **no** ATT operation at all. A link that
     * transacted even once got through encryption, so an ordinary dropout can never match.
     *
     * The link age is not part of the test. It would only be restating the supervision timeout the
     * controller has already applied by reporting the disconnect, and hard-coding a window here
     * would mean a peer that negotiates a different one stops being recognised.
     */
    private fun isEncryptionStall(session: GattSession<BluetoothGatt>, status: Int): Boolean =
        connectedDeviceBonded &&
            status == GattConnectionTimeoutStatus &&
            session.connectedAtElapsedRealtime != null &&
            !session.completedAnyOperation

    /**
     * Records a stall and, once it repeats, stops retrying and says so.
     *
     * Two in a row rather than one: a genuine range dropout in the instant between connecting and
     * the first CCCD write produces the same signature once, and retiring the link over a single
     * sample would strand a rider whose bike simply moved out of range at the wrong moment.
     *
     * Past that the state is terminal. The phone cannot present a different key, so every further
     * attempt costs a five-second connection and ends identically — which is what filled a capture
     * with forty-four of them. Only a fresh appearance or an explicit user action resumes.
     */
    private fun reportEncryptionStall(message: String, linkAgeMillis: Long?) {
        consecutiveEncryptionStalls++
        val failure = connectionFailure(
            message = "$message; no operation completed, so encryption did not finish",
            category = ConnectionFailureCategory.PairingRejected,
            statusCode = GattConnectionTimeoutStatus,
            statusName = gattConnectionStatusLabel(GattConnectionTimeoutStatus),
            linkAgeMillis = linkAgeMillis,
        )
        if (consecutiveEncryptionStalls < MinStallsBeforeGivingUp) {
            retireLinkAndReconnect(failure, updateConnectingState = false)
            return
        }
        recordConnectionFailure(failure)
        disconnectInternal(closeOnly = true)
        val reason = "The motorcycle is refusing the saved pairing. Turn the ignition off and on; " +
            "if that does not help, forget it in Bluetooth settings and pair again."
        mutableConnectionState.value = BikeConnectionState.Failed(reason, retriesExhausted = true)
        log(reason)
    }

    private fun retireLinkAndReconnect(
        failure: ConnectionFailure,
        updateConnectingState: Boolean = true,
    ) {
        recordConnectionFailure(failure)
        disconnectInternal(closeOnly = true)
        if (intentionalDisconnect) return
        if (updateConnectingState) {
            mutableConnectionState.value = BikeConnectionState.Connecting(deviceName)
        }
        scheduleReconnect()
    }

    /**
     * Last word on an operation that has run out of retries. Returns true when this failure
     * ends the whole connection rather than just that operation.
     *
     * Every step of the handshake qualifies: without it there is no session, so continuing
     * to drain the queue would leave the link half-established. Anything else — a display
     * write — is the caller's problem, and the link survives it.
     */
    private fun handleExhaustedOperation(operation: GattOperation): Boolean = when {
        operation.isChallengeSubscription() -> {
            failWithCategory(
                "Could not enable motorcycle authentication indications",
                ConnectionFailureCategory.Deterministic,
            )
            true
        }

        operation.isProtectionResponseWrite() -> {
            failWithCategory(
                "Motorcycle authentication response could not be delivered",
                ConnectionFailureCategory.Deterministic,
            )
            true
        }

        operation.isPostAuthenticationSubscription() -> {
            val uuid = (operation as GattOperation.Subscribe).characteristic.uuid
            protectionCoordinator.onRequiredProfileFailure(uuid)
            true
        }

        else -> false
    }

    private fun isChallengeResponsePending(): Boolean =
        protectionCoordinator.phase == ProtectionPhase.Responding

    /**
     * Polls signal strength while a session is up, so the UI can show link quality.
     *
     * Self-rescheduling, and keeps polling through a refused request: a refusal usually means the
     * stack is momentarily unregistered, and giving up would leave the meter frozen for the rest
     * of a session that then recovers. It stops only when the call *throws*, which means the GATT
     * underneath it is gone.
     *
     * There is deliberately no in-flight guard. A guard without a stale timeout turns one lost
     * callback into permanently stopped polling, and RSSI requests do not contend through
     * Android's ATT busy guard anyway — see `docs/cluster-link-decisions.md` (D6). Refusals are
     * logged instead, on the transition only, for the same reason as the callback failures above.
     */
    private val rssiRunnable = object : Runnable {
        override fun run() {
            if (!connectionMonitoringActive) return
            val requested = try {
                sessions.current()?.openTransport()?.readRemoteRssi() ?: false
            } catch (error: RuntimeException) {
                // A GATT closed underneath this call can throw from native code rather than
                // returning false. Stop polling instead of crashing the main handler.
                connectionMonitoringActive = false
                log("RSSI read threw, monitoring disabled: ${error.message}")
                return
            }
            if (!requested) {
                if (!rssiRequestRefused) {
                    rssiRequestRefused = true
                    log("RSSI reads are being refused; no callback will arrive for them")
                }
            } else if (rssiRequestRefused) {
                rssiRequestRefused = false
                log("RSSI reads are being accepted again")
            }
            mainHandler.postDelayed(this, RssiIntervalMillis)
        }
    }

    /** Whether the last [readRemoteRssi] request was refused, so only transitions are logged. */
    private var rssiRequestRefused = false

    /** Whether the last RSSI callback carried a failure status. Transition-logged, as above. */
    private var rssiReadFailing = false

    private fun scheduleRssiRead() {
        mainHandler.removeCallbacks(rssiRunnable)
        if (!connectionMonitoringActive) return
        mainHandler.postDelayed(rssiRunnable, RssiIntervalMillis)
    }

    /**
     * Schedules the next automatic attempt, or reports the budget as spent.
     *
     * Exhaustion is deliberately not a new failure: the failure that caused it is already
     * on record and is what the rider needs to see. Only a fresh appearance of the bike or
     * an explicit user action resumes from here — see [shouldAutoConnectOnLaunch].
     */
    private fun scheduleReconnect() {
        if (intentionalDisconnect || reconnectScheduled) return
        val delay = reconnectDelayMillis(reconnectAttempt)
        if (delay == null) {
            // Deliberately does not name a cause. Exhausted retries are equally consistent with
            // the bike being out of range, switched off, or an adapter problem on this phone,
            // and the failure that actually caused it is already on record above.
            val reason = "Could not reconnect; automatic retries paused after " +
                "$MaxReconnectAttempts attempts"
            mutableConnectionState.value = BikeConnectionState.Failed(reason, retriesExhausted = true)
            // The real failure stays on record; this only explains why nothing is retrying.
            diagnosticsRecorder.recordSuppression(
                reason = reason,
                category = ConnectionFailureCategory.LinkLost,
                context = attemptContext(),
            )
            log(reason)
            return
        }
        reconnectAttempt++
        reconnectScheduled = true
        attemptTrigger = ConnectionAttemptTrigger.AutomaticReconnect
        diagnosticsRecorder.updateAttempt(attemptContext())
        mutableConnectionState.value =
            BikeConnectionState.Connecting(deviceName, reconnectAttempt, MaxReconnectAttempts)
        log("Reconnecting in ${delay / 1_000}s (attempt $reconnectAttempt/$MaxReconnectAttempts)")
        mainHandler.postAtTime(
            {
                reconnectScheduled = false
                if (!intentionalDisconnect) connectGatt()
            },
            ReconnectToken,
            SystemClock.uptimeMillis() + delay,
        )
    }

    /**
     * Single teardown path: cancels every timer, resets each collaborator, and retires the
     * session. [closeOnly] skips the graceful `disconnect()` — meaningful only while the
     * link still works, and wasted on one that has already failed.
     */
    private fun disconnectInternal(closeOnly: Boolean) {
        connectionMonitoringActive = false
        reconnectScheduled = false
        mainHandler.removeCallbacks(rssiRunnable)
        mainHandler.removeCallbacksAndMessages(ReconnectToken)
        bondCoordinator.cancel()
        mainHandler.removeCallbacksAndMessages(ConnectionTimeoutToken)
        protectionCoordinator.reset()
        operationCoordinator.clear()
        profile.clear()
        telemetryStream.reset()
        val session = sessions.current()
        diagnosticsRecorder.resetForTeardown(
            sessionId = session?.id,
            establishedAtMillis = authenticatedAtMillis,
            durationMillis = authenticatedAtMillis?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) },
        )
        authenticatedAtMillis = null
        connectedDevice = null
        sessions.retireCurrent(disconnectFirst = !closeOnly)
    }

    /**
     * A callback from a GATT instance the app has already retired. The registry closed it exactly
     * once when it was retired, so closing again here would be the second close of the same handle.
     */
    private fun discardStaleCallback(callbackGatt: BluetoothGatt, description: String) {
        if (sessions.isRetired(callbackGatt)) {
            log("Ignoring $description from a retired GATT session")
            return
        }
        log("Closing an unrecognised GATT instance after a $description")
        sessions.closeUnadopted(callbackGatt, SystemClock.elapsedRealtime())
    }

    private fun updateIdentity(vin: String? = null, version: String? = null) {
        val address = connectionTarget?.address ?: return
        bikeIdentityRepository.update(address) { identity ->
            identity.copy(
                vin = vin?.takeIf(String::isNotBlank) ?: identity.vin,
                clusterSoftwareVersion = version?.takeIf(String::isNotBlank)
                    ?: identity.clusterSoftwareVersion,
            )
        }
    }

    /** A failure the phone caused: permissions, adapter state, or a service that would not start. */
    private fun failLocally(message: String) {
        failWithCategory(message, ConnectionFailureCategory.LocalPrecondition)
    }

    private fun failWithCategory(message: String, category: ConnectionFailureCategory) {
        // Built before teardown so the failure still carries the session it happened in.
        val failure = connectionFailure(message, category)
        disconnectInternal(closeOnly = true)
        mutableConnectionState.value = BikeConnectionState.Failed(message)
        recordConnectionFailure(failure)
    }

    private fun recordConnectionFailure(failure: ConnectionFailure) {
        diagnosticsRecorder.recordFailure(failure)
        log(failure.message)
    }

    private fun connectionFailure(
        message: String,
        category: ConnectionFailureCategory,
        statusCode: Int? = null,
        statusName: String? = null,
        linkAgeMillis: Long? = null,
    ): ConnectionFailure = ConnectionFailure(
        message = message,
        category = category,
        atMillis = System.currentTimeMillis(),
        statusCode = statusCode,
        statusName = statusName,
        context = attemptContext(linkAgeMillis),
    )

    /**
     * Snapshot of the current attempt, attached to every failure so a diagnostics entry
     * carries which session, which retry, and what bond state it happened under.
     */
    private fun attemptContext(linkAgeMillis: Long? = null): ConnectionAttemptContext {
        val session = sessions.current()
        return ConnectionAttemptContext(
            sessionId = session?.id,
            trigger = attemptTrigger,
            reconnectAttempt = reconnectAttempt,
            linkAgeMillis = linkAgeMillis ?: session?.linkAgeMillis(SystemClock.elapsedRealtime()),
            bondState = bondStateSnapshot(connectedDevice?.bondState),
        )
    }

    private fun bondStateSnapshot(bondState: Int?): BondStateSnapshot = when (bondState) {
        BluetoothDevice.BOND_NONE -> BondStateSnapshot.None
        BluetoothDevice.BOND_BONDING -> BondStateSnapshot.Bonding
        BluetoothDevice.BOND_BONDED -> BondStateSnapshot.Bonded
        else -> BondStateSnapshot.Unknown
    }

    /** Confines mutable state to one thread; runs inline when already on it. */
    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) block() else mainHandler.post(block)
    }

    private fun isCurrent(callbackGatt: BluetoothGatt): Boolean = sessions.isCurrent(callbackGatt)

    private fun log(message: String) {
        connectionEventJournal.record(message)
    }

    private companion object {
        // Handler tokens, so each kind of pending work can be cancelled without disturbing
        // the others posted to the same handler.
        val ReconnectToken = Any()
        val ConnectionTimeoutToken = Any()

        /** Signal-strength poll interval. Slow: this is a quality indicator, not telemetry. */
        const val RssiIntervalMillis = 10_000L

        /** Backstop for an awaited write, generous enough to outlast a queue and its retries. */
        const val AwaitedWriteTimeoutMillis = 30_000L

        /** Bond, connect, discover and authenticate must all complete inside this. */
        const val ConnectionTimeoutMillis = 20_000L

        /**
         * Android's status for a link that died on the supervision timeout. Also its value for
         * GATT_INSUFFICIENT_AUTHORIZATION, which is why the stall test above needs the rest of
         * its signature rather than the status alone.
         */
        const val GattConnectionTimeoutStatus = 8

        /** Consecutive encryption stalls before the state becomes terminal. */
        const val MinStallsBeforeGivingUp = 2

        /** The ATT default. No larger MTU is requested; see [onConnectionStateChanged]. */
        const val DefaultAttMtu = 23
    }
}
