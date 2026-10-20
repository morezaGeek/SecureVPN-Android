package com.vpnapp.vpn

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

class LanBrowserConcurrencyTest {
    @Test(timeout=15000) fun thirtyTwoOpenBrowserTunnelsDoNotResetTheNextRequest() {
        val loopback=InetAddress.getByName("127.0.0.1")
        val clients=mutableListOf<Socket>()
        val upstreams=ConcurrentLinkedQueue<Socket>()
        val acceptor=Executors.newSingleThreadExecutor()
        ServerSocket(0,64,loopback).use { origin ->
            acceptor.execute {
                try { while(!origin.isClosed) upstreams.add(origin.accept()) }
                catch (_: java.io.IOException) { /* Listener closed by test cleanup. */ }
            }
            val config=LanShareSettings(true,10809,"","",LanProxyProtocol.HTTP)
            try {
                LanProxyServer(config,{Socket()},{loopback},listenAddress=InetSocketAddress(loopback,0)).use { proxy ->
                    repeat(33) { index ->
                        val client=Socket(loopback,proxy.port).apply { soTimeout=2000 }
                        clients.add(client)
                        client.outputStream.write("CONNECT browser.invalid:${origin.localPort} HTTP/1.1\r\nHost: browser.invalid\r\n\r\n".toByteArray())
                        val header=StringBuilder()
                        while(!header.endsWith("\r\n\r\n")) {
                            val value=client.inputStream.read()
                            assertTrue("Browser tunnel ${index+1} must not be silently reset",value>=0)
                            header.append(value.toChar())
                        }
                        assertTrue(header.startsWith("HTTP/1.1 200"))
                    }
                    assertEquals(33,proxy.status().forwarded)
                    // Existing tunnels must still relay after accepting the additional request.
                    upstreams.forEach { it.outputStream.write(42);it.outputStream.flush() }
                    clients.forEach { assertEquals(42,it.inputStream.read()) }
                }
            } finally {
                clients.forEach { runCatching { it.close() } }
                upstreams.forEach { runCatching { it.close() } }
                origin.close()
                acceptor.shutdownNow()
            }
        }
    }
}
