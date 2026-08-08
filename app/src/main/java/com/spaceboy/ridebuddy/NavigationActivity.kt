package com.spaceboy.ridebuddy

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationUpdatesOptions
import com.google.android.libraries.navigation.NavigationView
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoutingOptions
import com.google.android.libraries.navigation.Waypoint
import com.google.android.libraries.navigation.SpeedAlertOptions
import com.google.android.libraries.navigation.SpeedingListener
import com.spaceboy.ridebuddy.service.NavInfoReceivingService
import com.spaceboy.ridebuddy.domain.BikeControlEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

class NavigationActivity : ComponentActivity() {
    private lateinit var navigationView: NavigationView
    private lateinit var statusView: TextView
    private lateinit var retryButton: Button
    private var navigator: Navigator? = null
    private var guidanceStarted = false
    private var navigationEndedByUser = false
    private val arrivalListener = Navigator.ArrivalListener { event ->
        (application as Rs457Application).container.tftNavigationBridge.arrived()
        if (event.isFinalDestination) runOnUiThread { statusView.setText(R.string.navigation_arrived) }
    }
    private val reroutingListener = Navigator.ReroutingListener {
        (application as Rs457Application).container.apply {
            tftNavigationBridge.rerouting()
            val message = "The route is being recalculated; check for changed road conditions"
            if (ridingAlertMonitor.navigationHazard(message)) {
                tftPriorityCoordinator.presentTextAlert("ROUTE ALERT. Recalculating. Check road conditions.")
            }
        }
        runOnUiThread { statusView.setText(R.string.navigation_rerouting) }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (hasRequiredLocationPermissions()) initializeNavigation()
        else showError("Precise location is required for turn-by-turn navigation")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        guidanceStarted = savedInstanceState?.getBoolean(KeyGuidanceStarted, false) == true
        navigationView = NavigationView(this).also { it.onCreate(savedInstanceState) }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = endNavigationAndFinish()
            },
        )
        val isDark =
            (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val cardBgColor = if (isDark) 0xF2271E1D.toInt() else 0xF2FCEAE7.toInt()
        val cardTextColor = if (isDark) 0xFFF0DEDC.toInt() else 0xFF231A19.toInt()
        val cardShape = android.graphics.drawable.GradientDrawable().apply {
            setColor(cardBgColor)
            cornerRadius = 16.dp().toFloat()
        }
        statusView = TextView(this).apply {
            text = getString(R.string.navigation_preparing_route)
            textSize = 15f
            setPadding(16.dp(), 10.dp(), 16.dp(), 10.dp())
            background = cardShape
            setTextColor(cardTextColor)
            elevation = 4.dp().toFloat()
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val closeButton = Button(this).apply {
            text = getString(R.string.navigation_end)
            contentDescription = "End navigation"
            setOnClickListener { endNavigationAndFinish() }
        }
        retryButton = Button(this).apply {
            setText(R.string.navigation_retry)
            visibility = View.GONE
            setOnClickListener {
                if (hasRequiredLocationPermissions()) {
                    navigator?.let(::calculateRoute) ?: initializeNavigation()
                } else {
                    requestLocationOrInitialize()
                }
            }
        }
        val topControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                statusView,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 8.dp()
                },
            )
            addView(
                closeButton,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        val root = FrameLayout(this).apply {
            addView(
                navigationView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            addView(
                topControls,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply {
                        gravity = Gravity.TOP
                    })
            addView(
                retryButton,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply {
                        gravity = Gravity.CENTER
                    })
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            (topControls.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = safeInsets.top + 8.dp()
                leftMargin = safeInsets.left + 12.dp()
                rightMargin = safeInsets.right + 12.dp()
                topControls.layoutParams = this
            }
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        requestLocationOrInitialize()
        lifecycleScope.launch {
            (application as Rs457Application).container.bikeConnection.controls.collect { event ->
                when (event) {
                    BikeControlEvent.ExitNavigation -> endNavigationAndFinish()
                    BikeControlEvent.SkipManeuver -> {
                        val currentNavigator = navigator
                        if ((currentNavigator?.timeAndDistanceList?.size
                                ?: 0) > 1
                        ) currentNavigator?.continueToNextDestination()
                        else navigationView.showRouteOverview()
                    }

                    is BikeControlEvent.CallAction -> Unit
                }
            }
        }
    }

    private fun requestLocationOrInitialize() {
        if (hasRequiredLocationPermissions()) {
            initializeNavigation()
        } else {
            permissionLauncher.launch(LocationPermissions)
        }
    }

    private fun hasRequiredLocationPermissions(): Boolean = LocationPermissions.all { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun initializeNavigation() {
        NavigationApi.getNavigator(this, object : NavigationApi.NavigatorListener {
            override fun onNavigatorReady(readyNavigator: Navigator) {
                if (isFinishing || isDestroyed) {
                    readyNavigator.cleanup()
                    return
                }
                navigator = readyNavigator
                navigationView.isNavigationUiEnabled = true
                val settings = (application as Rs457Application).container.appSettings.settings.value
                navigationView.setTrafficPromptsEnabled(settings.hazardAlerts)
                navigationView.setTrafficIncidentCardsEnabled(settings.hazardAlerts)
                // A Navigator can survive after its previous Activity leaves. Stop that route and
                // unregister its feed before applying the newly confirmed destination.
                if (readyNavigator.isGuidanceRunning) {
                    readyNavigator.stopGuidance()
                    readyNavigator.unregisterServiceForNavUpdates()
                    guidanceStarted = false
                    (application as Rs457Application).container.navigationFeed.clear()
                }
                val options = NavigationUpdatesOptions.builder().setNumNextStepsToPreview(1).build()
                val tftBridge = (application as Rs457Application).container.tftNavigationBridge
                tftBridge.start()
                val navUpdatesRegistered = runCatching {
                    readyNavigator.registerServiceForNavUpdates(
                        packageName,
                        NavInfoReceivingService::class.java.name,
                        options,
                    )
                }.getOrDefault(false)
                if (!navUpdatesRegistered) {
                    tftBridge.stop()
                    Toast.makeText(this@NavigationActivity, "TFT turn updates are unavailable", Toast.LENGTH_LONG)
                        .show()
                }
                readyNavigator.addArrivalListener(arrivalListener)
                readyNavigator.addReroutingListener(reroutingListener)
                readyNavigator.setTaskRemovedBehavior(Navigator.TaskRemovedBehavior.CONTINUE_SERVICE)
                readyNavigator.setAudioGuidance(
                    if (settings.voiceGuidance) Navigator.AudioGuidance.VOICE_ALERTS_AND_GUIDANCE
                    else Navigator.AudioGuidance.SILENT,
                )
                readyNavigator.setSpeedAlertOptions(SpeedAlertOptions(0.05f, 0.15f, 5.0))
                readyNavigator.setSpeedingListener(SpeedingListener { percentageAboveLimit, _ ->
                    val speed = (application as Rs457Application).container.bikeConnection.telemetry.value
                        ?.speedKilometresPerHour ?: return@SpeedingListener
                    if (percentageAboveLimit >= 0f && speed > 0.0) {
                        val limit = (speed / (1.0 + percentageAboveLimit)).div(5.0).toInt().times(5)
                        if (limit > 0) (application as Rs457Application).container.tftNavigationBridge.speedLimit(limit)
                    }
                })
                calculateRoute(readyNavigator)
            }

            override fun onError(errorCode: Int) {
                showError("Navigation could not start ($errorCode)")
            }
        })
    }

    private fun calculateRoute(navigator: Navigator) {
        val latitude = intent.getDoubleExtra(ExtraLatitude, Double.NaN)
        val longitude = intent.getDoubleExtra(ExtraLongitude, Double.NaN)
        if (!latitude.isFinite() || !longitude.isFinite()) {
            showError("The destination is invalid")
            return
        }
        val waypoint = Waypoint.builder()
            .setLatLng(latitude, longitude)
            .setTitle(intent.getStringExtra(ExtraTitle) ?: "Destination")
            .build()
        val preferences = (application as Rs457Application).container.appSettings.settings.value
        val routing = RoutingOptions()
            .travelMode(RoutingOptions.TravelMode.TWO_WHEELER)
            .avoidTolls(preferences.avoidTolls)
            .avoidHighways(preferences.avoidHighways)
            .avoidFerries(preferences.avoidFerries)
        navigator.setDestination(waypoint, routing).setOnResultListener { status ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (status == Navigator.RouteStatus.OK) {
                    retryButton.visibility = View.GONE
                    statusView.text = intent.getStringExtra(ExtraTitle) ?: "Navigation active"
                    navigator.startGuidance()
                    guidanceStarted = true
                } else showError("Route unavailable: ${status.name.replace('_', ' ').lowercase()}")
            }
        }
    }

    private fun showError(message: String) {
        statusView.text = message
        retryButton.visibility = View.VISIBLE
    }

    private fun endNavigationAndFinish() {
        navigationEndedByUser = true
        finish()
    }

    override fun onStart() {
        super.onStart()
        navigationView.onStart()
    }

    override fun onResume() {
        super.onResume()
        navigationView.onResume()
    }

    override fun onPause() {
        navigationView.onPause()
        super.onPause()
    }

    override fun onStop() {
        navigationView.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KeyGuidanceStarted, guidanceStarted || navigator?.isGuidanceRunning == true)
        navigationView.onSaveInstanceState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        navigationView.onConfigurationChanged(newConfig)
    }

    override fun onDestroy() {
        navigator?.removeArrivalListener(arrivalListener)
        navigator?.removeReroutingListener(reroutingListener)
        navigator?.setSpeedingListener(null)
        if (navigationEndedByUser) {
            if (guidanceStarted) navigator?.stopGuidance()
            navigator?.unregisterServiceForNavUpdates()
            navigator?.cleanup()
            (application as Rs457Application).container.apply {
                navigationFeed.clear()
                tftNavigationBridge.stop()
            }
        }
        navigationView.onDestroy()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        navigationView.onTrimMemory(level)
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val ExtraLatitude = "latitude"
        private const val ExtraLongitude = "longitude"
        private const val ExtraTitle = "title"
        private const val KeyGuidanceStarted = "guidance_started"
        private val LocationPermissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        fun intent(context: Context, latitude: Double, longitude: Double, title: String): Intent =
            Intent(context, NavigationActivity::class.java)
                .putExtra(ExtraLatitude, latitude)
                .putExtra(ExtraLongitude, longitude)
                .putExtra(ExtraTitle, title)
    }
}
