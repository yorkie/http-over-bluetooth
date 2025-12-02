package com.example.httpoverble.common

/**
 * Represents an HTTP response received over BLE.
 */
data class HttpResponse(
    val statusCode: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val isHttps: Boolean = false,
    val certificateValidated: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HttpResponse

        if (statusCode != other.statusCode) return false
        if (headers != other.headers) return false
        if (body != null) {
            if (other.body == null) return false
            if (!body.contentEquals(other.body)) return false
        } else if (other.body != null) return false
        if (isHttps != other.isHttps) return false
        if (certificateValidated != other.certificateValidated) return false

        return true
    }

    override fun hashCode(): Int {
        var result = statusCode
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.contentHashCode() ?: 0)
        result = 31 * result + isHttps.hashCode()
        result = 31 * result + certificateValidated.hashCode()
        return result
    }

    companion object {
        /**
         * Parses HTTP headers from a byte array.
         */
        fun parseHeaders(data: ByteArray): Map<String, String> {
            val headerString = String(data, Charsets.UTF_8)
            val headers = mutableMapOf<String, String>()
            headerString.split("\r\n").forEach { line ->
                val colonIndex = line.indexOf(':')
                if (colonIndex > 0) {
                    val key = line.substring(0, colonIndex).trim()
                    val value = line.substring(colonIndex + 1).trim()
                    headers[key] = value
                }
            }
            return headers
        }

        /**
         * Parses HTTP status code from a byte array.
         * The status code is encoded as a 16-bit unsigned integer (little-endian).
         */
        fun parseStatusCode(data: ByteArray): Int {
            if (data.size < 2) return 0
            return (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        }

        /**
         * Creates status code bytes for transmission.
         */
        fun serializeStatusCode(statusCode: Int): ByteArray {
            return byteArrayOf(
                (statusCode and 0xFF).toByte(),
                ((statusCode shr 8) and 0xFF).toByte()
            )
        }
    }
}
