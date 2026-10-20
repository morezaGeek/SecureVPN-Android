package com.vpnapp.data

/** Only transport mode may change; credentials, encryption and TLS stay intact. */
object XHttpSmartPolicy {
    val modes = listOf("auto", "stream-up", "packet-up", "stream-one")

    fun sameConnection(a: VpnProfile, b: VpnProfile): Boolean =
        a.protocol == b.protocol && a.serverAddress == b.serverAddress && a.port == b.port &&
            a.v2rayNetwork.equals(b.v2rayNetwork, true) && a.v2rayPath == b.v2rayPath &&
            a.v2rayHost == b.v2rayHost && a.v2raySni == b.v2raySni && a.v2rayEch == b.v2rayEch && a.v2rayUuid == b.v2rayUuid &&
            a.vlessEncryption == b.vlessEncryption && a.v2rayTls == b.v2rayTls &&
            a.v2raySecurity == b.v2raySecurity && a.v2rayAlpn == b.v2rayAlpn &&
            a.v2rayFp == b.v2rayFp && a.v2rayFlow == b.v2rayFlow &&
            a.v2rayPbk == b.v2rayPbk && a.v2rayShortId == b.v2rayShortId &&
            a.skipCertificateVerification == b.skipCertificateVerification && a.v2rayXhttpExtra == b.v2rayXhttpExtra

    fun candidates(profile: VpnProfile): List<String> =
        (listOfNotNull(profile.v2raySmartXhttpMode, profile.v2rayOriginalXhttpMode ?: profile.v2rayType) + modes)
            .filter { it in modes }.distinct()

    fun candidate(profile: VpnProfile, mode: String): VpnProfile {
        require(mode in modes)
        return profile.copy(v2rayType = mode)
    }
}
