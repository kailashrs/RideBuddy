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

internal sealed interface GattStartOutcome {
    data object Started : GattStartOutcome

    data class Rejected(
        val rejection: GattStartRejection,
        val statusCode: Int? = null,
    ) : GattStartOutcome
}

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

internal enum class GattStartAction {
    AwaitCallback,
    HandleSynchronousFailure,
}

internal enum class GattFailureSource {
    SynchronousStart,
    StatusCallback,
    CallbackTimeout,
}

internal enum class GattFailureAction {
    RetryCurrentGattAfterDelay,
    CompleteFailure,
    ResetGattAndReconnect,
}

internal enum class GattOperationPriority {
    Normal,
    Critical,
}

internal fun gattStartAction(started: Boolean): GattStartAction =
    if (started) GattStartAction.AwaitCallback else GattStartAction.HandleSynchronousFailure

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

internal fun gattOperationRetryDelayMillis(attempt: Int): Long =
    minOf(1_000L, 200L shl attempt.coerceIn(0, 2))

internal fun gattOperationStatusLabel(status: Int): String = when (status) {
    BluetoothGatt.GATT_SUCCESS -> "success"
    BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION -> "insufficient authentication"
    BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION -> "insufficient authorization"
    GattInsufficientEncryptionKeySize -> "insufficient encryption key size"
    BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> "insufficient encryption"
    BluetoothGatt.GATT_INVALID_OFFSET -> "invalid offset"
    BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> "invalid attribute length"
    BluetoothGatt.GATT_CONNECTION_CONGESTED -> "connection congested"
    GattBusy -> "GATT busy"
    GattGenericError -> "generic GATT error"
    BluetoothGatt.GATT_CONNECTION_TIMEOUT -> "GATT connection timeout"
    BluetoothGatt.GATT_FAILURE -> "GATT failure"
    else -> "status $status"
}

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

internal fun GattOperation.isChallengeSubscription(): Boolean =
    this is GattOperation.Subscribe && characteristic.uuid == BleCharacteristics.ProtectionChallenge

internal fun GattOperation.isPostAuthenticationSubscription(): Boolean =
    this is GattOperation.Subscribe && characteristic.uuid in BleCharacteristics.PostAuthenticationSubscriptions

internal fun GattOperation.isOptionalIdentityRead(): Boolean =
    this is GattOperation.Read && characteristic.uuid in BleCharacteristics.PostAuthenticationIdentityReads

internal fun GattOperation.isProtectionResponseWrite(): Boolean =
    this is GattOperation.Write && characteristic.uuid == BleCharacteristics.ProtectionResponse

internal fun GattOperation.characteristicUuid(): UUID = when (this) {
    is GattOperation.Subscribe -> characteristic.uuid
    is GattOperation.Read -> characteristic.uuid
    is GattOperation.Write -> characteristic.uuid
}

internal fun GattOperation.diagnosticLabel(): String = "$label ${characteristicUuid().shortName()}"

/** Reads as a clause: "Link lost while subscribing to 8610". */
internal fun GattOperation.progressiveLabel(): String {
    val uuid = characteristicUuid().shortName()
    return when (this) {
        is GattOperation.Subscribe -> "subscribing to $uuid"
        is GattOperation.Read -> "reading $uuid"
        is GattOperation.Write -> "writing $uuid"
    }
}

internal sealed interface GattOperation {
    val label: String
    val attempt: Int
    val priority: GattOperationPriority
        get() = GattOperationPriority.Normal
    fun retry(): GattOperation

    data class Subscribe(val characteristic: BluetoothGattCharacteristic, override val attempt: Int = 0) :
        GattOperation {
        override val label = "subscription"
        override fun retry() = copy(attempt = attempt + 1)
    }

    data class Read(
        val characteristic: BluetoothGattCharacteristic,
        override val attempt: Int = 0,
    ) :
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
        override val priority: GattOperationPriority = GattOperationPriority.Normal,
        override val attempt: Int = 0,
    ) : GattOperation {
        override val label = "write"
        override fun retry() = copy(attempt = attempt + 1)
    }
}
