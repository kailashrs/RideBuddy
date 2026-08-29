package com.spaceboy.ridebuddy

import android.app.Application
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
import com.spaceboy.ridebuddy.core.navigation.NavigationKeyBootstrap
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
import com.spaceboy.ridebuddy.core.companion.BikeConnectionDemandController
import android.os.BatteryManager
import com.spaceboy.ridebuddy.ble.BleCharacteristics
import com.spaceboy.ridebuddy.service.ClearAppEventsEvent
import com.spaceboy.ridebuddy.service.appEventPacket
import com.spaceboy.ridebuddy.data.RideRecorder
import com.spaceboy.ridebuddy.data.RideRepository
import com.spaceboy.ridebuddy.data.AppSettingsRepository
import com.spaceboy.ridebuddy.core.alerts.RidingAlertMonitor
import com.spaceboy.ridebuddy.core.alerts.WeatherAlertProvider
import com.spaceboy.ridebuddy.domain.BikeConnection
import com.spaceboy.ridebuddy.domain.BikeControlEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    /** Process-scoped: survives Activity destruction because RideBuddy relies on
     *  foreground services that keep the application process alive. Coroutines
     *  launched here are bound to the process, not to any individual Activity. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val bleCaptureRecorder = BleCaptureRecorder(applicationScope)
    private val protectionAcceptanceStore = SharedPreferencesProtectionAcceptanceStore(context)
    private val bikeIdentityRepository = BikeIdentityRepository(
        store = SharedPreferencesBikeIdentityStore(context),
        scope = applicationScope,
    )
    val appSettings = AppSettingsRepository(context)
    internal val bikeConnectionDemand = BikeConnectionDemandController(context)
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
    internal val navigationKeyBootstrap = NavigationKeyBootstrap(
        scope = applicationScope,
        loadKey = navigationApiKeyStore::load,
        configureKey = navigationSdkGateway::configureIfNeeded,
    )
    val destinationParser = DestinationParser(context)
    val navigationFeed = NavigationFeedRepository()
    internal val navigationStartStopGuard = NavigationStartStopGuard()
    val tftNavigationBridge = TftNavigationBridge(bikeConnection, appSettings.settings, applicationScope)
    internal val navigationGuidanceLifecycle = NavigationGuidanceLifecycle(
        clearNavigationFeed = navigationFeed::clear,
        finishTftArrival = tftNavigationBridge::arrivedAndStop,
    )
    val navigationStopController = NavigationStopController(
        application = context.applicationContext as Application,
        guard = navigationStartStopGuard,
        guidanceLifecycle = navigationGuidanceLifecycle,
        clearOutput = {
            navigationFeed.clear()
            runCatching(tftNavigationBridge::stop)
        },
    )
    /**
     * A destination the rider has chosen but not started. While one is set the cluster shows GO,
     * and the handlebar can start it without them touching the phone.
     */
    private val mutableStagedDestination = MutableStateFlow<String?>(null)
    val stagedDestination: StateFlow<String?> = mutableStagedDestination.asStateFlow()
    private val mutableStartNavigationRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when the rider pressed GO on the handlebar and a destination is staged. */
    val startNavigationRequests: SharedFlow<Unit> = mutableStartNavigationRequests.asSharedFlow()

    fun stageDestination(destination: String?) {
        mutableStagedDestination.value = destination?.takeIf(String::isNotBlank)
    }

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
        navigationFeed.acceptTerminalNavInfo = navigationGuidanceLifecycle::acceptAndMarkTerminalFeed
        navigationFeed.onNavInfo = { info ->
            when (navigationFeedOutputAction(info.navState)) {
                NavigationFeedOutputAction.Guidance -> tftNavigationBridge.accept(info)
                NavigationFeedOutputAction.Rerouting -> tftNavigationBridge.rerouting()
                NavigationFeedOutputAction.Stop -> tftNavigationBridge.stop()
            }
        }
        applicationScope.launch {
            // The handlebar EXIT has to work with the phone stowed and no navigation screen in
            // the task, so this collector is process-scoped. NavigationActivity keeps its own
            // handling for skip, which needs the map it owns.
            bikeConnection.controls.collect { event ->
                if (event is BikeControlEvent.ClusterReady) {
                    connectionEventJournal.record("Cluster reported ready; resending navigation and app events")
                    tftNavigationBridge.republishLast()
                    val battery = context.getSystemService(BatteryManager::class.java)
                        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
                    bikeConnection.enqueueWrite(
                        BleCharacteristics.AppEvent,
                        appEventPacket(ClearAppEventsEvent, battery),
                    )
                }
                if (event is BikeControlEvent.StartNavigation) {
                    val staged = mutableStagedDestination.value
                    if (staged == null) {
                        connectionEventJournal.record("Handlebar GO ignored; no destination is staged")
                    } else {
                        connectionEventJournal.record("Handlebar GO; starting the staged destination")
                        mutableStartNavigationRequests.tryEmit(Unit)
                    }
                }
                if (event is BikeControlEvent.ExitNavigation) {
                    connectionEventJournal.record("Handlebar exit; stopping navigation")
                    navigationStopController.stop { result ->
                        connectionEventJournal.record("Handlebar exit result: $result")
                    }
                }
            }
        }
        applicationScope.launch {
            stagedDestination.collect { destination ->
                tftNavigationBridge.previewDestination(destination.orEmpty())
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
