package com.spaceboy.ridebuddy.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.spaceboy.ridebuddy.domain.BikeConnection
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeConnectionTarget
import com.spaceboy.ridebuddy.domain.BikeControlEvent
import com.spaceboy.ridebuddy.domain.BikeWrite
import com.spaceboy.ridebuddy.domain.BikeWriteMode
import com.spaceboy.ridebuddy.domain.BleDiagnostics
import com.spaceboy.ridebuddy.domain.ProtectionPath
import com.spaceboy.ridebuddy.domain.ProtectionPhase
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.Request
import no.nordicsemi.android.ble.callback.FailCallback
import no.nordicsemi.android.ble.exception.RequestFailedException
import no.nordicsemi.android.ble.ktx.state.ConnectionState
import no.nordicsemi.android.ble.ktx.stateAsFlow
import no.nordicsemi.android.ble.ktx.suspend

/**
 * The link to the motorcycle, and the only thing that starts, retries or ends it.
 *
 * GATT I/O goes through Nordic's [BleManager], which serialises operations and correlates their
 * callbacks. What is left here is this cluster's own sequence: bond before opening GATT, as the
 * OEM app does; connect; answer the protection challenge, or skip it for a bike that has already
 * accepted one; subscribe to the session's notifications; and only once the cluster has sent
 * something over them, report the link as connected.
 */
@SuppressLint("MissingPermission")
internal class AndroidBikeConnection(
    context: Context,
    private val captureRecorder: BleCaptureRecorder,
    private val protectionAcceptance: ProtectionAcceptanceStore,
    private val journal: ConnectionEventJournal,
    private val identityRepository: BikeIdentityRepository,
    private val onAttemptsExhausted: () -> Unit,
) : BikeConnection {
    private val appContext = context.applicationContext
    private val adapter = appContext.getSystemService(BluetoothManager::class.java).adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager = BikeBleManager(appContext)
    private val telemetryStream = BikeTelemetryStream()
    private val mutableState = MutableStateFlow<BikeConnectionState>(BikeConnectionState.Disconnected)
    private val mutableDiagnostics = MutableStateFlow(BleDiagnostics(recentEvents = journal.events.value))
    private val mutableControls = MutableSharedFlow<BikeControlEvent>(extraBufferCapacity = 8)
    private var target: BikeConnectionTarget? = null
    private var session: Job? = null

    /** Completed by the challenge callback once a response lands, fails, or cannot be given. */
    private var challengeAnswered: CompletableDeferred<ChallengeOutcome>? = null

    private enum class ChallengeOutcome { Answered, WriteFailed, Unsupported }
    private var lastChallenge: ByteArray? = null

    /** Proof the session is live: a value the cluster only sends over an established link. */
    private val sessionEvidence = MutableStateFlow(false)

    override val connectionState: StateFlow<BikeConnectionState> = mutableState.asStateFlow()
    override val readings = telemetryStream.readings
    override val latestReading = telemetryStream.latestReading
    override val identity = identityRepository.identity
    override val diagnostics: StateFlow<BleDiagnostics> = mutableDiagnostics.asStateFlow()
    override val controls: SharedFlow<BikeControlEvent> = mutableControls

    init {
        scope.launch { journal.events.collect { events -> mutableDiagnostics.update { it.copy(recentEvents = events) } } }
    }

    /** A second request for the bike already being connected is ignored rather than restarting it. */
    override fun connect(target: BikeConnectionTarget) {
        scope.launch {
            if (!shouldStartConnection(this@AndroidBikeConnection.target, target, mutableState.value)) {
                journal.record("Ignored duplicate connection request for ${target.deviceName}")
                return@launch
            }
            if (this@AndroidBikeConnection.target?.address != target.address) {
                mutableDiagnostics.update { it.copy(lastError = null, lastErrorAtMillis = null, suppressionReason = null) }
            }
            this@AndroidBikeConnection.target = target
            identityRepository.select(target.address)
            // Reported before anything suspends, so an observer never sees the idle state between.
            mutableState.value = BikeConnectionState.Connecting(target.deviceName, 1, MaxConnectionAttempts)
            session?.cancel()
            closeLink()
            session = scope.launch { runSession(target) }
        }
    }

    override fun disconnect() {
        scope.launch {
            session?.cancel()
            session = null
            closeLink()
            mutableState.value = BikeConnectionState.Disconnected
            journal.record("Disconnected by app request")
        }
    }

    override fun notifyStartFailed(message: String) {
        scope.launch { fail(message, "Couldn't connect.") }
    }

    /** Fire-and-forget. Dropped when there is no authenticated session. */
    override fun enqueueWrite(characteristic: UUID, payload: ByteArray) {
        if (!mutableDiagnostics.value.authenticated) return
        val target = manager.characteristic(characteristic) ?: return
        captureRecorder.record(BleCaptureDirection.Outbound, characteristic, payload)
        manager.write(target, payload.copyOf(), BikeWriteMode.Default).done { countWrite() }.enqueue()
    }

    /** Writes and waits for the stack to confirm it; false on any failure or a missing callback. */
    override suspend fun writeAndAwait(write: BikeWrite): Boolean {
        if (!mutableDiagnostics.value.authenticated) return false
        val target = manager.characteristic(write.characteristic) ?: return false
        captureRecorder.record(BleCaptureDirection.Outbound, write.characteristic, write.payload)
        val delivered = manager.write(target, write.payload.copyOf(), write.mode).await("writing ${write.characteristic.shortName()}")
        if (delivered) countWrite()
        return delivered
    }

    /**
     * Attempts until the link is up, holds it until it drops, then tries again. Three attempts
     * per cycle; a session that authenticated starts the count again when it drops.
     */
    private suspend fun runSession(target: BikeConnectionTarget) {
        var attempts = 0
        while (true) {
            attempts++
            mutableState.value = BikeConnectionState.Connecting(target.deviceName, attempts, MaxConnectionAttempts)
            when (val outcome = attempt(target)) {
                AttemptOutcome.Ended -> attempts = 0
                is AttemptOutcome.Retry -> recordError(outcome.message)
                is AttemptOutcome.Stop -> {
                    fail(outcome.message, outcome.riderMessage)
                    return
                }
            }
            val delayMillis = reconnectDelayMillis(attempts)
            if (delayMillis == null) {
                val reason = "Couldn't connect."
                onAttemptsExhausted()
                mutableState.value = BikeConnectionState.Failed(reason, retriesExhausted = true)
                mutableDiagnostics.update { it.copy(suppressionReason = reason) }
                journal.record(reason)
                return
            }
            mutableState.value = BikeConnectionState.Connecting(target.deviceName, attempts + 1, MaxConnectionAttempts)
            journal.record("Reconnecting in ${delayMillis / 1_000}s (attempt ${attempts + 1}/$MaxConnectionAttempts)")
            delay(delayMillis.milliseconds)
        }
    }

    private sealed interface AttemptOutcome {
        /** An authenticated session ran and has now ended. */
        data object Ended : AttemptOutcome

        data class Retry(val message: String) : AttemptOutcome
        data class Stop(val message: String, val riderMessage: String) : AttemptOutcome
    }

    private suspend fun attempt(target: BikeConnectionTarget): AttemptOutcome {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return AttemptOutcome.Stop("Allow Nearby devices to connect to the motorcycle", "Allow Nearby devices access.")
        }
        if (!adapter.isEnabled) return AttemptOutcome.Stop("Turn on Bluetooth to connect to the motorcycle", "Turn on Bluetooth.")
        val device = adapter.getRemoteDevice(target.address.toByteArray())

        // A bond that is absent or forming belongs to a new pairing, which the stored shortcut predates.
        if (device.bondState != BluetoothDevice.BOND_BONDED) protectionAcceptance.clear(target.address)
        resetLinkDiagnostics(bonded = device.bondState == BluetoothDevice.BOND_BONDED)
        awaitBond(device, target.deviceName)?.let { message -> return AttemptOutcome.Stop(message, "Couldn't connect.") }
        mutableDiagnostics.update { it.copy(bonded = true) }

        journal.record("Connecting to ${target.deviceName} (${target.address.toString().takeLast(5)})")
        val failed = try {
            withTimeout(ConnectionTimeoutMillis) {
                manager.connect(device).useAutoConnect(false).suspend()
                mutableState.value = BikeConnectionState.Authenticating(target.deviceName)
                authenticate(target, device)
            }
        } catch (_: TimeoutCancellationException) {
            AttemptOutcome.Retry("Timed out connecting to the motorcycle after ${ConnectionTimeoutMillis}ms")
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            val reason = (error as? RequestFailedException)?.let { statusName(it.status) } ?: error.javaClass.simpleName
            AttemptOutcome.Retry("Connection failed: $reason")
        }
        if (failed != null) {
            closeLink()
            return failed
        }

        completeAuthentication(target)
        val rssiPolling = scope.launch { pollRssi() }
        try {
            val lost = manager.stateAsFlow().first { it is ConnectionState.Disconnected } as ConnectionState.Disconnected
            journal.record("Link lost: ${lost.reason.name}")
            recordError("Link lost: ${lost.reason.name}")
        } finally {
            rssiPolling.cancel()
            tearDownSession()
        }
        return AttemptOutcome.Ended
    }

    /**
     * The protection handshake and the session's subscriptions. Null when the session is up;
     * otherwise the outcome that ends this attempt.
     */
    private suspend fun authenticate(target: BikeConnectionTarget, device: BluetoothDevice): AttemptOutcome? {
        val storedAcceptance = device.bondState == BluetoothDevice.BOND_BONDED && protectionAcceptance.isAccepted(target.address)
        journal.record("Services ready; authenticating${if (storedAcceptance) " with stored acceptance" else ""}")
        val path = if (storedAcceptance) {
            ProtectionPath.StoredAcceptance
        } else {
            val answered = CompletableDeferred<ChallengeOutcome>().also { challengeAnswered = it }
            setProtection(ProtectionPhase.SubscribingChallenge)
            // The challenge can arrive before the subscription's own callback; the value callback
            // is registered first, so it is answered either way. Once a challenge has arrived the
            // subscription demonstrably worked, so a late error status on it is superseded.
            if (!manager.subscribe(BleCharacteristics.ProtectionChallenge).await("subscribing to the challenge") &&
                lastChallenge == null
            ) {
                return AttemptOutcome.Retry("Could not enable motorcycle authentication indications")
            }
            if (lastChallenge == null) setProtection(ProtectionPhase.AwaitingChallenge)
            when (withTimeoutOrNull(ChallengeTimeoutMillis) { answered.await() }) {
                null -> return AttemptOutcome.Retry("Motorcycle authentication challenge timed out")
                ChallengeOutcome.Unsupported -> {
                    // The bike offered a challenge this build cannot answer, so its stored
                    // acceptance is no longer valid either.
                    protectionAcceptance.clear(target.address)
                    return AttemptOutcome.Stop("Bike sent an unsupported authentication challenge", "Re-pair the bike in Settings.")
                }
                ChallengeOutcome.WriteFailed -> return AttemptOutcome.Retry("Motorcycle authentication response could not be delivered")
                ChallengeOutcome.Answered -> Unit
            }
            // The cluster will not issue a second challenge after accepting one, so the shortcut
            // is recorded now rather than once the whole session is up.
            protectionAcceptance.markAccepted(target.address)
            ProtectionPath.ChallengeIndication
        }

        setProtection(ProtectionPhase.Verifying, path)
        journal.record("Starting post-authentication verification via ${path.name}")
        for (uuid in BleCharacteristics.PostAuthenticationSubscriptions) {
            if (!manager.subscribe(uuid).await("subscribing to ${uuid.shortName()}")) {
                return AttemptOutcome.Stop("Could not enable required motorcycle data (${uuid.shortName()})", "Couldn't connect.")
            }
        }
        journal.record("Required motorcycle subscriptions are ready")
        if (withTimeoutOrNull(VerificationTimeoutMillis) { sessionEvidence.first { it } } == null) {
            return AttemptOutcome.Retry("Protected session verification timed out")
        }
        protectionAcceptance.markAccepted(target.address)
        mutableDiagnostics.update { it.copy(protectionPath = path) }
        return null
    }

    private fun completeAuthentication(target: BikeConnectionTarget) {
        mutableDiagnostics.update {
            it.copy(
                authenticated = true,
                protectionPhase = ProtectionPhase.Ready,
                attMtu = manager.currentMtu,
                lastError = null,
                lastErrorAtMillis = null,
                suppressionReason = null,
            )
        }
        identityRepository.update(target.address) { it.copy(lastConnectedAtMillis = System.currentTimeMillis()) }
        mutableState.value = BikeConnectionState.Connected(target.deviceName, null)
        journal.record("Protected session verified")
    }

    /** Routes one value from the cluster. Runs on the manager's callback thread, the main one. */
    private fun onValue(uuid: UUID, value: ByteArray) {
        captureRecorder.record(BleCaptureDirection.Notification, uuid, value)
        val now = System.currentTimeMillis()
        val frameLine = "${uuid.shortName()} ${value.toSpacedHex()}"
        when (uuid) {
            BleCharacteristics.ProtectionChallenge -> answerChallenge(value)

            BleCharacteristics.Telemetry -> {
                val telemetryHz = telemetryStream.accept(value, now, SystemClock::elapsedRealtime)
                mutableDiagnostics.update { it.withFrame(frameLine, now).copy(telemetryHz = telemetryHz) }
                sessionEvidence.value = true
                return
            }

            BleCharacteristics.Vin -> value.decodeBikeVin()?.let { vin ->
                updateIdentity(vin = vin)
                sessionEvidence.value = true
            } ?: journal.record("Ignored malformed VIN frame (${value.size} bytes)")

            BleCharacteristics.ClusterSoftwareVersion -> value.decodeClusterSoftwareVersion().takeIf(String::isNotBlank)?.let { version ->
                updateIdentity(version = version)
                sessionEvidence.value = true
            }

            // The command is byte 1 of a three-byte event, not byte 0.
            BleCharacteristics.NavigationControl -> when (value.takeIf { it.size >= 3 }?.get(1)?.toInt()?.and(0xFF)) {
                1 -> BikeControlEvent.StartNavigation
                3 -> BikeControlEvent.ExitNavigation
                else -> null.also { journal.record("Unhandled navigation control ${value.toSpacedHex()}") }
            }?.let(mutableControls::tryEmit)

            // Not call-only: 0 and 1 are handlebar reject and answer, 2 is the cluster announcing it
            // has come up, and 3 is the cluster asserting a call is live on its side.
            BleCharacteristics.CallControl -> when (val code = value.firstOrNull()?.toInt()?.and(0xFF)) {
                0, 1 -> BikeControlEvent.CallAction(code)
                2 -> BikeControlEvent.ClusterReady
                3 -> BikeControlEvent.ClusterCallActive
                else -> null.also { journal.record("Unhandled call control ${value.toSpacedHex()}") }
            }?.let(mutableControls::tryEmit)
        }
        mutableDiagnostics.update { it.withFrame(frameLine, now) }
    }

    /**
     * Answers a challenge. The cluster has been seen to repeat one before the first response
     * lands; each distinct challenge is answered, an exact repeat is not.
     */
    private fun answerChallenge(challenge: ByteArray) {
        journal.record("Protection challenge received")
        if (lastChallenge contentEquals challenge) return
        lastChallenge = challenge.copyOf()
        val response = ProtectionHandshake.responseFor(challenge)
        if (response == null) {
            challengeAnswered?.complete(ChallengeOutcome.Unsupported)
            return
        }
        val characteristic = manager.characteristic(BleCharacteristics.ProtectionResponse) ?: return
        setProtection(ProtectionPhase.Responding, ProtectionPath.ChallengeIndication)
        captureRecorder.record(BleCaptureDirection.Outbound, BleCharacteristics.ProtectionResponse, response)
        // A failed write ends the attempt straight away as an ordinary retry, rather than waiting
        // out the challenge timeout.
        manager.write(characteristic, response, BikeWriteMode.Default)
            .done {
                journal.record("Protection response write completed")
                challengeAnswered?.complete(ChallengeOutcome.Answered)
            }
            .fail { _, status ->
                journal.record("Protection response write failed: ${statusName(status)}")
                challengeAnswered?.complete(ChallengeOutcome.WriteFailed)
            }
            .enqueue()
    }

    /** Polls signal strength while the session is up. A failed read leaves the last value showing. */
    private suspend fun pollRssi() {
        while (true) {
            delay(RssiIntervalMillis.milliseconds)
            // Through the same deadline as every other request: a read that never calls back would
            // otherwise hold Nordic's queue, and every display write behind it.
            var rssi: Int? = null
            if (!manager.rssi().with { _, value -> rssi = value }.await("reading RSSI", logFailures = false)) continue
            val reading = rssi ?: continue
            mutableDiagnostics.update { it.copy(rssi = reading) }
            (mutableState.value as? BikeConnectionState.Connected)?.let { mutableState.value = it.copy(rssi = reading) }
        }
    }

    /**
     * Enqueues a request and waits for its outcome. A request that never calls back leaves the
     * stack unable to do anything else on this link, so the link is retired.
     */
    private suspend fun Request.await(label: String, logFailures: Boolean = true): Boolean {
        val outcome = withTimeoutOrNull(OperationTimeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                done { if (continuation.isActive) continuation.resume(true) }
                    .fail { _, status ->
                        if (logFailures) journal.record("GATT failure while $label: ${statusName(status)}")
                        if (continuation.isActive) continuation.resume(false)
                    }
                    .invalid { if (continuation.isActive) continuation.resume(false) }
                    .enqueue()
            }
        }
        if (outcome == null) {
            journal.record("Link lost while $label: no callback within ${OperationTimeoutMillis}ms")
            manager.disconnect().enqueue()
        }
        return outcome == true
    }

    private fun resetLinkDiagnostics(bonded: Boolean) {
        sessionEvidence.value = false
        challengeAnswered = null
        lastChallenge = null
        telemetryStream.clearUiTelemetry()
        mutableDiagnostics.update {
            it.copy(
                authenticated = false,
                protectionPhase = ProtectionPhase.Idle,
                protectionPath = null,
                bonded = bonded,
                attMtu = null,
                servicesDiscovered = 0,
                rssi = null,
                suppressionReason = null,
            )
        }
    }

    private fun tearDownSession() {
        telemetryStream.reset()
        mutableDiagnostics.update {
            it.copy(
                authenticated = false,
                protectionPhase = ProtectionPhase.Idle,
                attMtu = null,
                rssi = null,
                telemetryHz = 0.0,
                lastFrameAtMillis = null,
            )
        }
    }

    private suspend fun closeLink() {
        tearDownSession()
        runCatching { manager.disconnect().suspend() }
    }

    private fun fail(message: String, riderMessage: String) {
        session?.cancel()
        tearDownSession()
        manager.disconnect().enqueue()
        recordError(message)
        mutableState.value = BikeConnectionState.Failed(riderMessage)
    }

    private fun recordError(message: String) {
        journal.record(message)
        mutableDiagnostics.update { it.copy(lastError = message, lastErrorAtMillis = System.currentTimeMillis()) }
    }

    private fun setProtection(phase: ProtectionPhase, path: ProtectionPath? = null) {
        mutableDiagnostics.update { it.copy(protectionPhase = phase, protectionPath = path ?: it.protectionPath) }
    }

    private fun countWrite() = mutableDiagnostics.update { it.copy(writesCompleted = it.writesCompleted + 1) }

    private fun updateIdentity(vin: String? = null, version: String? = null) {
        val address = target?.address ?: return
        identityRepository.update(address) { identity ->
            identity.copy(vin = vin ?: identity.vin, clusterSoftwareVersion = version ?: identity.clusterSoftwareVersion)
        }
    }

    /**
     * Waits for Android to bond with the bike. The cluster refuses GATT to an unbonded central, so
     * pairing completes first, as it does in the OEM app. Returns why it could not, or null.
     */
    private suspend fun awaitBond(device: BluetoothDevice, name: String): String? {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return null
        journal.record("Starting Android pairing for $name")
        val states = callbackFlow {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val changed = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    if (changed?.address == device.address) trySend(device.bondState)
                }
            }
            // Bluetooth broadcasts come from a privileged system app, so this must be exported.
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
            // Read after registering: a change between the caller's read and now would be missed.
            trySend(device.bondState)
            if (device.bondState == BluetoothDevice.BOND_NONE && !device.createBond()) trySend(BluetoothDevice.BOND_NONE)
            awaitClose { appContext.unregisterReceiver(receiver) }
        }
        var sawBonding = false
        val result = withTimeoutOrNull(BondTimeoutMillis) {
            states.first { state ->
                if (state == BluetoothDevice.BOND_BONDING) sawBonding = true
                state == BluetoothDevice.BOND_BONDED || (state == BluetoothDevice.BOND_NONE && sawBonding)
            }
        }
        return when (result) {
            BluetoothDevice.BOND_BONDED -> null.also { journal.record("Motorcycle paired") }
            null -> "Motorcycle pairing timed out"
            else -> "Motorcycle pairing was not completed"
        }
    }

    /** Nordic's manager, with this profile's characteristics indexed and its values routed to [onValue]. */
    private inner class BikeBleManager(context: Context) : BleManager(context) {
        private val characteristics = mutableMapOf<UUID, BluetoothGattCharacteristic>()
        val currentMtu: Int get() = mtu

        fun characteristic(uuid: UUID): BluetoothGattCharacteristic? = characteristics[uuid]

        /**
         * Looked up across every service rather than under one: the vendor service UUID is not
         * advertised and is not guaranteed stable across firmware.
         */
        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            characteristics.clear()
            gatt.services.flatMap { it.characteristics }.forEach { characteristics[it.uuid] = it }
            mutableDiagnostics.update { diagnostics ->
                diagnostics.copy(
                    servicesDiscovered = gatt.services.size,
                    serviceSnapshot = gatt.services.flatMap { service ->
                        service.characteristics.map { "${service.uuid.shortName()}/${it.uuid.shortName()} props=0x${it.properties.toString(16)}" }
                    },
                )
            }
            return characteristics.keys.containsAll(RequiredCharacteristics)
        }

        override fun onServicesInvalidated() {
            characteristics.clear()
        }

        /** Registers the value callback, then enables indications or notifications, whichever the characteristic has. */
        fun subscribe(uuid: UUID): Request {
            val characteristic = characteristics.getValue(uuid)
            setNotificationCallback(characteristic).with { _, data -> data.value?.let { onValue(uuid, it) } }
            return if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                enableIndications(characteristic)
            } else {
                enableNotifications(characteristic)
            }
        }

        fun write(characteristic: BluetoothGattCharacteristic, payload: ByteArray, mode: BikeWriteMode) =
            writeCharacteristic(characteristic, payload, writeType(characteristic.properties, mode))

        fun rssi() = readRssi()

        // Operation-level chatter would bury the journal; warnings and errors explain a failure.
        override fun getMinLogPriority(): Int = Log.WARN

        override fun log(priority: Int, message: String) {
            journal.record(message)
        }
    }

    private companion object {
        const val RssiIntervalMillis = 10_000L
        const val OperationTimeoutMillis = 8_000L
        const val ChallengeTimeoutMillis = 8_000L
        const val VerificationTimeoutMillis = 8_000L

        /** Connect, discover and authenticate must all complete inside this. */
        const val ConnectionTimeoutMillis = 20_000L

        /** Long, because first-time pairing may be waiting on the rider to confirm a prompt. */
        const val BondTimeoutMillis = 60_000L

        /** The protection pair and the session's subscription set. HID over GATT is hidden from apps. */
        val RequiredCharacteristics = listOf(BleCharacteristics.ProtectionChallenge, BleCharacteristics.ProtectionResponse) +
            BleCharacteristics.PostAuthenticationSubscriptions
    }
}

/**
 * The write type for a characteristic. [mode] is a preference only: whichever type the
 * characteristic declares wins, and the preference decides when it declares both.
 */
internal fun writeType(properties: Int, mode: BikeWriteMode): Int {
    val acknowledged = properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
    val unacknowledged = properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
    return when {
        mode == BikeWriteMode.NoResponsePreferred && unacknowledged -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        acknowledged -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    }
}

private fun BleDiagnostics.withFrame(frameLine: String, receivedAtMillis: Long) = copy(
    notificationsReceived = notificationsReceived + 1,
    lastFrameAtMillis = receivedAtMillis,
    recentFrames = (listOf(frameLine) + recentFrames).take(30),
)

private fun statusName(status: Int): String = when (status) {
    FailCallback.REASON_DEVICE_DISCONNECTED -> "device disconnected"
    FailCallback.REASON_DEVICE_NOT_SUPPORTED -> "device not supported"
    FailCallback.REASON_NULL_ATTRIBUTE -> "attribute missing"
    FailCallback.REASON_REQUEST_FAILED -> "request failed"
    FailCallback.REASON_TIMEOUT -> "timeout"
    FailCallback.REASON_BLUETOOTH_DISABLED -> "Bluetooth disabled"
    BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION -> "GATT_INSUFFICIENT_AUTHENTICATION"
    BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> "GATT_INSUFFICIENT_ENCRYPTION"
    0x85 -> "GATT_ERROR"
    else -> "status $status"
}
