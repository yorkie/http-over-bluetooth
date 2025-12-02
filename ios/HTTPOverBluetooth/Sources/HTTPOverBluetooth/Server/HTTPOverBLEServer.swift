import Foundation
import CoreBluetooth
import os.log

/// Delegate protocol for HTTP over BLE server events.
public protocol HTTPOverBLEServerDelegate: AnyObject {
    /// Called when the server starts advertising.
    func httpOverBLEServerDidStart()
    
    /// Called when the server stops advertising.
    func httpOverBLEServerDidStop()
    
    /// Called when a client connects to the server.
    /// - Parameter deviceIdentifier: The identifier of the connected client.
    func httpOverBLEServer(didConnectClient deviceIdentifier: UUID)
    
    /// Called when a client disconnects from the server.
    /// - Parameter deviceIdentifier: The identifier of the disconnected client.
    func httpOverBLEServer(didDisconnectClient deviceIdentifier: UUID)
    
    /// Called when an HTTP request is received from a client.
    /// - Parameters:
    ///   - request: The HTTP request received.
    ///   - clientIdentifier: The identifier of the client that sent the request.
    func httpOverBLEServer(didReceiveRequest request: HTTPRequest, from clientIdentifier: UUID)
    
    /// Called when an HTTP response is about to be sent to a client.
    /// - Parameters:
    ///   - response: The HTTP response being sent.
    ///   - clientIdentifier: The identifier of the client.
    func httpOverBLEServer(didSendResponse response: HTTPResponse, to clientIdentifier: UUID)
    
    /// Called when an error occurs during server operations.
    /// - Parameter error: The error that occurred.
    func httpOverBLEServer(didEncounterError error: HTTPOverBLEServerError)
}

/// Error types for HTTP over BLE server operations.
public enum HTTPOverBLEServerError: Error, Sendable {
    case advertisingFailed(String)
    case serviceSetupFailed(String)
    case httpRequestFailed(String)
    case responseSendFailed(String)
    case bluetoothNotEnabled(String)
    case bluetoothUnauthorized(String)
    case unknown(String)
}

/// HTTP over BLE Server implementation.
///
/// This server advertises the HTTP Proxy Service, receives HTTP requests from BLE clients,
/// executes them over WiFi/5G network, and sends responses back to the clients.
///
/// Usage:
/// ```swift
/// let server = HTTPOverBLEServer()
/// server.delegate = self
///
/// // Start the server
/// server.start()
///
/// // Stop the server
/// server.stop()
/// ```
public class HTTPOverBLEServer: NSObject {
    
    // MARK: - Properties
    
    /// The delegate to receive server events.
    public weak var delegate: HTTPOverBLEServerDelegate?
    
    private var peripheralManager: CBPeripheralManager!
    private var httpProxyService: CBMutableService?
    
    private var isAdvertising = false
    
    // Characteristics
    private var uriCharacteristic: CBMutableCharacteristic?
    private var headersCharacteristic: CBMutableCharacteristic?
    private var statusCodeCharacteristic: CBMutableCharacteristic?
    private var bodyCharacteristic: CBMutableCharacteristic?
    private var controlPointCharacteristic: CBMutableCharacteristic?
    private var httpsSecurityCharacteristic: CBMutableCharacteristic?
    
    // Connected clients
    private var connectedClients: Set<CBCentral> = []
    
    // Pending request data per client
    private var clientRequestData: [UUID: ClientRequestData] = [:]
    
    // URLSession for making actual HTTP requests
    private lazy var urlSession: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 60
        return URLSession(configuration: config)
    }()
    
    private let logger = Logger(subsystem: "com.example.httpoverble", category: "Server")
    
    private struct ClientRequestData {
        var uri: String?
        var headers: [String: String] = [:]
        var body: Data?
    }
    
    // MARK: - Initialization
    
    public override init() {
        super.init()
        peripheralManager = CBPeripheralManager(delegate: self, queue: nil)
    }
    
    // MARK: - Public Methods
    
    /// Checks if Bluetooth is enabled and ready.
    public var isBluetoothReady: Bool {
        return peripheralManager.state == .poweredOn
    }
    
    /// Starts the HTTP Proxy Service server.
    public func start() {
        guard isBluetoothReady else {
            delegate?.httpOverBLEServer(didEncounterError: .bluetoothNotEnabled("Bluetooth is not enabled"))
            return
        }
        
        guard !isAdvertising else {
            logger.warning("Server is already running")
            return
        }
        
        setupService()
    }
    
    /// Stops the HTTP Proxy Service server.
    public func stop() {
        stopAdvertising()
        removeService()
        delegate?.httpOverBLEServerDidStop()
    }
    
    /// Releases all resources held by this server.
    public func close() {
        stop()
        urlSession.invalidateAndCancel()
    }
    
    // MARK: - Private Methods
    
    private func setupService() {
        // Create characteristics
        uriCharacteristic = CBMutableCharacteristic(
            type: HTTPProxyServiceConstants.uriCharacteristicUUID,
            properties: .write,
            value: nil,
            permissions: .writeable
        )
        
        headersCharacteristic = CBMutableCharacteristic(
            type: HTTPProxyServiceConstants.httpHeadersCharacteristicUUID,
            properties: [.read, .write],
            value: nil,
            permissions: [.readable, .writeable]
        )
        
        statusCodeCharacteristic = CBMutableCharacteristic(
            type: HTTPProxyServiceConstants.httpStatusCodeCharacteristicUUID,
            properties: [.read, .notify],
            value: nil,
            permissions: .readable
        )
        
        bodyCharacteristic = CBMutableCharacteristic(
            type: HTTPProxyServiceConstants.httpEntityBodyCharacteristicUUID,
            properties: [.read, .write],
            value: nil,
            permissions: [.readable, .writeable]
        )
        
        controlPointCharacteristic = CBMutableCharacteristic(
            type: HTTPProxyServiceConstants.httpControlPointCharacteristicUUID,
            properties: .write,
            value: nil,
            permissions: .writeable
        )
        
        httpsSecurityCharacteristic = CBMutableCharacteristic(
            type: HTTPProxyServiceConstants.httpsSecurityCharacteristicUUID,
            properties: .read,
            value: nil,
            permissions: .readable
        )
        
        // Create service
        httpProxyService = CBMutableService(
            type: HTTPProxyServiceConstants.httpProxyServiceUUID,
            primary: true
        )
        
        httpProxyService?.characteristics = [
            uriCharacteristic!,
            headersCharacteristic!,
            statusCodeCharacteristic!,
            bodyCharacteristic!,
            controlPointCharacteristic!,
            httpsSecurityCharacteristic!
        ]
        
        peripheralManager.add(httpProxyService!)
    }
    
    private func startAdvertising() {
        let advertisementData: [String: Any] = [
            CBAdvertisementDataServiceUUIDsKey: [HTTPProxyServiceConstants.httpProxyServiceUUID],
            CBAdvertisementDataLocalNameKey: "HTTP Proxy"
        ]
        
        peripheralManager.startAdvertising(advertisementData)
        logger.info("Started advertising HTTP Proxy Service")
    }
    
    private func stopAdvertising() {
        guard isAdvertising else { return }
        
        peripheralManager.stopAdvertising()
        isAdvertising = false
        logger.info("Stopped advertising")
    }
    
    private func removeService() {
        if let service = httpProxyService {
            peripheralManager.remove(service)
        }
        httpProxyService = nil
        connectedClients.removeAll()
        clientRequestData.removeAll()
    }
    
    private func handleControlPoint(_ central: CBCentral, opcode: UInt8) {
        if opcode == HTTPProxyServiceConstants.opcodeHTTPRequestCancel {
            logger.info("Request cancelled by client")
            return
        }
        
        guard let clientData = clientRequestData[central.identifier],
              let uri = clientData.uri else {
            logger.error("No URI set for request")
            return
        }
        
        let method: HTTPMethod
        let isHTTPS: Bool
        
        switch opcode {
        case HTTPProxyServiceConstants.opcodeHTTPGetRequest:
            method = .get
            isHTTPS = false
        case HTTPProxyServiceConstants.opcodeHTTPSGetRequest:
            method = .get
            isHTTPS = true
        case HTTPProxyServiceConstants.opcodeHTTPHeadRequest:
            method = .head
            isHTTPS = false
        case HTTPProxyServiceConstants.opcodeHTTPSHeadRequest:
            method = .head
            isHTTPS = true
        case HTTPProxyServiceConstants.opcodeHTTPPostRequest:
            method = .post
            isHTTPS = false
        case HTTPProxyServiceConstants.opcodeHTTPSPostRequest:
            method = .post
            isHTTPS = true
        case HTTPProxyServiceConstants.opcodeHTTPPutRequest:
            method = .put
            isHTTPS = false
        case HTTPProxyServiceConstants.opcodeHTTPSPutRequest:
            method = .put
            isHTTPS = true
        case HTTPProxyServiceConstants.opcodeHTTPDeleteRequest:
            method = .delete
            isHTTPS = false
        case HTTPProxyServiceConstants.opcodeHTTPSDeleteRequest:
            method = .delete
            isHTTPS = true
        default:
            logger.error("Unknown opcode: \(opcode)")
            return
        }
        
        let request = HTTPRequest(
            uri: uri,
            method: method,
            headers: clientData.headers,
            body: clientData.body,
            isHTTPS: isHTTPS
        )
        
        delegate?.httpOverBLEServer(didReceiveRequest: request, from: central.identifier)
        
        // Execute HTTP request
        executeHTTPRequest(central, request: request)
    }
    
    private func executeHTTPRequest(_ central: CBCentral, request: HTTPRequest) {
        guard let url = URL(string: request.uri) else {
            logger.error("Invalid URL: \(request.uri)")
            sendErrorResponse(central, statusCode: 400, message: "Invalid URL", isHTTPS: request.isHTTPS)
            return
        }
        
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = request.method.rawValue
        
        // Add headers
        for (key, value) in request.headers {
            urlRequest.setValue(value, forHTTPHeaderField: key)
        }
        
        // Set body
        urlRequest.httpBody = request.body
        
        logger.info("Executing HTTP request: \(request.method.rawValue) \(request.uri)")
        
        let task = urlSession.dataTask(with: urlRequest) { [weak self] data, response, error in
            guard let self = self else { return }
            
            if let error = error {
                self.logger.error("HTTP request failed: \(error.localizedDescription)")
                self.delegate?.httpOverBLEServer(didEncounterError: .httpRequestFailed("HTTP request failed: \(error.localizedDescription)"))
                self.sendErrorResponse(central, statusCode: 500, message: error.localizedDescription, isHTTPS: request.isHTTPS)
                return
            }
            
            guard let httpResponse = response as? HTTPURLResponse else {
                self.sendErrorResponse(central, statusCode: 500, message: "Invalid response", isHTTPS: request.isHTTPS)
                return
            }
            
            var responseHeaders: [String: String] = [:]
            for (key, value) in httpResponse.allHeaderFields {
                if let keyString = key as? String, let valueString = value as? String {
                    responseHeaders[keyString] = valueString
                }
            }
            
            let response = HTTPResponse(
                statusCode: httpResponse.statusCode,
                headers: responseHeaders,
                body: data,
                isHTTPS: request.isHTTPS,
                certificateValidated: request.isHTTPS // Assume validated if request succeeded
            )
            
            self.sendResponse(central, response: response)
            self.delegate?.httpOverBLEServer(didSendResponse: response, to: central.identifier)
            
            self.logger.info("HTTP response: \(httpResponse.statusCode)")
        }
        
        task.resume()
    }
    
    private func sendResponse(_ central: CBCentral, response: HTTPResponse) {
        // Update characteristic values
        let statusCodeData = HTTPResponse.serializeStatusCode(response.statusCode)
        statusCodeCharacteristic?.value = statusCodeData
        
        let headerString = response.headers.map { "\($0.key): \($0.value)" }.joined(separator: "\r\n")
        headersCharacteristic?.value = Data(headerString.utf8)
        
        bodyCharacteristic?.value = response.body
        
        if response.isHTTPS {
            let securityValue = response.certificateValidated
                ? HTTPProxyServiceConstants.httpsSecurityCertificateValidated
                : HTTPProxyServiceConstants.httpsSecurityCertificateNotValidated
            httpsSecurityCharacteristic?.value = Data([securityValue])
        }
        
        // Notify client about the status code
        if let characteristic = statusCodeCharacteristic {
            peripheralManager.updateValue(statusCodeData, for: characteristic, onSubscribedCentrals: [central])
        }
        
        logger.info("Response sent to client: \(response.statusCode)")
    }
    
    private func sendErrorResponse(_ central: CBCentral, statusCode: Int, message: String, isHTTPS: Bool) {
        let response = HTTPResponse(
            statusCode: statusCode,
            headers: [:],
            body: Data(message.utf8),
            isHTTPS: isHTTPS,
            certificateValidated: false
        )
        sendResponse(central, response: response)
    }
}

// MARK: - CBPeripheralManagerDelegate

extension HTTPOverBLEServer: CBPeripheralManagerDelegate {
    
    public func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        switch peripheral.state {
        case .poweredOn:
            logger.info("Bluetooth is powered on")
        case .poweredOff:
            delegate?.httpOverBLEServer(didEncounterError: .bluetoothNotEnabled("Bluetooth is powered off"))
        case .unauthorized:
            delegate?.httpOverBLEServer(didEncounterError: .bluetoothUnauthorized("Bluetooth is not authorized"))
        case .unsupported:
            delegate?.httpOverBLEServer(didEncounterError: .bluetoothNotEnabled("Bluetooth LE is not supported"))
        default:
            break
        }
    }
    
    public func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error = error {
            logger.error("Failed to add service: \(error.localizedDescription)")
            delegate?.httpOverBLEServer(didEncounterError: .serviceSetupFailed("Failed to add service: \(error.localizedDescription)"))
            return
        }
        
        logger.info("Service added successfully")
        startAdvertising()
    }
    
    public func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error = error {
            logger.error("Failed to start advertising: \(error.localizedDescription)")
            delegate?.httpOverBLEServer(didEncounterError: .advertisingFailed("Failed to start advertising: \(error.localizedDescription)"))
            return
        }
        
        isAdvertising = true
        delegate?.httpOverBLEServerDidStart()
        logger.info("Advertising started successfully")
    }
    
    public func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        connectedClients.insert(central)
        clientRequestData[central.identifier] = ClientRequestData()
        delegate?.httpOverBLEServer(didConnectClient: central.identifier)
        logger.info("Client connected: \(central.identifier)")
    }
    
    public func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        connectedClients.remove(central)
        clientRequestData.removeValue(forKey: central.identifier)
        delegate?.httpOverBLEServer(didDisconnectClient: central.identifier)
        logger.info("Client disconnected: \(central.identifier)")
    }
    
    public func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        guard let characteristic = getCharacteristic(for: request.characteristic.uuid) else {
            peripheral.respond(to: request, withResult: .attributeNotFound)
            return
        }
        
        if let value = characteristic.value {
            let offset = request.offset
            if offset < value.count {
                request.value = value.subdata(in: offset..<value.count)
                peripheral.respond(to: request, withResult: .success)
            } else {
                peripheral.respond(to: request, withResult: .invalidOffset)
            }
        } else {
            request.value = Data()
            peripheral.respond(to: request, withResult: .success)
        }
    }
    
    public func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            guard let value = request.value else {
                peripheral.respond(to: request, withResult: .invalidAttributeValueLength)
                continue
            }
            
            var clientData = clientRequestData[request.central.identifier] ?? ClientRequestData()
            
            switch request.characteristic.uuid {
            case HTTPProxyServiceConstants.uriCharacteristicUUID:
                clientData.uri = String(data: value, encoding: .utf8)
                logger.info("Received URI: \(clientData.uri ?? "nil")")
                
            case HTTPProxyServiceConstants.httpHeadersCharacteristicUUID:
                clientData.headers = HTTPResponse.parseHeaders(from: value)
                headersCharacteristic?.value = value
                logger.info("Received headers: \(clientData.headers)")
                
            case HTTPProxyServiceConstants.httpEntityBodyCharacteristicUUID:
                clientData.body = value
                bodyCharacteristic?.value = value
                logger.info("Received body: \(value.count) bytes")
                
            case HTTPProxyServiceConstants.httpControlPointCharacteristicUUID:
                if !value.isEmpty {
                    clientRequestData[request.central.identifier] = clientData
                    handleControlPoint(request.central, opcode: value[0])
                }
                
            default:
                break
            }
            
            clientRequestData[request.central.identifier] = clientData
            peripheral.respond(to: request, withResult: .success)
        }
    }
    
    private func getCharacteristic(for uuid: CBUUID) -> CBMutableCharacteristic? {
        switch uuid {
        case HTTPProxyServiceConstants.uriCharacteristicUUID:
            return uriCharacteristic
        case HTTPProxyServiceConstants.httpHeadersCharacteristicUUID:
            return headersCharacteristic
        case HTTPProxyServiceConstants.httpStatusCodeCharacteristicUUID:
            return statusCodeCharacteristic
        case HTTPProxyServiceConstants.httpEntityBodyCharacteristicUUID:
            return bodyCharacteristic
        case HTTPProxyServiceConstants.httpControlPointCharacteristicUUID:
            return controlPointCharacteristic
        case HTTPProxyServiceConstants.httpsSecurityCharacteristicUUID:
            return httpsSecurityCharacteristic
        default:
            return nil
        }
    }
}
