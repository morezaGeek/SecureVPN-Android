package com.vpnapp.vpn

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BoundedShutdownTest {
    @Test fun blockedFinalizationStillHasAnIndependentDeadline() {
        val finalizing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val forced = CountDownLatch(1)
        val shutdown = BoundedShutdown(100)
        shutdown.begin({}, {
            finalizing.countDown()
            release.await()
        }, { forced.countDown() })
        try {
            assertTrue(finalizing.await(2, TimeUnit.SECONDS))
            assertTrue("Finalization cancelled the deadline", forced.await(2, TimeUnit.SECONDS))
        } finally { release.countDown() }
    }

    @Test fun blockedNativeCleanupCannotBlockDeadlineAndOnlyTerminatesOnce() {
        val releaseNative = CountDownLatch(1)
        val terminated = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val calls = AtomicInteger()
        val shutdown = BoundedShutdown(60)
        shutdown.begin({ releaseNative.await(); returned.countDown() }, { timedOut ->
            assertTrue(timedOut)
            calls.incrementAndGet()
            terminated.countDown()
        })
        shutdown.begin({ fail("Cleanup started twice") }, { fail("Terminated twice") })
        try {
            assertTrue("Deadline waited for native cleanup", terminated.await(2, TimeUnit.SECONDS))
            assertEquals(1L, returned.count)
        } finally { releaseNative.countDown() }
        assertTrue(returned.await(2, TimeUnit.SECONDS))
        assertEquals(1, calls.get())
    }

    @Test fun gracefulCleanupTerminatesWithoutWaitingForDeadline() {
        val done = CountDownLatch(1)
        var timeout: Boolean? = null
        BoundedShutdown(3000).begin({}, { timeout = it; done.countDown() })
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(false, timeout)
    }
}
