package com.example.httpoverble.common

/**
 * Represents an HTTP request to be sent over BLE.
 */
data class HttpRequest(
    val uri: String,
    val method: HttpMethod,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val isHttps: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HttpRequest

        if (uri != other.uri) return false
        if (method != other.method) return false
        if (headers != other.headers) return false
        if (body != null) {
            if (other.body == null) return false
            if (!body.contentEquals(other.body)) return false
        } else if (other.body != null) return false
        if (isHttps != other.isHttps) return false

        return true
    }

    override fun hashCode(): Int {
        var result = uri.hashCode()
        result = 31 * result + method.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.contentHashCode() ?: 0)
        result = 31 * result + isHttps.hashCode()
        return result
    }

    /**
     * Gets the control point opcode for this request.
     */
    fun getOpcode(): Byte {
        return if (isHttps) {
            when (method) {
                HttpMethod.GET -> HttpProxyServiceConstants.OPCODE_HTTPS_GET_REQUEST
                HttpMethod.HEAD -> HttpProxyServiceConstants.OPCODE_HTTPS_HEAD_REQUEST
                HttpMethod.POST -> HttpProxyServiceConstants.OPCODE_HTTPS_POST_REQUEST
                HttpMethod.PUT -> HttpProxyServiceConstants.OPCODE_HTTPS_PUT_REQUEST
                HttpMethod.DELETE -> HttpProxyServiceConstants.OPCODE_HTTPS_DELETE_REQUEST
            }
        } else {
            when (method) {
                HttpMethod.GET -> HttpProxyServiceConstants.OPCODE_HTTP_GET_REQUEST
                HttpMethod.HEAD -> HttpProxyServiceConstants.OPCODE_HTTP_HEAD_REQUEST
                HttpMethod.POST -> HttpProxyServiceConstants.OPCODE_HTTP_POST_REQUEST
                HttpMethod.PUT -> HttpProxyServiceConstants.OPCODE_HTTP_PUT_REQUEST
                HttpMethod.DELETE -> HttpProxyServiceConstants.OPCODE_HTTP_DELETE_REQUEST
            }
        }
    }

    /**
     * Serializes headers to a byte array for BLE transmission.
     */
    fun serializeHeaders(): ByteArray {
        val headerString = headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" }
        return headerString.toByteArray(Charsets.UTF_8)
    }
}

/**
 * HTTP methods supported by the HTTP Proxy Service.
 */
enum class HttpMethod {
    GET,
    HEAD,
    POST,
    PUT,
    DELETE
}
