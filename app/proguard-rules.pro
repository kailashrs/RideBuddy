# Navigation SDK and AndroidX ship consumer rules. Keep callback services that
# are instantiated by the platform and the Turn-by-Turn SDK by class name.
-keep class com.spaceboy.ridebuddy.service.NavInfoReceivingService { *; }
-keep class com.spaceboy.ridebuddy.service.BikeNotificationListenerService { *; }
-keep class com.spaceboy.ridebuddy.service.BikeCompanionDeviceService { *; }

# Maps Compose reflects on Google Play Services for optional manifest metadata;
# the referenced class is not present on every device/driver. It is safe to
# silence the warning so R8 doesn't fail the release build.
-dontwarn com.google.android.gms.common.GooglePlayServicesMissingManifestValueException

# Navigation SDK 7.9.0's extension-registry loader reflects a package-private
# implementation in the same package. R8 otherwise repackages this caller,
# causing IllegalAccessException when a GoogleMap is first created.
-keep class com.google.android.libraries.navigation.internal.alt.ax { *; }
