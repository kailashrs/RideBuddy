package com.spaceboy.ridebuddy.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
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
import com.spaceboy.ridebuddy.domain.BikeIdentity
import com.spaceboy.ridebuddy.domain.BikeWrite
import com.spaceboy.ridebuddy.domain.BleDiagnostics
import com.spaceboy.ridebuddy.domain.ProtectionPhase
import com.spaceboy.ridebuddy.domain.TelemetryReading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

internal fun reconnectDelayMillis(attempt: Int): Long? {
    if (attempt !in 0 until MaxReconnectAttempts) return null
    return minOf(MaxReconnectDelayMillis, 1_000L shl minOf(attempt, 5))
}

private const val MaxReconnectAttempts = 6
private const val MaxReconnectDelayMillis = 30_000L

@SuppressLint("MissingPermission")
@OptIn(FlowPreview::class)
internal class AndroidBikeConnection(
    context: Context,
    private val captureRecorder: BleCaptureRecorder,
    private val protectionAcceptanceStore: ProtectionAcceptanceStore,
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
    private val operationScheduler = GattOperationScheduler()
    private val operationExecutor = AndroidGattOperationExecutor(captureRecorder)
    private val characteristics = mutableMapOf<UUID, BluetoothGattCharacteristic>()
    private var gatt: BluetoothGatt? = null
    private var connectedDevice: BluetoothDevice? = null
    private var connectionTarget: BikeConnectionTarget? = null
    private var deviceName: String? = null
    private var protectionSession: ProtectionSession? = null
    private var postAuthenticationGate: PostAuthenticationGate? = null
    private var connectedDeviceBonded = false
    private var intentionalDisconnect = false
    private var reconnectAttempt = 0
    private var connectionGeneration = 0L
    private var connectionMonitoringActive = false
    private val nextWriteRequestId = AtomicLong()
    private val telemetryTimestamps = ArrayDeque<Long>()
    private val telemetryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rawTelemetryChannel = Channel<TelemetryReading>(Channel.UNLIMITED)

    private val mutableConnectionState = MutableStateFlow<BikeConnectionState>(BikeConnectionState.Disconnected)
    private val mutableTelemetry = MutableStateFlow<TelemetryFrame?>(null)
    private val mutableLatestTelemetryReading = MutableStateFlow<TelemetryReading?>(null)
    private val mutableRawTelemetry = MutableSharedFlow<TelemetryReading>(
        extraBufferCapacity = RawTelemetryBufferCapacity,
    )
    private val mutableIdentity = MutableStateFlow(BikeIdentity())
    private val mutableDiagnostics = MutableStateFlow(BleDiagnostics())
    private val mutableControls = MutableSharedFlow<BikeControlEvent>(extraBufferCapacity = 8)
    private val sampledTelemetry = mutableTelemetry
        .sample(TelemetrySampleIntervalMillis.milliseconds)
        .stateIn(telemetryScope, SharingStarted.Eagerly, null)

    init {
        telemetryScope.launch {
            for (reading in rawTelemetryChannel) mutableRawTelemetry.emit(reading)
        }
    }

    override val connectionState: StateFlow<BikeConnectionState> = mutableConnectionState.asStateFlow()
    override val rawTelemetry: SharedFlow<TelemetryReading> = mutableRawTelemetry
    override val telemetry: StateFlow<TelemetryFrame?> = sampledTelemetry
    override val latestTelemetryReading: StateFlow<TelemetryReading?> = mutableLatestTelemetryReading.asStateFlow()
    override val identity: StateFlow<BikeIdentity> = mutableIdentity.asStateFlow()
    override val diagnostics: StateFlow<BleDiagnostics> = mutableDiagnostics.asStateFlow()
    override val controls: SharedFlow<BikeControlEvent> = mutableControls

    override fun connect(target: BikeConnectionTarget) {
        mainHandler.post {
            if (target.deviceName.hasUnsupportedTelemetryLayout()) {
                fail("This motorcycle model uses an unsupported telemetry format")
                return@post
            }
            connectionGeneration++
            intentionalDisconnect = true
            disconnectInternal(closeOnly = false)
            connectionTarget = target
            deviceName = target.deviceName
            mutableIdentity.value = BikeIdentity()
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
            log("Disconnected")
        }
    }

    override fun notifyStartFailed(message: String) {
        mainHandler.post { fail(message) }
    }

    override fun write(characteristic: UUID, payload: ByteArray): Boolean {
        mainHandler.post { writeInternal(characteristic, payload) }
        return true
    }

    override suspend fun writeAndAwait(characteristic: UUID, payload: ByteArray): Boolean =
        writeAndAwait(BikeWrite(characteristic, payload))

    override suspend fun writeAndAwait(write: BikeWrite): Boolean {
        val completion = CompletableDeferred<Boolean>()
        val requestId = nextWriteRequestId.incrementAndGet()
        mainHandler.post {
            if (!mutableDiagnostics.value.authenticated) {
                completion.complete(false)
                return@post
            }
            val target = characteristics[write.characteristic]
            if (target == null) {
                completion.complete(false)
                return@post
            }
            enqueue(
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
                val activeWrite = operationScheduler.active() as? GattOperation.Write
                when {
                    activeWrite?.requestId == requestId -> {
                        log("Aborting timed-out write")
                        disconnectInternal(closeOnly = true)
                        completion.complete(false)
                        mutableConnectionState.value = BikeConnectionState.Connecting(deviceName)
                        scheduleReconnect()
                    }

                    operationScheduler.removeQueued { operation ->
                        (operation as? GattOperation.Write)?.requestId == requestId
                    }.isNotEmpty() -> completion.complete(false)

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

    override fun writeBatch(writes: List<BikeWrite>, priority: Boolean): Boolean {
        if (!mutableDiagnostics.value.authenticated) return false
        val copied = writes.map { BikeWrite(it.characteristic, it.payload.copyOf(), it.mode) }
        return mainHandler.post {
            if (!mutableDiagnostics.value.authenticated) return@post
            val operations = copied.mapNotNull { write ->
                characteristics[write.characteristic]?.let { characteristic ->
                    GattOperation.Write(characteristic, write.payload, mode = write.mode)
                }
            }
            val update = if (priority) {
                operationScheduler.replaceQueued(
                    removeIf = { operation ->
                        (operation as? GattOperation.Write)?.characteristic?.uuid in
                            BleCharacteristics.NavigationWrites
                    },
                    replacements = operations,
                    front = true,
                )
            } else {
                GattQueueUpdate(
                    removed = emptyList(),
                    shouldStart = operationScheduler.enqueueAll(operations),
                )
            }
            update.removed.filterIsInstance<GattOperation.Write>().forEach { it.completion?.complete(false) }
            if (update.shouldStart) runNextOperation()
        }
    }

    private fun writeInternal(characteristic: UUID, payload: ByteArray) {
        if (mutableDiagnostics.value.authenticated.not()) return
        val target = characteristics[characteristic] ?: return
        enqueue(GattOperation.Write(target, payload.copyOf()))
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
        mutableTelemetry.value = null
        mutableDiagnostics.value = mutableDiagnostics.value.copy(
            authenticated = false,
            protectionPhase = ProtectionPhase.Idle,
            protectionPath = null,
            bonded = bondState?.let { it == BluetoothDevice.BOND_BONDED },
            negotiatedMtu = null,
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

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!isCurrent(gatt)) {
                gatt.close()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        log("GATT connection failed, status $status")
                        disconnectInternal(closeOnly = true)
                        if (!intentionalDisconnect) scheduleReconnect()
                        return
                    }
                    this@AndroidBikeConnection.gatt = gatt
                    log("GATT connected")
                    discoverServices(gatt)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("GATT disconnected, status $status")
                    disconnectInternal(closeOnly = true)
                    if (!intentionalDisconnect) scheduleReconnect()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!isCurrent(gatt)) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mutableDiagnostics.value = mutableDiagnostics.value.copy(negotiatedMtu = mtu)
                log("MTU $mtu")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!isCurrent(gatt)) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Could not discover bike services ($status)")
                return
            }
            characteristics.clear()
            gatt.services.flatMap { it.characteristics }.forEach { characteristics[it.uuid] = it }
            mutableDiagnostics.value = mutableDiagnostics.value.copy(
                servicesDiscovered = gatt.services.size,
                serviceSnapshot = gatt.services.flatMap { service ->
                    service.characteristics.map { characteristic ->
                        "${service.uuid.shortName()}/${characteristic.uuid.shortName()} props=0x${
                            characteristic.properties.toString(
                                16
                            )
                        }"
                    }
                },
            )
            val requiredCharacteristics = listOf(
                BleCharacteristics.ProtectionChallenge,
                BleCharacteristics.ProtectionResponse,
            ) + BleCharacteristics.PostAuthenticationSubscriptions
            val missingCharacteristics = requiredCharacteristics.filterNot(characteristics::containsKey)
            if (missingCharacteristics.isNotEmpty()) {
                fail(
                    "Motorcycle companion profile is incomplete (missing ${
                        missingCharacteristics.joinToString { it.shortName() }
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
            protectionSession = ProtectionSession(previouslyAccepted = previouslyAccepted)
            syncProtectionDiagnostics()
            log("Services ready; authenticating${if (previouslyAccepted) " with stored acceptance" else ""}")
            handleProtectionAction(protectionSession?.begin() ?: ProtectionAction.Fail("Authentication session is missing"))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = onNotification(gatt, characteristic.uuid, value.copyOf())

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) = onRead(gatt, characteristic.uuid, value.copyOf(), status)

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (isCurrent(gatt)) {
                val completed = completeOperation(status, "subscribe ${descriptor.characteristic.uuid.shortName()}") { operation ->
                    operation is GattOperation.Subscribe && operation.characteristic.uuid == descriptor.characteristic.uuid
                }
                if (completed is GattOperation.Subscribe) {
                    onSubscriptionCompleted(completed)
                    runNextOperation()
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (!isCurrent(gatt)) return
            val completed = completeOperation(status, "write ${characteristic.uuid.shortName()}") { operation ->
                operation is GattOperation.Write && operation.characteristic.uuid == characteristic.uuid
            }
            if (completed is GattOperation.Write) {
                onWriteCompleted(completed)
                runNextOperation()
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (!isCurrent(gatt)) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mutableDiagnostics.value = mutableDiagnostics.value.copy(rssi = rssi)
                val current = mutableConnectionState.value
                if (current is BikeConnectionState.Connected) {
                    mutableConnectionState.value = current.copy(rssi = rssi)
                }
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
            BleCharacteristics.ProtectionChallenge -> {
                log("Protection challenge received by indication")
                mainHandler.removeCallbacksAndMessages(ChallengeTimeoutToken)
                handleProtectionAction(
                    protectionSession?.onChallenge(value)
                        ?: ProtectionAction.Fail("Authentication session is missing"),
                )
            }

            BleCharacteristics.Telemetry -> {
                telemetryTimestamps.addLast(now)
                while (telemetryTimestamps.firstOrNull()
                        ?.let { now - it > TelemetryWindowMillis } == true
                ) telemetryTimestamps.removeFirst()
                mutableDiagnostics.update { diagnostics ->
                    diagnostics.copy(telemetryHz = telemetryTimestamps.size / (TelemetryWindowMillis / 1_000.0))
                }
                val frame = TelemetryFrame.parse(value)
                if (frame == null) {
                    mutableDiagnostics.update { diagnostics ->
                        diagnostics.copy(malformedTelemetryFrames = diagnostics.malformedTelemetryFrames + 1)
                    }
                } else {
                    val reading = TelemetryReading(
                        frame = frame,
                        receivedAtMillis = now,
                        receivedAtElapsedRealtime = SystemClock.elapsedRealtime(),
                    )
                    mutableLatestTelemetryReading.value = reading
                    mutableTelemetry.value = frame
                    rawTelemetryChannel.trySend(reading)
                    acceptPostAuthenticationEvidence("valid telemetry")
                }
            }

            BleCharacteristics.Vin -> {
                val vin = value.decodeVin()
                if (vin == null) {
                    log("Ignored malformed VIN frame (${value.size} bytes)")
                } else {
                    updateIdentity(vin = vin)
                    acceptPostAuthenticationEvidence("VIN")
                }
            }

            BleCharacteristics.ClusterSoftwareVersion -> {
                val version = value.cleanText()
                updateIdentity(version = version)
                if (version.isNotBlank()) acceptPostAuthenticationEvidence("cluster software version")
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
        val completed = completeOperation(status, "read ${uuid.shortName()}") { operation ->
            operation is GattOperation.Read && operation.characteristic.uuid == uuid
        }
        if (completed is GattOperation.Read) {
            mutableDiagnostics.update { diagnostics ->
                diagnostics.copy(readsCompleted = diagnostics.readsCompleted + 1)
            }
            log("Read ${uuid.shortName()} completed (${value.size} bytes)")
            when (uuid) {
                BleCharacteristics.Vin -> {
                    val vin = value.decodeVin()
                    if (vin != null) {
                        updateIdentity(vin = vin)
                        acceptPostAuthenticationEvidence("VIN read")
                    }
                }

                BleCharacteristics.ClusterSoftwareVersion -> {
                    val version = value.cleanText()
                    updateIdentity(version = version)
                    if (version.isNotBlank()) acceptPostAuthenticationEvidence("cluster software version read")
                }
            }
            runNextOperation()
        }
    }

    private fun completeAuthentication(evidence: String) {
        if (mutableDiagnostics.value.authenticated) return
        reconnectAttempt = 0
        mainHandler.removeCallbacksAndMessages(ConnectionTimeoutToken)
        mainHandler.removeCallbacksAndMessages(ProtectionVerificationToken)
        connectionTarget?.address?.let(protectionAcceptanceStore::markAccepted)
        mutableDiagnostics.value = mutableDiagnostics.value.copy(
            authenticated = true,
            protectionPhase = ProtectionPhase.Ready,
            protectionPath = protectionSession?.path,
        )
        mutableIdentity.value = mutableIdentity.value.copy(lastConnectedAtMillis = System.currentTimeMillis())
        mutableConnectionState.value = BikeConnectionState.Connected(deviceName ?: "Motorcycle", null)
        log("Protected session verified by $evidence")
        connectionMonitoringActive = true
        scheduleRssiRead()
    }

    private fun beginPostAuthenticationVerification() {
        syncProtectionDiagnostics()
        log("Starting post-authentication verification via ${protectionSession?.path?.name ?: "unknown path"}")
        val subscriptions = BleCharacteristics.PostAuthenticationSubscriptions.map { uuid ->
            GattOperation.Subscribe(
                characteristics[uuid] ?: run {
                    fail("Motorcycle companion profile lost ${uuid.shortName()}")
                    return
                },
            )
        }
        postAuthenticationGate = PostAuthenticationGate(BleCharacteristics.PostAuthenticationSubscriptions)
        enqueueAll(subscriptions)
    }

    private fun onSubscriptionCompleted(operation: GattOperation.Subscribe) {
        mutableDiagnostics.update { diagnostics ->
            diagnostics.copy(descriptorWritesCompleted = diagnostics.descriptorWritesCompleted + 1)
        }
        val uuid = operation.characteristic.uuid
        log("Subscribed ${uuid.shortName()}")
        if (uuid == BleCharacteristics.ProtectionChallenge) {
            handleProtectionAction(
                protectionSession?.onChallengeSubscriptionReady()
                    ?: ProtectionAction.Fail("Authentication session is missing"),
            )
            if (protectionSession?.phase == ProtectionPhase.AwaitingChallenge) {
                scheduleChallengeTimeout()
            }
            return
        }

        val gateUpdate = postAuthenticationGate?.markSubscriptionEnabled(uuid) ?: return
        if (gateUpdate.becameReady) {
            log("Required motorcycle subscriptions are ready")
            gateUpdate.deferredEvidence?.let(::completePostAuthenticationEvidence)
            if (!mutableDiagnostics.value.authenticated) scheduleProtectionVerificationTimeout()
        }
    }

    private fun acceptPostAuthenticationEvidence(evidence: String) {
        val gate = postAuthenticationGate ?: return
        val acceptedEvidence = gate.acceptEvidence(evidence)
        if (acceptedEvidence == null) {
            log("Deferred $evidence until required subscriptions are ready")
            return
        }
        completePostAuthenticationEvidence(acceptedEvidence)
    }

    private fun completePostAuthenticationEvidence(evidence: String) {
        handleProtectionAction(protectionSession?.onPostAuthenticationEvidence(evidence) ?: ProtectionAction.None)
    }

    private fun handleProtectionAction(action: ProtectionAction) {
        syncProtectionDiagnostics()
        when (action) {
            ProtectionAction.None -> Unit
            ProtectionAction.SubscribeChallenge -> {
                val challenge = characteristics[BleCharacteristics.ProtectionChallenge]
                if (challenge == null) {
                    fail("Authentication challenge endpoint is missing")
                } else {
                    enqueue(GattOperation.Subscribe(challenge))
                }
            }

            is ProtectionAction.WriteResponse -> {
                mainHandler.removeCallbacksAndMessages(ChallengeTimeoutToken)
                val response = characteristics[BleCharacteristics.ProtectionResponse]
                if (response == null) {
                    fail("Authentication response endpoint is missing")
                } else {
                    log("Queueing protection response")
                    enqueue(
                        GattOperation.Write(
                            response,
                            action.value.copyOf(),
                            priority = GattOperationPriority.Critical,
                        ),
                    )
                }
            }

            ProtectionAction.BeginPostAuthentication -> beginPostAuthenticationVerification()
            is ProtectionAction.CompleteAuthentication -> completeAuthentication(action.evidence)
            is ProtectionAction.Fail -> handleProtectionFailure(action)
        }
        syncProtectionDiagnostics()
    }

    private fun handleProtectionFailure(failure: ProtectionAction.Fail) {
        when (failure.policy) {
            ProtectionFailurePolicy.Stop -> fail(failure.message)
            ProtectionFailurePolicy.ClearAcceptance -> {
                connectionTarget?.address?.let(protectionAcceptanceStore::clear)
                fail(failure.message)
            }

            ProtectionFailurePolicy.ClearAcceptanceAndReconnect -> {
                connectionTarget?.address?.let(protectionAcceptanceStore::clear)
                reconnectAfterProtectionFailure(failure.message)
            }
        }
    }

    private fun syncProtectionDiagnostics() {
        val session = protectionSession
        mutableDiagnostics.update { diagnostics ->
            diagnostics.copy(
                protectionPhase = session?.phase ?: ProtectionPhase.Idle,
                protectionPath = session?.path,
            )
        }
    }

    private fun scheduleProtectionVerificationTimeout() {
        mainHandler.removeCallbacksAndMessages(ProtectionVerificationToken)
        val session = protectionSession ?: return
        val generation = connectionGeneration
        mainHandler.postAtTime({
            if (generation == connectionGeneration && protectionSession === session) {
                handleProtectionAction(session.onVerificationTimeout())
            }
        }, ProtectionVerificationToken, SystemClock.uptimeMillis() + ProtectionVerificationTimeoutMillis)
    }

    private fun scheduleChallengeTimeout() {
        mainHandler.removeCallbacksAndMessages(ChallengeTimeoutToken)
        val session = protectionSession ?: return
        val generation = connectionGeneration
        mainHandler.postAtTime({
            if (generation == connectionGeneration && protectionSession === session) {
                handleProtectionAction(session.onChallengeTimeout())
            }
        }, ChallengeTimeoutToken, SystemClock.uptimeMillis() + ChallengeTimeoutMillis)
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

    private fun enqueue(operation: GattOperation) {
        val shouldStart = operationScheduler.enqueue(operation)
        if (shouldStart) runNextOperation()
    }

    private fun enqueueAll(operations: List<GattOperation>) {
        val shouldStart = operationScheduler.enqueueAll(operations)
        if (shouldStart) runNextOperation()
    }

    private fun runNextOperation() {
        val currentGatt = gatt ?: return
        val operation = operationScheduler.beginNext() ?: return
        mutableDiagnostics.update { diagnostics ->
            diagnostics.copy(activeGattOperation = operation.diagnosticLabel())
        }
        mainHandler.postAtTime(
            { handleOperationTimeout(operation) },
            OperationTimeoutToken,
            android.os.SystemClock.uptimeMillis() + OperationTimeoutMillis,
        )
        // GATT I/O must be performed outside the scheduler lock: BluetoothGatt methods may
        // synchronously invoke callbacks on the binder thread, which also use the scheduler.
        val started = try {
            operationExecutor.start(currentGatt, operation)
        } catch (e: RuntimeException) {
            // Closed gatt can throw IllegalStateException from native on some OEM stacks.
            // Treat as a synchronous failure so the existing timeout/failure path runs.
            log("Could not start ${operation.label}: ${e.message}")
            false
        }
        when (gattStartAction(started)) {
            GattStartAction.AwaitCallback -> Unit
            GattStartAction.HandleSynchronousFailure -> {
                mainHandler.removeCallbacksAndMessages(OperationTimeoutToken)
                log("Could not start ${operation.label}")
                handleOperationFailure(operation, GattFailureSource.SynchronousStart)
            }
        }
    }

    /**
     * Once Android accepted a GATT operation, a missing callback leaves its remote outcome
     * unknowable. Reset the whole link rather than retrying on the same GATT and allowing a late
     * callback to complete the retry or a later operation.
     */
    private fun handleOperationTimeout(operation: GattOperation) {
        if (!operationScheduler.isActive(operation)) return
        handleOperationFailure(operation, GattFailureSource.CallbackTimeout)
    }

    private fun completeOperation(
        status: Int,
        label: String,
        matchesActiveOperation: (GattOperation) -> Boolean,
    ): GattOperation? {
        val operation = operationScheduler.activeMatching(matchesActiveOperation)
        if (operation == null) {
            log("Ignoring unmatched callback for $label")
            return null
        }
        mainHandler.removeCallbacksAndMessages(OperationTimeoutToken)
        val success = status == BluetoothGatt.GATT_SUCCESS
        if (success) {
            if (!operationScheduler.complete(operation)) return null
            mutableDiagnostics.update { diagnostics -> diagnostics.copy(activeGattOperation = null) }
            return operation
        } else {
            log("Failed to $label ($status)")
            handleOperationFailure(operation, GattFailureSource.StatusCallback)
            return null
        }
    }

    private fun onWriteCompleted(operation: GattOperation.Write) {
        mutableDiagnostics.update { diagnostics ->
            diagnostics.copy(writesCompleted = diagnostics.writesCompleted + 1)
        }
        if (operation.isProtectionResponseWrite()) {
            log("Protection response write completed")
            val action = protectionSession?.onProtectionResponseWritten()
                ?: ProtectionAction.Fail("Authentication session is missing")
            if (action !is ProtectionAction.Fail) {
                connectionTarget?.address?.let(protectionAcceptanceStore::markAccepted)
            }
            handleProtectionAction(action)
        }
        operation.completion?.complete(true)
    }

    private fun handleOperationFailure(operation: GattOperation, source: GattFailureSource) {
        var challengeSubscriptionSuperseded = false
        var exhaustedRequiredOperation = false
        if (!operationScheduler.isActive(operation)) return
        val shouldContinue = if (isSupersededChallengeSubscription(operation, source)) {
            challengeSubscriptionSuperseded = operationScheduler.complete(operation)
            challengeSubscriptionSuperseded
        } else {
            when (gattFailureAction(source, operation.attempt, MaxOperationRetries)) {
                GattFailureAction.RetryCurrentGatt -> {
                    if (!operationScheduler.retry(operation)) return
                    log("Retrying ${operation.label} (${operation.attempt + 1}/$MaxOperationRetries)")
                    true
                }

                GattFailureAction.CompleteFailure -> {
                    if (!operationScheduler.complete(operation)) return
                    exhaustedRequiredOperation = true
                    mutableDiagnostics.value = mutableDiagnostics.value.copy(
                        lastError = "GATT ${operation.label} failed after retries",
                        lastErrorAtMillis = System.currentTimeMillis(),
                    )
                    (operation as? GattOperation.Write)?.completion?.complete(false)
                    true
                }

                GattFailureAction.ResetGattAndReconnect -> {
                    log("Timed out during ${operation.label}; resetting GATT")
                    disconnectInternal(closeOnly = true)
                    if (!intentionalDisconnect) {
                        mutableConnectionState.value = BikeConnectionState.Connecting(deviceName)
                        scheduleReconnect()
                    }
                    false
                }
            }
        }
        if (challengeSubscriptionSuperseded) {
            mutableDiagnostics.update { diagnostics -> diagnostics.copy(activeGattOperation = null) }
            log("Challenge arrived before its subscription callback; continuing with the response")
            runNextOperation()
            return
        }
        if (exhaustedRequiredOperation) {
            when {
                operation.isChallengeSubscription() -> {
                    fail("Could not enable motorcycle authentication indications")
                    return
                }

                operation.isProtectionResponseWrite() -> {
                    connectionTarget?.address?.let(protectionAcceptanceStore::clear)
                    fail("Motorcycle authentication response was rejected")
                    return
                }

                operation.isPostAuthenticationSubscription() -> {
                    val uuid = (operation as GattOperation.Subscribe).characteristic.uuid
                    handleProtectionAction(
                        protectionSession?.onRequiredProfileFailure(
                            "Could not enable required motorcycle data (${uuid.shortName()})",
                        ) ?: ProtectionAction.Fail(
                            "Protection session is missing after a required subscription failure",
                            ProtectionFailurePolicy.ClearAcceptanceAndReconnect,
                        ),
                    )
                    return
                }
            }
        }
        if (shouldContinue && operationScheduler.hasNoActiveOperation()) {
            mutableDiagnostics.update { diagnostics -> diagnostics.copy(activeGattOperation = null) }
        }
        if (shouldContinue) runNextOperation()
    }

    private fun isSupersededChallengeSubscription(
        operation: GattOperation,
        source: GattFailureSource,
    ): Boolean = source != GattFailureSource.CallbackTimeout &&
        operation.isChallengeSubscription() &&
        protectionSession?.phase == ProtectionPhase.Responding

    private fun GattOperation.isChallengeSubscription(): Boolean =
        this is GattOperation.Subscribe && characteristic.uuid == BleCharacteristics.ProtectionChallenge

    private fun GattOperation.isPostAuthenticationSubscription(): Boolean =
        this is GattOperation.Subscribe && characteristic.uuid in BleCharacteristics.PostAuthenticationSubscriptions

    private fun GattOperation.isProtectionResponseWrite(): Boolean =
        this is GattOperation.Write && characteristic.uuid == BleCharacteristics.ProtectionResponse

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
        mutableConnectionState.value = BikeConnectionState.Connecting(deviceName)
        log("Reconnecting in ${delay / 1_000}s")
        mainHandler.postAtTime(
            { if (!intentionalDisconnect) connectGatt() },
            ReconnectToken,
            android.os.SystemClock.uptimeMillis() + delay
        )
    }

    private fun disconnectInternal(closeOnly: Boolean) {
        connectionMonitoringActive = false
        mainHandler.removeCallbacks(rssiRunnable)
        bondCoordinator.cancel()
        mainHandler.removeCallbacksAndMessages(ConnectionTimeoutToken)
        mainHandler.removeCallbacksAndMessages(OperationTimeoutToken)
        mainHandler.removeCallbacksAndMessages(ProtectionVerificationToken)
        mainHandler.removeCallbacksAndMessages(ChallengeTimeoutToken)
        operationScheduler.clear().filterIsInstance<GattOperation.Write>().forEach {
            it.completion?.complete(false)
        }
        characteristics.clear()
        protectionSession = null
        postAuthenticationGate = null
        telemetryTimestamps.clear()
        mutableTelemetry.value = null
        mutableLatestTelemetryReading.value = null
        mutableIdentity.update { identity ->
            BikeIdentity(lastConnectedAtMillis = identity.lastConnectedAtMillis)
        }
        mutableDiagnostics.value = mutableDiagnostics.value.copy(
            authenticated = false,
            protectionPhase = ProtectionPhase.Idle,
            negotiatedMtu = null,
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
        mutableIdentity.value = mutableIdentity.value.copy(
            vin = vin?.takeIf(String::isNotBlank) ?: mutableIdentity.value.vin,
            clusterSoftwareVersion = version?.takeIf(String::isNotBlank)
                ?: mutableIdentity.value.clusterSoftwareVersion,
        )
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
        val timestamped = "${LocalTime.now().withNano(0)}  $message"
        mutableDiagnostics.value = mutableDiagnostics.value.copy(
            recentEvents = (listOf(timestamped) + mutableDiagnostics.value.recentEvents).take(MaxLogEntries),
        )
    }

    private fun ByteArray.cleanText(): String = toString(Charsets.UTF_8).trim('\u0000', ' ', '\r', '\n')

    private fun ByteArray.decodeVin(): String? {
        if (size != FramedVinLength) return null
        val vinBytes = copyOfRange(1, size - 1)
        if (vinBytes.any { (it.toInt() and 0xFF) !in PrintableAsciiRange }) return null
        return vinBytes.toString(Charsets.US_ASCII).takeIf { it.length == VinLength }
    }

    private fun UUID.shortName(): String = toString().takeLast(4)

    private fun GattOperation.diagnosticLabel(): String {
        val uuid = when (this) {
            is GattOperation.Subscribe -> characteristic.uuid
            is GattOperation.Read -> characteristic.uuid
            is GattOperation.Write -> characteristic.uuid
        }
        return "$label ${uuid.shortName()}"
    }

    private companion object {
        val ReconnectToken = Any()
        val ConnectionTimeoutToken = Any()
        val OperationTimeoutToken = Any()
        val ProtectionVerificationToken = Any()
        val ChallengeTimeoutToken = Any()
        const val RssiIntervalMillis = 10_000L
        const val TelemetryWindowMillis = 5_000L
        const val TelemetrySampleIntervalMillis = 100L
        const val RawTelemetryBufferCapacity = 256
        const val OperationTimeoutMillis = 8_000L
        const val ProtectionVerificationTimeoutMillis = 8_000L
        const val ChallengeTimeoutMillis = 8_000L
        const val AwaitedWriteTimeoutMillis = 30_000L
        const val ConnectionTimeoutMillis = 20_000L
        const val FramedVinLength = 19
        const val VinLength = 17
        val PrintableAsciiRange = 0x20..0x7E
        const val MaxOperationRetries = 2
        const val MaxLogEntries = 40
        const val MaxFrameEntries = 30
    }
}
