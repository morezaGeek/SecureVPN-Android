package com.vpnapp.data

/** Refresh server data without losing local identity, selection or XHTTP overrides. */
object SubscriptionProfiles {
    fun merge(current: List<VpnProfile>, incoming: List<VpnProfile>, subscriptionId: String,
              forceStreamUp: Boolean): List<VpnProfile> {
        val remaining = current.filter { it.subscriptionId == subscriptionId }.toMutableList()
        val refreshed = incoming.map { fresh ->
            val candidates = remaining.filter {
                it.name == fresh.name && it.protocol == fresh.protocol &&
                    it.v2rayNetwork.equals(fresh.v2rayNetwork, true)
            }
            val old = candidates.firstOrNull { sameEndpoint(it, fresh) }
                ?: candidates.singleOrNull()
                ?: remaining.firstOrNull { sameEndpoint(it, fresh) }
            if (old != null) remaining.remove(old)
            val preserved = if (old == null) fresh else {
                val localMode = if (XHttpModePolicy.isXHttp(fresh)) fresh.copy(
                    v2rayXhttpModeOverride = fresh.v2rayXhttpModeOverride ?: old.v2rayXhttpModeOverride,
                    v2raySmartXhttpMode = old.v2raySmartXhttpMode.takeIf { sameEndpoint(old, fresh) }
                ) else fresh
                localMode.copy(
                    id = old.id, createdAt = old.createdAt, lastConnected = old.lastConnected,
                    latency = old.latency.takeIf { sameEndpoint(old, fresh) }
                )
            }
            XHttpModePolicy.apply(preserved.copy(subscriptionId = subscriptionId), forceStreamUp)
        }
        val first = current.indexOfFirst { it.subscriptionId == subscriptionId }
        val other = current.filter { it.subscriptionId != subscriptionId }.toMutableList()
        other.addAll(if (first < 0) other.size else first.coerceAtMost(other.size), refreshed)
        return other
    }

    private fun sameEndpoint(a: VpnProfile, b: VpnProfile): Boolean =
        XHttpSmartPolicy.sameConnection(a, b) && a.username == b.username && a.password == b.password &&
            a.hysteriaObfs == b.hysteriaObfs && a.hysteriaObfsPassword == b.hysteriaObfsPassword &&
            (XHttpModePolicy.isXHttp(a) || a.v2rayType == b.v2rayType)
}
