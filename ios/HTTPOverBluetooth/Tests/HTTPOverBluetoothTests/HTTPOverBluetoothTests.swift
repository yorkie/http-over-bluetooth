import XCTest
@testable import HTTPOverBluetooth

final class HTTPOverBluetoothTests: XCTestCase {
    
    // MARK: - HTTPRequest Tests
    
    func testHTTPRequestOpcode_HTTPGet() {
        let request = HTTPRequest(uri: "http://example.com", method: .get, isHTTPS: false)
        XCTAssertEqual(request.opcode, HTTPProxyServiceConstants.opcodeHTTPGetRequest)
    }
    
    func testHTTPRequestOpcode_HTTPSGet() {
        let request = HTTPRequest(uri: "https://example.com", method: .get, isHTTPS: true)
        XCTAssertEqual(request.opcode, HTTPProxyServiceConstants.opcodeHTTPSGetRequest)
    }
    
    func testHTTPRequestOpcode_HTTPPost() {
        let request = HTTPRequest(uri: "http://example.com", method: .post, isHTTPS: false)
        XCTAssertEqual(request.opcode, HTTPProxyServiceConstants.opcodeHTTPPostRequest)
    }
    
    func testHTTPRequestOpcode_HTTPSPost() {
        let request = HTTPRequest(uri: "https://example.com", method: .post, isHTTPS: true)
        XCTAssertEqual(request.opcode, HTTPProxyServiceConstants.opcodeHTTPSPostRequest)
    }
    
    func testHTTPRequestSerializeHeaders() {
        let request = HTTPRequest(
            uri: "http://example.com",
            method: .get,
            headers: ["Content-Type": "application/json", "Accept": "application/json"],
            isHTTPS: false
        )
        let headerData = request.serializeHeaders()
        let headerString = String(data: headerData, encoding: .utf8)!
        
        XCTAssertTrue(headerString.contains("Content-Type: application/json"))
        XCTAssertTrue(headerString.contains("Accept: application/json"))
    }
    
    // MARK: - HTTPResponse Tests
    
    func testHTTPResponseParseStatusCode() {
        // Status code 200 in little-endian: 0xC8, 0x00
        let data = Data([0xC8, 0x00])
        let statusCode = HTTPResponse.parseStatusCode(from: data)
        XCTAssertEqual(statusCode, 200)
    }
    
    func testHTTPResponseParseStatusCode_404() {
        // Status code 404 in little-endian: 0x94, 0x01
        let data = Data([0x94, 0x01])
        let statusCode = HTTPResponse.parseStatusCode(from: data)
        XCTAssertEqual(statusCode, 404)
    }
    
    func testHTTPResponseSerializeStatusCode() {
        let data = HTTPResponse.serializeStatusCode(200)
        XCTAssertEqual(data.count, 2)
        XCTAssertEqual(data[0], 0xC8)
        XCTAssertEqual(data[1], 0x00)
    }
    
    func testHTTPResponseSerializeStatusCode_500() {
        let data = HTTPResponse.serializeStatusCode(500)
        XCTAssertEqual(data.count, 2)
        XCTAssertEqual(data[0], 0xF4)
        XCTAssertEqual(data[1], 0x01)
    }
    
    func testHTTPResponseParseHeaders() {
        let headerString = "Content-Type: application/json\r\nContent-Length: 100"
        let data = Data(headerString.utf8)
        let headers = HTTPResponse.parseHeaders(from: data)
        
        XCTAssertEqual(headers["Content-Type"], "application/json")
        XCTAssertEqual(headers["Content-Length"], "100")
    }
    
    func testHTTPResponseParseHeaders_EmptyData() {
        let data = Data()
        let headers = HTTPResponse.parseHeaders(from: data)
        XCTAssertTrue(headers.isEmpty)
    }
    
    // MARK: - Constants Tests
    
    func testHTTPProxyServiceUUID() {
        XCTAssertEqual(
            HTTPProxyServiceConstants.httpProxyServiceUUID.uuidString.lowercased(),
            "00001823-0000-1000-8000-00805f9b34fb"
        )
    }
    
    func testURICharacteristicUUID() {
        XCTAssertEqual(
            HTTPProxyServiceConstants.uriCharacteristicUUID.uuidString.lowercased(),
            "00002ab6-0000-1000-8000-00805f9b34fb"
        )
    }
    
    func testOpcodeValues() {
        XCTAssertEqual(HTTPProxyServiceConstants.opcodeHTTPGetRequest, 0x01)
        XCTAssertEqual(HTTPProxyServiceConstants.opcodeHTTPHeadRequest, 0x02)
        XCTAssertEqual(HTTPProxyServiceConstants.opcodeHTTPPostRequest, 0x03)
        XCTAssertEqual(HTTPProxyServiceConstants.opcodeHTTPPutRequest, 0x04)
        XCTAssertEqual(HTTPProxyServiceConstants.opcodeHTTPDeleteRequest, 0x05)
        XCTAssertEqual(HTTPProxyServiceConstants.opcodeHTTPSGetRequest, 0x06)
        XCTAssertEqual(HTTPProxyServiceConstants.opcodeHTTPSHeadRequest, 0x07)
        XCTAssertEqual(HTTPProxyServiceConstants.opcodeHTTPSPostRequest, 0x08)
        XCTAssertEqual(HTTPProxyServiceConstants.opcodeHTTPSPutRequest, 0x09)
        XCTAssertEqual(HTTPProxyServiceConstants.opcodeHTTPSDeleteRequest, 0x0A)
        XCTAssertEqual(HTTPProxyServiceConstants.opcodeHTTPRequestCancel, 0x0B)
    }
}
