package com.spaceboy.ridebuddy

import android.app.Application
import android.content.Context
import com.spaceboy.ridebuddy.ble.AndroidBikeConnection
import com.spaceboy.ridebuddy.ble.BleCaptureRecorder
import com.spaceboy.ridebuddy.ble.BikeIdentityRepository
import com.spaceboy.ridebuddy.ble.ConnectionEventJournal
import com.spaceboy.ridebuddy.ble.FileConnectionEventStore
import com.spaceboy.ridebuddy.ble.LinkStateProtectionAcceptanceStore
import com.spaceboy.ridebuddy.ble.LinkStateStore
import com.spaceboy.ridebuddy.core.navigation.DestinationParser
import com.spaceboy.ridebuddy.core.navigation.GuidanceOutput
import com.spaceboy.ridebuddy.core.navigation.NavigationApiKey
import com.spaceboy.ridebuddy.core.navigation.NavigationController
import com.spaceboy.ridebuddy.core.navigation.NavigationDestination
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import kotlinx.coroutines.flow.filter
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
import com.spaceboy.ridebuddy.service.NotificationIcons
import com.spaceboy.ridebuddy.data.LegacyRideImporter
import com.spaceboy.ridebuddy.data.RideHistoryMaintenance
import com.spaceboy.ridebuddy.data.db.RideHistoryDatabase
import com.spaceboy.ridebuddy.data.db.RideSamplesDatabase
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
    internal val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val bleCaptureRecorder = BleCaptureRecorder(applicationScope)
    private val linkState = LinkStateStore.create(context, applicationScope)
    private val protectionAcceptanceStore = LinkStateProtectionAcceptanceStore(linkState)
    private val bikeIdentityRepository = BikeIdentityRepository(linkState, applicationScope)
    val appSettings = AppSettingsRepository.create(context, applicationScope)
    internal val bikeConnectionDemand = BikeConnectionDemandController(linkState)
    internal val connectionEventJournal = ConnectionEventJournal(
        store = FileConnectionEventStore.create(context),
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
    private val rideLocationLabeler = RideLocationLabeler(context)
    private val rideHistoryDatabase = RideHistoryDatabase.open(context)
    private val rideSamplesDatabase = RideSamplesDatabase.open(context)
    val rideRepository = RideRepository(rideHistoryDatabase, rideSamplesDatabase, applicationScope)
    val bikeCompanionManager = BikeCompanionManager(context, protectionAcceptanceStore, bikeIdentityRepository)
    val rideRecorder = RideRecorder(
        bikeConnection,
        rideRepository,
        applicationScope,
        rideLocationTracker,
        appSettings,
        rideLocationLabeler,
    )
    private val rideHistoryMaintenance = RideHistoryMaintenance(
        repository = rideRepository,
        legacyImporter = LegacyRideImporter(
            legacyDatabase = context.getDatabasePath("rides.db"),
            legacyBackupSnapshot = java.io.File(context.filesDir, "backup/rides.backup"),
            history = rideHistoryDatabase,
            sampleStore = rideSamplesDatabase,
        ),
        settingsRepository = appSettings,
        scope = applicationScope,
    )
    val navigationApiKey = NavigationApiKey(SecureNavigationApiKeyStore(context), applicationScope)
    val destinationParser = DestinationParser(rideLocationLabeler)
    val tftNavigationBridge = TftNavigationBridge(bikeConnection, appSettings.settings, applicationScope)

    /** Routes guidance to the cluster. The hazard alert is raised before the reroute frames queue. */
    private val guidanceOutput: GuidanceOutput = object : GuidanceOutput {
        override fun preview(destination: NavigationDestination, distanceMetres: Int?, durationSeconds: Int?) =
            tftNavigationBridge.previewDestination(destination.title, distanceMetres, durationSeconds)

        override fun started(destination: NavigationDestination) = tftNavigationBridge.start(destination.title)
        override fun update(info: NavInfo) = tftNavigationBridge.accept(info)

        override fun rerouting() {
            if (ridingAlertMonitor.navigationHazard("The route is being recalculated; check for changed road conditions")) {
                tftPriorityCoordinator.presentTextAlert("ROUTE ALERT. Recalculating. Check road conditions.")
            }
            tftNavigationBridge.rerouting()
        }

        override fun arrived() = tftNavigationBridge.arrivedAndStop()
        override fun stopped() = tftNavigationBridge.stop()
    }
    val navigationController: NavigationController = NavigationController(
        application = appContext as Application,
        connection = bikeConnection,
        settings = appSettings.settings,
        output = guidanceOutput,
        log = connectionEventJournal::record,
        scope = applicationScope,
    )
    private val phoneBatteryPercent: () -> Int = {
        context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.coerceIn(0, 100)
            ?: 0
    }
    val stationaryTftValidator = StationaryTftValidator(bikeConnection, phoneBatteryPercent)
    internal val notificationIcons = NotificationIcons(bikeConnection, appSettings.settings, phoneBatteryPercent)
    val callBridge = CallBridge(context, bikeConnection, appSettings, applicationScope)
    val tftPriorityCoordinator: TftPriorityCoordinator =
        TftPriorityCoordinator(navigationController.guidance, callBridge, tftNavigationBridge, applicationScope)
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
        applicationScope.launch {
            bikeConnection.controls.filter { it is BikeControlEvent.ClusterReady }.collect {
                connectionEventJournal.record("Cluster reported ready; resending navigation and app events")
                tftNavigationBridge.republishLast()
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
                tftPriorityCoordinator.clearPendingBikeOutput()
                ridingAlertMonitor.clearPendingBikeOutput()
                tftNavigationBridge.clearPendingBikeOutput()
                connectionEventJournal.record("Connection ended; clearing pending output")
            }
        }
        notificationIcons.start(appContext, applicationScope)
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
