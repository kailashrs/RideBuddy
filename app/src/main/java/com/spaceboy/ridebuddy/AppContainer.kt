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
import com.spaceboy.ridebuddy.core.security.SecureNavigationApiKeyStore
import com.spaceboy.ridebuddy.core.tft.ClusterDisplay
import com.spaceboy.ridebuddy.core.tft.StationaryTftValidator
import com.spaceboy.ridebuddy.core.location.RideLocationTracker
import com.spaceboy.ridebuddy.core.location.RideLocationLabeler
import com.spaceboy.ridebuddy.core.companion.BikeCompanionManager
import com.spaceboy.ridebuddy.core.companion.BikeConnectionDemandController
import android.os.BatteryManager
import com.spaceboy.ridebuddy.service.PhoneBatteryReporter
import com.spaceboy.ridebuddy.data.RideHistoryMaintenance
import com.spaceboy.ridebuddy.data.db.RideBuddyDatabase
import com.spaceboy.ridebuddy.core.navigation.NavigationDestination
import com.spaceboy.ridebuddy.data.DestinationRepository
import com.spaceboy.ridebuddy.data.db.RawTelemetryDatabase
import com.spaceboy.ridebuddy.data.db.bridgeLegacyRides
import com.spaceboy.ridebuddy.data.db.bridgeLegacyTelemetry
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

/**
 * The process's dependency graph: one flat set of process-scoped singletons in a fixed
 * construction order, which a DI framework would not simplify.
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
    // TEMPORARY: the two bridge calls carry a development install's data over from the pre-1.2
    // file names; see LegacyDatabaseBridge. Remove them with that file.
    private val database = RideBuddyDatabase.open(context).also { bridgeLegacyRides(context, it) }
    val rideRepository = RideRepository(
        database,
        RawTelemetryDatabase.open(context).also { bridgeLegacyTelemetry(context, it) },
        applicationScope,
    )
    val bikeCompanionManager = BikeCompanionManager(context, protectionAcceptanceStore, bikeIdentityRepository)
    val rideRecorder = RideRecorder(
        bikeConnection,
        rideRepository,
        applicationScope,
        rideLocationTracker,
        appSettings,
        rideLocationLabeler,
    )
    private val rideHistoryMaintenance = RideHistoryMaintenance(rideRepository, appSettings, applicationScope)
    val navigationApiKey = NavigationApiKey(SecureNavigationApiKeyStore(context), applicationScope)
    val destinationParser = DestinationParser(rideLocationLabeler)
    val destinationRepository = DestinationRepository(
        database.destinations(),
        applicationScope,
        rideLocationLabeler::addressFirstLine,
    )
    val clusterDisplay = ClusterDisplay(bikeConnection, appSettings.settings, applicationScope)

    /** The hazard alert is raised before the reroute reaches the cluster, so it owns the rows first. */
    private val guidanceOutput = object : GuidanceOutput by clusterDisplay {
        /** A trip counts once guidance starts, so an abandoned preview does not make a place frequent. */
        override fun started(destination: NavigationDestination) {
            clusterDisplay.started(destination)
            applicationScope.launch { destinationRepository.recordTrip(destination) }
        }

        override fun rerouting() {
            if (ridingAlertMonitor.navigationHazard("The route is being recalculated; check for changed road conditions")) {
                clusterDisplay.presentTextAlert("ROUTE ALERT. Recalculating. Check road conditions.")
            }
            clusterDisplay.rerouting()
        }
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
    val stationaryTftValidator = StationaryTftValidator(bikeConnection)
    private val phoneBatteryReporter = PhoneBatteryReporter(bikeConnection, phoneBatteryPercent)
    val ridingAlertMonitor = RidingAlertMonitor(context, bikeConnection, rideRecorder, appSettings, applicationScope)
    val weatherAlertProvider = WeatherAlertProvider(
        rideLocationTracker,
        appSettings,
        applicationScope,
    ) { message ->
        if (ridingAlertMonitor.weatherAlert(message)) {
            clusterDisplay.presentTextAlert("WEATHER ALERT. $message")
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
        phoneBatteryReporter.start(appContext, applicationScope)
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
