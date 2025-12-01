package com.example.httpoverble.client

import com.example.httpoverble.common.HttpRequest
import com.example.httpoverble.common.HttpResponse

/**
 * Callback interface for HTTP over BLE client operations.
 */
interface HttpOverBleClientCallback {
    
    /**
     * Called when the client successfully connects to a BLE server.
     * @param deviceAddress The Bluetooth address of the connected device.
     */
    fun onConnected(deviceAddress: String)
    
    /**
     * Called when the client disconnects from the BLE server.
     * @param deviceAddress The Bluetooth address of the disconnected device.
     */
    fun onDisconnected(deviceAddress: String)
    
    /**
     * Called when an HTTP response is received from the server.
     * @param response The HTTP response received.
     */
    fun onResponseReceived(response: HttpResponse)
    
    /**
     * Called when an error occurs during BLE operations.
     * @param error The error that occurred.
     */
    fun onError(error: HttpOverBleError)
    
    /**
     * Called when BLE scanning finds an HTTP Proxy Service server.
     * @param deviceAddress The Bluetooth address of the found device.
     * @param deviceName The name of the found device (may be null).
     */
    fun onServerFound(deviceAddress: String, deviceName: String?)
}

/**
 * Error types for HTTP over BLE operations.
 */
sealed class HttpOverBleError(val message: String) {
    class ConnectionFailed(message: String) : HttpOverBleError(message)
    class ServiceNotFound(message: String) : HttpOverBleError(message)
    class CharacteristicWriteFailed(message: String) : HttpOverBleError(message)
    class CharacteristicReadFailed(message: String) : HttpOverBleError(message)
    class RequestTimeout(message: String) : HttpOverBleError(message)
    class BluetoothNotEnabled(message: String) : HttpOverBleError(message)
    class PermissionDenied(message: String) : HttpOverBleError(message)
    class Unknown(message: String) : HttpOverBleError(message)
}
