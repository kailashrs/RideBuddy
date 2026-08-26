package com.spaceboy.ridebuddy.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

@SuppressLint("MissingPermission")
internal class AndroidBikeConnection(
    context: Context,
    private val captureRecorder: BleCaptureRecorder,
    private val protectionAcceptanceStore: ProtectionAcceptanceStore,
    private val connectionEventJournal: ConnectionEventJournal,
    private val bikeIdentityRepository: BikeIdentityRepository,
) : BikeConnection {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(android.bluetooth.BluetoothManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bondCoordinator = BluetoothBondCoordinator(
        context = appContext,
        handler = mainHandler,
        isGenerationCurrent = { generation -> generation == connectionGeneration },
        onBondReady = ::onBondReady,
        onFailure = ::fail,
        log = ::log,
    )
    private val profile = BikeGattProfile()
    private var gatt: BluetoothGatt? = null
    private var connectedDevice: BluetoothDevice? = null
    private var connectionTarget: BikeConnectionTarget? = null
    private var deviceName: String? = null
    private var connectedDeviceBonded = false
    private var intentionalDisconnect = false
    private var reconnectAttempt = 0
    private var connectionGeneration = 0L
    private var connectionMonitoringActive = false
    private var gattConnectedAtElapsedRealtime: Long? = null
    private val nextWriteRequestId = AtomicLong()
    private val telemetryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val telemetryStream = BikeTelemetryStream(telemetryScope)

    private val mutableConnectionState = MutableStateFlow<BikeConnectionState>(BikeConnectionState.Disconnected)
    private val mutableDiagnostics = MutableStateFlow(
        BleDiagnostics(recentEvents = connectionEventJournal.events.value),
    )
    private val mutableControls = MutableSharedFlow<BikeControlEvent>(extraBufferCapacity = 8)
    private val operationCoordinator = GattOperationCoordinator(
        handler = mainHandler,
        executor = AndroidGattOperationExecutor(captureRecorder),
        currentGatt = { gatt },
        isChallengeResponsePending = ::isChallengeResponsePending,
        onActiveOperationChanged = { label ->
            mutableDiagnostics.update { diagnostics -> diagnostics.copy(activeGattOperation = label) }
        },
        onFailureRecorded = { message ->
            mutableDiagnostics.update { diagnostics ->
                diagnostics.copy(lastError = message, lastErrorAtMillis = System.currentTimeMillis())
            }
        },
        onResetRequired = ::resetGattAfterOperationTimeout,
        onOperationExhausted = ::handleExhaustedOperation,
        log = ::log,
    )
    private val protectionCoordinator = ProtectionCoordinator(
        handler = mainHandler,
        currentGeneration = { connectionGeneration },
        characteristic = { uuid -> profile[uuid] },
        enqueue = operationCoordinator::enqueue,
        enqueueAll = operationCoordinator::enqueueAll,
        isAuthenticated = { mutableDiagnostics.value.authenticated },
        markAccepted = { connectionTarget?.address?.let(protectionAcceptanceStore::markAccepted) },
        clearAcceptance = { connectionTarget?.address?.let(protectionAcceptanceStore::clear) },
        onAuthenticated = ::completeAuthentication,
        onFailure = ::fail,
        onReconnectRequired = ::reconnectAfterProtectionFailure,
        updateDiagnostics = { phase, path ->
            mutableDiagnostics.update { diagnostics ->
                diagnostics.copy(protectionPhase = phase, protectionPath = path)
            }
        },
        log = ::log,
    )

    init {
        telemetryScope.launch {
            connectionEventJournal.events.collect { events ->
                mainHandler.post {
                    mutableDiagnostics.update { diagnostics ->
                        diagnostics.copy(recentEvents = events)
                    }
                }
            }
        }
    }

    override val connectionState: StateFlow<BikeConnectionState> = mutableConnectionState.asStateFlow()
    override val rawTelemetry = telemetryStream.rawTelemetry
    override val telemetry = telemetryStream.telemetry
    override val latestTelemetryReading = telemetryStream.latestReading
    override val identity = bikeIdentityRepository.identity
    override val diagnostics: StateFlow<BleDiagnostics> = mutableDiagnostics.asStateFlow()
    override val controls: SharedFlow<BikeControlEvent> = mutableControls

    override fun connect(target: BikeConnectionTarget) {
        mainHandler.post {
            if (!shouldStartConnection(connectionTarget, target, mutableConnectionState.value)) {
                log("Ignored duplicate connection request for ${target.deviceName}; connection already active")
                return@post
            }
            connectionGeneration++
            intentionalDisconnect = true
            disconnectInternal(closeOnly = false)
            connectionTarget = target
            deviceName = target.deviceName
            bikeIdentityRepository.select(target.address)
            mutableDiagnostics.update { diagnostics ->
                diagnostics.copy(
                    lastError = null,
                    lastErrorAtMillis = null,
                    protectionPhase = ProtectionPhase.Idle,
                    protectionPath = null,
                    activeGattOperation = null,
                )
            }
            reconnectAttempt = 0
            mainHandler.removeCallbacksAndMessages(ReconnectToken)
            connectGatt()
            intentionalDisconnect = false
        }
    }

    override fun disconnect() {
        mainHandler.post {
            intentionalDisconnect = true
            connectionMonitoringActive = false
            mainHandler.removeCallbacksAndMessages(ReconnectToken)
            disconnectInternal(closeOnly = false)
            mutableConnectionState.value = BikeConnectionState.Disconnected
            log("Disconnected by app request")
        }
    }

    override fun notifyStartFailed(message: String) {
        mainHandler.post { fail(message) }
    }

    override fun enqueueWrite(characteristic: UUID, payload: ByteArray) {
        mainHandler.post { writeInternal(characteristic, payload) }
    }

    override suspend fun writeAndAwait(write: BikeWrite): Boolean {
        val completion = CompletableDeferred<Boolean>()
        val requestId = nextWriteRequestId.incrementAndGet()
        mainHandler.post {
            if (!mutableDiagnostics.value.authenticated) {
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
                        log("Aborting timed-out write")
                        disconnectInternal(closeOnly = true)
                        completion.complete(false)
                        mutableConnectionState.value = BikeConnectionState.Connecting(deviceName)
                        scheduleReconnect()
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
        if (mutableDiagnostics.value.authenticated.not()) return
        val target = profile[characteristic] ?: return
        operationCoordinator.enqueue(GattOperation.Write(target, payload.copyOf()))
    }

    private fun connectGatt() {
        val target = connectionTarget ?: return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            fail("Allow Nearby devices to connect to the motorcycle")
            return
        }

        val adapter = bluetoothManager.adapter ?: run {
            fail("Bluetooth is unavailable on this phone")
            return
        }
        val bluetoothEnabled = try {
            adapter.isEnabled
        } catch (error: SecurityException) {
            log("Bluetooth state check failed: ${error.javaClass.simpleName}")
            fail("Allow Nearby devices to connect to the motorcycle")
            return
        }
        if (!bluetoothEnabled) {
            fail("Turn on Bluetooth to connect to the motorcycle")
            return
        }

        val device = try {
            adapter.getRemoteDevice(target.address.toByteArray())
        } catch (error: SecurityException) {
            log("Bluetooth device access failed: ${error.javaClass.simpleName}")
            fail("Allow Nearby devices to connect to the motorcycle")
            return
        } catch (error: IllegalArgumentException) {
            log("Bluetooth address rejected: ${error.message.orEmpty()}")
            fail("The saved motorcycle address is invalid")
            return
        } catch (error: RuntimeException) {
            log("Bluetooth device resolution failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            fail("Android could not resolve the paired motorcycle")
            return
        }

        val bondState = runCatching { device.bondState }.getOrNull()
        connectedDeviceBonded = bondState == BluetoothDevice.BOND_BONDED
        connectedDevice = device
        if (bondState == BluetoothDevice.BOND_NONE) protectionAcceptanceStore.clear(target.address)
        mutableConnectionState.value = BikeConnectionState.Connecting(deviceName)
        telemetryStream.clearUiTelemetry()
        mutableDiagnostics.value = mutableDiagnostics.value.copy(
            authenticated = false,
            protectionPhase = ProtectionPhase.Idle,
            protectionPath = null,
            bonded = bondState?.let { it == BluetoothDevice.BOND_BONDED },
            attMtu = null,
            servicesDiscovered = 0,
            rssi = null,
            activeGattOperation = null,
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
        mutableDiagnostics.update { it.copy(bonded = true) }
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
        } catch (error: SecurityException) {
            log("GATT permission failure: ${error.javaClass.simpleName}")
            fail("Allow Nearby devices to connect to the motorcycle")
            return
        } catch (error: RuntimeException) {
            log("GATT start failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            fail("Android could not start the Bluetooth connection")
            return
        }
        if (newGatt == null) {
            log("Could not create a GATT connection")
            scheduleReconnect()
            return
        }
        if (requestedGeneration != connectionGeneration) {
            newGatt.close()
            return
        }
        gatt = newGatt
        mainHandler.removeCallbacksAndMessages(ConnectionTimeoutToken)
        mainHandler.postAtTime({
            if (requestedGeneration == connectionGeneration && gatt === newGatt &&
                mutableConnectionState.value !is BikeConnectionState.Connected
            ) {
                log("Timed out connecting to the bike")
                disconnectInternal(closeOnly = true)
                scheduleReconnect()
            }
        }, ConnectionTimeoutToken, android.os.SystemClock.uptimeMillis() + ConnectionTimeoutMillis)
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
            callbackGatt.close()
            return
        }
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log("GATT connection failed, status $status")
                    val wasIntentional = intentionalDisconnect
                    disconnectInternal(closeOnly = true)
                    if (!wasIntentional) scheduleReconnect()
                    return
                }
                gatt = callbackGatt
                gattConnectedAtElapsedRealtime = SystemClock.elapsedRealtime()
                // RideBuddy does not alter the OEM connection sequence with an MTU request.
                // Until Android reports a negotiated value, the active ATT bearer uses 23 bytes.
                mutableDiagnostics.update { diagnostics -> diagnostics.copy(attMtu = DefaultAttMtu) }
                log("GATT connected")
                discoverServices(callbackGatt)
            }

            BluetoothProfile.STATE_DISCONNECTED -> {
                val linkAgeSeconds = gattConnectedAtElapsedRealtime?.let { connectedAt ->
                    (SystemClock.elapsedRealtime() - connectedAt).coerceAtLeast(0L) / 1_000L
                }
                log(
                    "GATT disconnected, status $status (${gattConnectionStatusLabel(status)})" +
                        linkAgeSeconds?.let { ", link age ${it}s" }.orEmpty(),
                )
                val wasIntentional = intentionalDisconnect
                disconnectInternal(closeOnly = true)
                if (!wasIntentional) scheduleReconnect()
            }
        }
    }

    private fun onMtuChanged(callbackGatt: BluetoothGatt, mtu: Int, status: Int) {
        if (!isCurrent(callbackGatt)) return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            mutableDiagnostics.value = mutableDiagnostics.value.copy(attMtu = mtu)
            log("MTU $mtu")
        }
    }

    private fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
        if (!isCurrent(callbackGatt)) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail("Could not discover bike services ($status)")
            return
        }
        val snapshot = profile.replace(callbackGatt.services)
        mutableDiagnostics.value = mutableDiagnostics.value.copy(
            servicesDiscovered = snapshot.serviceCount,
            serviceSnapshot = snapshot.characteristicLabels,
        )
        if (snapshot.missingRequiredCharacteristics.isNotEmpty()) {
            fail(
                "Motorcycle companion profile is incomplete (missing ${
                    snapshot.missingRequiredCharacteristics.joinToString { it.shortName() }
                })",
            )
            return
        }
        mutableConnectionState.value = BikeConnectionState.Authenticating(deviceName ?: "Motorcycle")
        val address = connectionTarget?.address
        // Re-read the live bond state so a pair that completed between connectGatt() and the
        // service discovery callback is still recognised for the stored-acceptance shortcut.
        val bondState = runCatching { connectedDevice?.bondState }.getOrNull()
        if (bondState != null) connectedDeviceBonded = bondState == BluetoothDevice.BOND_BONDED
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
                mutableDiagnostics.update { diagnostics ->
                    diagnostics.copy(descriptorWritesCompleted = diagnostics.descriptorWritesCompleted + 1)
                }
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
            mutableDiagnostics.value = mutableDiagnostics.value.copy(rssi = rssi)
            val current = mutableConnectionState.value
            if (current is BikeConnectionState.Connected) {
                mutableConnectionState.value = current.copy(rssi = rssi)
            }
        }
    }

    private fun discoverServices(gatt: BluetoothGatt) {
        try {
            if (!gatt.discoverServices()) fail("Could not start service discovery")
        } catch (e: RuntimeException) {
            // Closed gatt can throw IllegalStateException from native on some OEM stacks.
            fail("Service discovery threw: ${e.message}")
        }
    }

    private fun onNotification(callbackGatt: BluetoothGatt, uuid: UUID, value: ByteArray) {
        if (!isCurrent(callbackGatt)) return
        captureRecorder.record(BleCaptureDirection.Notification, uuid, value)
        val now = System.currentTimeMillis()
        val frameLine = "${uuid.shortName()} ${value.toHex(" ")}"
        mutableDiagnostics.update { diagnostics ->
            diagnostics.copy(
                notificationsReceived = diagnostics.notificationsReceived + 1,
                lastFrameAtMillis = now,
                recentFrames = (listOf(frameLine) + diagnostics.recentFrames).take(MaxFrameEntries),
            )
        }
        when (uuid) {
            BleCharacteristics.ProtectionChallenge -> protectionCoordinator.onChallenge(value)

            BleCharacteristics.Telemetry -> {
                val acceptance = telemetryStream.accept(
                    payload = value,
                    receivedAtMillis = now,
                    elapsedRealtime = SystemClock::elapsedRealtime,
                )
                mutableDiagnostics.update { diagnostics ->
                    diagnostics.copy(telemetryHz = acceptance.telemetryHz)
                }
                if (!acceptance.valid) {
                    mutableDiagnostics.update { diagnostics ->
                        diagnostics.copy(malformedTelemetryFrames = diagnostics.malformedTelemetryFrames + 1)
                    }
                } else {
                    protectionCoordinator.acceptEvidence("valid telemetry")
                }
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
                when (value.firstOrNull()?.toInt()?.and(0xFF)) {
                    2 -> BikeControlEvent.SkipManeuver
                    3 -> BikeControlEvent.ExitNavigation
                    else -> null
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
                mutableDiagnostics.update { diagnostics ->
                    diagnostics.copy(readsCompleted = diagnostics.readsCompleted + 1)
                }
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
        if (mutableDiagnostics.value.authenticated) return
        reconnectAttempt = 0
        mainHandler.removeCallbacksAndMessages(ConnectionTimeoutToken)
        mutableDiagnostics.value = mutableDiagnostics.value.copy(
            authenticated = true,
            protectionPhase = ProtectionPhase.Ready,
            protectionPath = path,
        )
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
        mutableDiagnostics.update { diagnostics ->
            diagnostics.copy(lastError = message, lastErrorAtMillis = System.currentTimeMillis())
        }
        log(message)
        disconnectInternal(closeOnly = true)
        if (!intentionalDisconnect) {
            mutableConnectionState.value = BikeConnectionState.Connecting(deviceName)
            scheduleReconnect()
        }
    }

    private fun onWriteCompleted(operation: GattOperation.Write) {
        mutableDiagnostics.update { diagnostics ->
            diagnostics.copy(writesCompleted = diagnostics.writesCompleted + 1)
        }
        if (operation.isProtectionResponseWrite()) {
            protectionCoordinator.onProtectionResponseWritten()
        }
        operation.completion?.complete(true)
    }

    private fun resetGattAfterOperationTimeout() {
        disconnectInternal(closeOnly = true)
        if (!intentionalDisconnect) {
            mutableConnectionState.value = BikeConnectionState.Connecting(deviceName)
            scheduleReconnect()
        }
    }

    private fun handleExhaustedOperation(operation: GattOperation): Boolean = when {
        operation.isChallengeSubscription() -> {
            fail("Could not enable motorcycle authentication indications")
            true
        }

        operation.isProtectionResponseWrite() -> {
            connectionTarget?.address?.let(protectionAcceptanceStore::clear)
            fail("Motorcycle authentication response was rejected")
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
                gatt?.readRemoteRssi()
            } catch (e: RuntimeException) {
                // Closed gatt can throw IllegalStateException from native on some OEM stacks.
                // Stop polling rather than crashing the main handler.
                connectionMonitoringActive = false
                log("RSSI read threw, monitoring disabled: ${e.message}")
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
        if (intentionalDisconnect) return
        val delay = reconnectDelayMillis(reconnectAttempt)
        if (delay == null) {
            fail("Bike is out of range; automatic retries paused")
            return
        }
        reconnectAttempt++
        mutableConnectionState.value = BikeConnectionState.Connecting(deviceName, reconnectAttempt, MaxReconnectAttempts)
        log("Reconnecting in ${delay / 1_000}s")
        mainHandler.postAtTime(
            { if (!intentionalDisconnect) connectGatt() },
            ReconnectToken,
            android.os.SystemClock.uptimeMillis() + delay
        )
    }

    private fun disconnectInternal(closeOnly: Boolean) {
        connectionMonitoringActive = false
        gattConnectedAtElapsedRealtime = null
        mainHandler.removeCallbacks(rssiRunnable)
        bondCoordinator.cancel()
        mainHandler.removeCallbacksAndMessages(ConnectionTimeoutToken)
        protectionCoordinator.reset()
        operationCoordinator.clear()
        profile.clear()
        telemetryStream.reset()
        mutableDiagnostics.value = mutableDiagnostics.value.copy(
            authenticated = false,
            protectionPhase = ProtectionPhase.Idle,
            attMtu = null,
            servicesDiscovered = 0,
            serviceSnapshot = emptyList(),
            lastFrameAtMillis = null,
            rssi = null,
            telemetryHz = 0.0,
            activeGattOperation = null,
        )
        gatt?.let { current ->
            connectedDevice = null
            gatt = null
            if (!closeOnly) runCatching { current.disconnect() }
            runCatching { current.close() }
        }
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

    private fun fail(message: String) {
        disconnectInternal(closeOnly = true)
        mutableConnectionState.value = BikeConnectionState.Failed(message)
        mutableDiagnostics.value =
            mutableDiagnostics.value.copy(lastError = message, lastErrorAtMillis = System.currentTimeMillis())
        log(message)
    }

    private fun isCurrent(callbackGatt: BluetoothGatt): Boolean = callbackGatt === gatt

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
        const val MaxFrameEntries = 30
    }
}
