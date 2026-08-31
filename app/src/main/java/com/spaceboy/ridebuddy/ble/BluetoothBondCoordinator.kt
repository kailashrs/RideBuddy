package com.spaceboy.ridebuddy.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.SystemClock
import androidx.core.content.ContextCompat

/** What to do about a device's bond state before GATT can be opened. */
internal enum class BondPreparationAction {
    /** Already bonded; go straight to GATT. */
    ConnectGatt,

    /** Android is mid-pairing; wait for it rather than starting a second attempt. */
    ObserveBond,

    /** Not bonded; ask Android to pair. */
    StartBonding,

    /** The bond state could not be read, so nothing can be assumed about the device. */
    Reject,
}

/**
 * The cluster refuses GATT to an unbonded central, so pairing has to complete before a
 * connection is worth attempting.
 */
internal fun bondPreparationAction(bondState: Int?): BondPreparationAction = when (bondState) {
    BluetoothDevice.BOND_BONDED -> BondPreparationAction.ConnectGatt
    BluetoothDevice.BOND_BONDING -> BondPreparationAction.ObserveBond
    BluetoothDevice.BOND_NONE -> BondPreparationAction.StartBonding
    else -> BondPreparationAction.Reject
}

/** What a `createBond()` call's return value means, once the live state is consulted. */
internal enum class BondStartFollowUp {
    ConnectGatt,
    ContinueObserving,
    Fail,
}

/**
 * `createBond()` returns false both for "pairing could not be started" and for "there was
 * nothing to start", so its result alone is not a failure. Reading the live bond state
 * separates the two: already bonded means proceed, already bonding means keep waiting.
 */
internal fun bondStartFollowUp(started: Boolean, currentBondState: Int?): BondStartFollowUp = when {
    started -> BondStartFollowUp.ContinueObserving
    currentBondState == BluetoothDevice.BOND_BONDED -> BondStartFollowUp.ConnectGatt
    currentBondState == BluetoothDevice.BOND_BONDING -> BondStartFollowUp.ContinueObserving
    else -> BondStartFollowUp.Fail
}

internal enum class BondEventOutcome {
    /** The motorcycle completed pairing and the GATT link may now open. */
    Complete,
    /** The pairing attempt ended without producing a BOND_BONDED state. */
    FailPairingAborted,
    /** The broadcast does not change the current observation; ignore. */
    Ignore,
}

/**
 * Pure decision for a single ACTION_BOND_STATE_CHANGED broadcast. The reported state is the value
 * Android attached to the intent; the live state is the device's authoritative bond state read
 * after the broadcast fires. Reporting can lag behind reality, so they must match before any
 * transition is acted on.
 */
internal fun bondEventOutcome(
    reportedState: Int?,
    liveState: Int?,
    previousState: Int?,
): BondEventOutcome = when {
    liveState == null -> BondEventOutcome.Ignore
    reportedState != liveState -> BondEventOutcome.Ignore
    liveState == BluetoothDevice.BOND_BONDED -> BondEventOutcome.Complete
    liveState == BluetoothDevice.BOND_NONE &&
        previousState == BluetoothDevice.BOND_BONDING -> BondEventOutcome.FailPairingAborted
    else -> BondEventOutcome.Ignore
}

/**
 * Owns pairing so the GATT layer only ever receives a device that is already bonded.
 *
 * Every path out of here is one of two callbacks — [onBondReady] or [onFailure] — and each
 * fires at most once per [prepare], because [cancel] runs first and drops the observation.
 *
 * Observations are generation-stamped and rechecked on every event. A broadcast is global,
 * so it can arrive for a device this coordinator is no longer waiting on, or for a
 * connection attempt that has since been superseded.
 */
@SuppressLint("MissingPermission")
internal class BluetoothBondCoordinator(
    context: Context,
    private val handler: Handler,
    private val isGenerationCurrent: (Long) -> Boolean,
    private val onBondReady: (BluetoothDevice) -> Unit,
    private val onFailure: (String) -> Unit,
    private val log: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val timeoutToken = Any()
    private var observedDevice: BluetoothDevice? = null
    private var observedGeneration = 0L
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = intent.getParcelableExtra(
                BluetoothDevice.EXTRA_DEVICE,
                BluetoothDevice::class.java,
            ) ?: return
            if (!isActiveObservation(device, observedGeneration)) return

            val reportedState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, Int.MIN_VALUE)
            val previousState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, Int.MIN_VALUE)
            val liveState = readBondState(device)
            when (bondEventOutcome(reportedState, liveState, previousState)) {
                BondEventOutcome.Complete -> completeBond(
                    device,
                    "Motorcycle paired; starting GATT connection",
                )

                BondEventOutcome.FailPairingAborted -> failObservation(
                    "Motorcycle pairing was not completed",
                )

                BondEventOutcome.Ignore -> Unit
            }
        }
    }

    /**
     * Brings [device] to a bonded state, calling back when it is ready or when it cannot be.
     * Cancels any observation already in progress.
     */
    fun prepare(
        device: BluetoothDevice,
        initialBondState: Int?,
        generation: Long,
        deviceName: String,
    ) {
        cancel()
        when (bondPreparationAction(initialBondState)) {
            BondPreparationAction.ConnectGatt -> onBondReady(device)
            BondPreparationAction.ObserveBond -> {
                log("Waiting for Android to finish pairing $deviceName")
                observe(device, generation, requireBondingState = true)
            }

            BondPreparationAction.StartBonding -> {
                log("Starting Android pairing for $deviceName")
                if (!observe(device, generation, requireBondingState = false)) return
                val started = try {
                    device.createBond()
                } catch (error: SecurityException) {
                    log("Pairing permission failure: ${error.javaClass.simpleName}")
                    failObservation("Allow Nearby devices to pair with the motorcycle")
                    return
                } catch (error: RuntimeException) {
                    log("Pairing start failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
                    failObservation("Android could not start motorcycle pairing")
                    return
                }
                if (!isActiveObservation(device, generation)) return
                when (bondStartFollowUp(started, readBondState(device))) {
                    BondStartFollowUp.ConnectGatt -> completeBond(
                        device,
                        "Motorcycle pairing completed while Android started pairing",
                    )

                    BondStartFollowUp.ContinueObserving -> Unit
                    BondStartFollowUp.Fail -> failObservation("Android could not start motorcycle pairing")
                }
            }

            BondPreparationAction.Reject -> onFailure("Android could not determine motorcycle pairing state")
        }
    }

    /** Stops observing and unregisters. Safe to call when nothing is being observed. */
    fun cancel() {
        handler.removeCallbacksAndMessages(timeoutToken)
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        observedDevice = null
    }

    private fun observe(
        device: BluetoothDevice,
        generation: Long,
        requireBondingState: Boolean,
    ): Boolean {
        observedDevice = device
        observedGeneration = generation
        try {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                // Bluetooth broadcasts originate from a privileged system app, so NOT_EXPORTED
                // is not guaranteed to receive them. Device identity and live state are rechecked.
                ContextCompat.RECEIVER_EXPORTED,
            )
            receiverRegistered = true
        } catch (error: RuntimeException) {
            cancel()
            log("Could not observe pairing: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            onFailure("Android could not monitor motorcycle pairing")
            return false
        }

        // Re-read after registering: the state can change in the window between the caller
        // reading it and the receiver going live, and that broadcast is simply gone.
        when (readBondState(device)) {
            BluetoothDevice.BOND_BONDED -> {
                completeBond(device, "Motorcycle pairing completed before its broadcast was observed")
                return false
            }

            BluetoothDevice.BOND_NONE -> if (requireBondingState) {
                failObservation("Motorcycle pairing was not completed")
                return false
            }
        }

        handler.postAtTime({
            if (isActiveObservation(device, generation)) {
                failObservation("Motorcycle pairing timed out")
            }
        }, timeoutToken, SystemClock.uptimeMillis() + BondTimeoutMillis)
        return true
    }

    private fun completeBond(device: BluetoothDevice, message: String) {
        val generation = observedGeneration
        if (!isActiveObservation(device, generation)) return
        cancel()
        log(message)
        onBondReady(device)
    }

    private fun failObservation(message: String) {
        cancel()
        onFailure(message)
    }

    private fun isActiveObservation(device: BluetoothDevice, generation: Long): Boolean {
        val expected = observedDevice ?: return false
        // getAddress() reads the cached address and is safe for this identity comparison.
        return generation == observedGeneration &&
            isGenerationCurrent(generation) &&
            device.address == expected.address
    }

    private fun readBondState(device: BluetoothDevice): Int? =
        runCatching { device.bondState }.getOrNull()

    private companion object {
        /** Long, because first-time pairing may be waiting on the rider to confirm a prompt. */
        const val BondTimeoutMillis = 60_000L
    }
}
