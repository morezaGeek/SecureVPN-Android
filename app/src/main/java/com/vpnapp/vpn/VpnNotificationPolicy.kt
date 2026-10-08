package com.vpnapp.vpn

/** An old dismissal must never restore a stopped VPN or a different session. */
object VpnNotificationPolicy {
    fun shouldRestore(dismissedSession: Long, currentSession: Long, connected: Boolean,
                      connecting: Boolean, stopping: Boolean): Boolean =
        currentSession > 0 && dismissedSession == currentSession && !stopping && (connected || connecting)
}
