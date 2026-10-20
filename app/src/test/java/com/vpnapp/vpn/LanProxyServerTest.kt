package com.vpnapp.vpn

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LanProxyServerTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val settings = LanShareSettings(true, 10808, "my-user", "my-own password", LanProxyProtocol.SOCKS5)

    private class Origin(val handler: (Socket) -> Unit) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable).apply { isDaemon = true } }
        val port: Int get() = server.localPort
        private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
        init { worker.execute {
            runCatching { server.accept().use { socket -> clients.add(socket); socket.soTimeout = 3000; handler(socket) } }
        } }
        override fun close() { server.close(); clients.forEach { it.close() }; worker.shutdownNow() }
    }

    private fun server(config: LanShareSettings = settings, resolved: (String) -> Unit = {}) =
        LanProxyServer(config, { Socket() }, { host -> resolved(host); loopback }, listenAddress = InetSocketAddress(loopback, 0))

    private fun connect(proxy: LanProxyServer) = Socket(loopback, proxy.port).apply { soTimeout = 3000 }

    private fun headers(socket: Socket): String {
        val input = socket.getInputStream()
        val text = StringBuilder()
        while (!text.endsWith("\r\n\r\n")) {
            val byte = input.read()
            check(byte >= 0 && text.length < 20000) { "Missing proxy HTTP response" }
            text.append(byte.toChar())
        }
        return text.toString()
    }

    private fun authenticate(socket: Socket, username: String = settings.username, password: String = settings.password): Int {
        val output = socket.getOutputStream()
        val input = DataInputStream(socket.getInputStream())
        output.write(byteArrayOf(5, 2, 0, 2)) // Offers both methods: password must win when enabled.
        assertEquals(5, input.readUnsignedByte())
        assertEquals(2, input.readUnsignedByte())
        val user = username.toByteArray(Charsets.UTF_8)
        val pass = password.toByteArray(Charsets.UTF_8)
        output.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
        assertEquals(1, input.readUnsignedByte())
        return input.readUnsignedByte()
    }

    private fun requestSocks(socket: Socket, port: Int, address: ByteArray = byteArrayOf(3, 16) + "vpn-test.invalid".toByteArray()): Int {
        socket.getOutputStream().write(byteArrayOf(5, 1, 0) + address + byteArrayOf((port ushr 8).toByte(), port.toByte()))
        val response = ByteArray(10)
        DataInputStream(socket.getInputStream()).readFully(response)
        assertEquals(5, response[0].toInt())
        return response[1].toInt()
    }

    private fun echo(socket: Socket) {
        val data = ByteArray(6)
        DataInputStream(socket.getInputStream()).readFully(data)
        socket.getOutputStream().write(data)
    }
    private fun assertEcho(socket: Socket) {
        val payload = byteArrayOf(0, 1, 127, 128.toByte(), 255.toByte(), 42)
        socket.getOutputStream().write(payload)
        val actual = ByteArray(payload.size)
        DataInputStream(socket.getInputStream()).readFully(actual)
        assertArrayEquals(payload, actual)
    }

    @Test(timeout = 5000) fun httpWorksWithoutPasswordAndDoesNotForwardOldCredentials() {
        val request = AtomicReference<String>()
        Origin { origin ->
            request.set(headers(origin))
            origin.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
        }.use { origin -> server(settings.copy(protocol = LanProxyProtocol.HTTP, username = "", password = "")).use { proxy ->
            connect(proxy).use { client ->
                client.getOutputStream().write(("GET http://vpn-test.invalid:${origin.port}/example?a=1 HTTP/1.1\r\n" +
                    "Host: vpn-test.invalid\r\nProxy-Authorization: Basic old-secret\r\nProxy-Connection: keep-alive\r\n\r\n").toByteArray())
                val response = client.getInputStream().bufferedReader().readText()
                assertTrue(response.startsWith("HTTP/1.1 200"))
                assertTrue(response.endsWith("ok"))
                assertTrue(request.get().startsWith("GET /example?a=1 HTTP/1.1"))
                assertFalse(request.get().contains("old-secret"))
                assertFalse(request.get().contains("Proxy-"))
            }
        } }
    }

    @Test(timeout = 5000) fun httpConnectTunnelsBinaryTrafficWithoutAnAuthChallenge() {
        Origin(::echo).use { origin -> server(settings.copy(protocol = LanProxyProtocol.HTTP)).use { proxy ->
            connect(proxy).use { client ->
                client.getOutputStream().write("CONNECT vpn-test.invalid:${origin.port} HTTP/1.1\r\nHost: vpn-test.invalid\r\n\r\n".toByteArray())
                assertTrue(headers(client).startsWith("HTTP/1.1 200"))
                assertEcho(client)
            }
        } }
    }

    @Test(timeout = 5000) fun socksAcceptsUserChosenCredentialsAndResolvesDomainThroughInjectedVpnResolver() {
        val resolved = AtomicReference<String>()
        Origin(::echo).use { origin -> server(resolved = { resolved.set(it) }).use { proxy ->
            connect(proxy).use { client ->
                assertEquals(0, authenticate(client))
                assertEquals(0, requestSocks(client, origin.port))
                assertEquals("vpn-test.invalid", resolved.get())
                assertEcho(client)
            }
        } }
    }

    @Test(timeout = 5000) fun wrongPasswordIsRejectedBeforeAnyOutboundConnection() {
        val targets = AtomicInteger()
        server(resolved = { targets.incrementAndGet() }).use { proxy -> connect(proxy).use { client ->
            assertEquals(1, authenticate(client, password = "wrong"))
            assertEquals(-1, client.getInputStream().read())
            assertEquals(0, targets.get())
        } }
    }

    @Test(timeout = 5000) fun socksAcceptsUtf8UserChosenCredentials() {
        val custom=settings.copy(username="کاربر",password="رمز انتخابی من")
        Origin(::echo).use { origin -> server(custom).use { proxy -> connect(proxy).use { client ->
            assertEquals(0,authenticate(client,custom.username,custom.password))
            assertEquals(0,requestSocks(client,origin.port))
            assertEcho(client)
        } } }
    }

    @Test(timeout = 5000) fun wrongUsernameIsRejected() {
        server().use { proxy -> connect(proxy).use { client -> assertEquals(1, authenticate(client, username = "wrong")) } }
    }

    @Test(timeout = 5000) fun passwordModeRejectsClientsOfferingOnlyAnonymousSocks() {
        server().use { proxy -> connect(proxy).use { client ->
            client.getOutputStream().write(byteArrayOf(5, 1, 0))
            assertEquals(5, client.getInputStream().read())
            assertEquals(255, client.getInputStream().read())
        } }
    }

    @Test(timeout = 5000) fun anonymousSocksWorksWhenAuthenticationIsDisabled() {
        Origin(::echo).use { origin -> server(settings.copy(socksAuthentication = false, username = "", password = "")).use { proxy ->
            connect(proxy).use { client ->
                client.getOutputStream().write(byteArrayOf(5, 1, 0))
                assertEquals(5, client.getInputStream().read())
                assertEquals(0, client.getInputStream().read())
                assertEquals(0, requestSocks(client, origin.port, byteArrayOf(1, 127, 0, 0, 1)))
                assertEcho(client)
            }
        } }
    }

    @Test(timeout = 5000) fun selectedProtocolsCannotBeSilentlyMixed() {
        val targets = AtomicInteger()
        server(resolved = { targets.incrementAndGet() }).use { proxy -> connect(proxy).use { client ->
            client.getOutputStream().write("GET http://example.test/ HTTP/1.1\r\n\r\n".toByteArray())
            assertTrue(headers(client).contains("400 Bad Request"))
        } }
        server(settings.copy(protocol = LanProxyProtocol.HTTP), resolved = { targets.incrementAndGet() }).use { proxy -> connect(proxy).use { client ->
            client.getOutputStream().write(byteArrayOf(5, 1, 0))
            assertEquals(5, client.getInputStream().read())
            assertEquals(255, client.getInputStream().read())
        } }
        assertEquals(0, targets.get())
    }

    @Test(timeout = 5000) fun unavailableVpnResolverReturnsFailureWithoutFallingBack() {
        LanProxyServer(settings.copy(protocol = LanProxyProtocol.HTTP), { Socket() },
            { throw java.net.UnknownHostException("VPN unavailable") }, listenAddress = InetSocketAddress(loopback, 0)).use { proxy ->
            connect(proxy).use { client ->
                client.getOutputStream().write("CONNECT example.test:443 HTTP/1.1\r\n\r\n".toByteArray())
                assertTrue(headers(client).contains("502 Bad Gateway"))
            }
        }
    }

    @Test(timeout = 5000) fun closingProxyClosesActiveTunnelsAndListener() {
        val originClosed = CountDownLatch(1)
        Origin { origin -> if (origin.getInputStream().read() == -1) originClosed.countDown() }.use { origin ->
            val proxy = server(settings.copy(protocol = LanProxyProtocol.HTTP))
            try { connect(proxy).use { client ->
                client.getOutputStream().write("CONNECT example.test:${origin.port} HTTP/1.1\r\n\r\n".toByteArray())
                assertTrue(headers(client).contains("200 Connection Established"))
                proxy.close()
                assertEquals(-1, client.getInputStream().read())
                assertTrue(originClosed.await(2, TimeUnit.SECONDS))
                assertTrue(runCatching { connect(proxy).close() }.isFailure)
            } } finally { proxy.close() }
        }
    }

    @Test(timeout = 5000) fun preparesAcceptedClientBeforeReadingProtocolOrCreatingUpstream() {
        val preparing = CountDownLatch(1)
        val allowProtocol = CountDownLatch(1)
        val outbound = AtomicInteger()
        val prepared = AtomicReference<Socket>()
        LanProxyServer(settings, { outbound.incrementAndGet(); Socket() }, { loopback },
            prepareClient = { client ->
                prepared.set(client)
                preparing.countDown()
                allowProtocol.await(2, TimeUnit.SECONDS)
            }, listenAddress = InetSocketAddress(loopback, 0)).use { proxy ->
            try { connect(proxy).use { client ->
                assertTrue("Preparation must run before the peer sends a protocol byte", preparing.await(2, TimeUnit.SECONDS))
                assertNotNull(prepared.get())
                client.getOutputStream().write(byteArrayOf(5, 1, 2))
                client.soTimeout = 150
                assertTrue("No SOCKS response before client protection completes", runCatching { client.getInputStream().read() }.exceptionOrNull() is java.net.SocketTimeoutException)
                assertEquals(0, outbound.get())
                allowProtocol.countDown()
                client.soTimeout = 2000
                assertEquals(5, client.getInputStream().read())
                assertEquals(2, client.getInputStream().read())
            } } finally { allowProtocol.countDown() }
        }
    }

    @Test(timeout = 5000) fun failedClientPreparationClosesWithoutProtocolResponseOrOutbound() {
        val outbound = AtomicInteger()
        val attempts = AtomicInteger()
        LanProxyServer(settings, { outbound.incrementAndGet(); Socket() }, { loopback },
            prepareClient = { attempts.incrementAndGet(); false },
            listenAddress = InetSocketAddress(loopback, 0)).use { proxy ->
            connect(proxy).use { client ->
                assertEquals(-1, client.getInputStream().read())
                assertEquals(1, attempts.get())
                assertEquals(0, outbound.get())
            }
        }
    }

    @Test(timeout = 5000) fun throwingClientPreparationClosesWithoutAnOutboundFallback() {
        val outbound = AtomicInteger()
        LanProxyServer(settings.copy(protocol = LanProxyProtocol.HTTP), { outbound.incrementAndGet(); Socket() }, { loopback },
            prepareClient = { throw java.io.IOException("Protection unavailable") },
            listenAddress = InetSocketAddress(loopback, 0)).use { proxy ->
            connect(proxy).use { client ->
                assertEquals(-1, client.getInputStream().read())
                assertEquals(0, outbound.get())
            }
        }
    }

    @Test fun credentialLimitsUseProtocolByteLengthsAndHttpDoesNotRequireCredentials() {
        settings.copy(protocol = LanProxyProtocol.HTTP, username = "", password = "").validated()
        settings.copy(password = "x".repeat(255)).validated()
        assertTrue(runCatching { settings.copy(password = "x".repeat(256)).validated() }.isFailure)
        assertTrue(runCatching { settings.copy(password = "ی".repeat(128)).validated() }.isFailure)
        assertTrue(runCatching { settings.copy(username = "").validated() }.isFailure)
        assertTrue(runCatching { settings.copy(port = 80).validated() }.isFailure)
    }
}
