package com.vpnapp.vpn

import java.io.Closeable
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** TCP protocol handling, independent of Android so real socket/authentication exchanges can be tested. */
internal class LanProxyServer(
    private val settings: LanShareSettings,
    private val createSocket: () -> Socket,
    private val resolveHost: (String) -> InetAddress,
    private val prepareClient: (Socket) -> Boolean = { true },
    listenAddress: InetSocketAddress = InetSocketAddress("0.0.0.0", settings.port)
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    // Browsers keep many CONNECT tunnels alive across tabs and origins. Sixteen
    // slots are easily exhausted even by ordinary browsing. Keep a bounded budget
    // large enough for that workload (at most two relay workers per tunnel).
    private val slots = Semaphore(128)
    private val accepted = AtomicInteger()
    private val forwarded = AtomicInteger()
    @Volatile private var lastError: String? = null
    @Volatile private var accepting = true
    private val listener = ServerSocket().apply {
        settings.validated()
        reuseAddress = true
        bind(listenAddress)
    }
    val port: Int get() = listener.localPort
    fun status(): LanProxyStatus = LanProxyStatus(
        when { closed.get() || listener.isClosed -> "OFF"; !accepting -> "ERROR"; else -> "LISTENING" },
        settings.protocol.name, settings.port, accepted.get(), forwarded.get(), lastError)

    init {
        executor.execute {
            while (!closed.get()) {
                val client = try { listener.accept() } catch (_: Exception) {
                    if (!closed.get()) lastError = "The LAN listener stopped. Turn sharing off and on again."
                    accepting = false
                    break
                }
                if (!client.inetAddress.isSiteLocalAddress && !client.inetAddress.isLoopbackAddress) {
                    client.close()
                    continue
                }
                if (!slots.tryAcquire()) {
                    lastError = "LAN proxy connection capacity reached. Close unused connections and retry."
                    client.close()
                    continue
                }
                sockets.add(client)
                accepted.incrementAndGet()
                try {
                    executor.execute {
                        try { serve(client) } catch (_: Exception) { /* Explicit failures set a safe diagnostic below. */ }
                        finally {
                            sockets.remove(client)
                            runCatching { client.close() }
                            slots.release()
                        }
                    }
                } catch (_: RuntimeException) {
                    sockets.remove(client)
                    client.close()
                    slots.release()
                }
            }
        }
    }

    private fun target(host: String, port: Int): Socket {
        require(port in 1..65535 && host.isNotBlank() && '\u0000' !in host)
        check(!closed.get())
        val socket = try { createSocket() } catch (error: Exception) {
            lastError = "VPN network is unavailable. Reconnect this profile and retry."
            throw error
        }
        sockets.add(socket)
        try {
            val address = try { resolveHost(host) } catch (error: Exception) {
                lastError = "VPN DNS could not resolve a destination. Check the tunnel DNS setting."
                throw error
            }
            check(!closed.get())
            try { socket.connect(InetSocketAddress(address, port), 15000) } catch (error: Exception) {
                lastError = "VPN could not connect to a destination. Check that this profile has Internet access."
                throw error
            }
            forwarded.incrementAndGet()
            lastError = null
            return socket
        } catch (error: Exception) {
            sockets.remove(socket)
            socket.close()
            throw error
        }
    }

    private fun serve(client: Socket) {
        // This accepted socket replies to the LAN peer, not to the Internet. Keep its
        // responses outside the TUN even when the VPN captures private-address routes.
        // Upstream sockets are separately and explicitly bound to the active VPN.
        val prepared = runCatching { prepareClient(client) }.getOrDefault(false)
        if (!prepared) {
            lastError = "Android could not prepare the LAN reply route. Reconnect VPN and retry."
            return
        }
        client.soTimeout = 10000
        val input = DataInputStream(client.getInputStream())
        val output = client.getOutputStream()
        val first = input.readUnsignedByte()
        val upstream = if (first == 5) {
            if (settings.protocol != LanProxyProtocol.SOCKS5) {
                lastError = "Client used SOCKS5, but this port is HTTP. Select HTTP on the other device."
                output.write(byteArrayOf(5, 255.toByte()))
                output.flush()
                return
            }
            socksConnect(input, output) ?: return
        } else {
            if (settings.protocol != LanProxyProtocol.HTTP) {
                lastError = "Client used HTTP, but this port is SOCKS5. Match the proxy type on both devices."
                httpError(output, "400 Bad Request", "This port uses SOCKS5, not HTTP.")
                return
            }
            httpConnect(first, input, output) ?: return
        }
        upstream.use { remote ->
            try {
                client.soTimeout = 120000
                remote.soTimeout = 120000
                executor.execute {
                    runCatching {
                        input.copyTo(remote.getOutputStream())
                        remote.shutdownOutput()
                    }.onFailure { runCatching { remote.close() } }
                }
                remote.getInputStream().copyTo(output)
                output.flush()
            } finally { sockets.remove(remote) }
        }
    }

    private fun socksConnect(input: DataInputStream, output: OutputStream): Socket? {
        val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
        val method: Byte = if (settings.socksAuthentication) 2 else 0
        if (!methods.contains(method)) {
            lastError = if (settings.socksAuthentication)
                "SOCKS5 client must send both the saved username and password."
            else "SOCKS5 client must support authentication off."
            output.write(byteArrayOf(5, 255.toByte()))
            output.flush()
            return null
        }
        output.write(byteArrayOf(5, method))
        output.flush()
        if (settings.socksAuthentication) {
            if (input.readUnsignedByte() != 1) {
                lastError = "SOCKS5 client sent an unsupported authentication format."
                return null
            }
            val user = ByteArray(input.readUnsignedByte()).also(input::readFully)
            val password = ByteArray(input.readUnsignedByte()).also(input::readFully)
            val validUser = MessageDigest.isEqual(user, settings.username.toByteArray(Charsets.UTF_8))
            val validPassword = MessageDigest.isEqual(password, settings.password.toByteArray(Charsets.UTF_8))
            val accepted = user.isNotEmpty() && password.isNotEmpty() && validUser && validPassword
            output.write(byteArrayOf(1, if (accepted) 0 else 1))
            output.flush()
            if (!accepted) {
                lastError = "SOCKS5 username or password did not match the saved settings."
                return null
            }
        }
        val version = input.readUnsignedByte()
        val command = input.readUnsignedByte()
        val reserved = input.readUnsignedByte()
        if (version != 5 || command != 1 || reserved != 0) {
            lastError = "This SOCKS5 proxy supports TCP CONNECT only; UDP and BIND are unavailable."
            socksReply(output, 7) // CONNECT only; never advertise UDP or BIND support.
            return null
        }
        val host = when (input.readUnsignedByte()) {
            1 -> ByteArray(4).also(input::readFully).let { InetAddress.getByAddress(it).hostAddress!! }
            3 -> ByteArray(input.readUnsignedByte()).also(input::readFully).toString(Charsets.US_ASCII)
            4 -> ByteArray(16).also(input::readFully).let { InetAddress.getByAddress(it).hostAddress!! }
            else -> {
                lastError = "SOCKS5 client sent an unsupported address format."
                socksReply(output, 8); return null
            }
        }
        val port = input.readUnsignedShort()
        val upstream = try { target(host, port) } catch (_: Exception) {
            socksReply(output, 1)
            return null
        }
        try { socksReply(output, 0) } catch (error: Exception) {
            sockets.remove(upstream)
            upstream.close()
            throw error
        }
        return upstream
    }

    private fun socksReply(output: OutputStream, status: Int) {
        output.write(byteArrayOf(5, status.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        output.flush()
    }

    private fun httpConnect(first: Int, input: DataInputStream, output: OutputStream): Socket? {
        val header = StringBuilder().append(first.toChar())
        while (!header.endsWith("\r\n\r\n")) {
            if (header.length >= 16384) {
                lastError = "HTTP proxy request headers were too large."
                httpError(output, "431 Request Header Fields Too Large", "Proxy request headers are too large.")
                return null
            }
            header.append(input.readUnsignedByte().toChar())
        }
        val lines = header.toString().split("\r\n")
        val request = lines.first().split(' ', limit = 3)
        val connect = request.firstOrNull().equals("CONNECT", true)
        val uri = runCatching {
            require(request.size == 3 && request[2].startsWith("HTTP/1."))
            URI(if (connect) "https://" + request[1] else request[1]).also {
                require(it.host != null && it.rawUserInfo == null && (connect || it.scheme.equals("http", true)))
            }
        }.getOrElse {
            lastError = "Client sent an invalid HTTP proxy request. Use Manual/HTTP proxy settings."
            httpError(output, "400 Bad Request", "Use this address as an HTTP proxy.")
            return null
        }
        val upstream = try { target(uri.host, if (uri.port != -1) uri.port else if (connect) 443 else 80) }
        catch (_: Exception) {
            httpError(output, "502 Bad Gateway", "The VPN could not reach the destination.")
            return null
        }
        try {
        if (connect) output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
        else {
            val path = uri.rawPath.orEmpty().ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
            // Credentials left in an old client's proxy configuration must never reach destination sites.
            val forwarded = lines.drop(1).filter {
                it.isNotEmpty() && !it.startsWith("Proxy-", true) && !it.startsWith("Connection:", true)
            }
            upstream.getOutputStream().write(("${request[0]} $path ${request[2]}\r\n" +
                forwarded.joinToString("\r\n") + "\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
            upstream.getOutputStream().flush()
        }
        output.flush()
        return upstream
        } catch (error: Exception) {
            sockets.remove(upstream)
            upstream.close()
            throw error
        }
    }

    private fun httpError(output: OutputStream, status: String, message: String) {
        val body = message.toByteArray(Charsets.US_ASCII)
        output.write(("HTTP/1.1 $status\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        listener.close()
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        executor.shutdownNow()
    }
}
