package com.spaceboy.ridebuddy.ble

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleCaptureRecorderTest {
    private val characteristic = UUID.fromString("d6328aea-d630-4a83-b51b-1da8e8da8510")

    @Test
    fun `does not retain traffic until explicitly enabled`() {
        val recorder = BleCaptureRecorder()

        recorder.record(BleCaptureDirection.Outbound, characteristic, byteArrayOf(0x01))

        assertFalse(recorder.state.value.enabled)
        assertTrue(recorder.state.value.entries.isEmpty())
    }

    @Test
    fun `records raw payload and provides a shareable report`() {
        val recorder = BleCaptureRecorder()
        recorder.setEnabled(true)

        recorder.record(BleCaptureDirection.Notification, characteristic, byteArrayOf(0x01, 0xAF.toByte()))

        assertEquals(1, recorder.state.value.entries.size)
        assertEquals("01 AF", recorder.state.value.entries.single().payload.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) })
        assertTrue(recorder.exportText().contains("NTF"))
        assertTrue(recorder.exportText().contains("01 AF"))
    }

    @Test
    fun `clear retains capture preference and removes entries`() {
        val recorder = BleCaptureRecorder()
        recorder.setEnabled(true)
        recorder.record(BleCaptureDirection.Read, characteristic, byteArrayOf(0x10))

        recorder.clear()

        assertTrue(recorder.state.value.enabled)
        assertTrue(recorder.state.value.entries.isEmpty())
    }
}
