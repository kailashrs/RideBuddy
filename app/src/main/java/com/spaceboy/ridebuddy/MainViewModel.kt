package com.spaceboy.ridebuddy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.spaceboy.ridebuddy.ble.AndroidBikeScanner
import com.spaceboy.ridebuddy.ble.BleCaptureRecorder
import com.spaceboy.ridebuddy.ble.BikeScanState
import com.spaceboy.ridebuddy.ble.DiscoveredBike
import com.spaceboy.ridebuddy.data.InsightPeriod
import com.spaceboy.ridebuddy.data.InsightsCalculator
import com.spaceboy.ridebuddy.data.RideInsights
import com.spaceboy.ridebuddy.data.AppSettings
import com.spaceboy.ridebuddy.data.AppSettingsRepository
import com.spaceboy.ridebuddy.data.DistanceUnits
import com.spaceboy.ridebuddy.data.ThemeMode
import com.spaceboy.ridebuddy.data.TftTextMode
import com.spaceboy.ridebuddy.data.RideRecorder
import com.spaceboy.ridebuddy.data.RideRepository
import com.spaceboy.ridebuddy.core.navigation.NavigationFeedRepository
import com.spaceboy.ridebuddy.core.navigation.ConfigureResult
import com.spaceboy.ridebuddy.core.navigation.GoogleNavigationSdkGateway
import com.spaceboy.ridebuddy.core.navigation.NavigationApiKeyPolicy
import com.spaceboy.ridebuddy.core.security.SecureNavigationApiKeyStore
import com.spaceboy.ridebuddy.domain.BikeConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(
    private val apiKeyStore: SecureNavigationApiKeyStore,
    private val navigationSdkGateway: GoogleNavigationSdkGateway,
    private val bikeScanner: AndroidBikeScanner,
    private val bikeConnection: BikeConnection,
    private val bleCaptureRecorder: BleCaptureRecorder,
    private val rideRecorder: RideRecorder,
    private val rideRepository: RideRepository,
    private val navigationFeed: NavigationFeedRepository,
    private val appSettings: AppSettingsRepository,
) : ViewModel() {
    private val storedKey = apiKeyStore.load()
    private val mutableUiState = MutableStateFlow(
        MainUiState(
            navigationKey = NavigationKeyUiState(
                isConfigured = storedKey != null,
                maskedKey = storedKey?.let(NavigationApiKeyPolicy::mask),
            ),
        ),
    )

    val uiState: StateFlow<MainUiState> = mutableUiState.asStateFlow()
    val scanState: StateFlow<BikeScanState> = bikeScanner.scanState
    val discoveredBikes: StateFlow<List<DiscoveredBike>> = bikeScanner.bikes
    val connectionState = bikeConnection.connectionState
    val telemetry = bikeConnection.telemetry
    val identity = bikeConnection.identity
    val diagnostics = bikeConnection.diagnostics
    val bleCapture = bleCaptureRecorder.state
    val activeRide = rideRecorder.activeRide
    val liveRideSamples = rideRecorder.liveSamples
    val rides = rideRepository.rides
    val guidance = navigationFeed.guidance
    val settings = appSettings.settings
    private val insightPeriod = MutableStateFlow(InsightPeriod.ThirtyDays)
    val selectedInsightPeriod: StateFlow<InsightPeriod> = insightPeriod.asStateFlow()
    val insights: StateFlow<RideInsights> = combine(rides, insightPeriod) { currentRides, period ->
        InsightsCalculator.calculate(currentRides, period)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RideInsights())

    fun selectDestination(destination: TopLevelDestination) {
        mutableUiState.update { state ->
            state.copy(
                selectedDestination = destination,
                isNavigationSettingsOpen = false,
                isDiagnosticsOpen = false,
            )
        }
    }

    fun openNavigationSettings() {
        mutableUiState.update { it.copy(isNavigationSettingsOpen = true) }
    }

    fun closeNavigationSettings() {
        mutableUiState.update { it.copy(isNavigationSettingsOpen = false) }
    }

    fun openDiagnostics() {
        mutableUiState.update { it.copy(isDiagnosticsOpen = true, isNavigationSettingsOpen = false) }
    }

    fun closeDiagnostics() {
        mutableUiState.update { it.copy(isDiagnosticsOpen = false) }
    }

    fun setNavigationStarting(starting: Boolean) {
        mutableUiState.update { it.copy(isNavigationStarting = starting) }
    }

    fun saveNavigationApiKey(value: String) {
        val apiKey = value.trim()
        val validationError = NavigationApiKeyPolicy.validate(apiKey)
        if (validationError != null) {
            mutableUiState.update { state ->
                state.copy(navigationKey = state.navigationKey.copy(errorMessage = validationError))
            }
            return
        }

        mutableUiState.update { state ->
            state.copy(navigationKey = state.navigationKey.copy(isSaving = true, errorMessage = null))
        }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    navigationSdkGateway.configureIfNeeded(apiKey).also { result ->
                        if (result !is ConfigureResult.Failed) apiKeyStore.save(apiKey)
                    }
                }
            }

            mutableUiState.update { state ->
                outcome.fold(
                    onSuccess = { result ->
                        state.copy(
                            navigationKey = navigationKeyStateFor(result, apiKey, state.navigationKey),
                            transientMessage = if (result is ConfigureResult.Failed) null else "Navigation API key saved",
                        )
                    },
                    onFailure = { error ->
                        state.copy(
                            navigationKey = state.navigationKey.copy(
                                isSaving = false,
                                errorMessage = error.message ?: "Could not save the API key",
                            ),
                        )
                    },
                )
            }
        }
    }

    fun removeNavigationApiKey() {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching(apiKeyStore::clear) }
            mutableUiState.update { state ->
                outcome.fold(
                    onSuccess = {
                        state.copy(
                            navigationKey = NavigationKeyUiState(
                                restartRequired = navigationSdkGateway.isConfiguredInProcess,
                            ),
                            transientMessage = "Navigation API key removed",
                        )
                    },
                    onFailure = { error ->
                        state.copy(
                            navigationKey = state.navigationKey.copy(
                                errorMessage = error.message ?: "Could not remove the API key",
                            ),
                        )
                    },
                )
            }
        }
    }

    fun testNavigationApiKey() {
        val key = apiKeyStore.load()
        if (key == null) {
            showMessage("Add an API key before testing")
            return
        }
        val result = navigationSdkGateway.configureIfNeeded(key)
        showMessage(
            when (result) {
                ConfigureResult.Configured, ConfigureResult.AlreadyConfigured -> "Navigation SDK accepted the key configuration; cloud restrictions are verified when a route starts"
                ConfigureResult.RestartRequired -> "Restart the app to test the replacement key"
                is ConfigureResult.Failed -> result.message
            },
        )
    }

    fun startBikeScan() = bikeScanner.start()

    fun connectToBike(bike: DiscoveredBike) {
        bikeScanner.stop()
        bikeConnection.connect(bike.connectionTarget())
    }

    fun stopBikeScan() = bikeScanner.stop()

    fun disconnectBike() = bikeConnection.disconnect()

    fun selectInsightPeriod(period: InsightPeriod) {
        insightPeriod.value = period
    }

    fun clearRideHistory() {
        viewModelScope.launch {
            try {
                rideRepository.clear()
                showMessage("Ride history cleared")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showMessage("Could not clear ride history")
            }
        }
    }

    fun setDistanceUnits(value: DistanceUnits) = updateSettings { it.copy(distanceUnits = value) }
    fun setVoiceGuidance(value: Boolean) = updateSettings { it.copy(voiceGuidance = value) }
    fun setAvoidTolls(value: Boolean) = updateSettings { it.copy(avoidTolls = value) }
    fun setAvoidHighways(value: Boolean) = updateSettings { it.copy(avoidHighways = value) }
    fun setAvoidFerries(value: Boolean) = updateSettings { it.copy(avoidFerries = value) }
    fun setAutoStartSharedDestinations(value: Boolean) = updateSettings { it.copy(autoStartSharedDestinations = value) }
    fun setMessageAlerts(value: Boolean) = updateSettings { it.copy(messageAlerts = value) }
    fun setSocialAlerts(value: Boolean) = updateSettings { it.copy(socialAlerts = value) }
    fun setEmailAlerts(value: Boolean) = updateSettings { it.copy(emailAlerts = value) }
    fun setNotificationPackageEnabled(packageName: String, enabled: Boolean) = updateSettings { settings ->
        settings.copy(
            enabledNotificationPackages = if (enabled) {
                settings.enabledNotificationPackages + packageName
            } else {
                settings.enabledNotificationPackages - packageName
            },
        )
    }
    fun setLegacyCallControls(value: Boolean) = updateSettings { it.copy(legacyCallControls = value) }
    fun setCallerDisplay(value: Boolean) = updateSettings { it.copy(callerDisplay = value) }
    fun setTftCallControls(value: Boolean) = updateSettings { it.copy(tftCallControls = value) }
    fun setTftNavigationOutput(value: Boolean) = updateSettings { it.copy(tftNavigationOutputEnabled = value) }
    fun setBleCaptureEnabled(value: Boolean) = updateSettings { it.copy(bleCaptureEnabled = value) }
    fun completeOnboarding() = updateSettings { it.copy(onboardingComplete = true) }
    fun resetOnboarding() = updateSettings { it.copy(onboardingComplete = false) }
    fun setRideStartSpeed(value: Double) = updateSettings { it.copy(rideStartSpeedKph = value.coerceIn(1.0, 15.0)) }
    fun setRideStopSpeed(value: Double) = updateSettings { it.copy(rideStopSpeedKph = value.coerceIn(0.0, 10.0)) }
    fun setRideStopDelay(value: Int) = updateSettings { it.copy(rideStopDelaySeconds = value.coerceIn(10, 600)) }
    fun setOverspeedAlerts(value: Boolean) = updateSettings { it.copy(overspeedAlerts = value) }
    fun setOverspeedThreshold(value: Int) = updateSettings { it.copy(overspeedThresholdKph = value.coerceIn(40, 250)) }
    fun setRpmAlerts(value: Boolean) = updateSettings { it.copy(rpmAlerts = value) }
    fun setRpmThreshold(value: Int) = updateSettings { it.copy(rpmThreshold = value.coerceIn(3_000, 15_000)) }
    fun setAccelerationAlerts(value: Boolean) = updateSettings { it.copy(accelerationAlerts = value) }
    fun setBrakingAlerts(value: Boolean) = updateSettings { it.copy(brakingAlerts = value) }
    fun setWeatherAlerts(value: Boolean) = updateSettings { it.copy(weatherAlerts = value) }
    fun setHazardAlerts(value: Boolean) = updateSettings { it.copy(hazardAlerts = value) }
    fun setTftTextMode(value: TftTextMode) = updateSettings { it.copy(tftTextMode = value) }
    fun setThemeMode(value: ThemeMode) = updateSettings { it.copy(themeMode = value) }
    fun setDynamicColor(value: Boolean) = updateSettings { it.copy(dynamicColor = value) }
    fun setHighContrast(value: Boolean) = updateSettings { it.copy(highContrast = value) }
    fun clearBleCapture() = bleCaptureRecorder.clear()

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        appSettings.update(transform)
    }

    fun acceptSharedDestination(value: String) {
        mutableUiState.update { state ->
            state.copy(
                selectedDestination = TopLevelDestination.Live,
                sharedDestination = value,
                isNavigationSettingsOpen = false,
            )
        }
    }

    fun clearSharedDestination() {
        mutableUiState.update { it.copy(sharedDestination = null) }
    }

    fun clearTransientMessage() {
        mutableUiState.update { it.copy(transientMessage = null) }
    }

    fun showMessage(message: String) {
        mutableUiState.update { it.copy(transientMessage = message) }
    }

    override fun onCleared() {
        bikeScanner.stop()
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(
                    apiKeyStore = container.navigationApiKeyStore,
                    navigationSdkGateway = container.navigationSdkGateway,
                    bikeScanner = container.bikeScanner,
                    bikeConnection = container.bikeConnection,
                    bleCaptureRecorder = container.bleCaptureRecorder,
                    rideRecorder = container.rideRecorder,
                    rideRepository = container.rideRepository,
                    navigationFeed = container.navigationFeed,
                    appSettings = container.appSettings,
                ) as T
            }
    }
}

internal fun navigationKeyStateFor(
    result: ConfigureResult,
    apiKey: String,
    current: NavigationKeyUiState,
): NavigationKeyUiState = when (result) {
    is ConfigureResult.Failed -> current.copy(isSaving = false, errorMessage = result.message)
    ConfigureResult.Configured,
    ConfigureResult.AlreadyConfigured,
    ConfigureResult.RestartRequired
    -> NavigationKeyUiState(
        isConfigured = true,
        maskedKey = NavigationApiKeyPolicy.mask(apiKey),
        restartRequired = result is ConfigureResult.RestartRequired,
    )
}

data class MainUiState(
    val selectedDestination: TopLevelDestination = TopLevelDestination.Live,
    val isNavigationSettingsOpen: Boolean = false,
    val isDiagnosticsOpen: Boolean = false,
    val navigationKey: NavigationKeyUiState = NavigationKeyUiState(),
    val sharedDestination: String? = null,
    val transientMessage: String? = null,
    val isNavigationStarting: Boolean = false,
)

data class NavigationKeyUiState(
    val isConfigured: Boolean = false,
    val maskedKey: String? = null,
    val isSaving: Boolean = false,
    val restartRequired: Boolean = false,
    val errorMessage: String? = null,
)

enum class TopLevelDestination {
    Live,
    History,
    Insights,
    Info,
    More,
}
