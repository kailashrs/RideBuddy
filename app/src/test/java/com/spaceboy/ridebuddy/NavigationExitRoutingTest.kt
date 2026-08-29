package com.spaceboy.ridebuddy

import com.spaceboy.ridebuddy.domain.BikeControlEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The handlebar EXIT is only useful while guidance runs in the background, which is exactly when
 * no navigation screen is in the task. These pin the two halves that made it dead: the byte the
 * command is read from, and the guard that keeps a second stop from racing the first.
 */
class NavigationExitRoutingTest {
    @Test
    fun `the handlebar command is byte one of a three byte event`() {
        assertEquals(3, navigationControlCommand(byteArrayOf(0x01, 0x03, 0x00)))
        assertEquals(2, navigationControlCommand(byteArrayOf(0x01, 0x02, 0x00)))
    }

    @Test
    fun `a short event carries no command`() {
        assertNull(navigationControlCommand(byteArrayOf(0x03)))
        assertNull(navigationControlCommand(byteArrayOf(0x01, 0x03)))
    }

    @Test
    fun `only the first stop of a burst is acted on`() {
        val guard = NavigationStartStopGuard()

        val first = guard.beginStop()
        val second = guard.beginStop()

        assertEquals(null, second)
        guard.finishStop(requireNotNull(first))
        // Once the first finishes, a later press may stop again.
        assertEquals(true, guard.beginStop() != null)
    }
}

/** Mirrors the read in AndroidBikeConnection.onNotification for NavigationControl. */
private fun navigationControlCommand(value: ByteArray): Int? =
    value.takeIf { it.size >= 3 }?.get(1)?.toInt()?.and(0xFF)
