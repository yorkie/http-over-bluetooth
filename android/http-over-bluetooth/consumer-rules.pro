# Consumer ProGuard rules
# Keep HTTP over BLE public API
-keep class com.example.httpoverble.client.HttpOverBleClient { *; }
-keep class com.example.httpoverble.client.HttpOverBleClientCallback { *; }
-keep class com.example.httpoverble.client.HttpOverBleError { *; }
-keep class com.example.httpoverble.server.HttpOverBleServer { *; }
-keep class com.example.httpoverble.server.HttpOverBleServerCallback { *; }
-keep class com.example.httpoverble.server.HttpOverBleServerError { *; }
-keep class com.example.httpoverble.common.** { *; }
