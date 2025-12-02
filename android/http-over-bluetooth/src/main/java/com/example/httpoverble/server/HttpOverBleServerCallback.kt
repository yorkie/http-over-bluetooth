package com.example.httpoverble.server

import com.example.httpoverble.common.HttpRequest
import com.example.httpoverble.common.HttpResponse

/**
 * Callback interface for HTTP over BLE server operations.
 */
interface HttpOverBleServerCallback {
    
    /**
     * Called when the server starts advertising.
     */
    fun onServerStarted()
    
    /**
     * Called when the server stops advertising.
     */
    fun onServerStopped()
    
    /**
     * Called when a client connects to the server.
     * @param deviceAddress The Bluetooth address of the connected client.
     */
    fun onClientConnected(deviceAddress: String)
    
    /**
     * Called when a client disconnects from the server.
     * @param deviceAddress The Bluetooth address of the disconnected client.
     */
    fun onClientDisconnected(deviceAddress: String)
    
    /**
     * Called when an HTTP request is received from a client.
     * @param request The HTTP request received.
     * @param clientAddress The Bluetooth address of the client that sent the request.
     */
    fun onRequestReceived(request: HttpRequest, clientAddress: String)
    
    /**
     * Called when an HTTP response is about to be sent to a client.
     * @param response The HTTP response being sent.
     * @param clientAddress The Bluetooth address of the client.
     */
    fun onResponseSent(response: HttpResponse, clientAddress: String)
    
    /**
     * Called when an error occurs during server operations.
     * @param error The error that occurred.
     */
    fun onError(error: HttpOverBleServerError)
}

/**
 * Error types for HTTP over BLE server operations.
 */
sealed class HttpOverBleServerError(val message: String) {
    class AdvertisingFailed(message: String) : HttpOverBleServerError(message)
    class ServiceSetupFailed(message: String) : HttpOverBleServerError(message)
    class HttpRequestFailed(message: String) : HttpOverBleServerError(message)
    class ResponseSendFailed(message: String) : HttpOverBleServerError(message)
    class BluetoothNotEnabled(message: String) : HttpOverBleServerError(message)
    class PermissionDenied(message: String) : HttpOverBleServerError(message)
    class Unknown(message: String) : HttpOverBleServerError(message)
}
