import Foundation
import CoreBluetooth

/// Constants for HTTP Proxy Service based on Bluetooth HPS 1.0 specification.
/// Reference: https://www.bluetooth.com/specifications/specs/http-proxy-service-1-0/
public enum HTTPProxyServiceConstants {
    
    // MARK: - Service UUID
    
    /// HTTP Proxy Service UUID
    public static let httpProxyServiceUUID = CBUUID(string: "00001823-0000-1000-8000-00805f9b34fb")
    
    // MARK: - Characteristic UUIDs
    
    /// URI Characteristic UUID
    public static let uriCharacteristicUUID = CBUUID(string: "00002ab6-0000-1000-8000-00805f9b34fb")
    
    /// HTTP Headers Characteristic UUID
    public static let httpHeadersCharacteristicUUID = CBUUID(string: "00002ab7-0000-1000-8000-00805f9b34fb")
    
    /// HTTP Status Code Characteristic UUID
    public static let httpStatusCodeCharacteristicUUID = CBUUID(string: "00002ab8-0000-1000-8000-00805f9b34fb")
    
    /// HTTP Entity Body Characteristic UUID
    public static let httpEntityBodyCharacteristicUUID = CBUUID(string: "00002ab9-0000-1000-8000-00805f9b34fb")
    
    /// HTTP Control Point Characteristic UUID
    public static let httpControlPointCharacteristicUUID = CBUUID(string: "00002aba-0000-1000-8000-00805f9b34fb")
    
    /// HTTPS Security Characteristic UUID
    public static let httpsSecurityCharacteristicUUID = CBUUID(string: "00002abb-0000-1000-8000-00805f9b34fb")
    
    // MARK: - HTTP Control Point Opcodes
    
    /// HTTP GET Request opcode
    public static let opcodeHTTPGetRequest: UInt8 = 0x01
    
    /// HTTP HEAD Request opcode
    public static let opcodeHTTPHeadRequest: UInt8 = 0x02
    
    /// HTTP POST Request opcode
    public static let opcodeHTTPPostRequest: UInt8 = 0x03
    
    /// HTTP PUT Request opcode
    public static let opcodeHTTPPutRequest: UInt8 = 0x04
    
    /// HTTP DELETE Request opcode
    public static let opcodeHTTPDeleteRequest: UInt8 = 0x05
    
    /// HTTPS GET Request opcode
    public static let opcodeHTTPSGetRequest: UInt8 = 0x06
    
    /// HTTPS HEAD Request opcode
    public static let opcodeHTTPSHeadRequest: UInt8 = 0x07
    
    /// HTTPS POST Request opcode
    public static let opcodeHTTPSPostRequest: UInt8 = 0x08
    
    /// HTTPS PUT Request opcode
    public static let opcodeHTTPSPutRequest: UInt8 = 0x09
    
    /// HTTPS DELETE Request opcode
    public static let opcodeHTTPSDeleteRequest: UInt8 = 0x0A
    
    /// HTTP Request Cancel opcode
    public static let opcodeHTTPRequestCancel: UInt8 = 0x0B
    
    // MARK: - HTTPS Security Values
    
    /// Certificate not validated
    public static let httpsSecurityCertificateNotValidated: UInt8 = 0x00
    
    /// Certificate validated
    public static let httpsSecurityCertificateValidated: UInt8 = 0x01
    
    // MARK: - Maximum Data Sizes
    
    /// Maximum URI length
    public static let maxURILength = 512
    
    /// Maximum headers length
    public static let maxHeadersLength = 512
    
    /// Maximum body length
    public static let maxBodyLength = 512
}
