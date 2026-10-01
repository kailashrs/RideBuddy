package com.spaceboy.ridebuddy.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.MacAddress
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.spaceboy.ridebuddy.appContainer
import com.spaceboy.ridebuddy.core.companion.AssociatedBike
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeConnectionTarget
import com.spaceboy.ridebuddy.domain.ConnectionAttemptTrigger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the motorcycle link alive while the app is not in front.
 *
 * It does not own the connection — [com.spaceboy.ridebuddy.ble.AndroidBikeConnection] does.
 * What it provides is the foreground lifetime that keeps the process alive and the platform
 * from killing a background Bluetooth session, plus the notification the rider can
 * disconnect from.
 *
 * The service type is escalated rather than declared once. It starts as connected-device
 * only, and adds the location type when a ride or navigation actually needs GPS and the
 * permissions are in place. Declaring location up front would demand the permission from
 * every rider, including those who never record a route.
 *
 * The service stops itself as soon as there is nothing to keep alive — see
 * [stop] — rather than lingering with a notification the rider
 * cannot explain.
 */
class BikeConnectionService : LifecycleService() {
    private val container get() = appContainer
    private val notifications by lazy { BikeConnectionNotifications(this) }
    private var stateJob: Job? = null
    private var shutdownJob: Job? = null
    private var latestStartId = 0
    private var locationForeground = false
    private var locationTracking = false
    private var locationPermissionMissing = false

    /**
     * Promotes to the foreground before any command is processed: a started service that has not
     * promoted within the platform's window is killed.
     */
    override fun onCreate() {
        super.onCreate()
        notifications.createChannel()
        ServiceCompat.startForeground(
            this,
            NotificationId,
            notifications.build("Preparing bike connection"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        // GPS runs only while a ride or navigation needs it.
        lifecycleScope.launch {
            combine(container.rideRecorder.activeRide, container.navigationController.guidance) { ride, guidance ->
                ride != null || guidance.active
            }.distinctUntilChanged().collect { syncLocationTracking() }
        }
    }

    /**
     * `START_NOT_STICKY` throughout: a sticky restart cannot tell whether the rider still wants a
     * connection, and presence observation is what reconnects when the motorcycle is actually there.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        latestStartId = startId
        // A new command supersedes a shutdown still waiting on a ride save.
        shutdownJob?.cancel()
        shutdownJob = null
        when (intent?.action) {
            ActionEnableLocation -> enableLocationIfAllowed(launchedFromVisibleActivity = true)
            ActionRestartConnect -> startConnection(intent)
        }
        if (stateJob == null) {
            stateJob = lifecycleScope.launch {
                container.bikeConnection.connectionState.collect { state ->
                    if (state is BikeConnectionState.Disconnected || state is BikeConnectionState.Failed) {
                        stop()
                    } else if (shutdownJob == null) {
                        publishNotification(state)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Automatic requests were already checked against the rider's suppression in [reconnect]. */
    private fun startConnection(intent: Intent) {
        val trigger = ConnectionAttemptTrigger.valueOf(intent.getStringExtra(ExtraTrigger)!!)
        val address = intent.getParcelableExtra(ExtraAddress, MacAddress::class.java)!!
        val name = intent.getStringExtra(ExtraName)!!
        if (trigger == ConnectionAttemptTrigger.UserRequest) container.bikeConnectionDemand.allowExplicitConnection()
        enableLocationIfAllowed(launchedFromVisibleActivity = intent.getBooleanExtra(ExtraVisibleActivityLaunch, false))
        container.bikeConnection.connect(BikeConnectionTarget(address, name, trigger))
    }

    override fun onDestroy() {
        if (locationTracking) container.rideLocationTracker.stop()
        removeForegroundNotification()
        super.onDestroy()
    }

    /**
     * Adds the location service type when permitted. Background location is required unless the
     * request came from a visible Activity, mirroring the platform's own rule. A refusal is not an
     * error: rides record without GPS, and the notification says what is missing.
     */
    private fun enableLocationIfAllowed(launchedFromVisibleActivity: Boolean) {
        if (!locationForeground) {
            val granted = { permission: String -> ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED }
            locationForeground = granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
                (launchedFromVisibleActivity || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) &&
                runCatching {
                    ServiceCompat.startForeground(
                        this,
                        NotificationId,
                        notifications.build("Preparing bike connection"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                    )
                }.isSuccess
            locationPermissionMissing = !locationForeground
        }
        syncLocationTracking()
        if (shutdownJob == null) publishNotification(container.bikeConnection.connectionState.value)
    }

    private fun syncLocationTracking() {
        val wanted = locationForeground &&
            (container.rideRecorder.activeRide.value != null || container.navigationController.guidance.value.active)
        if (wanted == locationTracking) return
        locationTracking = if (wanted) container.rideLocationTracker.start() else false.also { container.rideLocationTracker.stop() }
    }

    private fun publishNotification(state: BikeConnectionState) {
        if (state is BikeConnectionState.Disconnected || state is BikeConnectionState.Failed) return
        notifications.publish(connectionNotificationStatus(state, locationPermissionMissing))
    }

    /**
     * Stops, but only once the last ride is on disk: the state that ends a ride is the same one
     * that stops this service, and dropping the foreground first would leave the process
     * killable holding an unwritten ride. Foreground stays unless Android accepts this stop,
     * since a newer start may already be on its way.
     */
    private fun stop() {
        if (shutdownJob != null) return
        val startId = latestStartId
        notifications.publish("Saving ride")
        shutdownJob = lifecycleScope.launch {
            container.rideRecorder.finalizeAndAwaitSaves()
            if (stopSelfResult(startId)) removeForegroundNotification()
        }
    }

    private fun removeForegroundNotification() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        notifications.cancel()
    }

    companion object {
        internal const val NotificationId = 457
        internal const val ActionDisconnect = "com.spaceboy.ridebuddy.action.DISCONNECT_BIKE"
        private const val ActionEnableLocation = "enable_location"
        private const val ActionRestartConnect = "restart_connect"
        private const val ExtraAddress = "address"
        private const val ExtraName = "name"
        private const val ExtraVisibleActivityLaunch = "visible_activity_launch"
        private const val ExtraTrigger = "attempt_trigger"

        /**
         * Disconnects at the rider's request and stops the service.
         *
         * Suppresses automatic connection first: without that, presence observation would
         * see the motorcycle still advertising and reconnect within seconds, which reads as
         * the Disconnect button not working.
         */
        fun disconnect(context: Context) {
            val appContext = context.applicationContext
            val appContainer = appContext.appContainer
            appContainer.bikeConnectionDemand.suppressAutomaticConnections()
            appContainer.connectionEventJournal.record("Manual disconnect requested")
            appContainer.bikeConnection.disconnect()
            // Deliberately no stopService() and no notification cancel here. The Disconnected
            // state reaches the service's own collector, which stops through the path that first
            // waits for a ride finished by that same disconnect to reach the disk. Tearing the
            // service down from outside would skip exactly that wait — which is how a rider ends
            // a ride, so it is the last path that should be missing it.
        }

        /**
         * Ends the ride in progress and saves it, leaving the link up.
         *
         * Deliberately not a disconnect. Guidance and cluster notifications are separate
         * features, and suppressing automatic connection here would strand a rider who ended a
         * ride at a stop with no reconnect until the motorcycle left BLE range and came back.
         */
        fun endRide(context: Context) {
            context.applicationContext.appContainer.rideRecorder.endRideNow()
        }

        /**
         * Requests a connection, returning whether the service was started.
         *
         * Automatic requests are checked against the rider's suppression here, so a suppressed
         * request never starts a foreground service at all — and reports success, because
         * nothing failed: the request was correctly declined.
         */
        fun reconnect(
            context: Context,
            bike: AssociatedBike,
            launchedFromVisibleActivity: Boolean = false,
            trigger: ConnectionAttemptTrigger = ConnectionAttemptTrigger.UserRequest,
        ): Boolean {
            val appContainer = context.applicationContext.appContainer
            if (trigger != ConnectionAttemptTrigger.UserRequest && !appContainer.bikeConnectionDemand.canStartAutomaticConnection()) {
                appContainer.connectionEventJournal.record(
                    "Automatic connection request ignored while paused",
                )
                return true
            }
            val intent = Intent(context, BikeConnectionService::class.java)
                .setAction(ActionRestartConnect)
                .putExtra(ExtraAddress, bike.address)
                .putExtra(ExtraName, bike.name)
                .putExtra(ExtraVisibleActivityLaunch, launchedFromVisibleActivity)
                .putExtra(ExtraTrigger, trigger.name)
            val started = startSafely(intent) { ContextCompat.startForegroundService(context, intent) }
            if (!started) {
                context.appContainer.bikeConnection.notifyStartFailed(
                    "Unable to start connection service",
                )
            }
            return started
        }

        /** Asks a running service to add location tracking, once permission has been granted. */
        fun enableLocation(context: Context) {
            val intent = Intent(context, BikeConnectionService::class.java).setAction(ActionEnableLocation)
            startSafely(intent) { context.startService(intent) }
        }
    }

}

private fun startSafely(intent: Intent, start: () -> Unit): Boolean = runCatching {
    start()
    true
}.onFailure { error ->
    Log.w("BikeConnectionService", "Unable to start service for ${intent.action}", error)
}.getOrDefault(false)
