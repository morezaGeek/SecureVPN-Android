package com.vpnapp.vpn.singbox

import android.content.Context
import android.util.Log
import com.vpnapp.data.VpnProfile
import com.vpnapp.data.VpnProtocol
import io.nekohasekai.libbox.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * Accurately measures real proxy latency by establishing a temporary
 * sing-box mixed proxy and querying https://www.google.com/generate_204
 * through the actual proxy tunnel with keep-alive connection pooling (identical to V2RayN).
 */
object SingBoxDelayTester {
    private const val TAG = "SingBoxDelayTester"
    private val testMutex = Mutex()

    suspend fun testProfileDelay(context: Context, profile: VpnProfile,
        targetUrl: String = "https://www.google.com/generate_204", quickCheck: Boolean = false): Int = withContext(Dispatchers.IO) {
        if (profile.protocol == VpnProtocol.OPENCONNECT || profile.protocol == VpnProtocol.SSTP) {
            return@withContext testTlsServerDelay(profile)
        }

        testMutex.withLock {
            testSingBoxDelay(context, profile, targetUrl, quickCheck)
        }
    }

    // This checks server TLS reachability, not credentials or a completed VPN tunnel.
    private fun testTlsServerDelay(profile: VpnProfile): Int {
        return try {
            val factory = if (profile.skipCertificateVerification) {
                SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf<TrustManager>(object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                    }), null)
                }.socketFactory
            } else SSLSocketFactory.getDefault() as SSLSocketFactory
            val addresses = InetAddress.getAllByName(profile.serverAddress)
                .sortedBy { if (it.address.size == 4) 0 else 1 }.take(3)
            var failure: Exception? = null
            for (address in addresses) {
                try {
                    val start = System.nanoTime()
                    val elapsed = Socket().use { raw ->
                        raw.soTimeout = 4000
                        raw.connect(InetSocketAddress(address, profile.port), 4000)
                        (factory.createSocket(raw, profile.serverAddress, profile.port, true) as SSLSocket).use { tls ->
                            tls.soTimeout = 4000
                            if (!profile.skipCertificateVerification) tls.sslParameters = tls.sslParameters.apply {
                                endpointIdentificationAlgorithm = "HTTPS"
                            }
                            tls.startHandshake()
                            ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1).toInt()
                        }
                    }
                    Log.i(TAG, "${profile.protocol} TLS delay for ${profile.name}: ${elapsed}ms")
                    return elapsed
                } catch (e: Exception) { failure = e }
            }
            throw failure ?: IllegalStateException("No server addresses")
        } catch (e: Exception) {
            Log.w(TAG, "${profile.protocol} TLS delay test failed for ${profile.name}: ${e.message}")
            -1
        }
    }

    private suspend fun testSingBoxDelay(context: Context, profile: VpnProfile, target: String, quickCheck: Boolean): Int {
        var server: CommandServer? = null
        var quickConnection: HttpURLConnection? = null
        return try {
            val testPort = try {
                ServerSocket(0).use { it.localPort }
            } catch (e: Exception) {
                18080
            }

            val setupOptions = SetupOptions().apply {
                setBasePath(context.filesDir.absolutePath)
                setWorkingPath(context.cacheDir.absolutePath)
                setTempPath(context.cacheDir.absolutePath)
            }
            try {
                Libbox.setup(setupOptions)
            } catch (e: Exception) {
                // Ignore if already set up
            }

            // Pre-resolve server domain to IP to bypass Go runtime DNS issues
            val resolvedAddress = try {
                InetAddress.getByName(profile.serverAddress).hostAddress ?: profile.serverAddress
            } catch (e: Exception) {
                profile.serverAddress
            }

            val resolvedProfile = profile.copy(
                serverAddress = resolvedAddress,
                v2raySni = profile.v2raySni.ifEmpty { profile.serverAddress }
            )

            val proxyOutbound = SingBoxConfigGenerator.buildOutbounds(resolvedProfile).getJSONObject(0)

            val config = JSONObject().apply {
                put("log", JSONObject().apply {
                    put("level", "panic")
                })
                put("dns", JSONObject().apply {
                    put("servers", JSONArray().apply {
                        put(JSONObject().apply {
                            put("tag", "direct-dns")
                            put("type", "udp")
                            put("server", "178.22.122.100")
                        })
                    })
                })
                put("inbounds", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "mixed")
                        put("tag", "test-in")
                        put("listen", "127.0.0.1")
                        put("listen_port", testPort)
                    })
                })
                put("outbounds", JSONArray().apply {
                    put(proxyOutbound)
                    put(JSONObject().apply {
                        put("type", "direct")
                        put("tag", "direct")
                    })
                })
                put("route", JSONObject().apply {
                    put("final", "proxy")
                })
            }.toString()

            val handler = object : CommandServerHandler {
                override fun connectSSHAgent(): Int = -1
                override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()
                override fun serviceReload() {}
                override fun serviceStop() {}
                override fun setSystemProxyEnabled(enabled: Boolean) {}
                override fun triggerNativeCrash() {}
                override fun writeDebugMessage(message: String?) {}
            }

            val platformInterface = object : PlatformInterface {
                override fun autoDetectInterfaceControl(fd: Int) {}
                override fun checkPlatformShell() {}
                override fun clearDNSCache() {}
                override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {}
                override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {}
                override fun createBridge(options: BridgeOptions?): BridgeSession? = null
                override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String?, sourcePort: Int, destinationAddress: String?, destinationPort: Int): ConnectionOwner = ConnectionOwner()
                override fun getInterfaces(): NetworkInterfaceIterator? = null
                override fun includeAllNetworks(): Boolean = false
                override fun localDNSTransport(): LocalDNSTransport? = null
                override fun lookupSFTPServer(): String = ""
                override fun lookupUser(name: String?): PlatformUser? = null
                override fun openShellSession(user: PlatformUser?, command: String?, args: StringIterator?, workingDirectory: String?, pty: Int, ptyWindow: Int): ShellSession? = null
                override fun openTun(options: TunOptions?): Int = -1
                override fun readSystemSSHHostKey(): String = ""
                override fun readWIFIState(): WIFIState? = null
                override fun registerMyInterface(name: String?) {}
                override fun sendNotification(notification: Notification?) {}
                override fun cancelNotification(identifier: String?, id: Int) {}
                override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {}
                override fun startNeighborMonitor(listener: NeighborUpdateListener?) {}
                override fun tailscaleHostname(): String = ""
                override fun underNetworkExtension(): Boolean = false
                override fun usePlatformAutoDetectInterfaceControl(): Boolean = false
                override fun usePlatformBridge(): Boolean = false
                override fun usePlatformShell(): Boolean = false
                override fun useProcFS(): Boolean = false
            }

            server = CommandServer(handler, platformInterface)
            server.start()
            server.startOrReloadService(config, OverrideOptions())

            // Wait up to 500ms for test-in to listen on 127.0.0.1:testPort
            var ready = false
            for (i in 0 until 10) {
                try {
                    val s = Socket()
                    s.connect(InetSocketAddress("127.0.0.1", testPort), 50)
                    s.close()
                    ready = true
                    break
                } catch (e: Exception) {
                    delay(50)
                }
            }

            if (!ready) {
                Log.w(TAG, "Sing-box test listener on port $testPort did not become ready")
                return -1
            }

            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", testPort))
            val targetUrl = URL(target)

            // Request 1: Handshake + Request
            val conn1 = targetUrl.openConnection(proxy) as HttpURLConnection
            if (quickCheck) quickConnection = conn1
            conn1.connectTimeout = 4000
            conn1.readTimeout = 4000
            conn1.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn1.setRequestProperty("Connection", if (quickCheck) "close" else "keep-alive")
            conn1.useCaches = false
            val start1 = System.currentTimeMillis()
            val code1 = conn1.responseCode
            val firstDelay = (System.currentTimeMillis() - start1).toInt()
            try { conn1.inputStream?.close() } catch (e: Exception) {}

            if (code1 != 204 && code1 != 200) {
                Log.w(TAG, "Test request 1 returned HTTP $code1 for ${profile.name}")
                return -1
            }
            if (quickCheck) return firstDelay.coerceAtLeast(1)

            // Request 2: Reused connection for pure Real Delay (RTT without handshake overhead)
            var realDelay = firstDelay
            try {
                val conn2 = targetUrl.openConnection(proxy) as HttpURLConnection
                conn2.connectTimeout = 3000
                conn2.readTimeout = 3000
                conn2.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn2.useCaches = false
                val start2 = System.currentTimeMillis()
                val code2 = conn2.responseCode
                val secondDelay = (System.currentTimeMillis() - start2).toInt()
                try { conn2.inputStream?.close() } catch (e: Exception) {}

                if (code2 == 204 || code2 == 200) {
                    realDelay = secondDelay
                }
            } catch (e: Exception) {
                // If 2nd request fails, keep firstDelay
            }

            Log.i(TAG, "Real delay for ${profile.name}: ${realDelay}ms")
            realDelay
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sing-box delay test failed for ${profile.name}: ${e.message}")
            -1
        } finally {
            quickConnection?.disconnect()
            try {
                server?.closeService()
                server?.close()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }
}
