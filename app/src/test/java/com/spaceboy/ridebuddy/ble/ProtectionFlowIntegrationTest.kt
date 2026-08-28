package com.spaceboy.ridebuddy.ble

import android.bluetooth.BluetoothGattCharacteristic
import com.spaceboy.ridebuddy.domain.ProtectionPath
import com.spaceboy.ridebuddy.domain.ProtectionPhase
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectionFlowIntegrationTest {
    @Test
    fun `fresh India profile flow reaches ready only after response and every required subscription`() {
        val session = ProtectionSession(previouslyAccepted = false)
        val scheduler = GattOperationScheduler()
        val gate = PostAuthenticationGate(BleCharacteristics.PostAuthenticationSubscriptions)

        assertEquals(ProtectionAction.SubscribeChallenge, session.begin())
        val challengeSubscription = GattOperation.Subscribe(
            characteristic(BleCharacteristics.ProtectionChallenge, BluetoothGattCharacteristic.PROPERTY_INDICATE),
        )
        scheduler.enqueue(challengeSubscription)
        assertSame(challengeSubscription, scheduler.beginNext())
        scheduler.complete(challengeSubscription)
        assertEquals(ProtectionAction.None, session.onChallengeSubscriptionReady())

        val responseAction = session.onChallenge(hex("63 75 A3 A4 63 3B"))
        assertTrue(responseAction is ProtectionAction.WriteResponse)
        responseAction as ProtectionAction.WriteResponse
        val responseWrite = GattOperation.Write(
            characteristic(BleCharacteristics.ProtectionResponse, BluetoothGattCharacteristic.PROPERTY_WRITE),
            responseAction.value,
            priority = GattOperationPriority.Critical,
        )
        scheduler.enqueue(responseWrite)
        assertSame(responseWrite, scheduler.beginNext())
        scheduler.complete(responseWrite)
        assertEquals(ProtectionAction.BeginPostAuthentication, session.onProtectionResponseWritten())

        val subscriptions = BleCharacteristics.PostAuthenticationSubscriptions.map { uuid ->
            GattOperation.Subscribe(characteristic(uuid, BluetoothGattCharacteristic.PROPERTY_NOTIFY))
        }
        scheduler.enqueueAll(subscriptions)
        assertEquals(null, gate.acceptEvidence("valid telemetry"))
        var finalUpdate: SubscriptionGateUpdate? = null
        subscriptions.zip(BleCharacteristics.PostAuthenticationSubscriptions).forEach { (subscription, uuid) ->
            assertSame(subscription, scheduler.beginNext())
            scheduler.complete(subscription)
            finalUpdate = gate.markSubscriptionEnabled(uuid)
        }

        assertEquals(true, finalUpdate?.becameReady)
        assertEquals("valid telemetry", finalUpdate?.deferredEvidence)
        assertEquals(
            ProtectionAction.CompleteAuthentication("valid telemetry"),
            session.onPostAuthenticationEvidence(requireNotNull(finalUpdate?.deferredEvidence)),
        )
        assertEquals(ProtectionPhase.Ready, session.phase)
        assertEquals(ProtectionPath.ChallengeIndication, session.path)
    }

    @Test
    fun `recognized re-challenge preempts queued normal work and returns to ready`() {
        val session = ProtectionSession(previouslyAccepted = true)
        val scheduler = GattOperationScheduler()
        session.begin()
        session.onPostAuthenticationEvidence("VIN")
        val queuedNormal = GattOperation.Write(
            characteristic(UUID.randomUUID(), BluetoothGattCharacteristic.PROPERTY_WRITE),
            byteArrayOf(1),
        )
        scheduler.enqueue(queuedNormal)

        val responseAction = session.onChallenge(hex("63 75 A3 A4 63 3B"))
        responseAction as ProtectionAction.WriteResponse
        val responseWrite = GattOperation.Write(
            characteristic(BleCharacteristics.ProtectionResponse, BluetoothGattCharacteristic.PROPERTY_WRITE),
            responseAction.value,
            priority = GattOperationPriority.Critical,
        )
        scheduler.enqueue(responseWrite)

        assertSame(responseWrite, scheduler.beginNext())
        scheduler.complete(responseWrite)
        assertEquals(ProtectionAction.None, session.onProtectionResponseWritten())
        assertEquals(ProtectionPhase.Ready, session.phase)
        assertSame(queuedNormal, scheduler.beginNext())
    }

    private fun characteristic(uuid: UUID, properties: Int) = BluetoothGattCharacteristic(
        uuid,
        properties,
        BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
    )

    private fun hex(value: String): ByteArray = value.split(" ")
        .filter(String::isNotBlank)
        .map { part -> part.toInt(16).toByte() }
        .toByteArray()
}
