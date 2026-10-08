package com.vpnapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.junit.Assert.assertFalse
import java.util.concurrent.TimeUnit

internal object AwaitVpnTeardown {
    fun await(context: Context, pid: Int) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        fun workerAlive(): Boolean = pid > 0 && try {
            android.system.Os.kill(pid, 0); true
        } catch (_: android.system.ErrnoException) { false }
        fun vpnRemains() = connectivity.allNetworks.any {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while ((workerAlive() || vpnRemains()) && System.nanoTime() < end) Thread.sleep(50)
        assertFalse("VPN worker is still alive after disconnect", workerAlive())
        assertFalse("Android still reports an active VPN", vpnRemains())
    }
}
