package com.spaceboy.ridebuddy

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.spaceboy.ridebuddy.ble.BleCaptureRecorder
import com.spaceboy.ridebuddy.data.InsightPeriod
import com.spaceboy.ridebuddy.data.InsightsCalculator
import com.spaceboy.ridebuddy.data.LiveRideMetrics
import com.spaceboy.ridebuddy.data.RideInsights
import com.spaceboy.ridebuddy.data.RideWeekSummary
import com.spaceboy.ridebuddy.data.AppSettings
import com.spaceboy.ridebuddy.data.AppSettingsRepository
import com.spaceboy.ridebuddy.data.DistanceUnits
import com.spaceboy.ridebuddy.data.ThemeMode
import com.spaceboy.ridebuddy.data.TftTextMode
import com.spaceboy.ridebuddy.data.RideRecorder
import com.spaceboy.ridebuddy.data.RideRepository
import com.spaceboy.ridebuddy.data.calculateLiveRideMetrics
import com.spaceboy.ridebuddy.core.navigation.NavigationFeedRepository
import com.spaceboy.ridebuddy.core.navigation.ConfigureResult
import com.spaceboy.ridebuddy.core.navigation.GoogleNavigationSdkGateway
import com.spaceboy.ridebuddy.core.navigation.NavigationApiKeyPolicy
import com.spaceboy.ridebuddy.core.navigation.NavigationKeyBootstrap
import com.spaceboy.ridebuddy.core.security.SecureNavigationApiKeyStore
import com.spaceboy.ridebuddy.domain.BikeConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MainViewModel internal constructor(
    savedStateHandle: SavedStateHandle,
    private val apiKeyStore: SecureNavigationApiKeyStore,
    private val navigationSdkGateway: GoogleNavigationSdkGateway,
    private val navigationKeyBootstrap: NavigationKeyBootstrap,
    private val bikeConnection: BikeConnection,
    private val bleCaptureRecorder: BleCaptureRecorder,
    private val rideRecorder: RideRecorder,
    private val rideRepository: RideRepository,
    private val navigationFeed: NavigationFeedRepository,
    private val appSettings: AppSettingsRepository,
) : ViewModel() {
    private val sharedDestinationStateStore = SharedDestinationStateStore(savedStateHandle)
    private val restoredSharedDestinationState = sharedDestinationStateStore.restore()
    private val sharedDestinationRequestIds = AtomicLong(
        restoredSharedDestinationState.autoStartSharedDestination?.requestId ?: 0L,
    )
    private val navigationStartAttemptIds = AtomicLong()
    private val autoConnectAttempted = AtomicBoolean(false)
    private val mutableUiState = MutableStateFlow(
        restoredSharedDestinationState.copy(
            navigationKey = NavigationKeyUiState(isLoading = true),
        ),
    )

    val uiState: StateFlow<MainUiState> = mutableUiState.asStateFlow()
    val connectionState = bikeConnection.connectionState
    val telemetry = bikeConnection.telemetry
    val identity = bikeConnection.identity
    val diagnostics = bikeConnection.diagnostics
    val bleCapture = bleCaptureRecorder.state
    val activeRide = rideRecorder.activeRide
    val liveRideSamples = rideRecorder.liveSamples
    val liveRideMetrics: StateFlow<LiveRideMetrics> = liveRideSamples
        .map(::calculateLiveRideMetrics)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveRideMetrics())
    val rides = rideRepository.rides
    val guidance = navigationFeed.guidance
    val settings = appSettings.settings
    private val insightPeriod = MutableStateFlow(InsightPeriod.ThirtyDays)
    val selectedInsightPeriod: StateFlow<InsightPeriod> = insightPeriod.asStateFlow()
    val insights: StateFlow<RideInsights> = combine(rides, insightPeriod) { currentRides, period ->
        InsightsCalculator.calculate(currentRides, period)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RideInsights())
    val weekSummary: StateFlow<RideWeekSummary> = rides
        .map { currentRides -> InsightsCalculator.weekSummary(currentRides, System.currentTimeMillis()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RideWeekSummary())

    init {
        viewModelScope.launch {
            val bootstrapResult = navigationKeyBootstrap.await()
            mutableUiState.update { state ->
                if (!state.navigationKey.isLoading) state else state.copy(
                    navigationKey = navigationKeyStateForBootstrap(bootstrapResult),
                )
            }
        }
    }

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

    fun beginNavigationStart(): Long {
        val attemptId = navigationStartAttemptIds.incrementAndGet()
        mutableUiState.update { it.withNavigationStartAttempt(attemptId) }
        return attemptId
    }

    fun finishNavigationStart(attemptId: Long) {
        mutableUiState.update { it.withFinishedNavigationStartAttempt(attemptId) }
    }

    fun saveNavigationApiKey(value: String) {
        val apiKey = value.trim()
        NavigationApiKeyPolicy.validate(apiKey)?.let { validationError ->
            mutableUiState.update { state ->
                state.copy(navigationKey = state.navigationKey.copy(errorMessage = validationError))
            }
            return
        }
        runNavigationKeyOperation {
            val result = withContext(Dispatchers.IO) {
                navigationSdkGateway.configureIfNeeded(apiKey).also { outcome ->
                    if (outcome !is ConfigureResult.Failed) {
                        apiKeyStore.save(apiKey)
                        navigationKeyBootstrap.recordSavedKey(apiKey, outcome)
                    }
                }
            }
            mutableUiState.update { state ->
                state.copy(
                    navigationKey = navigationKeyStateFor(result, apiKey, state.navigationKey),
                    transientMessage = if (result is ConfigureResult.Failed) {
                        null
                    } else {
                        "Navigation API key saved"
                    },
                )
            }
        }
    }

    fun removeNavigationApiKey() {
        runNavigationKeyOperation {
            withContext(Dispatchers.IO) {
                apiKeyStore.clear()
                navigationKeyBootstrap.recordRemovedKey(
                    restartRequired = navigationSdkGateway.isConfiguredInProcess,
                )
            }
            mutableUiState.update { state ->
                state.copy(
                    navigationKey = NavigationKeyUiState(
                        restartRequired = navigationSdkGateway.isConfiguredInProcess,
                    ),
                    transientMessage = "Navigation API key removed",
                )
            }
        }
    }

    fun testNavigationApiKey() {
        runNavigationKeyOperation {
            val key = withContext(Dispatchers.IO) { apiKeyStore.load() }
            if (key == null) {
                showMessage("Add an API key before testing")
                return@runNavigationKeyOperation
            }
            val result = withContext(Dispatchers.IO) { navigationSdkGateway.configureIfNeeded(key) }
            showMessage(
                when (result) {
                    ConfigureResult.Configured, ConfigureResult.AlreadyConfigured -> "Navigation SDK accepted the key configuration; cloud restrictions are verified when a route starts"
                    ConfigureResult.RestartRequired -> "Restart the app to test the replacement key"
                    is ConfigureResult.Failed -> result.message
                },
            )
        }
    }

    /**
     * Runs one navigation-key operation at a time. `isSaving` is the mutex: every caller reaches
     * this from the main dispatcher, so the check and the set cannot interleave.
     */
    private fun runNavigationKeyOperation(operation: suspend () -> Unit) {
        val current = mutableUiState.value.navigationKey
        if (current.isLoading || current.isSaving) return
        mutableUiState.update { state ->
            state.copy(navigationKey = state.navigationKey.copy(isSaving = true, errorMessage = null))
        }
        viewModelScope.launch {
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableUiState.update { state ->
                    state.copy(
                        navigationKey = state.navigationKey.copy(
                            errorMessage = error.message ?: "Navigation setup failed",
                        ),
                    )
                }
            } finally {
                mutableUiState.update { state ->
                    state.copy(navigationKey = state.navigationKey.copy(isSaving = false))
                }
            }
        }
    }

    /**
     * One-shot: true on the first call after process start, false thereafter, so a resume or a
     * recreated Activity can never hand the connection stack a fresh retry budget.
     */
    fun consumeAutoConnectAttempt(): Boolean = autoConnectAttempted.compareAndSet(false, true)

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
    fun setPersistConnectionDiagnostics(value: Boolean) =
        updateSettings { it.copy(persistConnectionDiagnostics = value) }
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

    private fun updateSharedDestinationState(transform: (MainUiState) -> MainUiState) {
        mutableUiState.update(transform)
        sharedDestinationStateStore.persist(mutableUiState.value)
    }

    fun acceptSharedDestination(value: String) {
        val destination = value.normalizedDestinationInput() ?: return
        updateSharedDestinationState { it.withManualSharedDestination(destination) }
    }

    fun queueAutoStartSharedDestination(value: String) {
        val destination = value.normalizedDestinationInput() ?: return
        val request = AutoStartSharedDestinationRequest(
            requestId = sharedDestinationRequestIds.incrementAndGet(),
            destination = destination,
        )
        updateSharedDestinationState { it.withAutoStartSharedDestination(request) }
    }

    fun completeAutoStartSharedDestination(requestId: Long) {
        updateSharedDestinationState { it.withCompletedAutoStartSharedDestination(requestId) }
    }

    fun restoreAutoStartSharedDestination(requestId: Long, errorMessage: String? = null) {
        updateSharedDestinationState { it.withRestoredAutoStartSharedDestination(requestId, errorMessage) }
    }

    fun clearSharedDestination() {
        updateSharedDestinationState { it.copy(sharedDestination = null, sharedDestinationError = null) }
    }

    fun clearTransientMessage() {
        mutableUiState.update { it.copy(transientMessage = null) }
    }

    fun showMessage(message: String) {
        mutableUiState.update { it.copy(transientMessage = message) }
    }

    fun askTftTestConfirmation(message: String) {
        mutableUiState.update { it.copy(tftTestConfirmation = message) }
    }

    fun resolveTftTestConfirmation(displayLooksCorrect: Boolean) {
        mutableUiState.update { state ->
            state.copy(
                tftTestConfirmation = null,
                transientMessage = if (displayLooksCorrect) {
                    "TFT display test completed"
                } else {
                    "TFT display test needs investigation"
                },
            )
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MainViewModel(
                    savedStateHandle = createSavedStateHandle(),
                    apiKeyStore = container.navigationApiKeyStore,
                    navigationSdkGateway = container.navigationSdkGateway,
                    navigationKeyBootstrap = container.navigationKeyBootstrap,
                    bikeConnection = container.bikeConnection,
                    bleCaptureRecorder = container.bleCaptureRecorder,
                    rideRecorder = container.rideRecorder,
                    rideRepository = container.rideRepository,
                    navigationFeed = container.navigationFeed,
                    appSettings = container.appSettings,
                )
            }
        }
    }
}
