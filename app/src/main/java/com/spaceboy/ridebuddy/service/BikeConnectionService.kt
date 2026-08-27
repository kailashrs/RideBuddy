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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundPromotionSucceeded) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        receivedStartCommand = true
        when (intent?.action) {
            ActionEnableLocation -> enableLocationTrackingIfAllowed(launchedFromVisibleActivity = true)
            ActionRestartConnect -> {
                val automatic = intent.getBooleanExtra(ExtraAutomaticRequest, false)
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
                            trigger = if (automatic) {
                                ConnectionAttemptTrigger.PresenceAppearance
                            } else {
                                ConnectionAttemptTrigger.UserRequest
                            },
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

    private fun stopForegroundAndSelf() {
        if (shuttingDown) return
        shuttingDown = true
        removeForegroundNotification()
        stopSelf()
    }

    private fun removeForegroundNotification() {
        if (foregroundPromotionSucceeded) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        notifications.cancel()
    }

    private fun rememberBike(address: BluetoothAddress, name: String) {
        val store = AssociatedBikeStore(this)
        val current = store.read()
        store.write(AssociatedBike(address, name, current?.associationId))
    }

    private fun Intent.bluetoothAddressExtra(): BluetoothAddress? =
        BluetoothAddress.fromBytes(getByteArrayExtra(ExtraAddressBytes))

    companion object {
        internal const val NotificationId = 457
        internal const val ActionDisconnect = "com.spaceboy.ridebuddy.action.DISCONNECT_BIKE"
        private const val ActionEnableLocation = "enable_location"
        private const val ActionRestartConnect = "restart_connect"
        private const val ExtraAddressBytes = "address_bytes"
        private const val ExtraName = "name"
        private const val ExtraVisibleActivityLaunch = "visible_activity_launch"
        private const val ExtraAutomaticRequest = "automatic_request"

        fun disconnect(context: Context) {
            val appContext = context.applicationContext
            val appContainer = appContext.appContainer
            appContainer.bikeConnectionDemand.suppressAutomaticConnections()
            appContainer.connectionEventJournal.record("Manual disconnect requested")
            appContainer.bikeConnection.disconnect()
            appContext.stopService(Intent(appContext, BikeConnectionService::class.java))
            appContext.getSystemService(NotificationManager::class.java).cancel(NotificationId)
        }

        fun reconnect(
            context: Context,
            bike: AssociatedBike,
            launchedFromVisibleActivity: Boolean = false,
            automatic: Boolean = false,
        ): Boolean {
            val appContainer = context.applicationContext.appContainer
            if (automatic && !appContainer.bikeConnectionDemand.canStartAutomaticConnection()) {
                appContainer.connectionEventJournal.record(
                    "Automatic connection request ignored after manual disconnect",
                )
                return true
            }
            val started = ContextCompatBridge.startForegroundService(
                context,
                Intent(context, BikeConnectionService::class.java)
                    .setAction(ActionRestartConnect)
                    .putExtra(ExtraAddressBytes, bike.bluetoothAddress.toByteArray())
                    .putExtra(ExtraName, bike.name)
                    .putExtra(ExtraVisibleActivityLaunch, launchedFromVisibleActivity)
                    .putExtra(ExtraAutomaticRequest, automatic),
            )
            if (!started) {
                context.appContainer.bikeConnection.notifyStartFailed(
                    "Unable to start connection service",
                )
            }
            return started
        }

        fun enableLocation(context: Context) {
            ContextCompatBridge.startService(
                context,
                Intent(context, BikeConnectionService::class.java).setAction(ActionEnableLocation),
            )
        }
    }
}

internal fun shouldTrackRideLocation(
    locationForegroundEnabled: Boolean,
    hasActiveRide: Boolean,
    hasActiveNavigation: Boolean,
): Boolean = locationForegroundEnabled && (hasActiveRide || hasActiveNavigation)

internal enum class ConnectionServiceStateAction {
    WaitForStartCommand,
    PublishNotification,
    StopService,
}

internal fun connectionServiceStateAction(
    state: BikeConnectionState,
    receivedStartCommand: Boolean,
): ConnectionServiceStateAction = when {
    !receivedStartCommand -> ConnectionServiceStateAction.WaitForStartCommand
    state is BikeConnectionState.Disconnected || state is BikeConnectionState.Failed ->
        ConnectionServiceStateAction.StopService

    else -> ConnectionServiceStateAction.PublishNotification
}

internal fun connectionRequiresGattShutdown(state: BikeConnectionState): Boolean = when (state) {
    BikeConnectionState.Disconnected,
    is BikeConnectionState.Failed,
    -> false

    BikeConnectionState.Scanning,
    is BikeConnectionState.Connecting,
    is BikeConnectionState.Authenticating,
    is BikeConnectionState.Connected,
    -> true
}

private object ContextCompatBridge {
    fun startForegroundService(context: Context, intent: Intent): Boolean = start("foreground", intent) {
        ContextCompat.startForegroundService(context, intent)
    }

    fun startService(context: Context, intent: Intent): Boolean = start("service", intent) {
        context.startService(intent)
    }

    private fun start(kind: String, intent: Intent, block: () -> Unit): Boolean = runCatching {
        block()
        true
    }.onFailure { error ->
        Log.w("BikeConnectionService", "Unable to start $kind for ${intent.action}", error)
    }.getOrDefault(false)
}
