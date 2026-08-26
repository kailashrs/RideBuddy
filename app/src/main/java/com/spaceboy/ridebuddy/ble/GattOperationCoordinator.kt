package com.spaceboy.ridebuddy.ble

import android.bluetooth.BluetoothGatt
import android.os.Handler
import android.os.SystemClock

/**
 * Owns serialization, retries, callback correlation, and timeout handling for Android GATT I/O.
 * Protocol-specific terminal failures are reported back to the connection controller.
 */
internal class GattOperationCoordinator(
    private val handler: Handler,
    private val executor: AndroidGattOperationExecutor,
    private val currentGatt: () -> BluetoothGatt?,
    private val isChallengeResponsePending: () -> Boolean,
    private val onActiveOperationChanged: (String?) -> Unit,
    private val onFailureRecorded: (String) -> Unit,
    private val onResetRequired: () -> Unit,
    private val onOperationExhausted: (GattOperation) -> Boolean,
    private val log: (String) -> Unit,
) {
    private val scheduler = GattOperationScheduler()
    private val timeoutToken = Any()

    fun enqueue(operation: GattOperation) {
        if (scheduler.enqueue(operation)) runNext()
    }

    fun enqueueAll(operations: List<GattOperation>) {
        if (scheduler.enqueueAll(operations)) runNext()
    }

    fun activeWrite(): GattOperation.Write? = scheduler.active() as? GattOperation.Write

    fun removeQueuedWrite(requestId: Long): Boolean = scheduler.removeQueued { operation ->
        (operation as? GattOperation.Write)?.requestId == requestId
    }.isNotEmpty()

    fun clear() {
        handler.removeCallbacksAndMessages(timeoutToken)
        scheduler.clear().filterIsInstance<GattOperation.Write>().forEach { operation ->
            operation.completion?.complete(false)
        }
    }

    fun complete(
        status: Int,
        label: String,
        matchesActiveOperation: (GattOperation) -> Boolean,
        onSuccess: (GattOperation) -> Unit,
    ) {
        val operation = scheduler.activeMatching(matchesActiveOperation)
        if (operation == null) {
            log("Ignoring unmatched callback for $label")
            return
        }
        handler.removeCallbacksAndMessages(timeoutToken)
        if (status == BluetoothGatt.GATT_SUCCESS) {
            if (!scheduler.complete(operation)) return
            onActiveOperationChanged(null)
            onSuccess(operation)
            runNext()
        } else {
            log("Failed to $label ($status)")
            handleFailure(operation, GattFailureSource.StatusCallback)
        }
    }

    private fun runNext() {
        val gatt = currentGatt() ?: return
        val operation = scheduler.beginNext() ?: return
        onActiveOperationChanged(operation.diagnosticLabel())
        handler.postAtTime(
            { handleTimeout(operation) },
            timeoutToken,
            SystemClock.uptimeMillis() + OperationTimeoutMillis,
        )
        // Android may synchronously enter a callback from a BluetoothGatt method, so no scheduler
        // lock may be held while the framework call executes.
        val started = try {
            executor.start(gatt, operation)
        } catch (error: RuntimeException) {
            log("Could not start ${operation.label}: ${error.message}")
            false
        }
        when (gattStartAction(started)) {
            GattStartAction.AwaitCallback -> Unit
            GattStartAction.HandleSynchronousFailure -> {
                handler.removeCallbacksAndMessages(timeoutToken)
                log("Could not start ${operation.label}")
                handleFailure(operation, GattFailureSource.SynchronousStart)
            }
        }
    }

    /**
     * A missing callback makes the outcome of side-effecting work unknowable. Optional identity
     * reads are the one safe exception because they use distinct UUIDs and do not mutate the bike.
     */
    private fun handleTimeout(operation: GattOperation) {
        if (!scheduler.isActive(operation)) return
        if (operation.isOptionalIdentityRead()) {
            val read = operation as GattOperation.Read
            if (!scheduler.complete(operation)) return
            onActiveOperationChanged(null)
            log("Motorcycle identity snapshot ${read.characteristic.uuid.shortName()} timed out; continuing")
            runNext()
            return
        }
        handleFailure(operation, GattFailureSource.CallbackTimeout)
    }

    private fun handleFailure(operation: GattOperation, source: GattFailureSource) {
        if (!scheduler.isActive(operation)) return
        if (isSupersededChallengeSubscription(operation, source)) {
            if (!scheduler.complete(operation)) return
            onActiveOperationChanged(null)
            log("Challenge arrived before its subscription callback; continuing with the response")
            runNext()
            return
        }

        when (gattFailureAction(source, operation.attempt, MaxOperationRetries)) {
            GattFailureAction.RetryCurrentGatt -> {
                if (!scheduler.retry(operation)) return
                log("Retrying ${operation.label} (${operation.attempt + 1}/$MaxOperationRetries)")
                onActiveOperationChanged(null)
                runNext()
            }

            GattFailureAction.CompleteFailure -> {
                if (!scheduler.complete(operation)) return
                if (!operation.isOptionalIdentityRead()) {
                    onFailureRecorded("GATT ${operation.label} failed after retries")
                }
                (operation as? GattOperation.Write)?.completion?.complete(false)
                if (onOperationExhausted(operation)) return
                onActiveOperationChanged(null)
                runNext()
            }

            GattFailureAction.ResetGattAndReconnect -> {
                log("Timed out during ${operation.label}; resetting GATT")
                onResetRequired()
            }
        }
    }

    private fun isSupersededChallengeSubscription(
        operation: GattOperation,
        source: GattFailureSource,
    ): Boolean = source != GattFailureSource.CallbackTimeout &&
        operation.isChallengeSubscription() &&
        isChallengeResponsePending()

    private companion object {
        const val OperationTimeoutMillis = 8_000L
        const val MaxOperationRetries = 2
    }
}
