package com.vpnapp.vpn.singbox

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.io.DataInputStream
import java.util.UUID

/** An authenticated loopback listener owned by one running sing-box instance. */
data class ProbeEndpoint(val port: Int, val username: String, val password: String) {
    // Package-targeted status messages bridge the dedicated VPN process and UI process.
    fun writeTo(intent: android.content.Intent) {
        intent.putExtra("probe_port", port)
        intent.putExtra("probe_user", username)
        intent.putExtra("probe_password", password)
    }

    companion object {
        @Volatile var active: ProbeEndpoint? = null
            internal set
        fun readFrom(intent: android.content.Intent): ProbeEndpoint? {
            val port = intent.getIntExtra("probe_port", 0)
            val user = intent.getStringExtra("probe_user") ?: return null
            val password = intent.getStringExtra("probe_password") ?: return null
            return if (port in 1..65535 && user.isNotEmpty() && password.isNotEmpty())
                ProbeEndpoint(port, user, password) else null
        }
        fun create(): ProbeEndpoint = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use {
            ProbeEndpoint(it.localPort, UUID.randomUUID().toString(), UUID.randomUUID().toString())
        }
    }

    fun connect(host: String, destinationPort: Int): Socket {
        val socket = Socket()
        try {
            socket.soTimeout = 5000
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress("127.0.0.1", port), 3000)
            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            output.write(byteArrayOf(5, 1, 2)); output.flush()
            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 2) { "SOCKS authentication unavailable" }
            val user = username.toByteArray(Charsets.UTF_8)
            val pass = password.toByteArray(Charsets.UTF_8)
            output.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
            output.flush()
            check(input.readUnsignedByte() == 1 && input.readUnsignedByte() == 0) { "SOCKS authentication failed" }
            // Send the domain to the core; Android never resolves or caches its FakeIP.
            val domain = host.toByteArray(Charsets.UTF_8)
            require(domain.size in 1..255)
            output.write(byteArrayOf(5, 1, 0, 3, domain.size.toByte()) + domain +
                byteArrayOf((destinationPort ushr 8).toByte(), destinationPort.toByte()))
            output.flush()
            check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0) { "SOCKS connect failed" }
            input.readUnsignedByte()
            val length = when (input.readUnsignedByte()) {
                1 -> 4
                3 -> input.readUnsignedByte()
                4 -> 16
                else -> error("Invalid SOCKS address")
            }
            input.readFully(ByteArray(length + 2))
            return socket
        } catch (error: Throwable) {
            socket.close()
            throw error
        }
    }
}
