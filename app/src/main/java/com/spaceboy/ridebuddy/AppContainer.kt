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
import com.spaceboy.ridebuddy.core.calls.CallBridge
import com.spaceboy.ridebuddy.core.location.RideLocationTracker
import com.spaceboy.ridebuddy.core.location.RideLocationLabeler
import com.spaceboy.ridebuddy.core.companion.BikeCompanionManager
import com.spaceboy.ridebuddy.core.companion.BikeConnectionDemandController
import android.os.BatteryManager
import com.spaceboy.ridebuddy.ble.BleCharacteristics
import com.spaceboy.ridebuddy.service.NotificationIconWriter
import com.spaceboy.ridebuddy.data.RideHistoryMaintenance
import com.spaceboy.ridebuddy.data.RideRecorder
import com.spaceboy.ridebuddy.data.RideRepository
import com.spaceboy.ridebuddy.data.AppSettingsRepository
import com.spaceboy.ridebuddy.core.alerts.RidingAlertMonitor
import com.spaceboy.ridebuddy.core.alerts.WeatherAlertProvider
import com.spaceboy.ridebuddy.domain.BikeConnection
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.domain.BikeControlEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Manual dependency graph for the whole process, plus the wiring between its parts.
 *
 * Hand-rolled rather than generated: the graph is a single flat set of process-scoped
 * singletons in a fixed construction order, which a DI framework would not simplify.
 *
 * The `init` block is the more interesting half. It connects components that must not
 * depend on each other directly — the navigation feed to the cluster bridge, handlebar
 * controls to navigation, settings to the diagnostics recorders — so each stays testable in
 * isolation and this file is the one place the app's cross-cutting behaviour is described.
 */
class AppContainer(context: Context) {
    /**
     * The constructor parameter, named so it can be passed as a bare argument. Kotlin parses the
     * identifier `context` in that position as the start of a context-parameter clause.
     */
    private val appContext: Context = context.applicationContext

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
        onAttemptsExhausted = bikeConnectionDemand::onConnectionAttemptsExhausted,
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
    private val rideHistoryMaintenance = RideHistoryMaintenance(rideRepository, appSettings, applicationScope)
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
        scope = applicationScope,
    )
    val stationaryTftValidator = StationaryTftValidator(bikeConnection)
    internal val notificationIconWriter = NotificationIconWriter(
        batteryPercent = {
            context.getSystemService(BatteryManager::class.java)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.coerceIn(0, 100)
                ?: 0
        },
        write = { payload -> bikeConnection.enqueueWrite(BleCharacteristics.AppEvent, payload) },
    )
    val callBridge = CallBridge(context, bikeConnection, appSettings, applicationScope)
    val tftPriorityCoordinator =
        TftPriorityCoordinator(navigationFeed, callBridge, tftNavigationBridge, applicationScope)
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
        // Both diagnostics recorders are driven from settings rather than being consulted
        // at each call site, so turning either off takes effect immediately everywhere.
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
        // The guidance feed drives the cluster display. Routed here rather than from the
        // feed itself so the feed stays a plain fan-out point with no knowledge of the bike.
        navigationFeed.acceptTerminalNavInfo = navigationGuidanceLifecycle::acceptAndMarkTerminalFeed
        navigationFeed.onNavInfo = { info ->
            when (navigationFeedOutputAction(info.navState)) {
                NavigationFeedOutputAction.Guidance -> tftNavigationBridge.accept(info)
                NavigationFeedOutputAction.Rerouting -> {
                    // The alert is raised and published *before* the reroute frames are queued.
                    // Ordering carries the fix: a reroute batch already in the queue would drain
                    // over an alert regardless of any guard inside rerouting(), because it does
                    // not belong to the alert generation the coordinator prunes.
                    val message = "The route is being recalculated; check for changed road conditions"
                    if (ridingAlertMonitor.navigationHazard(message)) {
                        tftPriorityCoordinator.presentTextAlert(
                            "ROUTE ALERT. Recalculating. Check road conditions.",
                        )
                    }
                    tftNavigationBridge.rerouting()
                }
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
                    // Clear *and* re-light: the cluster has forgotten what it was showing, so a
                    // bare clear would leave still-live notifications with no icon until each
                    // one is dismissed and replaced.
                    notificationIconWriter.clearAndReplay()
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
            var hadConnectionSession = false
            var terminalHandled = false
            bikeConnection.connectionState.collect { state ->
                val terminal = state is BikeConnectionState.Failed ||
                    (state is BikeConnectionState.Disconnected && hadConnectionSession)
                if (state !is BikeConnectionState.Disconnected) hadConnectionSession = true
                if (!terminal) {
                    terminalHandled = false
                    return@collect
                }
                if (terminalHandled) return@collect
                terminalHandled = true
                hadConnectionSession = false
                // Brief reconnection attempts preserve the ride. A terminal session discards
                // every app-side queue, including timers that could otherwise replay old alerts.
                callBridge.clearPendingBikeOutput()
                notificationIconWriter.clearPendingBikeOutput()
                tftPriorityCoordinator.clearPendingBikeOutput()
                ridingAlertMonitor.clearPendingBikeOutput()
                navigationFeed.clear()
                tftNavigationBridge.clearPendingBikeOutput()
                connectionEventJournal.record("Connection ended; clearing pending output and navigation")
                navigationStopController.stop { result ->
                    connectionEventJournal.record("Navigation stopped after connection ended: $result")
                }
            }
        }
        rideRecorder.start()
        rideHistoryMaintenance.start()
        ridingAlertMonitor.start()
        weatherAlertProvider.start()
    }

}

/** Convenience for reaching the [AppContainer] from any [Context] without
 *  the repetitive `(application as RideBuddyApplication).container` cast. */
val Context.appContainer: AppContainer
    get() = (applicationContext as RideBuddyApplication).container
