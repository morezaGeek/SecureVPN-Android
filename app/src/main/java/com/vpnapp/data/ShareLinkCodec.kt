package com.vpnapp.data

import android.net.Uri
import android.util.Base64
import org.json.JSONObject

sealed interface LinkImport {
    data class Profile(val profile: VpnProfile) : LinkImport
    data class Subscription(val url: String) : LinkImport
}

/** Share connection parameters, never local IDs, timestamps or session statistics. */
object ShareLinkCodec {
    fun parseImport(text: String): LinkImport? {
        val value = text.trim()
        if (value.isEmpty() || value.length > 32_768 || value.any { it.isWhitespace() }) return null
        val uri = Uri.parse(value)
        if (uri.scheme in listOf("https", "http")) {
            return if (!uri.host.isNullOrBlank() && uri.userInfo == null &&
                (uri.port == -1 || uri.port in 1..65535)) LinkImport.Subscription(value) else null
        }
        val profile = if (uri.scheme in listOf("openconnect", "sstp")) parsePasswordProfile(uri)
            else V2RayLinkParser.parseLink(value)
        return profile?.takeIf { p -> p.serverAddress.isNotBlank() && p.port in 1..65535 && when (p.protocol) {
            VpnProtocol.VLESS, VpnProtocol.VMESS -> p.v2rayUuid.isNotBlank()
            VpnProtocol.TROJAN, VpnProtocol.SHADOWSOCKS, VpnProtocol.HYSTERIA2 -> p.password.isNotBlank()
            else -> p.username.isNotBlank() && p.password.isNotBlank()
        } }?.let(LinkImport::Profile)
    }

    private fun parsePasswordProfile(uri: Uri): VpnProfile? {
        val user = uri.encodedUserInfo?.split(":", limit = 2) ?: return null
        if (user.size != 2) return null
        val host = uri.host?.removeSurrounding("[", "]") ?: return null
        return VpnProfile(name = uri.fragment ?: "${uri.scheme}-$host", serverAddress = host,
            protocol = if (uri.scheme == "sstp") VpnProtocol.SSTP else VpnProtocol.OPENCONNECT,
            username = Uri.decode(user[0]), password = Uri.decode(user[1]),
            port = uri.port.takeIf { it != -1 } ?: 443,
            skipCertificateVerification = uri.getQueryParameter("insecure") == "1",
            disableDtls = uri.getQueryParameter("dtls") == "0",
            useNative = uri.getQueryParameter("native") != "0",
            mtu = uri.getQueryParameter("mtu")?.toIntOrNull()?.coerceIn(1280, 1500) ?: 1400)
    }

    fun export(profile: VpnProfile): String {
        require(profile.serverAddress.isNotBlank() && profile.port in 1..65535)
        val host = profile.serverAddress.removeSurrounding("[", "]").let { if (':' in it) "[$it]" else it }
        fun link(scheme: String, credential: String, parameters: Map<String, String>): String {
            val query = parameters.filterValues { it.isNotEmpty() }.entries
                .joinToString("&") { (k, v) -> "${Uri.encode(k)}=${Uri.encode(v)}" }
            return "$scheme://$credential@$host:${profile.port}" +
                (if (query.isEmpty()) "" else "?$query") + "#${Uri.encode(profile.name)}"
        }
        val tls = when {
            profile.v2rayTls == "reality" || profile.v2rayPbk.isNotEmpty() || profile.v2raySecurity == "reality" -> "reality"
            profile.v2rayTls == "tls" -> "tls"
            else -> "none"
        }
        val mode = if (profile.v2rayXhttpModeOverride == "smart")
            profile.v2raySmartXhttpMode ?: profile.v2rayType else profile.v2rayType
        val transport = linkedMapOf("type" to profile.v2rayNetwork, "security" to tls,
            "sni" to profile.v2raySni, "alpn" to profile.v2rayAlpn, "fp" to profile.v2rayFp,
            "host" to profile.v2rayHost, "path" to profile.v2rayPath,
            "serviceName" to profile.v2rayPath.takeIf { profile.v2rayNetwork == "grpc" }.orEmpty(),
            "flow" to profile.v2rayFlow, "pbk" to profile.v2rayPbk, "sid" to profile.v2rayShortId,
            "insecure" to if (profile.skipCertificateVerification) "1" else "0")
        if (XHttpModePolicy.isXHttp(profile)) {
            transport["mode"] = mode
            transport["extra"] = profile.v2rayXhttpExtra
        } else transport["headerType"] = mode
        return when (profile.protocol) {
            VpnProtocol.VLESS -> link("vless", Uri.encode(profile.v2rayUuid),
                transport + ("encryption" to profile.vlessEncryption))
            VpnProtocol.TROJAN -> link("trojan", Uri.encode(profile.password.ifEmpty { profile.v2rayUuid }), transport)
            VpnProtocol.VMESS -> {
                val json = JSONObject().apply {
                    put("v", "2"); put("ps", profile.name); put("add", profile.serverAddress.removeSurrounding("[", "]"))
                    put("port", profile.port); put("id", profile.v2rayUuid); put("aid", profile.v2rayAlterId)
                    put("scy", profile.v2raySecurity); put("net", profile.v2rayNetwork); put("type", mode)
                    put("host", profile.v2rayHost); put("path", profile.v2rayPath); put("tls", profile.v2rayTls)
                    put("sni", profile.v2raySni); put("alpn", profile.v2rayAlpn); put("fp", profile.v2rayFp)
                    put("extra", profile.v2rayXhttpExtra); put("allowInsecure", profile.skipCertificateVerification)
                }
                "vmess://" + Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            }
            VpnProtocol.SHADOWSOCKS -> link("ss", Base64.encodeToString(
                "${profile.username}:${profile.password}".toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), emptyMap())
            VpnProtocol.HYSTERIA2 -> link("hysteria2", Uri.encode(profile.password), mapOf(
                "sni" to profile.v2raySni, "alpn" to profile.v2rayAlpn, "fp" to profile.v2rayFp,
                "obfs" to profile.hysteriaObfs, "obfs-password" to profile.hysteriaObfsPassword,
                "insecure" to if (profile.skipCertificateVerification) "1" else "0"))
            VpnProtocol.OPENCONNECT, VpnProtocol.SSTP -> link(profile.protocol.name.lowercase(),
                "${Uri.encode(profile.username)}:${Uri.encode(profile.password)}", mapOf(
                    "insecure" to if (profile.skipCertificateVerification) "1" else "0",
                    "dtls" to if (profile.disableDtls) "0" else "1", "mtu" to profile.mtu.toString(),
                    "native" to if (profile.useNative) "1" else "0"))
        }
    }
}
