package com.vpnapp.vpn.singbox

import android.content.Context
import android.util.Log
import com.vpnapp.data.VpnProfile
import com.vpnapp.data.VpnProtocol
import com.vpnapp.data.LatencyTestState
import com.vpnapp.util.ProbeResources
import com.vpnapp.util.cancellableProbe
import io.nekohasekai.libbox.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
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
    private val testSlots = Semaphore(LatencyTestState.MAX_CONCURRENT_PROBES)
    private const val PROBE_TIMEOUT_MS = 10_000L
    @Volatile private var initialized = false
    @Synchronized private fun initialize(context: Context) {
        if (initialized) return
        Libbox.setup(SetupOptions().apply {
            setBasePath(java.io.File(context.filesDir, "latency-core").apply { mkdirs() }.absolutePath)
            setWorkingPath(java.io.File(context.cacheDir, "latency-core").apply { mkdirs() }.absolutePath)
            setTempPath(java.io.File(context.cacheDir, "latency-core").absolutePath)
        })
        initialized = true
    }

    suspend fun testProfileDelay(context: Context, profile: VpnProfile,
        targetUrl: String = "https://www.google.com/generate_204", quickCheck: Boolean = false): Int = withContext(Dispatchers.IO) {
        testSlots.withPermit {
            withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                cancellableProbe { resources ->
                    if (profile.protocol == VpnProtocol.OPENCONNECT || profile.protocol == VpnProtocol.SSTP) {
                        testTlsServerDelay(profile, resources)
                    } else testSingBoxDelay(context, profile, targetUrl, quickCheck, resources)
                }
            } ?: -1
        }
    }

    // This checks server TLS reachability, not credentials or a completed VPN tunnel.
    private suspend fun testTlsServerDelay(profile: VpnProfile, resources: ProbeResources): Int {
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
                coroutineContext.ensureActive()
                try {
                    val start = System.nanoTime()
                    val elapsed = Socket().use { raw ->
                        resources.register { raw.close() }
                        raw.soTimeout = 4000
                        raw.connect(InetSocketAddress(address, profile.port), 4000)
                        (factory.createSocket(raw, profile.serverAddress, profile.port, true) as SSLSocket).use { tls ->
                            resources.register { tls.close() }
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
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { failure = e }
            }
            throw failure ?: IllegalStateException("No server addresses")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            Log.w(TAG, "${profile.protocol} TLS delay test failed for ${profile.name}: ${e.message}")
            -1
        }
    }

    private suspend fun testSingBoxDelay(context: Context, profile: VpnProfile, target: String, quickCheck: Boolean, resources: ProbeResources): Int {
        return try {
            val testPort = try {
                ServerSocket(0).use { it.localPort }
            } catch (_: Exception) {
                return -1
            }

            initialize(context)

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
            coroutineContext.ensureActive()

            val proxyOutbound = SingBoxConfigGenerator.buildOutbounds(resolvedProfile).getJSONObject(0)
            // CommandServer enables cache-file internally even without an explicit enabled flag.
            // Sharing the default cache.db makes bbolt's exclusive file lock serialize all probes.
            val cacheDirectory = java.io.File(context.cacheDir, "latency-core/probe-cache").apply { mkdirs() }
            val cacheFile = java.io.File(cacheDirectory, "probe-${java.util.UUID.randomUUID()}.db")

            val config = JSONObject().apply {
                put("log", JSONObject().apply {
                    put("level", "panic")
                })
                put("experimental", JSONObject().put("cache_file", JSONObject()
                    .put("enabled", true).put("path", cacheFile.absolutePath)))
                put("dns", JSONObject().apply {
                    put("servers", JSONArray().apply {
                        put(JSONObject().apply {
                            put("tag", "direct-dns")
                            put("type", "udp")
                            put("server", "1.1.1.1")
                        })
                        if (profile.v2rayEch.isNotBlank()) put(TunnelDns.echResolver(profile.v2rayEch))
                    })
                    if (profile.v2rayEch.isNotBlank()) put("rules", JSONArray().put(JSONObject()
                        .put("domain", JSONArray().put(TunnelDns.echQueryName(profile.v2rayEch)))
                        .put("server", "ech-dns")))
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
                    put("default_domain_resolver", "direct-dns")
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

            val server = CommandServer(handler, platformInterface)
            resources.register {
                try { server.closeService() } finally { server.close() }
            }
            resources.register { cacheFile.delete() }
            // Direct in-process calls need no gRPC command.sock listener. Each test has its own
            // core instance and loopback proxy port, so concurrent tests cannot unlink one another's socket.
            server.startOrReloadService(config, OverrideOptions())
            coroutineContext.ensureActive()

            // Wait up to 500ms for test-in to listen on 127.0.0.1:testPort
            var ready = false
            for (i in 0 until 10) {
                try {
                    Socket().use { it.connect(InetSocketAddress("127.0.0.1", testPort), 50) }
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
            Log.d(TAG, "Delay listener ready for ${profile.name} on $testPort")

            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", testPort))
            val targetUrl = URL(target)

            // Request 1: Handshake + Request
            val conn1 = targetUrl.openConnection(proxy) as HttpURLConnection
            resources.register { conn1.disconnect() }
            coroutineContext.ensureActive()
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
            coroutineContext.ensureActive()

            // Request 2: Reused connection for pure Real Delay (RTT without handshake overhead)
            var realDelay = firstDelay
            try {
                val conn2 = targetUrl.openConnection(proxy) as HttpURLConnection
                resources.register { conn2.disconnect() }
                coroutineContext.ensureActive()
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
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                // If 2nd request fails, keep firstDelay
            }

            Log.i(TAG, "Real delay for ${profile.name}: ${realDelay}ms")
            realDelay
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sing-box delay test failed for ${profile.name}: ${e.message}")
            -1
        }
    }
}
