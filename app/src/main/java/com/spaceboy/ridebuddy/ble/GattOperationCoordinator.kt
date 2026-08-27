package com.spaceboy.ridebuddy.ble

import android.bluetooth.BluetoothGatt
import android.os.Handler
import android.os.SystemClock
import com.spaceboy.ridebuddy.domain.ConnectionAttemptContext
import com.spaceboy.ridebuddy.domain.ConnectionFailure
import com.spaceboy.ridebuddy.domain.ConnectionFailureCategory

/**
 * Owns serialization, retries, callback correlation, and timeout handling for Android GATT I/O.
 * Protocol-specific terminal failures are reported back to the connection controller.
 *
 * Every failure leaves here as a [ConnectionFailure] carrying the attempt it happened in, how long
 * the operation ran, and how old the link was, so diagnostics never has to guess afterwards.
 */
internal class GattOperationCoordinator(
    private val handler: Handler,
    private val executor: AndroidGattOperationExecutor,
    private val currentGatt: () -> BluetoothGatt?,
    private val isChallengeResponsePending: () -> Boolean,
    private val attemptContext: () -> ConnectionAttemptContext,
    private val onActiveOperationChanged: (String?) -> Unit,
    private val onFailureRecorded: (ConnectionFailure) -> Unit,
    private val onResetRequired: (ConnectionFailure) -> Unit,
    private val onOperationExhausted: (GattOperation) -> Boolean,
    private val log: (String) -> Unit,
) {
    private val scheduler = GattOperationScheduler()
    private val timeoutToken = Any()
    private val retryToken = Any()
    private var retryPending = false
    private var activeOperationStartedAtUptime: Long? = null
    private var toleratedIdentityReadTimeouts = 0

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
        handler.removeCallbacksAndMessages(retryToken)
        retryPending = false
        activeOperationStartedAtUptime = null
        toleratedIdentityReadTimeouts = 0
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
            finishActiveOperation()
            onSuccess(operation)
            runNext()
        } else {
            handleFailure(operation, GattFailureSource.StatusCallback, status = status)
        }
    }

    private fun runNext() {
        if (retryPending) return
        val gatt = currentGatt() ?: return
        val operation = scheduler.beginNext() ?: return
        onActiveOperationChanged(operation.diagnosticLabel())
        activeOperationStartedAtUptime = SystemClock.uptimeMillis()
        handler.postAtTime(
            { handleTimeout(operation) },
            timeoutToken,
            SystemClock.uptimeMillis() + OperationTimeoutMillis,
        )
        // Android may synchronously enter a callback from a BluetoothGatt method, so no scheduler
        // lock may be held while the framework call executes.
        val outcome = try {
            executor.start(gatt, operation)
        } catch (error: RuntimeException) {
            log("Could not start ${operation.label}: ${error.message}")
            GattStartOutcome.Rejected(GattStartRejection.LinkUnusable)
        }
        when (outcome) {
            GattStartOutcome.Started -> Unit
            is GattStartOutcome.Rejected -> {
                handler.removeCallbacksAndMessages(timeoutToken)
                handleFailure(
                    operation = operation,
                    source = GattFailureSource.SynchronousStart,
                    status = outcome.statusCode,
                    rejection = outcome.rejection,
                )
            }
        }
    }

    /**
     * A missing callback makes the outcome of side-effecting work unknowable, so it retires the
     * link. Optional identity reads are the one exception because they use distinct UUIDs and do
     * not mutate the bike — but the exception is bounded: a peer that stops answering reads has
     * stopped answering, and the next silence retires the link like any other timeout.
     */
    private fun handleTimeout(operation: GattOperation) {
        if (!scheduler.isActive(operation)) return
        if (operation.isOptionalIdentityRead() &&
            toleratedIdentityReadTimeouts < MaxToleratedIdentityReadTimeouts
        ) {
            val read = operation as GattOperation.Read
            if (!scheduler.complete(operation)) return
            toleratedIdentityReadTimeouts++
            finishActiveOperation()
            log("Motorcycle identity snapshot ${read.characteristic.uuid.shortName()} timed out; continuing")
            runNext()
            return
        }
        handleFailure(operation, GattFailureSource.CallbackTimeout)
    }

    private fun handleFailure(
        operation: GattOperation,
        source: GattFailureSource,
        status: Int? = null,
        rejection: GattStartRejection? = null,
    ) {
        if (!scheduler.isActive(operation)) return
        val failureAction =
            gattFailureAction(source, status, operation.attempt, MaxOperationRetries, rejection)
        if (failureAction != GattFailureAction.ResetGattAndReconnect &&
            isSupersededChallengeSubscription(operation, source)
        ) {
            if (!scheduler.complete(operation)) return
            finishActiveOperation()
            log("Challenge arrived before its subscription callback; continuing with the response")
            runNext()
            return
        }

        val failure = buildFailure(operation, source, status, rejection)
        when (failureAction) {
            GattFailureAction.RetryCurrentGattAfterDelay -> {
                if (!scheduler.retry(operation)) return
                val retryAttempt = operation.attempt + 1
                val delayMillis = gattOperationRetryDelayMillis(operation.attempt)
                log(
                    "${failure.message}; retrying ${operation.diagnosticLabel()} " +
                        "($retryAttempt/$MaxOperationRetries) in ${delayMillis}ms",
                )
                finishActiveOperation()
                retryPending = true
                handler.postAtTime({
                    retryPending = false
                    runNext()
                }, retryToken, SystemClock.uptimeMillis() + delayMillis)
            }

            GattFailureAction.CompleteFailure -> {
                if (!scheduler.complete(operation)) return
                log("${failure.message} (${failure.contextLine()})")
                if (!operation.isOptionalIdentityRead()) {
                    onFailureRecorded(failure)
                }
                (operation as? GattOperation.Write)?.completion?.complete(false)
                if (onOperationExhausted(operation)) return
                finishActiveOperation()
                runNext()
            }

            GattFailureAction.ResetGattAndReconnect -> {
                finishActiveOperation()
                onResetRequired(failure)
            }
        }
    }

    private fun buildFailure(
        operation: GattOperation,
        source: GattFailureSource,
        status: Int?,
        rejection: GattStartRejection?,
    ): ConnectionFailure {
        val category = gattFailureCategory(source, status, rejection)
        return ConnectionFailure(
            message = operationFailureMessage(operation, source, status, rejection, category),
            category = category,
            atMillis = System.currentTimeMillis(),
            statusCode = status,
            statusName = status?.let(::gattStatusName),
            operation = operation.diagnosticLabel(),
            operationDurationMillis = activeOperationStartedAtUptime?.let { startedAt ->
                (SystemClock.uptimeMillis() - startedAt).coerceAtLeast(0L)
            },
            context = attemptContext(),
        )
    }

    private fun operationFailureMessage(
        operation: GattOperation,
        source: GattFailureSource,
        status: Int?,
        rejection: GattStartRejection?,
        category: ConnectionFailureCategory,
    ): String = when (source) {
        GattFailureSource.SynchronousStart -> {
            val reason = gattStartRejectionLabel(rejection ?: GattStartRejection.Unknown)
            val code = status?.let { " (status code $it)" }.orEmpty()
            "Could not start GATT ${operation.diagnosticLabel()}: $reason$code"
        }

        GattFailureSource.CallbackTimeout ->
            "Link lost while ${operation.progressiveLabel()}: " +
                "no callback within ${OperationTimeoutMillis}ms"

        GattFailureSource.StatusCallback -> {
            val detail = "${gattStatusName(requireNotNull(status))} ($status)"
            when (category) {
                ConnectionFailureCategory.LinkLost ->
                    "Link lost while ${operation.progressiveLabel()}: $detail"

                ConnectionFailureCategory.AuthenticationRejected ->
                    "Authentication rejected while ${operation.progressiveLabel()}: $detail"

                else -> "GATT ${operation.diagnosticLabel()} failed: $detail"
            }
        }
    }

    private fun finishActiveOperation() {
        activeOperationStartedAtUptime = null
        onActiveOperationChanged(null)
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
        const val MaxToleratedIdentityReadTimeouts = 1
    }
}
