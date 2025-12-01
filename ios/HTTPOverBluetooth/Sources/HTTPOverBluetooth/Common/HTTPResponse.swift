import Foundation

/// Represents an HTTP response received over BLE.
public struct HTTPResponse: Sendable {
    /// The HTTP status code
    public let statusCode: Int
    
    /// HTTP response headers
    public let headers: [String: String]
    
    /// Response body data
    public let body: Data?
    
    /// Whether this was an HTTPS response
    public let isHTTPS: Bool
    
    /// Whether the certificate was validated (for HTTPS)
    public let certificateValidated: Bool
    
    /// Creates a new HTTP response.
    /// - Parameters:
    ///   - statusCode: The HTTP status code
    ///   - headers: HTTP response headers
    ///   - body: Response body data
    ///   - isHTTPS: Whether this was an HTTPS response
    ///   - certificateValidated: Whether the certificate was validated
    public init(
        statusCode: Int,
        headers: [String: String] = [:],
        body: Data? = nil,
        isHTTPS: Bool = false,
        certificateValidated: Bool = false
    ) {
        self.statusCode = statusCode
        self.headers = headers
        self.body = body
        self.isHTTPS = isHTTPS
        self.certificateValidated = certificateValidated
    }
    
    /// Parses HTTP headers from Data.
    /// - Parameter data: The data containing headers
    /// - Returns: A dictionary of header key-value pairs
    public static func parseHeaders(from data: Data) -> [String: String] {
        guard let headerString = String(data: data, encoding: .utf8) else {
            return [:]
        }
        
        var headers: [String: String] = [:]
        let lines = headerString.components(separatedBy: "\r\n")
        
        for line in lines {
            if let colonIndex = line.firstIndex(of: ":") {
                let key = String(line[..<colonIndex]).trimmingCharacters(in: .whitespaces)
                let value = String(line[line.index(after: colonIndex)...]).trimmingCharacters(in: .whitespaces)
                headers[key] = value
            }
        }
        
        return headers
    }
    
    /// Parses HTTP status code from Data.
    /// The status code is encoded as a 16-bit unsigned integer (little-endian).
    /// - Parameter data: The data containing the status code
    /// - Returns: The HTTP status code
    public static func parseStatusCode(from data: Data) -> Int {
        guard data.count >= 2 else { return 0 }
        return Int(data[0]) | (Int(data[1]) << 8)
    }
    
    /// Serializes status code to Data for transmission.
    /// - Parameter statusCode: The HTTP status code
    /// - Returns: The status code as Data
    public static func serializeStatusCode(_ statusCode: Int) -> Data {
        var data = Data(count: 2)
        data[0] = UInt8(statusCode & 0xFF)
        data[1] = UInt8((statusCode >> 8) & 0xFF)
        return data
    }
}
