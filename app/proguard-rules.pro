# Navigation SDK and AndroidX ship consumer rules. Keep callback services that
# are instantiated by the platform and the Turn-by-Turn SDK by class name.
-keep class com.spaceboy.ridebuddy.service.NavInfoReceivingService { *; }
-keep class com.spaceboy.ridebuddy.service.BikeNotificationListenerService { *; }
-keep class com.spaceboy.ridebuddy.service.BikeCompanionDeviceService { *; }

# Navigation SDK 7.8.0 instantiates this bundled Maps entry point by its exact
# class name and no-argument constructor. Keep both when R8 optimizes the app.
-keep class com.google.android.gms.maps.internal.CreatorImpl {
    public <init>();
}

# The generated protobuf registry is package-private and loaded reflectively by
# this class. Prevent R8 from moving the loader out of the registry's package.
-keep class com.google.android.libraries.navigation.internal.als.ax { *; }
