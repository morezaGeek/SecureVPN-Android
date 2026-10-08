package com.vpnapp.vpn

import kotlinx.coroutines.delay

/** An idle service reply does not imply a pending disconnect, even if another VPN is active. */
internal object VpnDisconnectConfirmation {
    fun requiresWait(closedWorkerPid: Int, isAlive: (Int) -> Boolean): Boolean =
        closedWorkerPid > 0 && isAlive(closedWorkerPid)

    suspend fun awaitExit(closedWorkerPid: Int, isAlive: (Int) -> Boolean, timeoutMillis: Long = 6000): Boolean {
        if (closedWorkerPid <= 0) return true
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (isAlive(closedWorkerPid)) {
            val remainingMillis = (deadline - System.nanoTime()) / 1_000_000
            if (remainingMillis <= 0) return false
            delay(minOf(100L, remainingMillis))
        }
        return true
    }
}
