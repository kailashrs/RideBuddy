package com.spaceboy.ridebuddy.ble

import android.bluetooth.BluetoothGattCharacteristic
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GattOperationSchedulerTest {
    private val characteristic = BluetoothGattCharacteristic(
        UUID.randomUUID(),
        BluetoothGattCharacteristic.PROPERTY_WRITE,
        BluetoothGattCharacteristic.PERMISSION_WRITE,
    )

    @Test
    fun `critical lane runs before every queued normal operation without preempting active work`() {
        val scheduler = GattOperationScheduler()
        val activeNormal = operation(1)
        val queuedNormal = operation(2)
        val firstCritical = operation(3, GattOperationPriority.Critical)
        val secondCritical = operation(4, GattOperationPriority.Critical)

        assertTrue(scheduler.enqueue(activeNormal))
        assertSame(activeNormal, scheduler.beginNext())
        assertFalse(scheduler.enqueue(queuedNormal))
        scheduler.enqueue(firstCritical)
        scheduler.enqueue(secondCritical)

        assertSame(activeNormal, scheduler.active())
        assertTrue(scheduler.complete(activeNormal))
        assertSame(firstCritical, scheduler.beginNext())
        scheduler.complete(firstCritical)
        assertSame(secondCritical, scheduler.beginNext())
        scheduler.complete(secondCritical)
        assertSame(queuedNormal, scheduler.beginNext())
    }

    @Test
    fun `normal retry waits behind all pending critical operations`() {
        val scheduler = GattOperationScheduler()
        val normal = operation(1)
        val firstCritical = operation(2, GattOperationPriority.Critical)
        val secondCritical = operation(3, GattOperationPriority.Critical)
        scheduler.enqueue(normal)
        scheduler.beginNext()
        scheduler.enqueue(firstCritical)
        scheduler.enqueue(secondCritical)

        assertTrue(scheduler.retry(normal))
        assertSame(firstCritical, scheduler.beginNext())
        scheduler.complete(firstCritical)
        assertSame(secondCritical, scheduler.beginNext())
        scheduler.complete(secondCritical)
        val retry = scheduler.beginNext() as GattOperation.Write

        assertEquals(1, retry.attempt)
        assertSame(normal.value, retry.value)
    }

    @Test
    fun `critical retry stays ahead of queued work at the same priority`() {
        val scheduler = GattOperationScheduler()
        val activeCritical = operation(1, GattOperationPriority.Critical)
        val queuedCritical = operation(2, GattOperationPriority.Critical)
        scheduler.enqueue(activeCritical)
        scheduler.beginNext()
        scheduler.enqueue(queuedCritical)

        assertTrue(scheduler.retry(activeCritical))
        val retry = scheduler.beginNext() as GattOperation.Write

        assertEquals(1, retry.attempt)
        scheduler.complete(retry)
        assertSame(queuedCritical, scheduler.beginNext())
    }

    @Test
    fun `clear returns active and queued operations and resets the scheduler`() {
        val scheduler = GattOperationScheduler()
        val active = operation(1)
        val critical = operation(2, GattOperationPriority.Critical)
        val normal = operation(3)
        scheduler.enqueue(active)
        scheduler.beginNext()
        scheduler.enqueue(critical)
        scheduler.enqueue(normal)

        assertEquals(listOf(active, critical, normal), scheduler.clear())
        assertTrue(scheduler.hasNoActiveOperation())
        assertNull(scheduler.beginNext())
    }

    private fun operation(
        marker: Int,
        priority: GattOperationPriority = GattOperationPriority.Normal,
    ) = GattOperation.Write(
        characteristic = characteristic,
        value = byteArrayOf(marker.toByte()),
        priority = priority,
    )
}
