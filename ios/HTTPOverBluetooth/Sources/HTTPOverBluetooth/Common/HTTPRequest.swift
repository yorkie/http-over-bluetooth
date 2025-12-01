import Foundation

/// HTTP methods supported by the HTTP Proxy Service.
public enum HTTPMethod: String, Sendable {
    case get = "GET"
    case head = "HEAD"
    case post = "POST"
    case put = "PUT"
    case delete = "DELETE"
}

/// Represents an HTTP request to be sent over BLE.
public struct HTTPRequest: Sendable {
    /// The URI of the request
    public let uri: String
    
    /// The HTTP method
    public let method: HTTPMethod
    
    /// HTTP headers
    public let headers: [String: String]
    
    /// Request body data
    public let body: Data?
    
    /// Whether this is an HTTPS request
    public let isHTTPS: Bool
    
    /// Creates a new HTTP request.
    /// - Parameters:
    ///   - uri: The URI of the request
    ///   - method: The HTTP method
    ///   - headers: HTTP headers (default: empty)
    ///   - body: Request body data (default: nil)
    ///   - isHTTPS: Whether this is an HTTPS request (default: false)
    public init(
        uri: String,
        method: HTTPMethod,
        headers: [String: String] = [:],
        body: Data? = nil,
        isHTTPS: Bool = false
    ) {
        self.uri = uri
        self.method = method
        self.headers = headers
        self.body = body
        self.isHTTPS = isHTTPS
    }
    
    /// Gets the control point opcode for this request.
    public var opcode: UInt8 {
        if isHTTPS {
            switch method {
            case .get:
                return HTTPProxyServiceConstants.opcodeHTTPSGetRequest
            case .head:
                return HTTPProxyServiceConstants.opcodeHTTPSHeadRequest
            case .post:
                return HTTPProxyServiceConstants.opcodeHTTPSPostRequest
            case .put:
                return HTTPProxyServiceConstants.opcodeHTTPSPutRequest
            case .delete:
                return HTTPProxyServiceConstants.opcodeHTTPSDeleteRequest
            }
        } else {
            switch method {
            case .get:
                return HTTPProxyServiceConstants.opcodeHTTPGetRequest
            case .head:
                return HTTPProxyServiceConstants.opcodeHTTPHeadRequest
            case .post:
                return HTTPProxyServiceConstants.opcodeHTTPPostRequest
            case .put:
                return HTTPProxyServiceConstants.opcodeHTTPPutRequest
            case .delete:
                return HTTPProxyServiceConstants.opcodeHTTPDeleteRequest
            }
        }
    }
    
    /// Serializes headers to Data for BLE transmission.
    public func serializeHeaders() -> Data {
        let headerString = headers.map { "\($0.key): \($0.value)" }.joined(separator: "\r\n")
        return Data(headerString.utf8)
    }
}
