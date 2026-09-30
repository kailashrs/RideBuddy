package com.spaceboy.ridebuddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The handlebar EXIT is only useful while guidance runs in the background, which is exactly when
 * no navigation screen is in the task. This pins the byte the command is read from.
 */
class NavigationExitRoutingTest {
    /**
     * The same handlebar button means three things, decided by which OEM screen is listening:
     * the route-preview screen acts on 1 by clicking its own GO button, and the guidance screen
     * acts on 2 and 3.
     */
    @Test
    fun `the handlebar command is byte one of a three byte event`() {
        assertEquals(1, navigationControlCommand(byteArrayOf(0x01, 0x01, 0x00)))
        assertEquals(2, navigationControlCommand(byteArrayOf(0x01, 0x02, 0x00)))
        assertEquals(3, navigationControlCommand(byteArrayOf(0x01, 0x03, 0x00)))
    }

    @Test
    fun `a short event carries no command`() {
        assertNull(navigationControlCommand(byteArrayOf(0x03)))
        assertNull(navigationControlCommand(byteArrayOf(0x01, 0x03)))
    }
}

/** Mirrors the read in AndroidBikeConnection.onNotification for NavigationControl. */
private fun navigationControlCommand(value: ByteArray): Int? =
    value.takeIf { it.size >= 3 }?.get(1)?.toInt()?.and(0xFF)
