package com.vpnapp.data

import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object V2RayLinkParser {

    /**
     * Parse any supported share link
     */
    fun parseLink(link: String): VpnProfile? {
        val trimmedLink = link.trim()
        return when {
            trimmedLink.startsWith("vless://") -> parseVless(trimmedLink)
            trimmedLink.startsWith("vmess://") -> parseVmess(trimmedLink)
            trimmedLink.startsWith("trojan://") -> parseTrojan(trimmedLink)
            trimmedLink.startsWith("ss://") -> parseShadowsocks(trimmedLink)
            trimmedLink.startsWith("hysteria2://") -> parseHysteria2(trimmedLink)
            else -> null
        }
    }

    /**
     * Parse VLESS link
     */
    private fun parseVless(link: String): VpnProfile? {
        try {
            // Replace vless:// with http:// for easier URI parsing
            val uri = Uri.parse(link.replace("vless://", "http://"))
            val userInfo = uri.userInfo ?: return null
            val uuid = userInfo
            val host = uri.host?.removeSurrounding("[", "]") ?: return null
            val port = if (uri.port != -1) uri.port else 443
            val name = uri.fragment ?: "VLESS-$host"

            val type = uri.getQueryParameter("type") ?: "tcp"
            val security = uri.getQueryParameter("security") ?: "none"
            val sni = uri.getQueryParameter("sni") ?: ""
            var alpn = uri.getQueryParameter("alpn") ?: ""
            // Allow ALPN from link to pass through, even for WebSocket (e.g. WS over HTTP/2)
            val path = uri.getQueryParameter("path") ?: uri.getQueryParameter("serviceName") ?: ""
            var wsHost = uri.getQueryParameter("host") ?: ""
            if (wsHost.isEmpty() && (type == "ws" || type == "xhttp" || type == "splithttp" || type == "httpupgrade")) {
                wsHost = sni.ifEmpty { host }
            }
            val fp = uri.getQueryParameter("fp") ?: "chrome"
            val flow = uri.getQueryParameter("flow") ?: ""
            
            // Reality specific parameters
            val pbk = uri.getQueryParameter("pbk") ?: ""
            val sid = uri.getQueryParameter("sid") ?: ""
            
            // xhttp mode or headerType
            val xhttpMode = if (type == "xhttp" || type == "splithttp") {
                uri.getQueryParameter("mode") ?: uri.getQueryParameter("xhttpMode") ?: "auto"
            } else {
                uri.getQueryParameter("headerType") ?: "none"
            }

            return VpnProfile(
                name = name,
                serverAddress = host,
                protocol = VpnProtocol.VLESS,
                port = port,
                v2rayUuid = uuid,
                v2rayNetwork = type,
                v2rayType = xhttpMode,
                v2rayXhttpExtra = uri.getQueryParameter("extra") ?: "",
                v2raySecurity = if (security == "tls") "none" else security,
                vlessEncryption = uri.getQueryParameter("encryption")?.trim()?.ifEmpty { "none" } ?: "none",
                v2raySni = sni,
                v2rayAlpn = alpn,
                v2rayPath = path,
                v2rayHost = wsHost,
                v2rayFp = fp,
                v2rayFlow = flow,
                v2rayPbk = pbk,
                v2rayShortId = sid,
                v2rayTls = if (security == "tls" || security == "reality") "tls" else "none",
                skipCertificateVerification = uri.getQueryParameter("insecure") == "1" || uri.getQueryParameter("allowInsecure") == "1"
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * Parse VMess link (base64 encoded JSON)
     */
    private fun parseVmess(link: String): VpnProfile? {
        try {
            val base64 = link.replace("vmess://", "")
            val jsonStr = String(Base64.decode(base64, Base64.DEFAULT), StandardCharsets.UTF_8)
            val json = JSONObject(jsonStr)

            val serverAddress = json.optString("add")
            val port = json.optString("port").toIntOrNull() ?: 443
            val decodedName = json.optString("ps", "VMess-$serverAddress")
            val uuid = json.optString("id")
            val net = json.optString("net", "tcp")
            var type = json.optString("type", "none")
            if ((net == "xhttp" || net == "splithttp") && type == "none") {
                type = json.optString("mode", "auto")
            }
            val sni = json.optString("sni", "")
            var host = json.optString("host", "")
            if (host.isEmpty() && (net == "ws" || net == "xhttp" || net == "splithttp" || net == "httpupgrade")) {
                host = sni.ifEmpty { serverAddress }
            }
            val path = json.optString("path", "")
            val tls = json.optString("tls", "")
            // sni already declared above
            var alpn = json.optString("alpn", "")
            // Allow ALPN from link to pass through, even for WebSocket (e.g. WS over HTTP/2)
            val fp = json.optString("fp", "")

            return VpnProfile(
                name = decodedName,
                serverAddress = serverAddress,
                port = port,
                protocol = VpnProtocol.VMESS,
                v2rayUuid = uuid,
                v2rayAlterId = json.optInt("aid", 0),
                v2rayXhttpExtra = json.optString("extra", ""),
                v2rayNetwork = net,
                v2rayType = type,
                v2rayHost = host,
                v2rayPath = path,
                v2rayTls = tls,
                v2raySni = sni,
                v2rayAlpn = alpn,
                v2rayFp = fp,
                v2raySecurity = json.optString("scy", "auto"),
                skipCertificateVerification = json.optBoolean("allowInsecure", false)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * Parse Trojan link
     */
    private fun parseTrojan(link: String): VpnProfile? {
        try {
            val uri = Uri.parse(link.replace("trojan://", "http://"))
            val password = uri.userInfo ?: return null
            val host = uri.host?.removeSurrounding("[", "]") ?: return null
            val port = if (uri.port != -1) uri.port else 443
            val name = uri.fragment ?: "Trojan-$host"

            val type = uri.getQueryParameter("type") ?: "tcp"
            val security = uri.getQueryParameter("security") ?: "tls"
            val sni = uri.getQueryParameter("sni") ?: host
            var alpn = uri.getQueryParameter("alpn") ?: ""
            // Allow ALPN from link to pass through, even for WebSocket (e.g. WS over HTTP/2)
            val path = uri.getQueryParameter("path") ?: uri.getQueryParameter("serviceName") ?: ""
            var wsHost = uri.getQueryParameter("host") ?: ""
            if (wsHost.isEmpty() && (type == "ws" || type == "xhttp" || type == "splithttp" || type == "httpupgrade")) {
                wsHost = sni.ifEmpty { host }
            }
            val fp = uri.getQueryParameter("fp") ?: "chrome"
            val mode = if (type == "xhttp" || type == "splithttp") {
                uri.getQueryParameter("mode") ?: uri.getQueryParameter("xhttpMode") ?: "auto"
            } else {
                "none"
            }

            return VpnProfile(
                name = name,
                serverAddress = host,
                protocol = VpnProtocol.TROJAN,
                port = port,
                password = password, // Trojan uses password field
                v2rayUuid = password, // Store in uuid as well for consistency
                v2rayNetwork = type,
                v2rayType = mode,
                v2rayXhttpExtra = uri.getQueryParameter("extra") ?: "",
                v2raySecurity = security,
                v2raySni = sni,
                v2rayAlpn = alpn,
                v2rayPath = path,
                v2rayHost = wsHost,
                v2rayFp = fp,
                v2rayTls = if (security == "tls") "tls" else "none",
                skipCertificateVerification = uri.getQueryParameter("insecure") == "1" || uri.getQueryParameter("allowInsecure") == "1"
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * Parse Shadowsocks link (SIP002 or Legacy)
     */
    private fun parseShadowsocks(link: String): VpnProfile? {
        try {
            var raw = link.replace("ss://", "")
            var name = ""
            
            if (raw.contains("#")) {
                val parts = raw.split("#", limit = 2)
                raw = parts[0]
                name = URLDecoder.decode(parts[1], StandardCharsets.UTF_8.toString())
            }

            var serverInfoString = ""
            
            // Check for SIP002 format (userinfo@host:port)
            if (raw.contains("@")) {
                val parts = raw.split("@", limit = 2)
                val userInfoEncoded = parts[0]
                val hostPort = parts[1]
                
                // userInfo is base64(method:password)
                // Need to handle URL-safe base64 potentially
                val userInfo = try {
                    String(Base64.decode(userInfoEncoded, Base64.URL_SAFE), StandardCharsets.UTF_8)
                } catch (e: Exception) {
                    String(Base64.decode(userInfoEncoded, Base64.DEFAULT), StandardCharsets.UTF_8)
                }
                
                serverInfoString = "$userInfo@$hostPort"
            } else {
                // Legacy format: base64(method:password@host:port)
                serverInfoString = try {
                    String(Base64.decode(raw, Base64.URL_SAFE), StandardCharsets.UTF_8)
                } catch (e: Exception) {
                    String(Base64.decode(raw, Base64.DEFAULT), StandardCharsets.UTF_8)
                }
            }

            // method:password@host:port
            // Split last : for port
            val lastColon = serverInfoString.lastIndexOf(':')
            val port = serverInfoString.substring(lastColon + 1).toInt()
            val everythingElse = serverInfoString.substring(0, lastColon)
            
            // Split last @ for host
            val lastAt = everythingElse.lastIndexOf('@')
            val host = everythingElse.substring(lastAt + 1).removeSurrounding("[", "]")
            val methodPassword = everythingElse.substring(0, lastAt)
            
            // Split : for method and password
            val methodPasswordParts = methodPassword.split(":", limit = 2)
            val method = methodPasswordParts[0]
            val password = if (methodPasswordParts.size > 1) methodPasswordParts[1] else ""

            if (name.isEmpty()) name = "SS-$host"

            return VpnProfile(
                name = name,
                serverAddress = host,
                protocol = VpnProtocol.SHADOWSOCKS,
                port = port,
                password = password,
                username = method, // Store method in username for SS
                v2raySecurity = "none",
                v2rayNetwork = "tcp",
                skipCertificateVerification = false
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * Parse Hysteria2 link
     */
    private fun parseHysteria2(link: String): VpnProfile? {
        try {
            val uri = Uri.parse(link.replace("hysteria2://", "http://"))
            val password = uri.userInfo ?: return null
            val host = uri.host?.removeSurrounding("[", "]") ?: return null
            val port = if (uri.port != -1) uri.port else 443
            val name = uri.fragment ?: "Hysteria2-$host"

            val alpn = uri.getQueryParameter("alpn") ?: ""
            val fp = uri.getQueryParameter("fp") ?: ""
            val obfs = uri.getQueryParameter("obfs") ?: ""
            val obfsPassword = uri.getQueryParameter("obfs-password") ?: ""
            val sni = uri.getQueryParameter("sni") ?: host

            return VpnProfile(
                name = name,
                serverAddress = host,
                port = port,
                protocol = VpnProtocol.HYSTERIA2,
                password = password,
                v2raySni = sni,
                v2rayAlpn = alpn,
                v2rayFp = fp,
                hysteriaObfs = obfs,
                hysteriaObfsPassword = obfsPassword,
                skipCertificateVerification = uri.getQueryParameter("insecure")?.let { it == "1" } ?: true
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * Download and parse a subscription URL
     * Returns a pair of VpnSubscription and list of parsed VpnProfiles.
     * This must be called from a background thread (Dispatchers.IO).
     */
    fun fetchSubscription(subscriptionUrl: String, subscriptionId: String, currentName: String? = null): Pair<VpnSubscription, List<VpnProfile>>? {
        try {
            val url = java.net.URL(subscriptionUrl)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "sing-box") // Tell the server we are a sing-box client so it returns correct config formats
            
            val responseCode = conn.responseCode
            if (responseCode != 200) {
                return null
            }
            
            // Read headers for Userinfo (upload, download, total, expire)
            var upload = 0L
            var download = 0L
            var total = 0L
            var expire = 0L
            var name = currentName ?: url.host ?: "Subscription"
            
            // Check headers case-insensitively
            for (key in conn.headerFields.keys) {
                if (key == null) continue
                if (key.equals("subscription-userinfo", ignoreCase = true)) {
                    val value = conn.getHeaderField(key)
                    val parts = value.split(";")
                    for (part in parts) {
                        val kv = part.trim().split("=")
                        if (kv.size == 2) {
                            val k = kv[0].trim().lowercase()
                            val v = kv[1].trim().toLongOrNull() ?: 0L
                            when (k) {
                                "upload" -> upload = v
                                "download" -> download = v
                                "total" -> total = v
                                "expire" -> expire = v
                            }
                        }
                    }
                } else if (key.equals("profile-title", ignoreCase = true)) {
                    if (currentName == null) {
                        val value = conn.getHeaderField(key)
                        if (value.startsWith("base64:")) {
                            try {
                                name = String(Base64.decode(value.replace("base64:", ""), Base64.DEFAULT), StandardCharsets.UTF_8)
                            } catch (e: Exception) {}
                        } else {
                            name = value
                        }
                    }
                }
            }
            
            val reader = java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream, StandardCharsets.UTF_8))
            val contentBuilder = java.lang.StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                contentBuilder.append(line).append("\n")
            }
            reader.close()
            conn.disconnect()
            
            val content = contentBuilder.toString().trim()
            val decoded = try {
                String(Base64.decode(content, Base64.DEFAULT), StandardCharsets.UTF_8)
            } catch (e: Exception) {
                // If not base64, maybe it's raw list
                content
            }
            
            val lines = decoded.split("\n")
            val profiles = mutableListOf<VpnProfile>()
            for (l in lines) {
                val cleanLine = l.trim()
                if (cleanLine.isEmpty()) continue
                val parsed = parseLink(cleanLine)
                if (parsed != null) {
                    // Associate with this subscription
                    profiles.add(parsed.copy(
                        subscriptionId = subscriptionId
                    ))
                }
            }
            
            val subscription = VpnSubscription(
                id = subscriptionId,
                name = name,
                url = subscriptionUrl,
                upload = upload,
                download = download,
                total = total,
                expire = expire,
                lastUpdated = System.currentTimeMillis()
            )
            
            return Pair(subscription, profiles)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}

