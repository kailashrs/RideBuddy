package com.spaceboy.ridebuddy.service

import com.spaceboy.ridebuddy.appContainer

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import com.spaceboy.ridebuddy.ble.BikeConnectionTarget
import com.spaceboy.ridebuddy.ble.BluetoothAddress
import com.spaceboy.ridebuddy.core.companion.AssociatedBike
import com.spaceboy.ridebuddy.core.companion.AssociatedBikeStore
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.ConnectionAttemptTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
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
 * [connectionServiceStateAction] — rather than lingering with a notification the rider
 * cannot explain.
 */
class BikeConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container get() = appContainer
    private val notifications by lazy { BikeConnectionNotifications(this) }
    private var stateJob: Job? = null
    private var locationDemandJob: Job? = null
    private var receivedStartCommand = false
    private var locationForegroundEnabled = false
    private var locationTrackerRunning = false
    private var locationPermissionMissing = false
    private var foregroundPromotionSucceeded = false
    private var foregroundPromotionFailed = false
    private var shuttingDown = false

    /**
     * Promotes to the foreground immediately.
     *
     * This has to happen in `onCreate`, before any command is processed: a started service
     * that has not promoted within the platform's window is killed. A refused promotion is
     * terminal, so the failure is reported and the service stops rather than running as a
     * background service the platform will kill anyway.
     */
    override fun onCreate() {
        super.onCreate()
        notifications.createChannel()
        val foregroundFailure = runCatching {
            ServiceCompat.startForeground(
                this,
                NotificationId,
                notifications.build("Preparing bike connection"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }.exceptionOrNull()
        if (foregroundFailure != null) {
            foregroundPromotionFailed = true
            Log.e("BikeConnectionService", "Unable to promote bike connection service", foregroundFailure)
            container.bikeConnection.notifyStartFailed("Unable to keep the bike connection active")
            stopSelf()
            return
        }
        foregroundPromotionSucceeded = true
        stateJob = scope.launch {
            container.bikeConnection.connectionState.collect { state ->
                handleConnectionState(state)
            }
        }
        locationDemandJob = scope.launch {
            combine(
                container.rideRecorder.activeRide,
                container.navigationFeed.guidance,
            ) { activeRide, guidance -> activeRide != null || guidance.active }
                .distinctUntilChanged()
                .collect {
                    synchronizeRideLocationTracking()
                }
        }
    }

    /**
     * Routes a start command.
     *
     * `START_NOT_STICKY` throughout, and a null intent stops the service. A sticky restart
     * arrives with no intent and no way to tell whether the rider still wants a connection;
     * treating that as "reconnect" would restart an out-of-range retry loop after every
     * process death. A genuine reconnect comes from presence observation instead, which
     * only fires when the motorcycle is actually there.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundPromotionSucceeded) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        receivedStartCommand = true
        when (intent?.action) {
            ActionEnableLocation -> enableLocationTrackingIfAllowed(launchedFromVisibleActivity = true)
            ActionRestartConnect -> {
                val trigger = intent.connectionTriggerExtra()
                val automatic = trigger.isAutomatic()
                if (automatic && !container.bikeConnectionDemand.canStartAutomaticConnection()) {
                    container.connectionEventJournal.record(
                        "Automatic connection request ignored after manual disconnect",
                    )
                    stopForegroundAndSelf()
                    return START_NOT_STICKY
                }
                val address = intent.bluetoothAddressExtra()
                val name = intent.getStringExtra(ExtraName)
                if (address != null && !name.isNullOrBlank()) {
                    if (!automatic) container.bikeConnectionDemand.allowExplicitConnection()
                    enableLocationTrackingIfAllowed(
                        launchedFromVisibleActivity = intent.getBooleanExtra(ExtraVisibleActivityLaunch, false),
                    )
                    rememberBike(address, name)
                    container.bikeConnection.connect(
                        BikeConnectionTarget(
                            address = address,
                            deviceName = name,
                            trigger = trigger,
                        ),
                    )
                } else {
                    container.bikeConnection.notifyStartFailed("The saved motorcycle address is invalid")
                    stopForegroundAndSelf()
                    return START_NOT_STICKY
                }
            }
            else -> {
                // CompanionDeviceService starts a fresh automatic connection when BLE presence returns.
                // A null intent here is an Android service recreation and must not start an
                // unbounded out-of-range reconnect loop.
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
        }
        handleConnectionState(container.bikeConnection.connectionState.value)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Tears down. The GATT link is only disconnected if it was actually up — see
     * [connectionRequiresGattShutdown] — and never after a failed foreground promotion,
     * where the service is stopping without having owned anything.
     */
    override fun onDestroy() {
        shuttingDown = true
        stateJob?.cancel()
        locationDemandJob?.cancel()
        if (locationTrackerRunning) container.rideLocationTracker.stop()
        locationTrackerRunning = false
        scope.cancel()
        removeForegroundNotification()
        if (!foregroundPromotionFailed && connectionRequiresGattShutdown(
                container.bikeConnection.connectionState.value,
            )
        ) {
            container.bikeConnection.disconnect()
        }
        super.onDestroy()
    }

    /**
     * Adds the location foreground-service type, if permitted.
     *
     * Background location is required *unless* the request came from a visible Activity,
     * which mirrors the platform's own rule for starting location work: while the rider is
     * looking at the app, foreground location is enough.
     *
     * A refusal is not an error. Location is optional — a ride records perfectly well
     * without it — so the shortfall is surfaced on the notification and everything else
     * carries on.
     */
    private fun enableLocationTrackingIfAllowed(launchedFromVisibleActivity: Boolean) {
        if (locationForegroundEnabled) {
            synchronizeRideLocationTracking()
            return
        }
        val hasFineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val hasBackgroundLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasFineLocation || (!launchedFromVisibleActivity && !hasBackgroundLocation)) {
            locationPermissionMissing = true
            updateNotificationSilently()
            return
        }
        runCatching {
            ServiceCompat.startForeground(
                this,
                NotificationId,
                notifications.build("Preparing bike connection"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        }.onFailure {
            locationPermissionMissing = true
            updateNotificationSilently()
            return
        }
        locationForegroundEnabled = true
        locationPermissionMissing = false
        synchronizeRideLocationTracking()
        updateNotificationSilently()
    }

    /**
     * Starts or stops GPS to match demand. Tracking only runs while a ride or navigation is
     * actually in progress; leaving it on between rides would drain the battery for
     * nothing.
     */
    private fun synchronizeRideLocationTracking() {
        val shouldTrack = shouldTrackRideLocation(
            locationForegroundEnabled = locationForegroundEnabled,
            hasActiveRide = container.rideRecorder.activeRide.value != null,
            hasActiveNavigation = container.navigationFeed.guidance.value.active,
        )
        if (shouldTrack == locationTrackerRunning) return
        if (shouldTrack) {
            locationTrackerRunning = container.rideLocationTracker.start()
        } else {
            container.rideLocationTracker.stop()
            locationTrackerRunning = false
        }
    }

    private fun updateNotificationSilently() {
        val state = container.bikeConnection.connectionState.value
        if (!shuttingDown && connectionServiceStateAction(state, receivedStartCommand) ==
            ConnectionServiceStateAction.PublishNotification
        ) {
            publishNotification(state)
        }
    }

    private fun handleConnectionState(state: BikeConnectionState) {
        if (shuttingDown) return
        when (connectionServiceStateAction(state, receivedStartCommand)) {
            ConnectionServiceStateAction.WaitForStartCommand -> Unit
            ConnectionServiceStateAction.PublishNotification -> publishNotification(state)
            ConnectionServiceStateAction.StopService -> stopForegroundAndSelf()
        }
    }

    private fun publishNotification(state: BikeConnectionState) {
        notifications.publish(connectionNotificationStatus(state, locationPermissionMissing))
    }

    /**
     * Stops the service, but not before the last ride is on disk.
     *
     * The connection state that ends a ride — a disconnect, or reconnection giving up — is the
     * same one that stops this service, and the ride is written asynchronously. Dropping the
     * foreground notification first hands the OS a killable process holding an unwritten ride,
     * which is exactly the ride a rider most expects to find afterwards.
     *
     * The wait is bounded so a stuck write cannot leave a foreground notification up for ever.
     */
    private fun stopForegroundAndSelf() {
        if (shuttingDown) return
        shuttingDown = true
        scope.launch {
            withTimeoutOrNull(PendingRideWriteTimeoutMillis) {
                container.rideRecorder.awaitPendingWrites()
            }
            removeForegroundNotification()
            stopSelf()
        }
    }

    private fun removeForegroundNotification() {
        if (foregroundPromotionSucceeded) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        notifications.cancel()
    }

    /**
     * Records the motorcycle a connection was requested for, preserving the existing
     * association id — the connect intent carries an address and a name, not the id, and
     * dropping it would break presence observation.
     */
    private fun rememberBike(address: BluetoothAddress, name: String) {
        val store = AssociatedBikeStore(this)
        val current = store.read()
        store.write(AssociatedBike(address, name, current?.associationId))
    }

    private fun Intent.bluetoothAddressExtra(): BluetoothAddress? =
        BluetoothAddress.fromBytes(getByteArrayExtra(ExtraAddressBytes))

    /** An intent from an older process may carry a name this build no longer knows. */
    private fun Intent.connectionTriggerExtra(): ConnectionAttemptTrigger =
        getStringExtra(ExtraTrigger)?.let { name ->
            ConnectionAttemptTrigger.entries.firstOrNull { trigger -> trigger.name == name }
        } ?: ConnectionAttemptTrigger.UserRequest

    companion object {
        /**
         * How long the service stays up waiting for a finished ride to be written. Generous
         * against a slow disk, short enough that a wedged write cannot strand the notification.
         */
        private const val PendingRideWriteTimeoutMillis = 5_000L

        internal const val NotificationId = 457
        internal const val ActionDisconnect = "com.spaceboy.ridebuddy.action.DISCONNECT_BIKE"
        private const val ActionEnableLocation = "enable_location"
        private const val ActionRestartConnect = "restart_connect"
        private const val ExtraAddressBytes = "address_bytes"
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
            appContext.stopService(Intent(appContext, BikeConnectionService::class.java))
            appContext.getSystemService(NotificationManager::class.java).cancel(NotificationId)
        }

        /**
         * Requests a connection, returning whether the service was started.
         *
         * Automatic requests are checked against the rider's suppression *here* as well as
         * in `onStartCommand`, so a suppressed request never starts a foreground service at
         * all — and reports success, because nothing failed: the request was correctly
         * declined.
         */
        fun reconnect(
            context: Context,
            bike: AssociatedBike,
            launchedFromVisibleActivity: Boolean = false,
            trigger: ConnectionAttemptTrigger = ConnectionAttemptTrigger.UserRequest,
        ): Boolean {
            val appContainer = context.applicationContext.appContainer
            if (trigger.isAutomatic() && !appContainer.bikeConnectionDemand.canStartAutomaticConnection()) {
                appContainer.connectionEventJournal.record(
                    "Automatic connection request ignored after manual disconnect",
                )
                return true
            }
            val intent = Intent(context, BikeConnectionService::class.java)
                .setAction(ActionRestartConnect)
                .putExtra(ExtraAddressBytes, bike.bluetoothAddress.toByteArray())
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

/**
 * Whether the stack, rather than the rider, asked for this attempt. Automatic attempts are the ones
 * a manual disconnect suppresses; an explicit request clears that suppression instead.
 */
internal fun ConnectionAttemptTrigger.isAutomatic(): Boolean = this != ConnectionAttemptTrigger.UserRequest

/**
 * GPS runs only when the foreground service is permitted to use location *and* something
 * needs it. Either condition alone is not enough.
 */
internal fun shouldTrackRideLocation(
    locationForegroundEnabled: Boolean,
    hasActiveRide: Boolean,
    hasActiveNavigation: Boolean,
): Boolean = locationForegroundEnabled && (hasActiveRide || hasActiveNavigation)

/** What a connection-state change means for the service. */
internal enum class ConnectionServiceStateAction {
    /** Created but not yet commanded; there is nothing to report. */
    WaitForStartCommand,

    PublishNotification,

    /** Nothing left to keep alive. */
    StopService,
}

/**
 * Maps connection state onto a service action.
 *
 * Note this does not stop the service between automatic retries: the connection reports
 * [BikeConnectionState.Connecting] while its backoff is pending, so the service stays up
 * and keeps the process alive across the whole retry schedule. Disconnected and Failed mean
 * the schedule is over — exhausted or abandoned — and there is genuinely nothing left to
 * hold the service open for.
 */
internal fun connectionServiceStateAction(
    state: BikeConnectionState,
    receivedStartCommand: Boolean,
): ConnectionServiceStateAction = when {
    !receivedStartCommand -> ConnectionServiceStateAction.WaitForStartCommand
    state is BikeConnectionState.Disconnected || state is BikeConnectionState.Failed ->
        ConnectionServiceStateAction.StopService

    else -> ConnectionServiceStateAction.PublishNotification
}

/**
 * Whether teardown should also disconnect GATT. Already-settled states have no link to
 * close, and calling disconnect on one would emit a spurious teardown event.
 */
internal fun connectionRequiresGattShutdown(state: BikeConnectionState): Boolean = when (state) {
    BikeConnectionState.Disconnected,
    is BikeConnectionState.Failed,
    -> false

    is BikeConnectionState.Connecting,
    is BikeConnectionState.Authenticating,
    is BikeConnectionState.Connected,
    -> true
}

/**
 * Android refuses a service start outright in several background states, and the refusal is an
 * exception rather than a return value. Callers need "did it start", not a crash.
 */
private fun startSafely(intent: Intent, start: () -> Unit): Boolean = runCatching {
    start()
    true
}.onFailure { error ->
    Log.w("BikeConnectionService", "Unable to start service for ${intent.action}", error)
}.getOrDefault(false)
