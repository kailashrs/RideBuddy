package com.spaceboy.ridebuddy.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothStatusCodes
import com.spaceboy.ridebuddy.domain.BikeWriteMode
import com.spaceboy.ridebuddy.domain.ConnectionFailureCategory
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

/**
 * Why the framework refused to start an operation.
 *
 * Android reports synchronous refusals as either a boolean or a [BluetoothStatusCodes] value.
 * Collapsing both into "failed" makes a permanently-rejected write indistinguishable from a
 * momentarily busy controller, so the reason is preserved here and drives the retry policy.
 */
internal enum class GattStartRejection {
    /** The controller is busy right now; the same GATT may retry shortly. */
    Busy,

    /** The attribute will never accept this operation. Retrying cannot help. */
    NotPermitted,

    /** The adapter, bond, or link is unusable; the GATT session must be retired. */
    LinkUnusable,

    /** The framework gave no usable reason. */
    Unknown,
}

/** Whether the framework accepted an operation for dispatch, and why not if it did not. */
internal sealed interface GattStartOutcome {
    data object Started : GattStartOutcome

    data class Rejected(
        val rejection: GattStartRejection,
        val statusCode: Int? = null,
    ) : GattStartOutcome
}

/** Maps a `BluetoothStatusCodes` value onto the retry policy's view of it. */
internal fun gattStartRejectionFor(statusCode: Int): GattStartRejection = when (statusCode) {
    BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> GattStartRejection.Busy

    BluetoothStatusCodes.ERROR_GATT_WRITE_NOT_ALLOWED,
    BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ALLOWED,
    -> GattStartRejection.NotPermitted

    BluetoothStatusCodes.ERROR_BLUETOOTH_NOT_ENABLED,
    BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED,
    -> GattStartRejection.LinkUnusable

    else -> GattStartRejection.Unknown
}

internal fun gattStartRejectionLabel(rejection: GattStartRejection): String = when (rejection) {
    GattStartRejection.Busy -> "controller busy"
    GattStartRejection.NotPermitted -> "operation not permitted"
    GattStartRejection.LinkUnusable -> "link unusable"
    GattStartRejection.Unknown -> "rejected by the Bluetooth stack"
}

/** Where in an operation's life the failure surfaced. Each stage is diagnosed differently. */
internal enum class GattFailureSource {
    /** The framework refused to dispatch the call at all. */
    SynchronousStart,

    /** The call was dispatched and the peer or stack reported a non-success status. */
    StatusCallback,

    /** The call was dispatched and no callback ever arrived. */
    CallbackTimeout,
}

/** What to do about a failed operation. */
internal enum class GattFailureAction {
    /** Re-queue on the same link after a backoff; the problem looks momentary. */
    RetryCurrentGattAfterDelay,

    /** Give up on this operation only; the link itself is still good. */
    CompleteFailure,

    /** The link is no longer trustworthy: tear the GATT down and reconnect. */
    ResetGattAndReconnect,
}

/**
 * Which lane an operation queues in. [Critical] is for steps the protocol sequence
 * depends on — authentication and subscription — so they are never stuck behind a burst
 * of routine display writes.
 */
internal enum class GattOperationPriority {
    Normal,
    Critical,
}

/**
 * The retry policy, in one place.
 *
 * The ordering of the branches is the policy. A missing callback is treated as the most
 * serious outcome — the stack has lost track of the request, so nothing about the link
 * can be trusted and it is rebuilt. A synchronous refusal is diagnosed from its reason,
 * because "the controller is busy" and "this attribute is read-only" need opposite
 * responses. Only after those does the status code decide, and it may still escalate to a
 * reconnect: a security status here means the bond is no longer accepted, which no amount
 * of retrying on the same link will fix.
 */
internal fun gattFailureAction(
    source: GattFailureSource,
    status: Int?,
    attempt: Int,
    maxRetries: Int,
    rejection: GattStartRejection? = null,
): GattFailureAction = when {
    source == GattFailureSource.CallbackTimeout -> GattFailureAction.ResetGattAndReconnect
    source == GattFailureSource.SynchronousStart -> when (rejection ?: GattStartRejection.Unknown) {
        GattStartRejection.NotPermitted -> GattFailureAction.CompleteFailure
        GattStartRejection.LinkUnusable -> GattFailureAction.ResetGattAndReconnect
        GattStartRejection.Busy,
        GattStartRejection.Unknown,
        -> if (attempt < maxRetries) {
            GattFailureAction.RetryCurrentGattAfterDelay
        } else {
            GattFailureAction.CompleteFailure
        }
    }

    status in GattStatusesRequiringFreshConnection -> GattFailureAction.ResetGattAndReconnect
    status in GattSecurityStatuses -> GattFailureAction.ResetGattAndReconnect
    status in GattRetryableStatuses && attempt < maxRetries ->
        GattFailureAction.RetryCurrentGattAfterDelay

    else -> GattFailureAction.CompleteFailure
}

/**
 * Classifies a failure for diagnostics. Only the unambiguous ATT security statuses become
 * [ConnectionFailureCategory.AuthenticationRejected]; status 8 is deliberately excluded because
 * Android reuses it for both GATT_INSUFFICIENT_AUTHORIZATION and GATT_CONNECTION_TIMEOUT, and
 * reporting a dropped link as an authentication failure is exactly the confusion to avoid.
 */
internal fun gattFailureCategory(
    source: GattFailureSource,
    status: Int?,
    rejection: GattStartRejection? = null,
): ConnectionFailureCategory = when (source) {
    GattFailureSource.CallbackTimeout -> ConnectionFailureCategory.LinkLost

    GattFailureSource.SynchronousStart -> when (rejection ?: GattStartRejection.Unknown) {
        GattStartRejection.Busy -> ConnectionFailureCategory.Transient
        GattStartRejection.NotPermitted -> ConnectionFailureCategory.Deterministic
        GattStartRejection.LinkUnusable -> ConnectionFailureCategory.LocalPrecondition
        GattStartRejection.Unknown -> ConnectionFailureCategory.Unknown
    }

    GattFailureSource.StatusCallback -> when (status) {
        null -> ConnectionFailureCategory.Unknown
        in GattUnambiguousSecurityStatuses -> ConnectionFailureCategory.AuthenticationRejected
        in GattStatusesRequiringFreshConnection -> ConnectionFailureCategory.LinkLost
        in GattRetryableStatuses -> ConnectionFailureCategory.Transient
        else -> ConnectionFailureCategory.Deterministic
    }
}

/** The framework's own constant name, so a log reads GATT_ERROR (133) rather than a prose label. */
internal fun gattStatusName(status: Int): String = when (status) {
    BluetoothGatt.GATT_SUCCESS -> "GATT_SUCCESS"
    BluetoothGatt.GATT_READ_NOT_PERMITTED -> "GATT_READ_NOT_PERMITTED"
    BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> "GATT_WRITE_NOT_PERMITTED"
    BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION -> "GATT_INSUFFICIENT_AUTHENTICATION"
    BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED -> "GATT_REQUEST_NOT_SUPPORTED"
    BluetoothGatt.GATT_INVALID_OFFSET -> "GATT_INVALID_OFFSET"
    BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION -> "GATT_INSUFFICIENT_AUTHORIZATION_OR_CONNECTION_TIMEOUT"
    GattInsufficientEncryptionKeySize -> "GATT_INSUFFICIENT_ENCRYPTION_KEY_SIZE"
    BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> "GATT_INVALID_ATTRIBUTE_LENGTH"
    BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> "GATT_INSUFFICIENT_ENCRYPTION"
    GattBusy -> "GATT_BUSY"
    GattGenericError -> "GATT_ERROR"
    BluetoothGatt.GATT_CONNECTION_TIMEOUT -> "GATT_CONNECTION_TIMEOUT"
    BluetoothGatt.GATT_CONNECTION_CONGESTED -> "GATT_CONNECTION_CONGESTED"
    BluetoothGatt.GATT_FAILURE -> "GATT_FAILURE"
    else -> "GATT_STATUS_$status"
}

/**
 * Backoff before retrying an operation on the same link: 200 ms, 400 ms, then 800 ms.
 * Much shorter than the reconnect backoff, because this is waiting out a busy controller
 * rather than a bike that may have been switched off.
 */
internal fun gattOperationRetryDelayMillis(attempt: Int): Long =
    minOf(1_000L, 200L shl attempt.coerceIn(0, 2))

private const val GattBusy = 0x84
private const val GattGenericError = 0x85
private const val GattInsufficientEncryptionKeySize = 0x0C

/**
 * The supervision-timeout status the native stack actually delivers to a GATT callback.
 *
 * `BluetoothGatt.GATT_CONNECTION_TIMEOUT` is 0x93, a framework-only value that never appears on
 * the wire; the HCI status a dropped link reports is 0x08, which is why it collides with
 * `GATT_INSUFFICIENT_AUTHORIZATION` and must not be read as an authentication rejection.
 */
private const val GattConnectionTimeout = 0x08

private val GattRetryableStatuses = setOf(
    GattBusy,
    BluetoothGatt.GATT_CONNECTION_CONGESTED,
)

private val GattSecurityStatuses = setOf(
    BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION,
    BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION,
    GattInsufficientEncryptionKeySize,
    BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION,
)

private val GattStatusesRequiringFreshConnection = setOf(
    GattGenericError,
    GattConnectionTimeout,
    BluetoothGatt.GATT_CONNECTION_TIMEOUT,
    BluetoothGatt.GATT_FAILURE,
)

/**
 * Security statuses that cannot also mean "the link dropped". [GattSecurityStatuses] still drives
 * the retire-the-link action for status 8, but only these may be reported as an authentication
 * rejection.
 */
private val GattUnambiguousSecurityStatuses = setOf(
    BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION,
    GattInsufficientEncryptionKeySize,
    BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION,
)

// Role predicates. The connection controller reacts to *which* protocol step finished,
// not to the operation type alone, and these keep that decision out of long `when` chains.

/** The subscription that must land before the cluster will issue its challenge. */
internal fun GattOperation.isChallengeSubscription(): Boolean =
    this is GattOperation.Subscribe && characteristic.uuid == BleCharacteristics.ProtectionChallenge

internal fun GattOperation.isPostAuthenticationSubscription(): Boolean =
    this is GattOperation.Subscribe && characteristic.uuid in BleCharacteristics.PostAuthenticationSubscriptions

internal fun GattOperation.isProtectionResponseWrite(): Boolean =
    this is GattOperation.Write && characteristic.uuid == BleCharacteristics.ProtectionResponse

internal fun GattOperation.characteristicUuid(): UUID = when (this) {
    is GattOperation.Subscribe -> characteristic.uuid
    is GattOperation.Write -> characteristic.uuid
}

internal fun GattOperation.diagnosticLabel(): String = "$label ${characteristicUuid().shortName()}"

/** Reads as a clause: "Link lost while subscribing to 8610". */
internal fun GattOperation.progressiveLabel(): String {
    val uuid = characteristicUuid().shortName()
    return when (this) {
        is GattOperation.Subscribe -> "subscribing to $uuid"
        is GattOperation.Write -> "writing $uuid"
    }
}

/**
 * One queued unit of GATT work.
 *
 * Everything the connection does to the peer goes through this type, which is what makes
 * a single serialised queue possible: the Android stack accepts one operation at a time
 * per connection, and dispatching a second before the first calls back silently drops it.
 *
 * [attempt] rides along on the operation rather than living in the scheduler so a retry
 * is a plain value — [retry] returns a copy with the count bumped — and the failure policy
 * can see how many times this exact operation has already been tried.
 */
internal sealed interface GattOperation {
    val label: String
    val attempt: Int
    val priority: GattOperationPriority
        get() = GattOperationPriority.Normal

    /** This operation again, with its attempt count incremented. */
    fun retry(): GattOperation

    /** Enable notifications or indications by writing the characteristic's CCCD. */
    data class Subscribe(val characteristic: BluetoothGattCharacteristic, override val attempt: Int = 0) :
        GattOperation {
        override val label = "subscription"
        override fun retry() = copy(attempt = attempt + 1)
    }

    /**
     * Write a value to the peer.
     *
     * [completion] lets a caller await the outcome, which is how display writes are
     * correlated with the callback that confirms them instead of being fired blind.
     * [requestId] carries the same correlation into the diagnostics journal.
     */
    data class Write(
        val characteristic: BluetoothGattCharacteristic,
        val value: ByteArray,
        val mode: BikeWriteMode = BikeWriteMode.Default,
        val completion: CompletableDeferred<Boolean>? = null,
        val requestId: Long? = null,
        override val priority: GattOperationPriority = GattOperationPriority.Normal,
        override val attempt: Int = 0,
    ) : GattOperation {
        override val label = "write"
        override fun retry() = copy(attempt = attempt + 1)
    }
}
