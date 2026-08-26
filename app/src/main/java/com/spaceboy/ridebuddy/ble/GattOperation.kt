package com.spaceboy.ridebuddy.ble

import android.bluetooth.BluetoothGattCharacteristic
import com.spaceboy.ridebuddy.domain.BikeWriteMode
import kotlinx.coroutines.CompletableDeferred

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
    RetryCurrentGatt,
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
    attempt: Int,
    maxRetries: Int,
): GattFailureAction = when {
    source == GattFailureSource.CallbackTimeout -> GattFailureAction.ResetGattAndReconnect
    attempt < maxRetries -> GattFailureAction.RetryCurrentGatt
    else -> GattFailureAction.CompleteFailure
}

internal fun GattOperation.isChallengeSubscription(): Boolean =
    this is GattOperation.Subscribe && characteristic.uuid == BleCharacteristics.ProtectionChallenge

internal fun GattOperation.isPostAuthenticationSubscription(): Boolean =
    this is GattOperation.Subscribe && characteristic.uuid in BleCharacteristics.PostAuthenticationSubscriptions

internal fun GattOperation.isOptionalIdentityRead(): Boolean =
    this is GattOperation.Read && characteristic.uuid in BleCharacteristics.PostAuthenticationIdentityReads

internal fun GattOperation.isProtectionResponseWrite(): Boolean =
    this is GattOperation.Write && characteristic.uuid == BleCharacteristics.ProtectionResponse

internal fun GattOperation.diagnosticLabel(): String {
    val uuid = when (this) {
        is GattOperation.Subscribe -> characteristic.uuid
        is GattOperation.Read -> characteristic.uuid
        is GattOperation.Write -> characteristic.uuid
    }
    return "$label ${uuid.shortName()}"
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
