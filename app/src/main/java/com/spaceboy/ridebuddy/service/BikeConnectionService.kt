package com.spaceboy.ridebuddy.service

import com.spaceboy.ridebuddy.appContainer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.spaceboy.ridebuddy.MainActivity
import com.spaceboy.ridebuddy.R
import com.spaceboy.ridebuddy.ble.BikeConnectionTarget
import com.spaceboy.ridebuddy.ble.BluetoothAddress
import com.spaceboy.ridebuddy.ble.DiscoveredBike
import com.spaceboy.ridebuddy.core.companion.AssociatedBike
import com.spaceboy.ridebuddy.core.companion.AssociatedBikeStore
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BikeConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container get() = appContainer
    private var stateJob: Job? = null
    private var receivedStartCommand = false
    private var locationTracking = false
    private var locationPermissionMissing = false
    private var foregroundPromotionSucceeded = false
    private var foregroundPromotionFailed = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val foregroundFailure = runCatching {
            ServiceCompat.startForeground(
                this,
                NotificationId,
                notification("Preparing bike connection"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
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
            container.bikeConnection.connectionState.collectLatest { state ->
                runCatching {
                    getSystemService(NotificationManager::class.java).notify(NotificationId, notification(state.label()))
                }
                if (receivedStartCommand && (state is BikeConnectionState.Disconnected || state is BikeConnectionState.Failed)) {
                    if (state is BikeConnectionState.Failed) container.bikeConnection.disconnect()
                    stopSelf()
                }
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
            ActionDisconnect -> {
                container.bikeConnection.disconnect()
                stopSelf()
                return START_NOT_STICKY
            }
            ActionDeviceAbsent -> {
                val address = intent.bluetoothAddressExtra()
                val remembered = AssociatedBikeStore(this).read()
                if (remembered != null && remembered.bluetoothAddress == address) {
                    container.bikeConnection.disconnect()
                    stopSelf()
                } else if (container.bikeConnection.connectionState.value is BikeConnectionState.Disconnected) {
                    // A stale presence event can start a fresh service after the association has
                    // changed. There is no active connection to own in that case.
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ActionEnableLocation -> enableLocationTrackingIfAllowed(launchedFromVisibleActivity = true)
            ActionConnect -> {
                val address = intent.bluetoothAddressExtra()
                val name = intent.getStringExtra(ExtraName)
                if (address != null && !name.isNullOrBlank()) {
                    enableLocationTrackingIfAllowed(
                        launchedFromVisibleActivity = intent.getBooleanExtra(ExtraVisibleActivityLaunch, false),
                    )
                    val currentState = container.bikeConnection.connectionState.value
                    val remembered = AssociatedBikeStore(this).read()
                    if (remembered?.bluetoothAddress == address &&
                        (currentState is BikeConnectionState.Connecting ||
                            currentState is BikeConnectionState.Authenticating ||
                            currentState is BikeConnectionState.Connected)
                    ) return START_NOT_STICKY
                    rememberBike(address, name)
                    container.bikeConnection.connect(
                        BikeConnectionTarget(
                            address = address,
                            deviceName = name,
                            device = intent.bluetoothDeviceExtra(),
                        ),
                    )
                } else {
                    container.bikeConnection.notifyStartFailed("The saved motorcycle address is invalid")
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            else -> {
                // CompanionDeviceService starts a fresh explicit connection when presence returns.
                // A null intent here is an Android service recreation and must not start an
                // unbounded out-of-range reconnect loop.
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stateJob?.cancel()
        scope.cancel()
        if (locationTracking) container.rideLocationTracker.stop()
        if (!foregroundPromotionFailed &&
            container.bikeConnection.connectionState.value !is BikeConnectionState.Disconnected
        ) {
            container.bikeConnection.disconnect()
        }
        super.onDestroy()
    }

    private fun enableLocationTrackingIfAllowed(launchedFromVisibleActivity: Boolean) {
        if (locationTracking) return
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
                notification("Preparing bike connection"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        }.onFailure {
            locationPermissionMissing = true
            updateNotificationSilently()
            return
        }
        container.rideLocationTracker.start()
        locationTracking = true
        locationPermissionMissing = false
        updateNotificationSilently()
    }

    private fun updateNotificationSilently() {
        val state = container.bikeConnection.connectionState.value
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NotificationId, notification(state.label()))
        }
    }

    private fun rememberBike(address: BluetoothAddress, name: String) {
        val current = AssociatedBikeStore(this).read()
        AssociatedBikeStore(this).write(AssociatedBike(address, name, current?.associationId))
    }

    private fun Intent.bluetoothAddressExtra(): BluetoothAddress? =
        BluetoothAddress.fromBytes(getByteArrayExtra(ExtraAddressBytes))

    @Suppress("DEPRECATION")
    private fun Intent.bluetoothDeviceExtra(): BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(ExtraDevice, BluetoothDevice::class.java)
    } else {
        getParcelableExtra(ExtraDevice)
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ChannelId, "Bike connection", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(status: String) = NotificationCompat.Builder(this, ChannelId)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("RideBuddy")
        .setContentText(status)
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .addAction(
            0,
            "Disconnect",
            PendingIntent.getService(
                this,
                1,
                Intent(this, BikeConnectionService::class.java).setAction(ActionDisconnect),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    private fun BikeConnectionState.label(): String {
        val connectionLabel = when (this) {
            is BikeConnectionState.Connected -> "Connected to $deviceName"
            is BikeConnectionState.Connecting -> "Connecting to ${deviceName ?: "bike"}"
            is BikeConnectionState.Authenticating -> "Verifying motorcycle link"
            is BikeConnectionState.Failed -> message
            BikeConnectionState.Scanning -> "Scanning"
            BikeConnectionState.Disconnected -> "Disconnected"
        }
        return if (locationPermissionMissing && this is BikeConnectionState.Connected) {
            "$connectionLabel — location needs permission"
        } else connectionLabel
    }

    companion object {
        private const val ChannelId = "bike_connection"
        private const val NotificationId = 457
        private const val ActionConnect = "connect"
        private const val ActionDisconnect = "disconnect"
        private const val ActionDeviceAbsent = "device_absent"
        private const val ActionEnableLocation = "enable_location"
        private const val ExtraAddressBytes = "address_bytes"
        private const val ExtraDevice = "device"
        private const val ExtraName = "name"
        private const val ExtraVisibleActivityLaunch = "visible_activity_launch"

        fun connect(context: Context, bike: DiscoveredBike): Boolean {
            val intent = Intent(context, BikeConnectionService::class.java)
                .setAction(ActionConnect)
                .putExtra(ExtraAddressBytes, bike.bluetoothAddress.toByteArray())
                .putExtra(ExtraName, bike.name)
                .putExtra(ExtraVisibleActivityLaunch, true)
            bike.bluetoothDevice?.let { intent.putExtra(ExtraDevice, it) }
            val started = ContextCompatBridge.startForegroundService(
                context,
                intent,
            )
            if (!started) {
                context.appContainer.bikeConnection.notifyStartFailed(
                    "Unable to start connection service",
                )
            }
            return started
        }

        fun disconnect(context: Context): Boolean {
            val started = ContextCompatBridge.startForegroundService(
                context,
                Intent(context, BikeConnectionService::class.java).setAction(ActionDisconnect),
            )
            if (!started) {
                context.appContainer.bikeConnection.disconnect()
            }
            return started
        }

        fun reconnect(
            context: Context,
            bike: AssociatedBike,
            launchedFromVisibleActivity: Boolean = false,
        ): Boolean {
            val started = ContextCompatBridge.startForegroundService(
                context,
                Intent(context, BikeConnectionService::class.java)
                    .setAction(ActionConnect)
                    .putExtra(ExtraAddressBytes, bike.bluetoothAddress.toByteArray())
                    .putExtra(ExtraName, bike.name)
                    .putExtra(ExtraVisibleActivityLaunch, launchedFromVisibleActivity),
            )
            if (!started) {
                context.appContainer.bikeConnection.notifyStartFailed(
                    "Unable to start connection service",
                )
            }
            return started
        }

        fun deviceAbsent(context: Context, address: BluetoothAddress): Boolean {
            val started = ContextCompatBridge.startForegroundService(
                context,
                Intent(context, BikeConnectionService::class.java)
                    .setAction(ActionDeviceAbsent)
                    .putExtra(ExtraAddressBytes, address.toByteArray()),
            )
            if (!started && AssociatedBikeStore(context).read()?.bluetoothAddress == address) {
                context.appContainer.bikeConnection.disconnect()
            }
            return started
        }

        fun deviceAbsent(context: Context, address: String): Boolean =
            BluetoothAddress.parse(address)?.let { deviceAbsent(context, it) } ?: false

        fun enableLocation(context: Context): Boolean {
            return ContextCompatBridge.startService(
                context,
                Intent(context, BikeConnectionService::class.java).setAction(ActionEnableLocation),
            )
        }
    }
}

private object ContextCompatBridge {
    fun startForegroundService(context: Context, intent: Intent): Boolean = start("foreground", intent) {
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }

    fun startService(context: Context, intent: Intent): Boolean = start("service", intent) {
        context.startService(intent)
    }

    private inline fun start(kind: String, intent: Intent, block: () -> Unit): Boolean = runCatching {
        block()
        true
    }.onFailure { error ->
        Log.w("BikeConnectionService", "Unable to start $kind for ${intent.action}", error)
    }.getOrDefault(false)
}
