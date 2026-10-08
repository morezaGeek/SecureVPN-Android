package com.vpnapp.data

/** Reversible client policy; subscription transport extras remain untouched. */
object XHttpModePolicy {
    const val PREFERENCE_KEY = "force_xhttp_stream_up"

    fun isXHttp(profile: VpnProfile): Boolean =
        profile.v2rayNetwork.equals("xhttp", ignoreCase = true) ||
            profile.v2rayNetwork.equals("splithttp", ignoreCase = true)

    fun apply(profile: VpnProfile, forceStreamUp: Boolean): VpnProfile {
        if (!isXHttp(profile)) return if (profile.v2rayOriginalXhttpMode == null && profile.v2rayXhttpModeOverride == null && profile.v2raySmartXhttpMode == null) profile
            else profile.copy(v2rayOriginalXhttpMode = null, v2rayXhttpModeOverride = null, v2raySmartXhttpMode = null)
        if (profile.v2rayXhttpModeOverride == "smart") {
            return profile.copy(v2rayType = XHttpSmartPolicy.candidates(profile).first(),
                v2rayOriginalXhttpMode = profile.v2rayOriginalXhttpMode ?: profile.v2rayType)
        }
        profile.v2rayXhttpModeOverride?.takeIf { it in listOf("auto", "packet-up", "stream-up", "stream-one") }?.let {
            return profile.copy(v2rayType = it, v2rayOriginalXhttpMode = profile.v2rayOriginalXhttpMode ?: profile.v2rayType)
        }
        return if (forceStreamUp) profile.copy(
            v2rayType = "stream-up",
            v2rayOriginalXhttpMode = profile.v2rayOriginalXhttpMode ?: profile.v2rayType
        ) else profile.v2rayOriginalXhttpMode?.let {
            profile.copy(v2rayType = it, v2rayOriginalXhttpMode = null)
        } ?: profile
    }

    fun preserveOverride(incoming: VpnProfile, previous: List<VpnProfile>): VpnProfile {
        if (!isXHttp(incoming) || incoming.v2rayXhttpModeOverride != null) return incoming
        val old = previous.firstOrNull {
            it.name == incoming.name && it.protocol == incoming.protocol && it.serverAddress == incoming.serverAddress &&
                it.port == incoming.port && it.v2rayNetwork.equals(incoming.v2rayNetwork, true) &&
                it.v2rayPath == incoming.v2rayPath && it.v2rayHost == incoming.v2rayHost && it.v2raySni == incoming.v2raySni
        }
        return incoming.copy(v2rayXhttpModeOverride = old?.v2rayXhttpModeOverride,
            v2raySmartXhttpMode = old?.takeIf { XHttpSmartPolicy.sameConnection(it, incoming) }?.v2raySmartXhttpMode)
    }
}
