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
 * Nothing outside this class may start, retry, or tear down a GATT session: the foreground service
 * and the companion presence receiver express demand, and this class decides. Sessions are tracked
 * through [GattSessionRegistry] so each Android instance is closed exactly once even when a retired
 * instance keeps delivering callbacks, and every failure is recorded as a structured
 * [ConnectionFailure] rather than a bare string.
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
    private val sessions = GattSessionRegistry<BluetoothGatt>(::closeAndroidGatt)
    private var connectedDevice: BluetoothDevice? = null
    private var connectionTarget: BikeConnectionTarget? = null
    private var deviceName: String? = null
    private var connectedDeviceBonded = false
    private var intentionalDisconnect = false
    private var reconnectAttempt = 0
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

    override fun enqueueWrite(characteristic: UUID, payload: ByteArray) {
        runOnMain { writeInternal(characteristic, payload) }
    }

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

    private fun startGattConnection(device: BluetoothDevice) {
        val target = connectionTarget ?: return
        val requestedGeneration = connectionGeneration
        val address = target.address.toString()
        log("Connecting to ${deviceName.orEmpty()} (${address.takeLast(5)}), bonded=true")
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
        handleRead = { callbackGatt, characteristic, value, status ->
            onRead(callbackGatt, characteristic.uuid, value, status)
        },
        handleDescriptorWrite = ::onDescriptorWrite,
        handleWrite = ::onCharacteristicWrite,
        handleRssiRead = ::onRssiRead,
    )

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
                // RideBuddy does not alter the OEM connection sequence with an MTU request.
                // Until Android reports a negotiated value, the active ATT bearer uses 23 bytes.
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

    private fun onDescriptorWrite(
        callbackGatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        status: Int,
    ) {
        if (!isCurrent(callbackGatt)) return
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

    private fun onRssiRead(callbackGatt: BluetoothGatt, rssi: Int, status: Int) {
        if (!isCurrent(callbackGatt)) return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            diagnosticsRecorder.setRssi(rssi)
            val current = mutableConnectionState.value
            if (current is BikeConnectionState.Connected) {
                mutableConnectionState.value = current.copy(rssi = rssi)
            }
        }
    }

    private fun discoverServices(gatt: BluetoothGatt) {
        val started = try {
            gatt.discoverServices()
        } catch (error: RuntimeException) {
            // Closed gatt can throw IllegalStateException from native on some OEM stacks.
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
                // The handlebar command is byte 1 of a three-byte event, not byte 0: the OEM
                // reads `value[1]` after checking `value.length == 3`. Reading byte 0 meant the
                // handlebar could never skip a waypoint or exit navigation.
                val command = value.takeIf { it.size >= 3 }?.get(1)?.toInt()?.and(0xFF)
                when (command) {
                    2 -> BikeControlEvent.SkipManeuver
                    3 -> BikeControlEvent.ExitNavigation
                    else -> {
                        log("Unhandled navigation control ${value.toHex(" ")}")
                        null
                    }
                }?.let(mutableControls::tryEmit)
            }

            BleCharacteristics.CallControl -> value.firstOrNull()?.let {
                mutableControls.tryEmit(BikeControlEvent.CallAction(it.toInt() and 0xFF))
            }
        }
    }

    private fun onRead(callbackGatt: BluetoothGatt, uuid: UUID, value: ByteArray, status: Int) {
        if (!isCurrent(callbackGatt)) return
        captureRecorder.record(BleCaptureDirection.Read, uuid, value, "status=$status")
        operationCoordinator.complete(
            status = status,
            label = "read ${uuid.shortName()}",
            matchesActiveOperation = { operation ->
                operation is GattOperation.Read && operation.characteristic.uuid == uuid
            },
        ) { completed ->
            if (completed is GattOperation.Read) {
                diagnosticsRecorder.countRead()
                log("Read ${uuid.shortName()} completed (${value.size} bytes)")
                when (uuid) {
                    BleCharacteristics.Vin -> {
                        val vin = value.decodeBikeVin()
                        if (vin != null) {
                            updateIdentity(vin = vin)
                            protectionCoordinator.acceptEvidence("VIN read")
                        }
                    }

                    BleCharacteristics.ClusterSoftwareVersion -> {
                        val version = value.decodeClusterSoftwareVersion()
                        updateIdentity(version = version)
                        if (version.isNotBlank()) {
                            protectionCoordinator.acceptEvidence("cluster software version read")
                        }
                    }
                }
            }
        }
    }

    private fun completeAuthentication(evidence: String, path: ProtectionPath?) {
        if (diagnosticsRecorder.value.authenticated) return
        reconnectAttempt = 0
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

    private val rssiRunnable = object : Runnable {
        override fun run() {
            if (!connectionMonitoringActive) return
            try {
                sessions.current()?.openTransport()?.readRemoteRssi()
            } catch (error: RuntimeException) {
                // Closed gatt can throw IllegalStateException from native on some OEM stacks.
                // Stop polling rather than crashing the main handler.
                connectionMonitoringActive = false
                log("RSSI read threw, monitoring disabled: ${error.message}")
                return
            }
            mainHandler.postDelayed(this, RssiIntervalMillis)
        }
    }

    private fun scheduleRssiRead() {
        mainHandler.removeCallbacks(rssiRunnable)
        if (!connectionMonitoringActive) return
        mainHandler.postDelayed(rssiRunnable, RssiIntervalMillis)
    }

    private fun scheduleReconnect() {
        if (intentionalDisconnect || reconnectScheduled) return
        val delay = reconnectDelayMillis(reconnectAttempt)
        if (delay == null) {
            val reason = "Bike is out of range; automatic retries paused after " +
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

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) block() else mainHandler.post(block)
    }

    private fun isCurrent(callbackGatt: BluetoothGatt): Boolean = sessions.isCurrent(callbackGatt)

    private fun log(message: String) {
        connectionEventJournal.record(message)
    }

    private companion object {
        val ReconnectToken = Any()
        val ConnectionTimeoutToken = Any()
        const val RssiIntervalMillis = 10_000L
        const val AwaitedWriteTimeoutMillis = 30_000L
        const val ConnectionTimeoutMillis = 20_000L
        const val DefaultAttMtu = 23
    }
}
