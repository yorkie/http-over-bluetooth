package com.example.httpoverble

import com.example.httpoverble.common.HttpMethod
import com.example.httpoverble.common.HttpProxyServiceConstants
import com.example.httpoverble.common.HttpRequest
import com.example.httpoverble.common.HttpResponse
import org.junit.Assert.*
import org.junit.Test

class HttpOverBleTest {

    // MARK: - HttpRequest Tests

    @Test
    fun testHttpRequestOpcode_HttpGet() {
        val request = HttpRequest(uri = "http://example.com", method = HttpMethod.GET, isHttps = false)
        assertEquals(HttpProxyServiceConstants.OPCODE_HTTP_GET_REQUEST, request.getOpcode())
    }

    @Test
    fun testHttpRequestOpcode_HttpsGet() {
        val request = HttpRequest(uri = "https://example.com", method = HttpMethod.GET, isHttps = true)
        assertEquals(HttpProxyServiceConstants.OPCODE_HTTPS_GET_REQUEST, request.getOpcode())
    }

    @Test
    fun testHttpRequestOpcode_HttpPost() {
        val request = HttpRequest(uri = "http://example.com", method = HttpMethod.POST, isHttps = false)
        assertEquals(HttpProxyServiceConstants.OPCODE_HTTP_POST_REQUEST, request.getOpcode())
    }

    @Test
    fun testHttpRequestOpcode_HttpsPost() {
        val request = HttpRequest(uri = "https://example.com", method = HttpMethod.POST, isHttps = true)
        assertEquals(HttpProxyServiceConstants.OPCODE_HTTPS_POST_REQUEST, request.getOpcode())
    }

    @Test
    fun testHttpRequestSerializeHeaders() {
        val request = HttpRequest(
            uri = "http://example.com",
            method = HttpMethod.GET,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json"
            ),
            isHttps = false
        )
        val headerBytes = request.serializeHeaders()
        val headerString = String(headerBytes, Charsets.UTF_8)
        
        assertTrue(headerString.contains("Content-Type: application/json"))
        assertTrue(headerString.contains("Accept: application/json"))
    }

    // MARK: - HttpResponse Tests

    @Test
    fun testHttpResponseParseStatusCode() {
        // Status code 200 in little-endian: 0xC8, 0x00
        val data = byteArrayOf(0xC8.toByte(), 0x00)
        val statusCode = HttpResponse.parseStatusCode(data)
        assertEquals(200, statusCode)
    }

    @Test
    fun testHttpResponseParseStatusCode_404() {
        // Status code 404 in little-endian: 0x94, 0x01
        val data = byteArrayOf(0x94.toByte(), 0x01)
        val statusCode = HttpResponse.parseStatusCode(data)
        assertEquals(404, statusCode)
    }

    @Test
    fun testHttpResponseSerializeStatusCode() {
        val data = HttpResponse.serializeStatusCode(200)
        assertEquals(2, data.size)
        assertEquals(0xC8.toByte(), data[0])
        assertEquals(0x00.toByte(), data[1])
    }

    @Test
    fun testHttpResponseSerializeStatusCode_500() {
        val data = HttpResponse.serializeStatusCode(500)
        assertEquals(2, data.size)
        assertEquals(0xF4.toByte(), data[0])
        assertEquals(0x01.toByte(), data[1])
    }

    @Test
    fun testHttpResponseParseHeaders() {
        val headerString = "Content-Type: application/json\r\nContent-Length: 100"
        val data = headerString.toByteArray(Charsets.UTF_8)
        val headers = HttpResponse.parseHeaders(data)
        
        assertEquals("application/json", headers["Content-Type"])
        assertEquals("100", headers["Content-Length"])
    }

    @Test
    fun testHttpResponseParseHeaders_EmptyData() {
        val data = byteArrayOf()
        val headers = HttpResponse.parseHeaders(data)
        assertTrue(headers.isEmpty())
    }

    // MARK: - Constants Tests

    @Test
    fun testHttpProxyServiceUUID() {
        assertEquals(
            "00001823-0000-1000-8000-00805f9b34fb",
            HttpProxyServiceConstants.HTTP_PROXY_SERVICE_UUID.toString().lowercase()
        )
    }

    @Test
    fun testURICharacteristicUUID() {
        assertEquals(
            "00002ab6-0000-1000-8000-00805f9b34fb",
            HttpProxyServiceConstants.URI_CHARACTERISTIC_UUID.toString().lowercase()
        )
    }

    @Test
    fun testOpcodeValues() {
        assertEquals(0x01.toByte(), HttpProxyServiceConstants.OPCODE_HTTP_GET_REQUEST)
        assertEquals(0x02.toByte(), HttpProxyServiceConstants.OPCODE_HTTP_HEAD_REQUEST)
        assertEquals(0x03.toByte(), HttpProxyServiceConstants.OPCODE_HTTP_POST_REQUEST)
        assertEquals(0x04.toByte(), HttpProxyServiceConstants.OPCODE_HTTP_PUT_REQUEST)
        assertEquals(0x05.toByte(), HttpProxyServiceConstants.OPCODE_HTTP_DELETE_REQUEST)
        assertEquals(0x06.toByte(), HttpProxyServiceConstants.OPCODE_HTTPS_GET_REQUEST)
        assertEquals(0x07.toByte(), HttpProxyServiceConstants.OPCODE_HTTPS_HEAD_REQUEST)
        assertEquals(0x08.toByte(), HttpProxyServiceConstants.OPCODE_HTTPS_POST_REQUEST)
        assertEquals(0x09.toByte(), HttpProxyServiceConstants.OPCODE_HTTPS_PUT_REQUEST)
        assertEquals(0x0A.toByte(), HttpProxyServiceConstants.OPCODE_HTTPS_DELETE_REQUEST)
        assertEquals(0x0B.toByte(), HttpProxyServiceConstants.OPCODE_HTTP_REQUEST_CANCEL)
    }
}
