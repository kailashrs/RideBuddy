package com.spaceboy.ridebuddy

import android.content.Context
import com.spaceboy.ridebuddy.ble.AndroidBikeConnection
import com.spaceboy.ridebuddy.ble.BleCaptureRecorder
import com.spaceboy.ridebuddy.ble.BikeIdentityRepository
import com.spaceboy.ridebuddy.ble.ConnectionEventJournal
import com.spaceboy.ridebuddy.ble.SharedPreferencesConnectionEventStore
import com.spaceboy.ridebuddy.ble.SharedPreferencesBikeIdentityStore
import com.spaceboy.ridebuddy.ble.SharedPreferencesProtectionAcceptanceStore
import com.spaceboy.ridebuddy.core.navigation.DestinationParser
import com.spaceboy.ridebuddy.core.navigation.GoogleNavigationSdkGateway
import com.spaceboy.ridebuddy.core.navigation.NavigationFeedRepository
import com.spaceboy.ridebuddy.core.navigation.NavigationFeedOutputAction
import com.spaceboy.ridebuddy.core.navigation.navigationFeedOutputAction
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
import com.spaceboy.ridebuddy.domain.BikeConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    /** Process-scoped: survives Activity destruction because RideBuddy relies on
     *  foreground services that keep the application process alive. Coroutines
     *  launched here are bound to the process, not to any individual Activity. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val bleCaptureRecorder = BleCaptureRecorder()
    private val protectionAcceptanceStore = SharedPreferencesProtectionAcceptanceStore(context)
    private val bikeIdentityRepository = BikeIdentityRepository(
        store = SharedPreferencesBikeIdentityStore(context),
        scope = applicationScope,
    )
    val appSettings = AppSettingsRepository(context)
    internal val connectionEventJournal = ConnectionEventJournal(
        store = SharedPreferencesConnectionEventStore(context),
        scope = applicationScope,
        initialPersistenceEnabled = appSettings.settings.value.persistConnectionDiagnostics,
    )
    val bikeConnection: BikeConnection = AndroidBikeConnection(
        context,
        bleCaptureRecorder,
        protectionAcceptanceStore,
        connectionEventJournal,
        bikeIdentityRepository,
    )
    val rideLocationTracker = RideLocationTracker(context)
    val rideRepository = RideRepository(context)
    val bikeCompanionManager = BikeCompanionManager(
        context,
        protectionAcceptanceStore,
        bikeIdentityRepository,
        applicationScope,
    )
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
    internal val navigationStartStopGuard = NavigationStartStopGuard()
    val tftNavigationBridge = TftNavigationBridge(bikeConnection, appSettings, applicationScope)
    internal val navigationGuidanceLifecycle = NavigationGuidanceLifecycle(
        clearNavigationFeed = navigationFeed::clear,
        finishTftArrival = tftNavigationBridge::arrivedAndStop,
    )
    val stationaryTftValidator = StationaryTftValidator(bikeConnection)
    val callNotificationBridge = CallNotificationBridge(context, bikeConnection, appSettings, applicationScope)
    val tftPriorityCoordinator =
        TftPriorityCoordinator(navigationFeed, callNotificationBridge, tftNavigationBridge, applicationScope)
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
        applicationScope.launch {
            appSettings.settings
                .map { it.persistConnectionDiagnostics }
                .distinctUntilChanged()
                .collect(connectionEventJournal::setPersistenceEnabled)
        }
        // Defer Keystore decrypt off Application.onCreate — the slow path is the
        // first KeyStore.getInstance() call (cold-cache Binder IPC, ~20-50ms on
        // older chipsets). The gateway only needs the key when navigation is used,
        // which won't happen until MainViewModel is created and the user navigates.
        applicationScope.launch(Dispatchers.IO) {
            navigationApiKeyStore.load()?.let(navigationSdkGateway::configureIfNeeded)
        }
        navigationFeed.acceptTerminalNavInfo = navigationGuidanceLifecycle::acceptAndMarkTerminalFeed
        navigationFeed.onNavInfo = { info ->
            when (navigationFeedOutputAction(info.navState)) {
                NavigationFeedOutputAction.Guidance -> tftNavigationBridge.accept(info)
                NavigationFeedOutputAction.Rerouting -> tftNavigationBridge.rerouting()
                NavigationFeedOutputAction.Stop -> tftNavigationBridge.stop()
            }
        }
        rideRecorder.start()
        ridingAlertMonitor.start()
        weatherAlertProvider.start()
    }
}

/** Convenience for reaching the [AppContainer] from any [Context] without
 *  the repetitive `(application as RideBuddyApplication).container` cast. */
val Context.appContainer: AppContainer
    get() = (applicationContext as RideBuddyApplication).container
