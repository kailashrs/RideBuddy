package com.spaceboy.ridebuddy.service

import com.spaceboy.ridebuddy.FakeBikeConnection
import com.spaceboy.ridebuddy.data.AppSettings
import com.spaceboy.ridebuddy.data.SupportedNotificationAppsByPackage
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationIconsTest {
    private val whatsapp = SupportedNotificationAppsByPackage.getValue("com.whatsapp")
    private val gmail = SupportedNotificationAppsByPackage.getValue("com.google.android.gm")
    private val connection = FakeBikeConnection()
    private val settings = MutableStateFlow(AppSettings())
    private val icons = NotificationIcons(connection, settings, batteryPercent = { 55 })

    private fun events() = connection.writes.map { it.payload[1].toInt() and 0xFF }

    @Test
    fun `every post shows the icon, as the OEM app does`() {
        icons.posted("a", whatsapp)
        icons.posted("b", whatsapp)

        assertEquals(listOf(7, 7), events())
        assertEquals(listOf(11, 7, 55, 0), connection.writes.first().payload.map(Byte::toInt))
    }

    @Test
    fun `the icon is hidden only when the last notification behind it goes`() {
        icons.posted("a", whatsapp)
        icons.posted("b", whatsapp)
        connection.writes.clear()

        icons.removed("a")
        assertEquals(emptyList<Int>(), events())

        icons.removed("b")
        assertEquals(listOf(whatsapp.hiddenEvent), events())
    }

    @Test
    fun `notifications are tracked while disconnected and relit on replay`() {
        connection.connectionState.value = BikeConnectionState.Disconnected
        icons.posted("a", whatsapp)
        icons.posted("b", gmail)
        assertEquals(emptyList<Int>(), events())

        connection.connectionState.value = BikeConnectionState.Connected("RS 457", null)
        icons.replay()

        assertEquals(listOf(ClearAppEventsEvent, whatsapp.shownEvent, gmail.shownEvent), events())
    }

    @Test
    fun `an app the rider switched off is neither shown nor replayed`() {
        settings.value = AppSettings(disabledNotificationPackages = setOf(gmail.packageName))

        icons.posted("a", gmail)
        icons.replay()

        assertEquals(listOf(ClearAppEventsEvent), events())
    }

    @Test
    fun `reconcile drops notifications that vanished while the listener was away`() {
        icons.posted("a", whatsapp)
        connection.writes.clear()

        icons.reconcile(mapOf("b" to gmail))

        assertEquals(listOf(ClearAppEventsEvent, gmail.shownEvent), events())
    }
}
