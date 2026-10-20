package com.vpnapp

import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.data.VpnProfile
import com.vpnapp.data.VpnProtocol
import com.vpnapp.vpn.singbox.SingBoxDelayTester
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue

/** A local unresponsive VMess peer holds eight encrypted native handshakes, then observes Stop. */
class NativePingStopTest {
    @Test fun eightNativeProbesOverlapAndStopClosesTheirConnections() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cacheDirectory = java.io.File(context.cacheDir, "latency-core/probe-cache")
        val cacheBefore = cacheDirectory.list()?.toSet().orEmpty()
        val server = ServerSocket(0)
        val workers = Executors.newCachedThreadPool()
        val clients = ConcurrentLinkedQueue<Socket>()
        val arrived = CountDownLatch(8); val closed = CountDownLatch(8)
        val accept = workers.submit {
            try { repeat(8) {
                val socket = server.accept(); clients.add(socket)
                workers.submit {
                    socket.use {
                        try {
                            val input = socket.getInputStream()
                            check(input.read() >= 0) // The native VMess outbound sent its encrypted handshake.
                            arrived.countDown()
                            while (input.read() >= 0) { }
                        } finally { closed.countDown() }
                    }
                }
            } } catch (_: Exception) { }
        }
        val profile = VpnProfile(name = "Local native concurrency fixture", serverAddress = "127.0.0.1", port = server.localPort,
            protocol = VpnProtocol.VMESS, v2rayUuid = "00000000-0000-0000-0000-000000000001", v2raySecurity = "auto", v2rayTls = "none")
        val jobs = (0 until 8).map { index -> launch(Dispatchers.IO) { SingBoxDelayTester.testProfileDelay(context, profile.copy(name = "Local native fixture $index")) } }
        try {
            val overlapping = withContext(Dispatchers.IO) { arrived.await(8, TimeUnit.SECONDS) }
            assertTrue("Eight native cores must overlap: accepted=${clients.size}, sent handshake=${8 - arrived.count}", overlapping)
            val start = android.os.SystemClock.elapsedRealtime()
            withTimeout(5000) { jobs.forEach { it.cancel() }; jobs.joinAll() }
            assertTrue("Cancellation must close all peer sockets", withContext(Dispatchers.IO) { closed.await(2, TimeUnit.SECONDS) })
            assertEquals("Probe cache files must be cleaned after Stop", cacheBefore, cacheDirectory.list()?.toSet().orEmpty())
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("native_stop", "concurrent=8; cancellation_ms=${android.os.SystemClock.elapsedRealtime() - start}")
            })
        } finally {
            jobs.forEach { it.cancel() }; server.close(); clients.forEach { runCatching { it.close() } }
            accept.cancel(true); workers.shutdownNow()
        }
    }
}
