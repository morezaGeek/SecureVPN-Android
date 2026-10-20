package com.vpnapp.util

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CancellableProbeTest {
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
