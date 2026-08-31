package com.spaceboy.ridebuddy.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothStatusCodes
import com.spaceboy.ridebuddy.domain.BikeWriteMode
import com.spaceboy.ridebuddy.domain.ConnectionFailureCategory
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GattOperationTest {
    @Test
    fun requestedWriteModePrefersTheRequestedCapabilityAndFallsBackSafely() {
        val both = BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE

        assertEquals(
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            requestedWriteType(both, BikeWriteMode.Default),
        )
        assertEquals(
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            requestedWriteType(both, BikeWriteMode.NoResponsePreferred),
        )
        assertEquals(
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            requestedWriteType(
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BikeWriteMode.NoResponsePreferred,
            ),
        )
        assertNull(requestedWriteType(BluetoothGattCharacteristic.PROPERTY_READ, BikeWriteMode.Default))
    }

    @Test
    fun writeEqualityUsesPayloadReferenceSemantics() {
        val characteristic = BluetoothGattCharacteristic(
            UUID.randomUUID(),
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val payload = byteArrayOf(0x01, 0x02)
        val write = GattOperation.Write(characteristic, payload)

        assertEquals(write, GattOperation.Write(characteristic, payload))
        assertNotEquals(write, GattOperation.Write(characteristic, payload.copyOf()))
    }

    @Test
    fun retryRetainsPayloadAndIncrementsAttempt() {
        val characteristic = BluetoothGattCharacteristic(
            UUID.randomUUID(),
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val payload = byteArrayOf(0x01, 0x02)

        val retry = GattOperation.Write(characteristic, payload).retry()

        assertSame(payload, retry.value)
        assertEquals(1, retry.attempt)
    }

    @Test
    fun subscribeRetryIncrementsAttempt() {
        val characteristic = BluetoothGattCharacteristic(
            UUID.randomUUID(),
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )

        val retry = GattOperation.Subscribe(characteristic).retry()

        assertEquals(1, retry.attempt)
    }

    @Test
    fun retryRetainsOperationPriority() {
        val characteristic = BluetoothGattCharacteristic(
            UUID.randomUUID(),
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        val retry = GattOperation.Write(
            characteristic,
            byteArrayOf(0x01),
            priority = GattOperationPriority.Critical,
        ).retry()

        assertEquals(GattOperationPriority.Critical, retry.priority)
    }

    @Test
    fun retryRetainsNoResponseWriteMode() {
        val characteristic = BluetoothGattCharacteristic(
            UUID.randomUUID(),
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        val retry = GattOperation.Write(
            characteristic = characteristic,
            value = byteArrayOf(0x01),
            mode = BikeWriteMode.NoResponsePreferred,
        ).retry()

        assertEquals(BikeWriteMode.NoResponsePreferred, retry.mode)
        assertEquals(1, retry.attempt)
    }

    @Test
    fun acceptedNoResponseWriteWaitsForItsFrameworkCallback() {
        val characteristic = BluetoothGattCharacteristic(
            UUID.randomUUID(),
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val operation = GattOperation.Write(
            characteristic = characteristic,
            value = byteArrayOf(0x01),
            mode = BikeWriteMode.NoResponsePreferred,
        )

        assertEquals(BikeWriteMode.NoResponsePreferred, operation.mode)
    }

    @Test
    fun acceptedOperationTimeoutResetsGattInsteadOfRetryingCurrentSession() {
        assertEquals(
            GattFailureAction.ResetGattAndReconnect,
            gattFailureAction(
                source = GattFailureSource.CallbackTimeout,
                status = null,
                attempt = 0,
                maxRetries = 2,
            ),
        )
    }

    @Test
    fun synchronousStartFailuresUseBoundedDelayedRetries() {
        assertEquals(
            GattFailureAction.RetryCurrentGattAfterDelay,
            gattFailureAction(GattFailureSource.SynchronousStart, null, 0, maxRetries = 2),
        )
        assertEquals(
            GattFailureAction.RetryCurrentGattAfterDelay,
            gattFailureAction(GattFailureSource.SynchronousStart, null, 1, maxRetries = 2),
        )
        assertEquals(
            GattFailureAction.CompleteFailure,
            gattFailureAction(GattFailureSource.SynchronousStart, null, 2, maxRetries = 2),
        )
        assertEquals(200L, gattOperationRetryDelayMillis(0))
        assertEquals(400L, gattOperationRetryDelayMillis(1))
        assertEquals(800L, gattOperationRetryDelayMillis(2))
    }

    @Test
    fun genericGattAndSecurityFailuresRetireTheCurrentConnection() {
        listOf(0x85, 5, 8, 12, 15).forEach { status ->
            assertEquals(
                GattFailureAction.ResetGattAndReconnect,
                gattFailureAction(GattFailureSource.StatusCallback, status, 0, maxRetries = 2),
            )
        }
    }

    @Test
    fun congestionRetriesAfterDelayButProtocolErrorsComplete() {
        assertEquals(
            GattFailureAction.RetryCurrentGattAfterDelay,
            gattFailureAction(GattFailureSource.StatusCallback, 143, 0, maxRetries = 2),
        )
        assertEquals(
            GattFailureAction.CompleteFailure,
            gattFailureAction(GattFailureSource.StatusCallback, 143, 2, maxRetries = 2),
        )
        assertEquals(
            GattFailureAction.CompleteFailure,
            gattFailureAction(GattFailureSource.StatusCallback, 3, 0, maxRetries = 2),
        )
    }

    @Test
    fun synchronousRejectionReasonsAreClassifiedInsteadOfCollapsedToRetry() {
        assertEquals(
            GattStartRejection.Busy,
            gattStartRejectionFor(BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY),
        )
        assertEquals(
            GattStartRejection.NotPermitted,
            gattStartRejectionFor(BluetoothStatusCodes.ERROR_GATT_WRITE_NOT_ALLOWED),
        )
        assertEquals(
            GattStartRejection.LinkUnusable,
            gattStartRejectionFor(BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED),
        )
        assertEquals(GattStartRejection.Unknown, gattStartRejectionFor(BluetoothStatusCodes.ERROR_UNKNOWN))
    }

    @Test
    fun deterministicStartRejectionsAreNotRetried() {
        assertEquals(
            GattFailureAction.CompleteFailure,
            gattFailureAction(
                source = GattFailureSource.SynchronousStart,
                status = BluetoothStatusCodes.ERROR_GATT_WRITE_NOT_ALLOWED,
                attempt = 0,
                maxRetries = 2,
                rejection = GattStartRejection.NotPermitted,
            ),
        )
    }

    @Test
    fun unusableLinkRejectionsRetireTheSessionInsteadOfRetryingOnIt() {
        assertEquals(
            GattFailureAction.ResetGattAndReconnect,
            gattFailureAction(
                source = GattFailureSource.SynchronousStart,
                status = BluetoothStatusCodes.ERROR_DEVICE_NOT_BONDED,
                attempt = 0,
                maxRetries = 2,
                rejection = GattStartRejection.LinkUnusable,
            ),
        )
    }

    @Test
    fun busyStartRejectionsKeepTheBoundedRetry() {
        assertEquals(
            GattFailureAction.RetryCurrentGattAfterDelay,
            gattFailureAction(
                source = GattFailureSource.SynchronousStart,
                status = BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY,
                attempt = 0,
                maxRetries = 2,
                rejection = GattStartRejection.Busy,
            ),
        )
        assertEquals(
            GattFailureAction.CompleteFailure,
            gattFailureAction(
                source = GattFailureSource.SynchronousStart,
                status = BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY,
                attempt = 2,
                maxRetries = 2,
                rejection = GattStartRejection.Busy,
            ),
        )
    }

    @Test
    fun status133IsReportedAsLinkLossRatherThanAnAuthenticationFailure() {
        assertEquals(
            ConnectionFailureCategory.LinkLost,
            gattFailureCategory(GattFailureSource.StatusCallback, 0x85),
        )
        assertEquals("GATT_ERROR", gattStatusName(0x85))
    }

    @Test
    fun onlyUnambiguousSecurityStatusesAreReportedAsAuthenticationRejection() {
        listOf(5, 0x0C, 15).forEach { status ->
            assertEquals(
                ConnectionFailureCategory.AuthenticationRejected,
                gattFailureCategory(GattFailureSource.StatusCallback, status),
            )
        }
        // Android reuses 8 for both GATT_INSUFFICIENT_AUTHORIZATION and GATT_CONNECTION_TIMEOUT.
        assertEquals(
            ConnectionFailureCategory.LinkLost,
            gattFailureCategory(GattFailureSource.StatusCallback, 8),
        )
        // 0x93 is the framework-only BluetoothGatt.GATT_CONNECTION_TIMEOUT value, which never
        // arrives on the wire but must classify the same way if a stack ever reports it.
        assertEquals(
            ConnectionFailureCategory.LinkLost,
            gattFailureCategory(GattFailureSource.StatusCallback, 0x93),
        )
    }

    @Test
    fun timeoutsAndCongestionAreCategorisedApart() {
        assertEquals(
            ConnectionFailureCategory.LinkLost,
            gattFailureCategory(GattFailureSource.CallbackTimeout, null),
        )
        assertEquals(
            ConnectionFailureCategory.Transient,
            gattFailureCategory(GattFailureSource.StatusCallback, 143),
        )
        assertEquals(
            ConnectionFailureCategory.Deterministic,
            gattFailureCategory(GattFailureSource.StatusCallback, 3),
        )
    }
}
