package com.vpnapp.util

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** A session-local HTTP/1.1 connection. Nothing is pooled across VPN sessions. */
class ProbeHttp private constructor(private val socket: Socket, private val url: URL) : Closeable {
    data class Response(val status: Int, val body: String)
    private val input = BufferedInputStream(socket.getInputStream())

    companion object {
        fun open(url: URL, connect: (String, Int) -> Socket): ProbeHttp {
            val raw = connect(url.host, if (url.port > 0) url.port else url.defaultPort)
            try {
                val socket = if (url.protocol == "https") {
                    ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, url.host,
                        if (url.port > 0) url.port else 443, true) as SSLSocket).apply {
                        soTimeout = 5000
                        sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                        startHandshake()
                    }
                } else {
                    require(url.protocol == "http")
                    raw
                }
                return ProbeHttp(socket, url)
            } catch (error: Throwable) {
                raw.close()
                throw error
            }
        }
    }

    fun get(keepAlive: Boolean = false): Response {
        val path = url.file.ifEmpty { "/" }
        val host = url.host + if (url.port > 0) ":${url.port}" else ""
        socket.getOutputStream().apply {
            write(("GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: SecureVPN\r\n" +
                "Accept-Encoding: identity\r\nConnection: ${if (keepAlive) "keep-alive" else "close"}\r\n\r\n")
                .toByteArray(Charsets.US_ASCII))
            flush()
        }
        val status = readLine().split(' ').getOrNull(1)?.toIntOrNull() ?: error("Invalid HTTP status")
        val headers = mutableMapOf<String, String>()
        var headerBytes = 0
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            headerBytes += line.length
            check(headerBytes < 65536) { "HTTP headers too large" }
            val separator = line.indexOf(':')
            if (separator > 0) headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
        }
        val body = ByteArrayOutputStream()
        fun readBytes(count: Int) {
            require(count in 0..1048576 && body.size() + count <= 1048576) { "HTTP body too large" }
            var remaining = count
            val bytes = ByteArray(4096)
            while (remaining > 0) {
                val read = input.read(bytes, 0, minOf(bytes.size, remaining))
                check(read >= 0) { "Truncated HTTP response" }
                body.write(bytes, 0, read)
                remaining -= read
            }
        }
        if (status != 204 && status != 304 && status !in 100..199) {
            when {
                headers["transfer-encoding"]?.contains("chunked", true) == true -> {
                    while (true) {
                        val count = readLine().substringBefore(';').trim().toInt(16)
                        if (count == 0) {
                            var trailers = 0
                            while (true) {
                                val line = readLine()
                                if (line.isEmpty()) break
                                trailers += line.length
                                check(trailers < 65536)
                            }
                            break
                        }
                        readBytes(count)
                        check(readLine().isEmpty()) { "Invalid chunk terminator" }
                    }
                }
                headers.containsKey("content-length") -> readBytes(headers.getValue("content-length").toInt())
                else -> {
                    check(!keepAlive) { "HTTP response has no body framing" }
                    while (true) {
                        val value = input.read()
                        if (value < 0) break
                        check(body.size() < 1048576)
                        body.write(value)
                    }
                }
            }
        }
        return Response(status, body.toString("UTF-8"))
    }

    private fun readLine(): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            check(value >= 0) { "HTTP connection closed" }
            if (value == 10) return bytes.toString("US-ASCII").removeSuffix("\r")
            check(bytes.size() < 8192) { "HTTP line too long" }
            bytes.write(value)
        }
    }

    override fun close() = socket.close()
}
