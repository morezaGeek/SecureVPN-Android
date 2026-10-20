package com.vpnapp.vpn

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class LanProxyDiagnosticsTest {
    private val loopback=InetAddress.getByName("127.0.0.1")
    private val config=LanShareSettings(true,10808,"private-user","private-password",LanProxyProtocol.HTTP)

    private fun header(socket:Socket):String {
        val text=StringBuilder()
        while(!text.endsWith("\r\n\r\n")) {
            val value=socket.getInputStream().read()
            check(value>=0 && text.length<20000)
            text.append(value.toChar())
        }
        return text.toString()
    }

    @Test(timeout=4000) fun wrongProtocolReportsActionableStatusWithoutCredentials() {
        LanProxyServer(config,{Socket()},{loopback},listenAddress=InetSocketAddress(loopback,0)).use { proxy ->
            assertEquals("LISTENING",proxy.status().state)
            assertEquals(0,proxy.status().accepted)
            Socket(loopback,proxy.port).use { socket ->
                socket.soTimeout=1500
                socket.getOutputStream().write(byteArrayOf(5,1,0))
                assertEquals(5,socket.getInputStream().read())
                assertEquals(255,socket.getInputStream().read())
            }
            val status=proxy.status()
            assertEquals(1,status.accepted)
            assertEquals(0,status.forwarded)
            assertTrue(status.lastError!!.contains("Select HTTP"))
            assertFalse(status.toString().contains("private-user"))
            assertFalse(status.toString().contains("private-password"))
        }
    }

    @Test(timeout=4000) fun dnsFailureDoesNotExposeDestinationOrExceptionText() {
        LanProxyServer(config,{Socket()},{throw java.net.UnknownHostException("secret-host.example")},
            listenAddress=InetSocketAddress(loopback,0)).use { proxy ->
            Socket(loopback,proxy.port).use { socket ->
                socket.soTimeout=1500
                socket.getOutputStream().write("CONNECT secret-host.example:443 HTTP/1.1\r\n\r\n".toByteArray())
                assertTrue(header(socket).startsWith("HTTP/1.1 502"))
            }
            val status=proxy.status()
            assertEquals(1,status.accepted)
            assertEquals(0,status.forwarded)
            assertTrue(status.lastError!!.contains("DNS"))
            assertFalse(status.lastError!!.contains("secret-host"))
        }
    }

    @Test(timeout=4000) fun closedListenerReportsOff() {
        val proxy=LanProxyServer(config,{Socket()},{loopback},listenAddress=InetSocketAddress(loopback,0))
        proxy.close()
        assertEquals("OFF",proxy.status().state)
    }
}
