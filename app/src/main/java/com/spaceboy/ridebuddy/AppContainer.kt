package com.spaceboy.ridebuddy

import android.content.Context
import com.spaceboy.ridebuddy.ble.AndroidBikeScanner
import com.spaceboy.ridebuddy.ble.AndroidBikeConnection
import com.spaceboy.ridebuddy.ble.BleCaptureRecorder
import com.spaceboy.ridebuddy.core.navigation.DestinationParser
import com.spaceboy.ridebuddy.core.navigation.GoogleNavigationSdkGateway
import com.spaceboy.ridebuddy.core.navigation.NavigationFeedRepository
import com.spaceboy.ridebuddy.core.security.SecureNavigationApiKeyStore
import com.spaceboy.ridebuddy.core.tft.TftNavigationBridge
import com.spaceboy.ridebuddy.core.tft.TftPriorityCoordinator
import com.spaceboy.ridebuddy.core.tft.StationaryTftValidator
import com.spaceboy.ridebuddy.core.calls.CallNotificationBridge
import com.spaceboy.ridebuddy.core.location.RideLocationTracker
import com.spaceboy.ridebuddy.core.location.RideLocationLabeler
import com.spaceboy.ridebuddy.core.companion.BikeCompanionManager
import com.spaceboy.ridebuddy.data.RideRecorder
import com.spaceboy.ridebuddy.data.RideRepository
import com.spaceboy.ridebuddy.data.AppSettingsRepository
import com.spaceboy.ridebuddy.core.alerts.RidingAlertMonitor
import com.spaceboy.ridebuddy.core.alerts.WeatherAlertProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val bikeScanner = AndroidBikeScanner(context)
    val bleCaptureRecorder = BleCaptureRecorder()
    val bikeConnection = AndroidBikeConnection(context, bleCaptureRecorder)
    val rideLocationTracker = RideLocationTracker(context)
    val rideRepository = RideRepository(context)
    val appSettings = AppSettingsRepository(context)
    val bikeCompanionManager = BikeCompanionManager(context)
    private val rideLocationLabeler = RideLocationLabeler(context)
    val rideRecorder = RideRecorder(
        bikeConnection,
        rideRepository,
        applicationScope,
        rideLocationTracker,
        appSettings,
        rideLocationLabeler,
    )
    val navigationApiKeyStore = SecureNavigationApiKeyStore(context)
    val navigationSdkGateway = GoogleNavigationSdkGateway()
    val destinationParser = DestinationParser(context)
    val navigationFeed = NavigationFeedRepository()
    val tftNavigationBridge = TftNavigationBridge(bikeConnection, appSettings, applicationScope)
    val stationaryTftValidator = StationaryTftValidator(bikeConnection)
    val callNotificationBridge = CallNotificationBridge(context, bikeConnection, appSettings, applicationScope)
    val tftPriorityCoordinator = TftPriorityCoordinator(navigationFeed, callNotificationBridge, tftNavigationBridge, applicationScope)
    val ridingAlertMonitor = RidingAlertMonitor(context, bikeConnection, rideRecorder, appSettings, applicationScope)
    val weatherAlertProvider = WeatherAlertProvider(
        rideLocationTracker,
        appSettings,
        applicationScope,
    ) { message ->
        if (ridingAlertMonitor.weatherAlert(message)) {
            tftPriorityCoordinator.presentTextAlert("WEATHER ALERT. $message")
        }
    }

    init {
        applicationScope.launch {
            appSettings.settings
                .map { it.bleCaptureEnabled }
                .distinctUntilChanged()
                .collect(bleCaptureRecorder::setEnabled)
        }
        navigationApiKeyStore.load()?.let(navigationSdkGateway::configureIfNeeded)
        navigationFeed.onNavInfo = tftNavigationBridge::accept
        rideRecorder.start()
        ridingAlertMonitor.start()
        weatherAlertProvider.start()
    }
}
