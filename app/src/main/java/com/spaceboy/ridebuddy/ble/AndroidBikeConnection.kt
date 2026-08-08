package com.spaceboy.ridebuddy.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.spaceboy.ridebuddy.domain.BikeConnection
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeControlEvent
import com.spaceboy.ridebuddy.domain.BikeIdentity
import com.spaceboy.ridebuddy.domain.BikeWrite
import com.spaceboy.ridebuddy.domain.BikeWriteMode
import com.spaceboy.ridebuddy.domain.BleDiagnostics
import com.spaceboy.ridebuddy.domain.TelemetryReading
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

private fun requestedWriteType(
    characteristic: BluetoothGattCharacteristic,
    mode: BikeWriteMode,
): Int? {
    val supportsDefault = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
    val supportsNoResponse = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
    return when (mode) {
        BikeWriteMode.Default -> when {
            supportsDefault -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            supportsNoResponse -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else -> null
        }

        BikeWriteMode.NoResponsePreferred -> when {
            supportsNoResponse -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            supportsDefault -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else -> null
        }
    }
}

internal fun reconnectDelayMillis(attempt: Int): Long? {
    if (attempt !in 0 until MaxReconnectAttempts) return null
    return minOf(MaxReconnectDelayMillis, 1_000L shl minOf(attempt, 5))
}

private const val MaxReconnectAttempts = 6
private const val MaxReconnectDelayMillis = 30_000L

@SuppressLint("MissingPermission")
@OptIn(FlowPreview::class)
class AndroidBikeConnection(
    context: Context,
    private val captureRecorder: BleCaptureRecorder = BleCaptureRecorder(),
) : BikeConnection {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(android.bluetooth.BluetoothManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val operationQueue = ArrayDeque<GattOperation>()
    private val characteristics = mutableMapOf<UUID, BluetoothGattCharacteristic>()
    private var activeOperation: GattOperation? = null
    private var gatt: BluetoothGatt? = null
    private var connectionTarget: BikeConnectionTarget? = null
    private var deviceName: String? = null
    private var intentionalDisconnect = false
    private var reconnectAttempt = 0
    private var connectionGeneration = 0L
    private var heartbeatActive = false
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
            reconnectAttempt = 0
            mainHandler.removeCallbacksAndMessages(ReconnectToken)
            connectGatt()
            intentionalDisconnect = false
        }
    }

    override fun disconnect() {
        mainHandler.post {
            intentionalDisconnect = true
            heartbeatActive = false
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
                val activeWrite = activeOperation as? GattOperation.Write
                when {
                    activeWrite?.requestId == requestId -> {
                        log("Aborting timed-out write")
                        disconnectInternal(closeOnly = true)
                        completion.complete(false)
                        mutableConnectionState.value = BikeConnectionState.Connecting(deviceName)
                        scheduleReconnect()
                    }

                    operationQueue.removeAll { operation ->
                        (operation as? GattOperation.Write)?.requestId == requestId
                    } -> completion.complete(false)

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
            if (priority) {
                operationQueue.removeAll { operation ->
                    (operation as? GattOperation.Write)?.takeIf {
                        it.characteristic.uuid in BleCharacteristics.NavigationWrites
                    }?.let { dropped ->
                        dropped.completion?.complete(false)
                        true
                    } ?: false
                }
                operations.asReversed().forEach(operationQueue::addFirst)
            } else {
                operations.forEach(operationQueue::addLast)
            }
            if (activeOperation == null) runNextOperation()
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
            target.device ?: adapter.getRemoteDevice(target.address.toByteArray())
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

        val address = target.address.toString()
        mutableConnectionState.value = BikeConnectionState.Connecting(deviceName)
        log("Connecting to ${deviceName.orEmpty()} (${address.takeLast(5)})")
        val requestedGeneration = connectionGeneration
        mutableTelemetry.value = null
        mutableDiagnostics.value = mutableDiagnostics.value.copy(
            authenticated = false,
            negotiatedMtu = null,
            servicesDiscovered = 0,
            rssi = null,
        )
        val newGatt = try {
            if (Build.VERSION.SDK_INT >= 37) {
                device.connectGatt(
                    BluetoothGattConnectionSettings.Builder()
                        .setAutoConnectEnabled(false)
                        .setAutomaticMtuEnabled(false)
                        .setTransport(BluetoothDevice.TRANSPORT_LE)
                        .build(),
                    ContextCompat.getMainExecutor(appContext),
                    callback,
                )
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(
                    appContext,
                    false,
                    callback,
                    BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_1M_MASK,
                    mainHandler,
                )
            }
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
            mainHandler.post {
                if (!isCurrent(gatt)) {
                    gatt.close()
                    return@post
                }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            log("GATT connection failed, status $status")
                            disconnectInternal(closeOnly = true)
                            if (!intentionalDisconnect) scheduleReconnect()
                            return@post
                        }
                        this@AndroidBikeConnection.gatt = gatt
                        log("GATT connected")
                        if (!gatt.requestMtu(247)) discoverServices(gatt)
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        log("GATT disconnected, status $status")
                        disconnectInternal(closeOnly = true)
                        if (!intentionalDisconnect) scheduleReconnect()
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            mainHandler.post {
                if (!isCurrent(gatt)) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    mutableDiagnostics.value = mutableDiagnostics.value.copy(negotiatedMtu = mtu)
                    log("MTU $mtu")
                }
                discoverServices(gatt)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            mainHandler.post {
                if (!isCurrent(gatt)) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("Could not discover bike services ($status)")
                    return@post
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
                val challenge = characteristics[BleCharacteristics.ProtectionChallenge]
                val response = characteristics[BleCharacteristics.ProtectionResponse]
                if (challenge == null || response == null) {
                    fail("Selected Bluetooth endpoint does not expose the motorcycle companion service")
                    return@post
                }
                mutableConnectionState.value = BikeConnectionState.Authenticating(deviceName ?: "Motorcycle")
                log("Services ready; authenticating")
                enqueue(GattOperation.Subscribe(challenge))
            }
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onNotification(gatt, characteristic.uuid, characteristic.value?.copyOf() ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = onNotification(gatt, characteristic.uuid, value.copyOf())

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) = onRead(gatt, characteristic.uuid, characteristic.value?.copyOf() ?: byteArrayOf(), status)

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) = onRead(gatt, characteristic.uuid, value.copyOf(), status)

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            mainHandler.post {
                if (isCurrent(gatt)) {
                    completeOperation(status, "subscribe ${descriptor.characteristic.uuid.shortName()}") { operation ->
                        operation is GattOperation.Subscribe && operation.characteristic.uuid == descriptor.characteristic.uuid
                    }
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            mainHandler.post {
                if (!isCurrent(gatt)) return@post
                val completed = completeOperation(status, "write ${characteristic.uuid.shortName()}") { operation ->
                    operation is GattOperation.Write && operation.awaitsCallback &&
                            operation.characteristic.uuid == characteristic.uuid
                }
                (completed as? GattOperation.Write)?.let(::onWriteCompleted)
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            mainHandler.post {
                if (!isCurrent(gatt)) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    mutableDiagnostics.value = mutableDiagnostics.value.copy(rssi = rssi)
                    val current = mutableConnectionState.value
                    if (current is BikeConnectionState.Connected) {
                        mutableConnectionState.value = current.copy(rssi = rssi)
                    }
                }
            }
        }
    }

    private fun discoverServices(gatt: BluetoothGatt) {
        if (!gatt.discoverServices()) fail("Could not start service discovery")
    }

    private fun onNotification(callbackGatt: BluetoothGatt, uuid: UUID, value: ByteArray) {
        mainHandler.post {
            if (!isCurrent(callbackGatt)) return@post
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
                    val response = ProtectionHandshake.responseFor(value)
                    if (response == null) {
                        fail("Bike sent an unknown authentication challenge")
                    } else {
                        val target = characteristics[BleCharacteristics.ProtectionResponse]
                        if (target == null) fail("Authentication response endpoint is missing")
                        else enqueue(GattOperation.Write(target, response))
                    }
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
                    }
                }

                BleCharacteristics.Vin -> updateIdentity(vin = value.decodeVin())
                BleCharacteristics.ClusterSoftwareVersion -> updateIdentity(version = value.cleanText())
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
    }

    private fun onRead(callbackGatt: BluetoothGatt, uuid: UUID, value: ByteArray, status: Int) {
        mainHandler.post {
            if (!isCurrent(callbackGatt)) return@post
            captureRecorder.record(BleCaptureDirection.Read, uuid, value, "status=$status")
            val completed = completeOperation(status, "read ${uuid.shortName()}") { operation ->
                operation is GattOperation.Read && operation.characteristic.uuid == uuid
            }
            if (completed is GattOperation.Read) {
                when (uuid) {
                    BleCharacteristics.Vin -> updateIdentity(vin = value.decodeVin())
                    BleCharacteristics.ClusterSoftwareVersion -> updateIdentity(version = value.cleanText())
                }
            }
        }
    }

    private fun onAuthenticated() {
        if (mutableDiagnostics.value.authenticated) return
        reconnectAttempt = 0
        mainHandler.removeCallbacksAndMessages(ConnectionTimeoutToken)
        mutableDiagnostics.value = mutableDiagnostics.value.copy(authenticated = true)
        mutableIdentity.value = mutableIdentity.value.copy(lastConnectedAtMillis = System.currentTimeMillis())
        mutableConnectionState.value = BikeConnectionState.Connected(deviceName ?: "Motorcycle", null)
        log("Authentication accepted")
        BleCharacteristics.PostAuthenticationSubscriptions.mapNotNull(characteristics::get).forEach {
            enqueue(GattOperation.Subscribe(it))
        }
        listOf(BleCharacteristics.ClusterSoftwareVersion, BleCharacteristics.Vin)
            .mapNotNull(characteristics::get)
            .filter { it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 }
            .forEach { enqueue(GattOperation.Read(it)) }
        heartbeatActive = true
        scheduleHeartbeat()
        scheduleRssiRead()
    }

    private fun enqueue(operation: GattOperation) {
        operationQueue.addLast(operation)
        if (activeOperation == null) runNextOperation()
    }

    private fun runNextOperation() {
        val currentGatt = gatt ?: return
        val operation = if (operationQueue.isEmpty()) return else operationQueue.removeFirst()
        activeOperation = operation
        if (operation.awaitsCallback) {
            mainHandler.postAtTime({
                if (activeOperation === operation) {
                    log("Timed out during ${operation.label}")
                    retryOrComplete(operation)
                }
            }, OperationTimeoutToken, android.os.SystemClock.uptimeMillis() + OperationTimeoutMillis)
        }
        val started = when (operation) {
            is GattOperation.Subscribe -> subscribe(currentGatt, operation.characteristic)
            is GattOperation.Read -> currentGatt.readCharacteristic(operation.characteristic)
            is GattOperation.Write -> writeCharacteristic(
                currentGatt,
                operation.characteristic,
                operation.value,
                operation.mode,
            )
        }
        if (!started) {
            mainHandler.removeCallbacksAndMessages(OperationTimeoutToken)
            log("Could not start ${operation.label}")
            retryOrComplete(operation)
        } else if (!operation.awaitsCallback) {
            // Android deliberately does not deliver onCharacteristicWrite for
            // WRITE_TYPE_NO_RESPONSE. The successful API return means the stack
            // accepted this write, so advance the serialized queue immediately.
            (completeOperation(BluetoothGatt.GATT_SUCCESS, operation.label) { active -> active === operation }
                    as? GattOperation.Write)?.let(::onWriteCompleted)
        }
    }

    private fun subscribe(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        if (!gatt.setCharacteristicNotification(characteristic, true)) return false
        val descriptor =
            characteristic.getDescriptor(BleCharacteristics.ClientCharacteristicConfiguration) ?: return false
        val indication = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        val value =
            if (indication) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    private fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        mode: BikeWriteMode,
    ): Boolean {
        val writeType = requestedWriteType(characteristic, mode) ?: return false
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.writeType = writeType
                characteristic.value = value
                gatt.writeCharacteristic(characteristic)
            }
        }
        captureRecorder.record(
            BleCaptureDirection.Outbound,
            characteristic.uuid,
            value,
            if (started) "accepted" else "rejected",
        )
        return started
    }

    private fun completeOperation(
        status: Int,
        label: String,
        matchesActiveOperation: (GattOperation) -> Boolean,
    ): GattOperation? {
        val operation = activeOperation ?: return null
        if (!matchesActiveOperation(operation)) {
            log("Ignoring unmatched callback for $label")
            return null
        }
        mainHandler.removeCallbacksAndMessages(OperationTimeoutToken)
        if (status != BluetoothGatt.GATT_SUCCESS) {
            log("Failed to $label ($status)")
            retryOrComplete(operation)
            return null
        }
        activeOperation = null
        runNextOperation()
        return operation
    }

    private fun onWriteCompleted(operation: GattOperation.Write) {
        if (operation.characteristic.uuid == BleCharacteristics.ProtectionResponse) {
            onAuthenticated()
        } else {
            mutableDiagnostics.value = mutableDiagnostics.value.copy(
                writesCompleted = mutableDiagnostics.value.writesCompleted + 1,
            )
        }
        operation.completion?.complete(true)
    }

    private fun retryOrComplete(operation: GattOperation) {
        activeOperation = null
        if (operation.attempt < MaxOperationRetries) {
            operationQueue.addFirst(operation.retry())
            log("Retrying ${operation.label} (${operation.attempt + 1}/$MaxOperationRetries)")
        } else {
            mutableDiagnostics.value = mutableDiagnostics.value.copy(
                lastError = "GATT ${operation.label} failed after retries",
                lastErrorAtMillis = System.currentTimeMillis(),
            )
            (operation as? GattOperation.Write)?.completion?.complete(false)
        }
        runNextOperation()
    }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            if (heartbeatActive && mutableDiagnostics.value.authenticated) {
                val time = LocalTime.now()
                write(
                    BleCharacteristics.MobileHeartbeat,
                    byteArrayOf(
                        0x50,
                        'L'.code.toByte(),
                        'I'.code.toByte(),
                        'V'.code.toByte(),
                        'E'.code.toByte(),
                        time.hour.toByte(),
                        time.minute.toByte(),
                        0xF1.toByte(),
                        0xF0.toByte()
                    ),
                )
                mainHandler.postDelayed(this, HeartbeatIntervalMillis)
            }
        }
    }

    private val rssiRunnable = object : Runnable {
        override fun run() {
            if (heartbeatActive) {
                gatt?.readRemoteRssi()
                mainHandler.postDelayed(this, RssiIntervalMillis)
            }
        }
    }

    private fun scheduleHeartbeat() {
        mainHandler.removeCallbacks(heartbeatRunnable)
        if (!heartbeatActive) return
        mainHandler.postDelayed(heartbeatRunnable, HeartbeatIntervalMillis)
    }

    private fun scheduleRssiRead() {
        mainHandler.removeCallbacks(rssiRunnable)
        if (!heartbeatActive) return
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
        heartbeatActive = false
        mainHandler.removeCallbacks(heartbeatRunnable)
        mainHandler.removeCallbacks(rssiRunnable)
        mainHandler.removeCallbacksAndMessages(ConnectionTimeoutToken)
        mainHandler.removeCallbacksAndMessages(OperationTimeoutToken)
        (activeOperation as? GattOperation.Write)?.completion?.complete(false)
        operationQueue.filterIsInstance<GattOperation.Write>().forEach { it.completion?.complete(false) }
        operationQueue.clear()
        activeOperation = null
        characteristics.clear()
        telemetryTimestamps.clear()
        mutableTelemetry.value = null
        mutableLatestTelemetryReading.value = null
        mutableDiagnostics.value = mutableDiagnostics.value.copy(authenticated = false)
        gatt?.let { current ->
            if (closeOnly) {
                current.close()
                gatt = null
            } else {
                current.disconnect()
            }
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
    private fun ByteArray.decodeVin(): String = when {
        size >= 3 -> copyOfRange(1, size - 1).cleanText()
        else -> cleanText()
    }

    private fun UUID.shortName(): String = toString().takeLast(4)

    private sealed interface GattOperation {
        val label: String
        val attempt: Int
        val awaitsCallback: Boolean get() = true
        fun retry(): GattOperation
        data class Subscribe(val characteristic: BluetoothGattCharacteristic, override val attempt: Int = 0) :
            GattOperation {
            override val label = "subscription"
            override fun retry() = copy(attempt = attempt + 1)
        }

        data class Read(val characteristic: BluetoothGattCharacteristic, override val attempt: Int = 0) :
            GattOperation {
            override val label = "read"
            override fun retry() = copy(attempt = attempt + 1)
        }

        data class Write(
            val characteristic: BluetoothGattCharacteristic,
            val value: ByteArray,
            val mode: BikeWriteMode = BikeWriteMode.Default,
            val completion: CompletableDeferred<Boolean>? = null,
            val requestId: Long? = null,
            override val attempt: Int = 0,
        ) : GattOperation {
            override val label = "write"
            override val awaitsCallback: Boolean
                get() = requestedWriteType(characteristic, mode) == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

            override fun retry() = copy(attempt = attempt + 1)
        }
    }

    private companion object {
        val ReconnectToken = Any()
        val ConnectionTimeoutToken = Any()
        val OperationTimeoutToken = Any()
        const val HeartbeatIntervalMillis = 10_000L
        const val RssiIntervalMillis = 10_000L
        const val TelemetryWindowMillis = 5_000L
        const val TelemetrySampleIntervalMillis = 100L
        const val RawTelemetryBufferCapacity = 256
        const val OperationTimeoutMillis = 8_000L
        const val AwaitedWriteTimeoutMillis = 30_000L
        const val ConnectionTimeoutMillis = 20_000L
        const val MaxOperationRetries = 2
        const val MaxLogEntries = 40
        const val MaxFrameEntries = 30
    }
}
