package com.spaceboy.ridebuddy.core.navigation

import android.os.Looper
import androidx.activity.ComponentActivity
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.google.android.libraries.navigation.ListenableResultFuture
import com.google.android.libraries.navigation.Navigator
import com.spaceboy.ridebuddy.FakeBikeConnection
import com.spaceboy.ridebuddy.data.AppSettings
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeControlEvent
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The handlebar has to work with the phone stowed: GO starts a staged route and EXIT ends it,
 * whether or not the map screen is showing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NavigationControllerTest {
    private val destination = NavigationDestination(12.97, 77.59, "Marina Beach")
    private val connection = FakeBikeConnection()
    private val navigatorCalls = mutableListOf<String>()
    private val outputCalls = mutableListOf<String>()
    private val controller = NavigationController(
        application = RuntimeEnvironment.getApplication(),
        connection = connection,
        settings = MutableStateFlow(AppSettings()),
        output = object : GuidanceOutput {
            override fun preview(destination: NavigationDestination, distanceMetres: Int?, durationSeconds: Int?) {
                outputCalls += "preview"
            }
            override fun started(destination: NavigationDestination) { outputCalls += "started" }
            override fun update(info: NavInfo) { outputCalls += "update" }
            override fun rerouting() { outputCalls += "rerouting" }
            override fun arrived() { outputCalls += "arrived" }
            override fun stopped() { outputCalls += "stopped" }
        },
        log = {},
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
        requestNavigator = { fakeNavigator() },
    )

    private fun stage() {
        idle()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        runBlocking { controller.prepare(activity, destination) }
        assertEquals(NavigationSession.Ready(destination, null, null), controller.session.value)
    }

    @Test
    fun `handlebar GO starts a staged route`() {
        stage()

        connection.controls.tryEmit(BikeControlEvent.StartNavigation)
        idle()

        assertEquals(NavigationSession.Guiding(destination), controller.session.value)
        assertTrue("startGuidance" in navigatorCalls)
        assertEquals(listOf("preview", "started"), outputCalls)
    }

    @Test
    fun `handlebar EXIT abandons a staged route and clears the cluster`() {
        stage()

        connection.controls.tryEmit(BikeControlEvent.ExitNavigation)
        idle()

        assertEquals(NavigationSession.Idle, controller.session.value)
        assertTrue("cleanup" in navigatorCalls)
        assertEquals(listOf("preview", "stopped"), outputCalls)
    }

    @Test
    fun `handlebar EXIT ends running guidance`() {
        stage()
        connection.controls.tryEmit(BikeControlEvent.StartNavigation)
        idle()

        connection.controls.tryEmit(BikeControlEvent.ExitNavigation)
        idle()

        assertEquals(NavigationSession.Idle, controller.session.value)
        assertTrue("stopGuidance" in navigatorCalls)
    }

    @Test
    fun `GO is refused without the bike, leaving the route staged`() {
        connection.connectionState.value = BikeConnectionState.Disconnected
        stage()

        connection.controls.tryEmit(BikeControlEvent.StartNavigation)
        idle()

        assertEquals(NavigationSession.Ready(destination, null, null), controller.session.value)
    }

    @Test
    fun `losing the bike ends the route`() {
        stage()

        connection.connectionState.value = BikeConnectionState.Failed("Link lost", retriesExhausted = true)
        idle()

        assertEquals(NavigationSession.Idle, controller.session.value)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Records calls and answers a route request with OK. */
    private fun fakeNavigator(): Navigator = Proxy.newProxyInstance(
        Navigator::class.java.classLoader,
        arrayOf(Navigator::class.java),
    ) { _, method, args ->
        navigatorCalls += method.name
        when {
            method.returnType == ListenableResultFuture::class.java -> routeResult()
            method.returnType == Boolean::class.javaPrimitiveType -> method.name == "registerServiceForNavUpdates"
            else -> null
        }
    } as Navigator

    private fun routeResult(): ListenableResultFuture<*> = Proxy.newProxyInstance(
        ListenableResultFuture::class.java.classLoader,
        arrayOf(ListenableResultFuture::class.java),
    ) { _, method, args ->
        if (method.name == "setOnResultListener") {
            val listener = args[0]
            listener.javaClass.methods.first { it.name == "onResult" }.invoke(listener, Navigator.RouteStatus.OK)
        }
        null
    } as ListenableResultFuture<*>
}
