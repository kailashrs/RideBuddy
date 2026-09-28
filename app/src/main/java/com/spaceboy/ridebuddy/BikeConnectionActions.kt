package com.spaceboy.ridebuddy

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.spaceboy.ridebuddy.core.companion.AssociatedBike
import com.spaceboy.ridebuddy.domain.BikeConnectionState
import com.spaceboy.ridebuddy.service.BikeConnectionService

/** Shared by the main screen and route preview. Construct before the host Activity starts. */
internal class BikeConnectionActions(
    private val activity: ComponentActivity,
    private val onMessage: (String) -> Unit,
    private val onPermissionResult: () -> Unit = {},
) {
    private val container get() = activity.appContainer
    private var lastAssociationAddress: String? = null

    private val permissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        onPermissionResult()
        if (granted) requestConnection() else onMessage("Allow Nearby devices access")
    }

    private val associationLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val bike = container.bikeCompanionManager.acceptActivityResult(result.resultCode, result.data)
        if (bike != null) connectAssociatedBike(bike)
        else if (result.resultCode == Activity.RESULT_CANCELED) onMessage("Pairing canceled")
    }

    fun requestConnection() {
        if (activity.isFinishing || activity.isDestroyed || connectionBusy()) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        val manager = container.bikeCompanionManager
        if (manager.state.value.associationInProgress) return
        // A fresh user request is a retry; the two callbacks from one pairing are duplicates.
        lastAssociationAddress = null
        val bike = manager.state.value.bike
        if (bike != null) {
            connectAssociatedBike(bike)
        } else if (!manager.state.value.supported) {
            onMessage("This phone doesn't support motorcycle pairing.")
        } else {
            manager.associate(
                launchApproval = {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        associationLauncher.launch(IntentSenderRequest.Builder(it).build())
                    }
                },
                onAssociated = ::connectAssociatedBike,
                onFailure = onMessage,
            )
        }
    }

    private fun connectAssociatedBike(bike: AssociatedBike) {
        if (activity.isFinishing || activity.isDestroyed || connectionBusy() ||
            lastAssociationAddress == bike.address
        ) return
        lastAssociationAddress = bike.address
        if (!BikeConnectionService.reconnect(activity, bike, launchedFromVisibleActivity = true)) {
            onMessage("Couldn't start the connection. Try again.")
        }
    }

    private fun connectionBusy(): Boolean = when (container.bikeConnection.connectionState.value) {
        is BikeConnectionState.Connecting,
        is BikeConnectionState.Authenticating,
        is BikeConnectionState.Connected -> true
        else -> false
    }
}
