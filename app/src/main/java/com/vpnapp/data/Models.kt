package com.vpnapp.data

import java.util.UUID

// VPN Protocol types
enum class VpnProtocol(val displayName: String, val description: String) {
    OPENCONNECT("OpenConnect", "Cisco AnyConnect compatible"),
    SSTP("SSTP", "Microsoft Secure Socket Tunneling"),
    VLESS("VLESS", "V2Ray VLESS Protocol"),
    VMESS("VMess", "V2Ray VMess Protocol"),
    TROJAN("Trojan", "Trojan Protocol"),
    SHADOWSOCKS("Shadowsocks", "Shadowsocks Protocol"),
    HYSTERIA2("Hysteria2", "Hysteria v2 Protocol")
}

// Connection state
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}

// Authentication type for OpenConnect (only PASSWORD is currently implemented)
enum class AuthType(val displayName: String) {
    PASSWORD("Password")
}

/**
 * VPN Profile - User-defined connection configuration
 */
data class VpnProfile(
    val id: String = UUID.randomUUID().toString(),
    // Required fields
    val name: String,                    // Profile display name
    val serverAddress: String,           // Server hostname or IP
    val protocol: VpnProtocol = VpnProtocol.OPENCONNECT,
    
    // Authentication
    val port: Int = 443,                 // Default HTTPS port
    val username: String = "",
    val password: String = "",
    val authType: AuthType = AuthType.PASSWORD,
    val skipCertificateVerification: Boolean = true,
    
    // OpenConnect specific (functional options)
    val useNative: Boolean = true,       // Use native libopenconnect for DTLS
    val disableDtls: Boolean = false,    // Enable DTLS by default
    val mtu: Int = 1400,                 // Custom MTU
    
    // Server location (for flag display)
    val countryCode: String = "",        // 2-letter country code (US, DE, JP, etc.)

    // V2Ray Specific Configuration
    val v2rayUuid: String = "",
    val v2rayAlterId: Int = 0,
    val v2raySecurity: String = "auto",
    val vlessEncryption: String = "none", // VLESS protocol encryption, independent of TLS/REALITY
    val v2rayNetwork: String = "tcp", // tcp, kcp, ws, http, quic, grpc
    val v2rayType: String = "none",   // header type: none, http, srtp, utp, wechat-video, dtls, wireguard
    val v2rayHost: String = "",       // WS/HTTP Host
    val v2rayPath: String = "",       // WS/HTTP Path or GRPC Service Name
    val v2rayTls: String = "none",    // none, tls, reality
    val v2rayEch: String = "",
    val v2raySni: String = "",
    val v2rayAlpn: String = "",
    val v2rayFp: String = "",         // fingerprint: chrome, firefox, etc.
    val v2rayFlow: String = "",       // xtls-rprx-vision
    val v2rayPbk: String = "",        // Reality public key
    val v2rayShortId: String = "",    // Reality short ID
    val v2rayXhttpExtra: String = "", // Original JSON extra parameters from an XHTTP share link
    val v2rayOriginalXhttpMode: String? = null, // Original server mode retained while a client policy overrides it
    val v2rayXhttpModeOverride: String? = null,
    val v2raySmartXhttpMode: String? = null, // Last mode that passed a Smart traffic check
    
    // Hysteria2 Specific Configuration
    val hysteriaObfs: String = "",
    val hysteriaObfsPassword: String = "",
    val hysteriaObfsMinPacketSize: Int = 512,
    val hysteriaObfsMaxPacketSize: Int = 1200,
    
    // Latency (TCP ping result in ms, null = not measured)
    val latency: Int? = null,
    
    // Bypass Iran routes (don't proxy Iranian domains/IPs)
    val bypassIranRoutes: Boolean = false,
    
    // Bypass Private IPs (keep local network traffic on physical interface)
    val bypassPrivateIps: Boolean = true,
    
    // Subscription reference
    val subscriptionId: String? = null,
    
    // Profile metadata
    val isDefault: Boolean = false,
    val lastConnected: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Connection Log Entry
 */
data class ConnectionLog(
    val id: String = UUID.randomUUID().toString(),
    val profileId: String,
    val profileName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val message: String
)

enum class LogLevel {
    INFO,
    WARNING,
    ERROR,
    DEBUG,
    SUCCESS
}

// Connection statistics
data class ConnectionStats(
    val uploadSpeed: Long = 0L,
    val downloadSpeed: Long = 0L,
    val totalUploaded: Long = 0L,
    val totalDownloaded: Long = 0L,
    val connectedTime: Long = 0L,
    val privateIp: String = "",      // VPN tunnel IP
    val publicIp: String = "",       // Server's public IP
    val originalIp: String = "",     // Client's original IP before connection
    val countryCode: String? = null, // GeoIP Country Code
    val countryName: String? = null, // GeoIP Country Name
    val mtu: Int = 1100,             // Current MTU
    val transportProtocol: String = "TCP", // e.g., "TCP" or "UDP/DTLS"
    val ping: Long = -1L             // Ping/Latency in ms (-1 if not tested)
)

// Current VPN connection state
data class VpnConnectionInfo(
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val profile: VpnProfile? = null,
    val stats: ConnectionStats = ConnectionStats(),
    val errorMessage: String? = null
)

// VPN Subscription Model
data class VpnSubscription(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val upload: Long = 0L,
    val download: Long = 0L,
    val total: Long = 0L,
    val expire: Long = 0L, // Timestamp in seconds, 0 = no expiration
    val lastUpdated: Long = System.currentTimeMillis()
)
