package com.vpnapp.util

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CancellableProbeTest {
    @Test(timeout = 6000) fun cancellationClosesHttpWhileWaitingForResponseHeaders() = runBlocking {
        cancelStalledHttp("http")
    }

    @Test(timeout = 6000) fun cancellationClosesTlsWhileWaitingForHandshake() = runBlocking {
        cancelStalledHttp("https")
    }

    private suspend fun cancelStalledHttp(scheme: String) = coroutineScope {
        ServerSocket(0).use { server ->
            val received = CountDownLatch(1)
            val release = CountDownLatch(1)
            val peer = launch(Dispatchers.IO) {
                server.accept().use { socket ->
                    socket.getInputStream().read()
                    received.countDown()
                    release.await(4, TimeUnit.SECONDS)
                }
            }
            val probe = launch(Dispatchers.IO) {
                cancellableProbe { resources ->
                    ProbeHttp.open(URL("$scheme://localhost:${server.localPort}/"),
                        registerSocket = { socket -> resources.register { socket.close() } }) { _, port ->
                        Socket("127.0.0.1", port)
                    }.use { it.get() }
                }
            }
            try {
                assertTrue(received.await(3, TimeUnit.SECONDS))
                withTimeout(1500) { probe.cancelAndJoin() }
            } finally {
                release.countDown()
                probe.cancel()
                peer.join()
            }
        }
    }

    @Test(timeout = 5000) fun cancellationClosesBlockingReadAndDoesNotSkipLaterCleanup() = runBlocking {
        ServerSocket(0).use { server ->
            Socket("127.0.0.1", server.localPort).use { peer ->
                server.accept().use { socket ->
                    val started = CountDownLatch(1)
                    var secondCleanup = false
                    val job = launch(Dispatchers.IO) {
                        cancellableProbe { resources ->
                            resources.register { socket.close(); error("Cleanup failures must not stop later cleanup") }
                            resources.register { secondCleanup = true }
                            started.countDown()
                            socket.getInputStream().read() // Deliberately blocked, with no socket timeout.
                        }
                    }
                    assertTrue(started.await(2, TimeUnit.SECONDS))
                    try { withTimeout(1500) { job.cancelAndJoin() } }
                    finally { peer.close(); socket.close() }
                    assertTrue(socket.isClosed)
                    assertTrue(secondCleanup)
                }
            }
        }
    }
}
