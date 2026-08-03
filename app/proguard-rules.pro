# Navigation SDK and AndroidX ship consumer rules. Keep callback services that
# are instantiated by the platform and the Turn-by-Turn SDK by class name.
-keep class com.spaceboy.ridebuddy.service.NavInfoReceivingService { *; }
-keep class com.spaceboy.ridebuddy.service.BikeNotificationListenerService { *; }
-keep class com.spaceboy.ridebuddy.service.BikeCompanionDeviceService { *; }
