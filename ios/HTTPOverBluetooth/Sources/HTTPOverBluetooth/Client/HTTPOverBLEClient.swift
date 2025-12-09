import Foundation
import CoreBluetooth
import os.log

/// Delegate protocol for HTTP over BLE client events.
public protocol HTTPOverBLEClientDelegate: AnyObject {
    /// Called when the client successfully connects to a BLE server.
    /// - Parameter deviceIdentifier: The identifier of the connected device.
    func httpOverBLEClientDidConnect(deviceIdentifier: UUID)
    
    /// Called when the client disconnects from the BLE server.
    /// - Parameter deviceIdentifier: The identifier of the disconnected device.
    func httpOverBLEClientDidDisconnect(deviceIdentifier: UUID)
    
    /// Called when an HTTP response is received from the server.
    /// - Parameter response: The HTTP response received.
    func httpOverBLEClient(didReceiveResponse response: HTTPResponse)
    
    /// Called when an error occurs during BLE operations.
    /// - Parameter error: The error that occurred.
    func httpOverBLEClient(didEncounterError error: HTTPOverBLEClientError)
    
    /// Called when BLE scanning finds an HTTP Proxy Service server.
    /// - Parameters:
    ///   - deviceIdentifier: The identifier of the found device.
    ///   - deviceName: The name of the found device (may be nil).
    func httpOverBLEClient(didFindServer deviceIdentifier: UUID, name deviceName: String?)
}

/// Error types for HTTP over BLE client operations.
public enum HTTPOverBLEClientError: Error, Sendable {
    case connectionFailed(String)
    case serviceNotFound(String)
    case characteristicWriteFailed(String)
    case characteristicReadFailed(String)
    case requestTimeout(String)
    case bluetoothNotEnabled(String)
    case bluetoothUnauthorized(String)
    case unknown(String)
}

/// HTTP over BLE Client implementation.
///
/// This client scans for and connects to BLE devices that implement the HTTP Proxy Service,
/// then sends HTTP requests over BLE and receives responses.
///
/// Usage:
/// ```swift
/// let client = HTTPOverBLEClient()
/// client.delegate = self
///
/// // Start scanning for servers
/// client.startScanning()
///
/// // Connect to a found server
/// client.connect(to: deviceIdentifier)
///
/// // Send HTTP request
/// let request = HTTPRequest(
///     uri: "https://api.example.com/data",
///     method: .get,
///     isHTTPS: true
/// )
/// client.sendRequest(request)
/// ```
public class HTTPOverBLEClient: NSObject {
    
    // MARK: - Constants
    
    private static let defaultMTU = 23
    private static let requestedMTU = 510
    private static let attOverhead = 3
    private static let packetHeaderSize = 1
    private static let flagIsFinal: UInt8 = 0x01
    
    // MARK: - Properties
    
    /// The delegate to receive client events.
    public weak var delegate: HTTPOverBLEClientDelegate?
    
    private var centralManager: CBCentralManager!
    private var connectedPeripheral: CBPeripheral?
    private var discoveredPeripherals: [UUID: CBPeripheral] = [:]
    
    private var isScanning = false
    private var isConnected = false
    
    // MTU tracking
    private var negotiatedMTU: Int = defaultMTU
    
    // Characteristics
    private var uriCharacteristic: CBCharacteristic?
    private var headersCharacteristic: CBCharacteristic?
    private var statusCodeCharacteristic: CBCharacteristic?
    private var bodyCharacteristic: CBCharacteristic?
    private var controlPointCharacteristic: CBCharacteristic?
    private var httpsSecurityCharacteristic: CBCharacteristic?
    
    // Response building
    private var pendingStatusCode: Int = 0
    private var pendingHeaders: [String: String] = [:]
    private var pendingBody: Data?
    private var pendingHTTPS: Bool = false
    private var pendingCertValidated: Bool = false
    
    // Packet reassembly buffers
    private var headersPacketBuffer: Data?
    private var bodyPacketBuffer: Data?
    
    // Write queue for sequential characteristic writes
    private var writeQueue: [(CBCharacteristic, Data)] = []
    private var isWriting = false
    
    // Read queue for sequential characteristic reads
    private var readQueue: [CBCharacteristic] = []
    private var isReading = false
    
    private let logger = Logger(subsystem: "com.example.httpoverble", category: "Client")
    
    // MARK: - Initialization
    
    public override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil)
    }
    
    // MARK: - Public Methods
    
    /// Checks if Bluetooth is enabled and ready.
    public var isBluetoothReady: Bool {
        return centralManager.state == .poweredOn
    }
    
    /// Starts scanning for HTTP Proxy Service servers.
    public func startScanning() {
        guard isBluetoothReady else {
            delegate?.httpOverBLEClient(didEncounterError: .bluetoothNotEnabled("Bluetooth is not enabled"))
            return
        }
        
        guard !isScanning else {
            logger.warning("Already scanning")
            return
        }
        
        centralManager.scanForPeripherals(
            withServices: [HTTPProxyServiceConstants.httpProxyServiceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )
        isScanning = true
        logger.info("Started scanning for HTTP Proxy Service devices")
    }
    
    /// Stops scanning for BLE devices.
    public func stopScanning() {
        guard isScanning else { return }
        
        centralManager.stopScan()
        isScanning = false
        logger.info("Stopped scanning")
    }
    
    /// Connects to a BLE device with the specified identifier.
    /// - Parameter deviceIdentifier: The UUID of the device to connect to.
    public func connect(to deviceIdentifier: UUID) {
        guard isBluetoothReady else {
            delegate?.httpOverBLEClient(didEncounterError: .bluetoothNotEnabled("Bluetooth is not enabled"))
            return
        }
        
        guard let peripheral = discoveredPeripherals[deviceIdentifier] else {
            delegate?.httpOverBLEClient(didEncounterError: .connectionFailed("Device not found: \(deviceIdentifier)"))
            return
        }
        
        stopScanning()
        centralManager.connect(peripheral, options: nil)
        logger.info("Connecting to device: \(deviceIdentifier)")
    }
    
    /// Disconnects from the currently connected BLE device.
    public func disconnect() {
        if let peripheral = connectedPeripheral {
            centralManager.cancelPeripheralConnection(peripheral)
        }
        connectedPeripheral = nil
        isConnected = false
        clearCharacteristics()
    }
    
    /// Sends an HTTP request over BLE.
    /// - Parameter request: The HTTP request to send.
    public func sendRequest(_ request: HTTPRequest) {
        guard isConnected else {
            delegate?.httpOverBLEClient(didEncounterError: .connectionFailed("Not connected to a server"))
            return
        }
        
        // Reset pending response data
        pendingStatusCode = 0
        pendingHeaders = [:]
        pendingBody = nil
        pendingHTTPS = request.isHTTPS
        pendingCertValidated = false
        headersPacketBuffer = nil
        bodyPacketBuffer = nil
        
        // Queue writes with packet splitting
        if let uriCharacteristic = uriCharacteristic {
            let uriData = Data(request.uri.utf8)
            let packets = splitIntoPackets(uriData)
            logger.info("Writing URI in \(packets.count) packet(s)")
            for packet in packets {
                queueWrite(uriCharacteristic, packet)
            }
        }
        
        if !request.headers.isEmpty, let headersCharacteristic = headersCharacteristic {
            let headerData = request.serializeHeaders()
            let packets = splitIntoPackets(headerData)
            logger.info("Writing headers in \(packets.count) packet(s)")
            for packet in packets {
                queueWrite(headersCharacteristic, packet)
            }
        }
        
        if let body = request.body, let bodyCharacteristic = bodyCharacteristic {
            let packets = splitIntoPackets(body)
            logger.info("Writing body in \(packets.count) packet(s)")
            for packet in packets {
                queueWrite(bodyCharacteristic, packet)
            }
        }
        
        if let controlPointCharacteristic = controlPointCharacteristic {
            queueWrite(controlPointCharacteristic, Data([request.opcode]))
        }
        
        logger.info("Request queued: \(request.method.rawValue) \(request.uri)")
    }
    
    /// Cancels the current HTTP request.
    public func cancelRequest() {
        if let controlPointCharacteristic = controlPointCharacteristic {
            queueWrite(controlPointCharacteristic, Data([HTTPProxyServiceConstants.opcodeHTTPRequestCancel]))
        }
    }
    
    /// Releases all resources held by this client.
    public func close() {
        stopScanning()
        disconnect()
    }
    
    // MARK: - Private Methods
    
    private func queueWrite(_ characteristic: CBCharacteristic, _ data: Data) {
        writeQueue.append((characteristic, data))
        processWriteQueue()
    }
    
    private func processWriteQueue() {
        guard !isWriting, !writeQueue.isEmpty else { return }
        
        let (characteristic, data) = writeQueue.removeFirst()
        isWriting = true
        
        connectedPeripheral?.writeValue(data, for: characteristic, type: .withResponse)
    }
    
    private func clearCharacteristics() {
        uriCharacteristic = nil
        headersCharacteristic = nil
        statusCodeCharacteristic = nil
        bodyCharacteristic = nil
        controlPointCharacteristic = nil
        httpsSecurityCharacteristic = nil
    }
    
    private func queueRead(_ characteristic: CBCharacteristic) {
        readQueue.append(characteristic)
        processReadQueue()
    }
    
    private func processReadQueue() {
        guard !isReading, !readQueue.isEmpty else { return }
        
        let characteristic = readQueue.removeFirst()
        isReading = true
        connectedPeripheral?.readValue(for: characteristic)
    }
    
    private func readResponseCharacteristics() {
        if let headersCharacteristic = headersCharacteristic {
            queueRead(headersCharacteristic)
        }
        if let bodyCharacteristic = bodyCharacteristic {
            queueRead(bodyCharacteristic)
        }
        if pendingHTTPS, let httpsSecurityCharacteristic = httpsSecurityCharacteristic {
            queueRead(httpsSecurityCharacteristic)
        }
    }
}

// MARK: - CBCentralManagerDelegate

extension HTTPOverBLEClient: CBCentralManagerDelegate {
    
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            logger.info("Bluetooth is powered on")
        case .poweredOff:
            delegate?.httpOverBLEClient(didEncounterError: .bluetoothNotEnabled("Bluetooth is powered off"))
        case .unauthorized:
            delegate?.httpOverBLEClient(didEncounterError: .bluetoothUnauthorized("Bluetooth is not authorized"))
        case .unsupported:
            delegate?.httpOverBLEClient(didEncounterError: .bluetoothNotEnabled("Bluetooth LE is not supported"))
        default:
            break
        }
    }
    
    public func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String : Any], rssi RSSI: NSNumber) {
        discoveredPeripherals[peripheral.identifier] = peripheral
        delegate?.httpOverBLEClient(didFindServer: peripheral.identifier, name: peripheral.name)
        logger.info("Found device: \(peripheral.identifier) - \(peripheral.name ?? "Unknown")")
    }
    
    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        connectedPeripheral = peripheral
        peripheral.delegate = self
        peripheral.discoverServices([HTTPProxyServiceConstants.httpProxyServiceUUID])
        logger.info("Connected to device: \(peripheral.identifier)")
    }
    
    public func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        delegate?.httpOverBLEClient(didEncounterError: .connectionFailed("Failed to connect: \(error?.localizedDescription ?? "Unknown error")"))
        logger.error("Failed to connect to device: \(error?.localizedDescription ?? "Unknown error")")
    }
    
    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        isConnected = false
        connectedPeripheral = nil
        clearCharacteristics()
        delegate?.httpOverBLEClientDidDisconnect(deviceIdentifier: peripheral.identifier)
        logger.info("Disconnected from device: \(peripheral.identifier)")
    }
}

// MARK: - CBPeripheralDelegate

extension HTTPOverBLEClient: CBPeripheralDelegate {
    
    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil else {
            delegate?.httpOverBLEClient(didEncounterError: .serviceNotFound("Service discovery failed: \(error!.localizedDescription)"))
            return
        }
        
        guard let services = peripheral.services else {
            delegate?.httpOverBLEClient(didEncounterError: .serviceNotFound("No services found"))
            return
        }
        
        for service in services {
            if service.uuid == HTTPProxyServiceConstants.httpProxyServiceUUID {
                peripheral.discoverCharacteristics([
                    HTTPProxyServiceConstants.uriCharacteristicUUID,
                    HTTPProxyServiceConstants.httpHeadersCharacteristicUUID,
                    HTTPProxyServiceConstants.httpStatusCodeCharacteristicUUID,
                    HTTPProxyServiceConstants.httpEntityBodyCharacteristicUUID,
                    HTTPProxyServiceConstants.httpControlPointCharacteristicUUID,
                    HTTPProxyServiceConstants.httpsSecurityCharacteristicUUID
                ], for: service)
            }
        }
    }
    
    public func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard error == nil else {
            delegate?.httpOverBLEClient(didEncounterError: .serviceNotFound("Characteristic discovery failed: \(error!.localizedDescription)"))
            return
        }
        
        guard let characteristics = service.characteristics else { return }
        
        for characteristic in characteristics {
            switch characteristic.uuid {
            case HTTPProxyServiceConstants.uriCharacteristicUUID:
                uriCharacteristic = characteristic
            case HTTPProxyServiceConstants.httpHeadersCharacteristicUUID:
                headersCharacteristic = characteristic
            case HTTPProxyServiceConstants.httpStatusCodeCharacteristicUUID:
                statusCodeCharacteristic = characteristic
                peripheral.setNotifyValue(true, for: characteristic)
            case HTTPProxyServiceConstants.httpEntityBodyCharacteristicUUID:
                bodyCharacteristic = characteristic
            case HTTPProxyServiceConstants.httpControlPointCharacteristicUUID:
                controlPointCharacteristic = characteristic
            case HTTPProxyServiceConstants.httpsSecurityCharacteristicUUID:
                httpsSecurityCharacteristic = characteristic
            default:
                break
            }
        }
        
        // Note: iOS automatically negotiates MTU with the peripheral
        // The maximum MTU on iOS is typically 185 bytes (ATT MTU of 182 + 3)
        // We use the negotiatedMTU (defaulting to 23) for packet splitting
        negotiatedMTU = peripheral.maximumWriteValueLength(for: .withoutResponse) + HTTPOverBLEClient.attOverhead
        logger.info("Using MTU: \(negotiatedMTU) (max write length: \(peripheral.maximumWriteValueLength(for: .withoutResponse)))")
        
        isConnected = true
        delegate?.httpOverBLEClientDidConnect(deviceIdentifier: peripheral.identifier)
        logger.info("HTTP Proxy Service discovered and configured")
    }
    
    public func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        isWriting = false
        
        if let error = error {
            delegate?.httpOverBLEClient(didEncounterError: .characteristicWriteFailed("Failed to write characteristic: \(error.localizedDescription)"))
            logger.error("Characteristic write failed: \(error.localizedDescription)")
        }
        
        // Process next item in queue
        processWriteQueue()
    }
    
    public func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        isReading = false
        
        guard error == nil, let value = characteristic.value else {
            if let error = error {
                delegate?.httpOverBLEClient(didEncounterError: .characteristicReadFailed("Failed to read characteristic: \(error.localizedDescription)"))
            }
            processReadQueue()
            return
        }
        
        switch characteristic.uuid {
        case HTTPProxyServiceConstants.httpStatusCodeCharacteristicUUID:
            pendingStatusCode = HTTPResponse.parseStatusCode(from: value)
            readResponseCharacteristics()
            processReadQueue()
            
        case HTTPProxyServiceConstants.httpHeadersCharacteristicUUID:
            // Packet-based protocol: reassemble packets
            let (accumulated, isFinal) = reassemblePacket(buffer: headersPacketBuffer, newPacket: value)
            headersPacketBuffer = accumulated
            
            if isFinal, let data = accumulated {
                pendingHeaders = HTTPResponse.parseHeaders(from: data)
                logger.info("Read complete headers: \(data.count) bytes")
                headersPacketBuffer = nil
                checkResponseComplete()
                processReadQueue()
            } else if !isFinal {
                logger.debug("Headers packet not final, continue reading: accumulated=\(accumulated?.count ?? 0) bytes")
                // Queue another read to get the next packet
                if let char = headersCharacteristic {
                    queueRead(char)
                }
            }
            
        case HTTPProxyServiceConstants.httpEntityBodyCharacteristicUUID:
            // Packet-based protocol: reassemble packets
            let (accumulated, isFinal) = reassemblePacket(buffer: bodyPacketBuffer, newPacket: value)
            bodyPacketBuffer = accumulated
            
            if isFinal {
                pendingBody = accumulated
                logger.info("Read complete body: \(accumulated?.count ?? 0) bytes")
                bodyPacketBuffer = nil
                checkResponseComplete()
                processReadQueue()
            } else if !isFinal {
                logger.debug("Body packet not final, continue reading: accumulated=\(accumulated?.count ?? 0) bytes")
                // Queue another read to get the next packet
                if let char = bodyCharacteristic {
                    queueRead(char)
                }
            }
            
        case HTTPProxyServiceConstants.httpsSecurityCharacteristicUUID:
            // Security characteristic - single packet
            let (accumulated, _) = reassemblePacket(buffer: nil, newPacket: value)
            pendingCertValidated = accumulated?.count ?? 0 > 0 && accumulated![0] == HTTPProxyServiceConstants.httpsSecurityCertificateValidated
            logger.debug("Read HTTPS security: certified=\(pendingCertValidated)")
            checkResponseComplete()
            processReadQueue()
            
        default:
            processReadQueue()
        }
    }
    
    private func checkResponseComplete() {
        guard pendingStatusCode > 0 else { return }
        
        let response = HTTPResponse(
            statusCode: pendingStatusCode,
            headers: pendingHeaders,
            body: pendingBody,
            isHTTPS: pendingHTTPS,
            certificateValidated: pendingCertValidated
        )
        
        delegate?.httpOverBLEClient(didReceiveResponse: response)
    }
    
    // MARK: - Packet Protocol Helpers
    
    /// Splits data into packets with header bytes indicating if it's the final packet.
    private func splitIntoPackets(_ data: Data) -> [Data] {
        let maxPacketPayload = negotiatedMTU - HTTPOverBLEClient.attOverhead - HTTPOverBLEClient.packetHeaderSize
        guard maxPacketPayload > 0 else {
            logger.error("Invalid MTU configuration: negotiatedMTU=\(self.negotiatedMTU)")
            var packet = Data([HTTPOverBLEClient.flagIsFinal])
            packet.append(data)
            return [packet]
        }
        
        var packets: [Data] = []
        var offset = 0
        
        while offset < data.count {
            let remainingBytes = data.count - offset
            let payloadSize = min(remainingBytes, maxPacketPayload)
            let isFinal = (offset + payloadSize) >= data.count
            
            let header: UInt8 = isFinal ? HTTPOverBLEClient.flagIsFinal : 0x00
            var packet = Data([header])
            packet.append(data[offset..<(offset + payloadSize)])
            
            packets.append(packet)
            offset += payloadSize
            
            logger.debug("Created packet: offset=\(offset), payloadSize=\(payloadSize), isFinal=\(isFinal), totalPackets=\(packets.count)")
        }
        
        return packets
    }
    
    /// Reassembles a received packet, accumulating data until final packet is received.
    /// Returns tuple: (accumulated data, is final)
    private func reassemblePacket(buffer: Data?, newPacket: Data) -> (Data?, Bool) {
        guard !newPacket.isEmpty else {
            logger.warning("Received empty packet")
            return (buffer, false)
        }
        
        let header = newPacket[0]
        let isFinal = (header & HTTPOverBLEClient.flagIsFinal) != 0
        let payload = newPacket.count > 1 ? newPacket[1...] : Data()
        
        let accumulated = buffer != nil ? buffer! + payload : payload
        
        logger.debug("Reassembled packet: payloadSize=\(payload.count), isFinal=\(isFinal), totalSize=\(accumulated.count)")
        
        return (accumulated, isFinal)
    }
}
