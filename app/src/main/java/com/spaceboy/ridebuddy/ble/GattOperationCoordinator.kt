package com.spaceboy.ridebuddy.ble

import android.bluetooth.BluetoothGatt
import android.os.Handler
import android.os.SystemClock
import com.spaceboy.ridebuddy.domain.ConnectionAttemptContext
import com.spaceboy.ridebuddy.domain.ConnectionFailure
import com.spaceboy.ridebuddy.domain.ConnectionFailureCategory
import java.util.UUID

/**
 * Owns serialization, retries, callback correlation, and timeouts for GATT I/O.
 *
 * Android accepts one characteristic or descriptor operation at a time per connection and
 * silently discards a second issued before the first calls back, so every read, write and
 * subscription is funnelled through here and dispatched one at a time. Each dispatch arms a
 * timeout, because the stack can also accept an operation and then never call back at all —
 * the failure mode a naive queue deadlocks on.
 *
 * The scope is exactly that guard, not "everything sent to the peer". `readRemoteRssi()` is
 * issued directly from the connection and never enters this queue, because Android does not
 * apply the busy guard to it — see `docs/cluster-link-decisions.md` (D6).
 *
 * Failure handling is delegated: [gattFailureAction] decides retry, give up, or rebuild
 * the link, and [gattFailureCategory] decides how it is described. Every failure leaves
 * here as a [ConnectionFailure] carrying the attempt it happened in, how long the
 * operation ran, and the status behind it, so diagnostics never has to guess afterwards.
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

    fun enqueue(operation: GattOperation) {
        if (scheduler.enqueue(operation)) runNext()
    }

    fun enqueueAll(operations: List<GattOperation>) {
        if (scheduler.enqueueAll(operations)) runNext()
    }

    /** The write currently with the framework, used to correlate an incoming write callback. */
    fun activeWrite(): GattOperation.Write? = scheduler.active() as? GattOperation.Write

    /**
     * Cancels a queued write by request id, for a display update that has been superseded
     * before it was ever sent. Returns false if it is already in flight — at that point it
     * belongs to the framework and has to run to completion.
     */
    fun removeQueuedWrite(requestId: Long): Boolean = scheduler.removeQueued { operation ->
        (operation as? GattOperation.Write)?.requestId == requestId
    }.isNotEmpty()

    /**
     * Abandons all work, failing every pending write's completion. Callers awaiting a
     * write get a definite `false` rather than hanging on a link that no longer exists.
     */
    fun clear() {
        handler.removeCallbacksAndMessages(timeoutToken)
        handler.removeCallbacksAndMessages(retryToken)
        retryPending = false
        activeOperationStartedAtUptime = null
        scheduler.clear().filterIsInstance<GattOperation.Write>().forEach { operation ->
            operation.completion?.complete(false)
        }
    }

    /**
     * Handles a completion callback from the framework.
     *
     * [matchesActiveOperation] correlates the callback with what was dispatched: Android
     * delivers callbacks without a request identifier, so a stale one from a superseded
     * operation is indistinguishable except by checking the characteristic it names. An
     * unmatched callback is logged and dropped rather than completing whatever happens to
     * be active.
     */
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

    /** Dispatches the next queued operation, if the link is up and nothing is in flight. */
    private fun runNext() {
        // A backoff is running; it will call back here when it expires. Starting now would
        // reissue the operation immediately and defeat the delay.
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
     * link — Android leaves the GATT unable to perform any further characteristic or descriptor
     * operation until the callback, a disconnect, or a replacement arrives. There is no operation
     * for which completing it locally is safe: doing so while Android still considers the
     * connection busy only moves the failure onto whatever runs next.
     */
    private fun handleTimeout(operation: GattOperation) {
        if (!scheduler.isActive(operation)) return
        handleFailure(operation, GattFailureSource.CallbackTimeout)
    }

    /** Applies the retry policy to a failed operation and reports the outcome. */
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
                onFailureRecorded(failure)
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

    /**
     * True when a challenge subscription "failed" only because it has already done its job.
     *
     * The cluster can issue its challenge before Android reports the CCCD write that
     * enabled it. Once a response is pending, the subscription demonstrably worked, and
     * failing the attempt on its late error status would abort a handshake that is
     * succeeding. A missing callback is excluded: that says nothing about the link, so it
     * still retires it.
     */
    private fun isSupersededChallengeSubscription(
        operation: GattOperation,
        source: GattFailureSource,
    ): Boolean = source != GattFailureSource.CallbackTimeout &&
        operation.isChallengeSubscription() &&
        isChallengeResponsePending()

    private companion object {
        /** How long to wait for a callback before treating the link as lost. */
        const val OperationTimeoutMillis = 8_000L

        /** Retries per operation on the same link, before it is given up or escalated. */
        const val MaxOperationRetries = 2
    }
}
