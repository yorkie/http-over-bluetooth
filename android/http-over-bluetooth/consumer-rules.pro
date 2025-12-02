# Consumer ProGuard rules
# Keep HTTP over BLE public API
-keep class com.rokid.mlabs.httpoverble.client.HttpOverBleClient { *; }
-keep class com.rokid.mlabs.httpoverble.client.HttpOverBleClientCallback { *; }
-keep class com.rokid.mlabs.httpoverble.client.HttpOverBleError { *; }
-keep class com.rokid.mlabs.httpoverble.server.HttpOverBleServer { *; }
-keep class com.rokid.mlabs.httpoverble.server.HttpOverBleServerCallback { *; }
-keep class com.rokid.mlabs.httpoverble.server.HttpOverBleServerError { *; }
-keep class com.rokid.mlabs.httpoverble.common.** { *; }
