package com.vpnapp.vpn.singbox

import android.content.Context
import android.util.Log
import com.vpnapp.data.VpnProfile
import com.vpnapp.data.VpnProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress

/**
 * Generates sing-box configuration JSON from a VpnProfile.
 * Supports VLESS, VMess, Trojan, and Shadowsocks protocols.
 */
object SingBoxConfigGenerator {

    private const val TAG = "SingBoxConfigGenerator"

    /**
     * Resolve domain to IP address to avoid DNS resolution issues inside sing-box's Go runtime.
     * The Go runtime cannot resolve domains properly when VPN is active, even with app exclusion.
     */
    private fun resolveServerAddress(address: String, context: Context): String {
        // Check if already an IP address
        val ipRegex = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")
        if (ipRegex.matches(address)) {
            Log.d(TAG, "Server address is already an IP: $address")
            return address
        }

        return try {
            val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
            val network = connectivity.allNetworks.firstOrNull {
                val caps = connectivity.getNetworkCapabilities(it)
                caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                    !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
            }
            val inetAddress = network?.getAllByName(address)?.firstOrNull()
                ?: return address // Let the protected core resolve; never resolve through an old VPN.
            val resolvedIp = inetAddress.hostAddress ?: address
            Log.i(TAG, "Resolved $address to $resolvedIp")
            resolvedIp
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve $address, using as-is: ${e.message}")
            address // Fallback to original if resolution fails
        }
    }

    fun generate(profile: VpnProfile, context: Context, probe: ProbeEndpoint? = null): String {
        val config = JSONObject()

        val isIp = profile.serverAddress.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"))
        // PRE-RESOLVE the server address to IP to avoid Go runtime DNS issues
        val resolvedServerAddress = resolveServerAddress(profile.serverAddress, context)
        
        // Preserve original domain in v2raySni and v2rayHost if empty
        val originalDomain = profile.v2raySni.ifEmpty { if (!isIp) profile.serverAddress else "" }
        val resolvedProfile = profile.copy(
            serverAddress = resolvedServerAddress,
            v2raySni = originalDomain,
            v2rayHost = profile.v2rayHost.ifEmpty { originalDomain }
        )

        // Log configuration
        config.put("log", JSONObject().apply {
            put("level", "warn")
            put("timestamp", true)
        })

        // Applications can retain FakeIP answers after a VPN reconnect. Keep their domain mapping
        // across workers/profiles, using an absolute app-private path (never the core's working dir).
        config.put("experimental", JSONObject().put("cache_file", JSONObject().apply {
            put("enabled", true)
            put("path", java.io.File(context.noBackupFilesDir, "sing-box-fakeip.db").absolutePath)
            put("store_fakeip", true)
        }))

        // DNS configuration - FakeIP mode for ZERO-LATENCY DNS resolution
        // FakeIP returns instant fake IPs (0ms). When connecting to a fake IP,
        // sing-box sends the REAL domain name to the VPN server which resolves it.
        // This bypasses ALL local ISP DNS blocking/filtering.
        config.put("dns", JSONObject().apply {
            put("servers", JSONArray().apply {
                // Real DNS through proxy - standard TCP port 53 through proxy tunnel
                put(JSONObject().apply {
                    put("tag", "proxy-dns")
                    put("type", "tcp")
                    put("server", "8.8.8.8")
                    put("server_port", 53)
                    put("detour", "proxy")
                })
                // FakeIP server: returns instant fake IPs with ZERO latency (IPv4 only)
                put(JSONObject().apply {
                    put("tag", "fakeip")
                    put("type", "fakeip")
                    // sing-box 1.12+: inet4_range inside server definition
                    put("inet4_range", "198.18.0.0/15")
                })
                // Direct DNS for Iran domains: using 8.8.8.8 via direct outbound. 
                // Iranian ISPs transparently hijack UDP 53 to 8.8.8.8 and route it to their internal DNS, 
                // guaranteeing 100% reliable resolution for internal/Iranian domains without relying on Shecan.
                put(JSONObject().apply {
                    put("tag", "direct-dns")
                    put("type", "udp")
                    put("server", "8.8.8.8")
                })
                put(JSONObject().apply {
                    put("tag", "direct-dns-secondary")
                    put("type", "udp")
                    put("server", "1.1.1.1")
                })
                put(JSONObject().apply {
                    put("tag", "direct-dns-fallback")
                    put("type", "udp")
                    put("server", "9.9.9.9")
                })
            })
            put("rules", JSONArray().apply {
                // Direct DNS for VPN server domain (prevents DNS resolution loop)
                if (profile.serverAddress.isNotEmpty() && !isIp) {
                    put(JSONObject().apply {
                        put("domain", JSONArray().apply { put(profile.serverAddress) })
                        put("server", "direct-dns")
                    })
                }
                if (profile.v2raySni.isNotEmpty() && !profile.v2raySni.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"))) {
                    put(JSONObject().apply {
                        put("domain", JSONArray().apply { put(profile.v2raySni) })
                        put("server", "direct-dns")
                    })
                }
                // Iran domains need REAL IPs (for direct routing, not fakeip)
                if (profile.bypassIranRoutes) {
                    val fullIranDomains = IranIps.getDomains(context)
                    put(JSONObject().apply {
                        put("domain_suffix", JSONArray().apply { 
                            put("ir")
                            put(".ir") 
                            fullIranDomains.forEach { put(it) }
                        })
                        put("server", "direct-dns")
                    })
                    Log.i(TAG, "Bypass Iran DNS enabled - ${fullIranDomains.size} Iranian domains + .ir use direct DNS")
                }
                // All other domain queries get FakeIP for instant resolution (both A and AAAA)
                put(JSONObject().apply {
                    put("query_type", JSONArray().apply {
                        put("A")
                        put("AAAA")
                    })
                    put("server", "fakeip")
                })
            })
            put("strategy", "ipv4_only")
            put("final", "proxy-dns")
        })

        // Inbounds - TUN interface
        config.put("inbounds", JSONArray().apply {
            // Authenticated, session-local probe traffic goes through the active proxy core.
            if (probe != null) put(JSONObject().apply {
                put("type", "mixed")
                put("tag", "app-probe")
                put("listen", "127.0.0.1")
                put("listen_port", probe.port)
                put("users", JSONArray().put(JSONObject()
                    .put("username", probe.username).put("password", probe.password)))
            })
            put(JSONObject().apply {
                put("type", "tun")
                put("tag", "tun-in")
                put("interface_name", "tun0")
                put("address", JSONArray().apply { 
                    put("172.19.0.1/30")
                })
                // Standard MTU 1400 to prevent packet fragmentation and buffering on mobile LTE carriers
                val safeMtu = if (profile.mtu in 1200..1500) profile.mtu else 1400
                put("mtu", safeMtu)
                put("auto_route", false) // Handled by Android VpnService Builder
                put("strict_route", false)
                put("stack", "mixed")
                put("endpoint_independent_nat", true)
            })
        })

        // Outbounds - use resolvedProfile with pre-resolved IP address
        config.put("outbounds", buildOutbounds(resolvedProfile))

        // Route configuration
        config.put("route", JSONObject().apply {
            put("auto_detect_interface", true)
            put("override_android_vpn", true)
            put("default_domain_resolver", "direct-dns")
            put("final", "proxy")
            put("rules", JSONArray().apply {
                if (probe != null) put(JSONObject().apply {
                    put("inbound", JSONArray().put("app-probe"))
                    put("outbound", "proxy")
                })
                put(JSONObject().apply {
                    put("action", "sniff")
                    put("sniffer", JSONArray().apply {
                        put("http")
                        put("tls")
                        put("quic")
                        put("dns")
                    })
                })
                put(JSONObject().apply {
                    put("port", JSONArray().apply { put(53) })
                    put("action", "hijack-dns")
                })
                put(JSONObject().apply {
                    put("protocol", "dns")
                    put("action", "hijack-dns")
                })
                // Direct route for VPN server address/IP (prevents routing loops)
                if (profile.serverAddress.isNotEmpty()) {
                    if (isIp) {
                        put(JSONObject().apply {
                            put("ip_cidr", JSONArray().apply { put("${profile.serverAddress}/32") })
                            put("outbound", "direct")
                        })
                    } else {
                        put(JSONObject().apply {
                            put("domain", JSONArray().apply { put(profile.serverAddress) })
                            put("outbound", "direct")
                        })
                    }
                }
                if (resolvedServerAddress.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) && resolvedServerAddress != profile.serverAddress) {
                    put(JSONObject().apply {
                        put("ip_cidr", JSONArray().apply { put("$resolvedServerAddress/32") })
                        put("outbound", "direct")
                    })
                }
                // Direct route for local DNS servers (prevents routing loops and ensures resolution)
                put(JSONObject().apply {
                    put("ip_cidr", JSONArray().apply {
                        put("8.8.8.8/32")
                        put("1.1.1.1/32")
                        put("9.9.9.9/32")
                    })
                    put("outbound", "direct")
                })
                val bypassRules = BypassRouting.rules(
                    profile.bypassIranRoutes, profile.bypassPrivateIps,
                    if (profile.bypassIranRoutes) IranIps.getDomains(context) else emptyList(),
                    if (profile.bypassIranRoutes) IranIps.getCidrs(context) else emptyList()
                )
                for (index in 0 until bypassRules.length()) {
                    put(bypassRules.getJSONObject(index))
                }

                // UDP/QUIC uses the same proxy policy as TCP; applications choose fallback themselves.
            })
        })

        val finalConfig = config.toString(2)
        Log.i(TAG, "Configuration generated: Iran=${profile.bypassIranRoutes}, LAN=${profile.bypassPrivateIps}")
        return finalConfig
    }

    fun buildOutbounds(profile: VpnProfile): JSONArray {
        return JSONArray().apply {
            // Main proxy outbound
            put(buildProxyOutbound(profile))

            // Direct outbound
            put(JSONObject().apply {
                put("type", "direct")
                put("tag", "direct")
            })

            // Block outbound
            put(JSONObject().apply {
                put("type", "block")
                put("tag", "block")
            })
        }
    }

    private fun buildProxyOutbound(profile: VpnProfile): JSONObject {
        return JSONObject().apply {
            put("tag", "proxy")

            when (profile.protocol) {
                VpnProtocol.HYSTERIA2 -> {
                    put("type", "hysteria2")
                    put("server", profile.serverAddress)
                    put("server_port", profile.port)
                    put("password", profile.password)
                    if (profile.hysteriaObfs.lowercase() == "salamander") {
                        put("obfs", JSONObject().apply {
                            put("type", "salamander")
                            put("password", profile.hysteriaObfsPassword)
                        })
                    }
                    put("tls", JSONObject().apply {
                        put("enabled", true)
                        if (profile.v2raySni.isNotEmpty()) put("server_name", profile.v2raySni)
                        put("insecure", profile.skipCertificateVerification)
                        if (profile.v2rayAlpn.isNotEmpty()) {
                            val alpnList = JSONArray()
                            profile.v2rayAlpn.split(",").forEach { alpnList.put(it.trim()) }
                            put("alpn", alpnList)
                        }
                    })
                }

                VpnProtocol.VLESS -> {
                    put("type", "vless")
                    put("server", profile.serverAddress)
                    put("server_port", profile.port)
                    put("uuid", profile.v2rayUuid)
                    profile.vlessEncryption.trim().takeIf { it.isNotEmpty() && it != "none" }?.let {
                        put("encryption", it)
                    }
                    put("packet_encoding", "xudp")
                    
                    if (profile.v2rayFlow.isNotEmpty()) {
                        put("flow", profile.v2rayFlow)
                    }

                    // TLS/Reality configuration
                    when (profile.v2raySecurity) {
                        "reality" -> put("tls", buildRealityTls(profile))
                        "tls" -> put("tls", buildTls(profile))
                    }
                    if (profile.v2rayTls == "tls" && profile.v2raySecurity != "reality") {
                        put("tls", buildTls(profile))
                    }

                    // Transport
                    buildTransport(profile)?.let { put("transport", it) }
                }

                VpnProtocol.VMESS -> {
                    put("type", "vmess")
                    put("server", profile.serverAddress)
                    put("server_port", profile.port)
                    put("uuid", profile.v2rayUuid)
                    put("alter_id", profile.v2rayAlterId)
                    put("security", profile.v2raySecurity.ifEmpty { "auto" })
                    put("packet_encoding", "xudp")

                    if (profile.v2rayTls == "tls") {
                        put("tls", buildTls(profile))
                    }
                    buildTransport(profile)?.let { put("transport", it) }
                }

                VpnProtocol.TROJAN -> {
                    put("type", "trojan")
                    put("server", profile.serverAddress)
                    put("server_port", profile.port)
                    put("password", profile.password)
                    put("tls", buildTls(profile))
                    buildTransport(profile)?.let { put("transport", it) }
                }

                VpnProtocol.SHADOWSOCKS -> {
                    put("type", "shadowsocks")
                    put("server", profile.serverAddress)
                    put("server_port", profile.port)
                    put("method", profile.username) // Method is stored in username field
                    put("password", profile.password)
                }

                else -> throw IllegalArgumentException("Unsupported protocol: ${profile.protocol}")
            }
        }
    }

    private fun isIpAddress(addr: String): Boolean {
        val clean = addr.trim().trim('[', ']')
        return clean.matches(Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) || clean.contains(":")
    }

    private fun buildTls(profile: VpnProfile): JSONObject {
        return JSONObject().apply {
            put("enabled", true)
            val sni = profile.v2raySni.ifEmpty { profile.v2rayHost }.ifEmpty { profile.serverAddress }
            if (sni.isNotEmpty() && !isIpAddress(sni)) {
                put("server_name", sni)
            }
            put("insecure", profile.skipCertificateVerification)
            
            // Enforce correct ALPN based on network type to prevent misconfiguration and crashes
            val network = profile.v2rayNetwork.lowercase()
            if (network == "ws" || network == "websocket" || network == "httpupgrade") {
                put("alpn", JSONArray().apply { put("http/1.1") }) // ws/httpupgrade require http/1.1
            } else if (network == "h2" || network == "http") {
                put("alpn", JSONArray().apply { put("h2") })
            } else if (profile.v2rayAlpn.isNotEmpty()) {
                put("alpn", JSONArray().apply {
                    profile.v2rayAlpn.split(",").forEach { alpn ->
                        put(alpn.trim())
                    }
                })
            } else {
                put("alpn", JSONArray().apply {
                    put("h2")
                    put("http/1.1")
                })
            }
            
            if (profile.v2rayFp.isNotEmpty()) {
                put("utls", JSONObject().apply {
                    put("enabled", true)
                    put("fingerprint", profile.v2rayFp)
                })
            }
        }
    }

    private fun buildRealityTls(profile: VpnProfile): JSONObject {
        return JSONObject().apply {
            put("enabled", true)
            val sni = profile.v2raySni.ifEmpty { profile.v2rayHost }.ifEmpty { profile.serverAddress }
            if (sni.isNotEmpty() && !isIpAddress(sni)) {
                put("server_name", sni)
            }
            put("reality", JSONObject().apply {
                put("enabled", true)
                put("public_key", profile.v2rayPbk)
                if (profile.v2rayShortId.isNotEmpty()) {
                    put("short_id", profile.v2rayShortId)
                }
            })
            
            // Enforce correct ALPN based on network type
            val network = profile.v2rayNetwork.lowercase()
            if (network == "ws" || network == "websocket" || network == "httpupgrade") {
                put("alpn", JSONArray().apply { put("http/1.1") })
            } else if (network == "h2" || network == "http") {
                put("alpn", JSONArray().apply { put("h2") })
            } else if (profile.v2rayAlpn.isNotEmpty()) {
                put("alpn", JSONArray().apply {
                    profile.v2rayAlpn.split(",").forEach { alpn ->
                        put(alpn.trim())
                    }
                })
            } else {
                put("alpn", JSONArray().apply {
                    put("h2")
                    put("http/1.1")
                })
            }
            
            if (profile.v2rayFp.isNotEmpty()) {
                put("utls", JSONObject().apply {
                    put("enabled", true)
                    put("fingerprint", profile.v2rayFp)
                })
            }
        }
    }

    private fun buildTransport(profile: VpnProfile): JSONObject? {
        val fallbackHost = profile.v2rayHost.ifEmpty { 
            profile.v2raySni.ifEmpty { 
                if (!isIpAddress(profile.serverAddress)) profile.serverAddress else "" 
            } 
        }

        return when (profile.v2rayNetwork.lowercase()) {
            "ws", "websocket" -> JSONObject().apply {
                put("type", "ws")
                var path = profile.v2rayPath.ifEmpty { "/" }
                var maxEarlyData: Int? = null
                var earlyDataHeaderName: String? = null

                if (path.contains("ed=") || path.contains("eh=")) {
                    val uriParts = path.split("?", limit = 2)
                    val basePath = uriParts[0]
                    if (uriParts.size > 1) {
                        val queryParams = uriParts[1].split("&")
                        val remainingParams = mutableListOf<String>()
                        for (param in queryParams) {
                            when {
                                param.startsWith("ed=") -> {
                                    val edVal = param.substring(3).toIntOrNull()
                                    if (edVal != null && edVal > 0) {
                                        maxEarlyData = edVal
                                        earlyDataHeaderName = "Sec-WebSocket-Protocol"
                                    }
                                }
                                param.startsWith("eh=") -> {
                                    val rawEh = param.substring(3)
                                    earlyDataHeaderName = try {
                                        java.net.URLDecoder.decode(rawEh, "UTF-8")
                                    } catch (e: Exception) {
                                        rawEh
                                    }
                                }
                                param.isNotEmpty() -> remainingParams.add(param)
                            }
                        }
                        path = if (remainingParams.isEmpty()) {
                            basePath
                        } else {
                            "$basePath?${remainingParams.joinToString("&")}"
                        }
                    }
                }
                if (path.isEmpty()) path = "/"

                put("path", path)
                if (fallbackHost.isNotEmpty()) {
                    put("headers", JSONObject().put("Host", fallbackHost))
                }
                if (maxEarlyData != null && maxEarlyData > 0) {
                    put("max_early_data", maxEarlyData)
                    put("early_data_header_name", earlyDataHeaderName ?: "Sec-WebSocket-Protocol")
                }
            }
            "httpupgrade" -> JSONObject().apply {
                put("type", "httpupgrade")
                put("path", profile.v2rayPath.ifEmpty { "/" })
                if (fallbackHost.isNotEmpty()) {
                    put("host", fallbackHost)
                }
            }
            "xhttp", "splithttp" -> XHttpOptions.build(profile)
            "grpc" -> JSONObject().apply {
                put("type", "grpc")
                put("service_name", profile.v2rayPath)
            }
            "http", "h2" -> JSONObject().apply {
                put("type", "http")
                put("path", profile.v2rayPath.ifEmpty { "/" })
                if (profile.v2rayHost.isNotEmpty()) {
                    put("host", JSONArray().put(profile.v2rayHost))
                }
            }
            "tcp", "" -> null // TCP doesn't need transport config
            else -> null
        }
    }
}

