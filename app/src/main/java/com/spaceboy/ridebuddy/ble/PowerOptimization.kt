package com.spaceboy.ridebuddy.ble

import android.content.Context
import android.os.PowerManager

/**
 * True when RideBuddy is on the system battery-optimization allowlist. Vivo X200 Ultra's
 * Power Engine Manager (`com.vivo.pem`) and similar OEM background-killers aggressively
 * suspend apps that aren't on this allowlist, which tears down the GATT socket the
 * connection service is holding. Surfacing this state lets the rider see why a connection
 * keeps dropping.
 */
internal fun isRideBuddyIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    // API 35+ requires a package name; we want the app-wide exemption status, so use the
    // package whose allowlist state is being queried. PowerManager.isIgnoringBatteryOptimizations
    // without arguments is deprecated but still functional on supported Android versions.
    @Suppress("DEPRECATION")
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}