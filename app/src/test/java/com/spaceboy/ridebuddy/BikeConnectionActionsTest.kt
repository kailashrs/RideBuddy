package com.spaceboy.ridebuddy

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = RideBuddyApplication::class)
class BikeConnectionActionsTest {
    @Test fun permissionDenialReportsAnActionableMessageWithoutStartingAConnection() {
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        val activity = controller.get()
        shadowOf(activity.application).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        activity.actions.requestConnection()
        val request = requireNotNull(shadowOf(activity).lastRequestedPermission)
        assertArrayEquals(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), request.requestedPermissions)
        activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK,
            Intent()
                .putExtra(RequestMultiplePermissions.EXTRA_PERMISSIONS, request.requestedPermissions)
                .putExtra(RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS,
                    intArrayOf(PackageManager.PERMISSION_DENIED)))
        assertEquals(listOf("Allow Nearby devices access"), activity.messages)
        assertEquals(1, activity.permissionUpdates)
        assertNull(shadowOf(activity.application).nextStartedService)
        controller.pause().stop().destroy()
    }

    @Test fun alreadyGrantedPermissionDoesNotPromptAgain() {
        val controller = Robolectric.buildActivity(Host::class.java).setup()
        val activity = controller.get()
        shadowOf(activity.application).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        activity.actions.requestConnection()
        assertNull(shadowOf(activity).lastRequestedPermission)
        assertEquals(0, activity.permissionUpdates)
        assertNull(shadowOf(activity.application).nextStartedService)
        controller.pause().stop().destroy()
    }

    class Host : ComponentActivity() {
        val messages = mutableListOf<String>()
        var permissionUpdates = 0
        internal val actions = BikeConnectionActions(this, { messages += it }, { permissionUpdates++ })
    }
}
