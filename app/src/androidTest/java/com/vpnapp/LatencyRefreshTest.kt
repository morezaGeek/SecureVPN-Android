package com.vpnapp

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.data.*
import com.vpnapp.viewmodel.VpnViewModel
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LatencyRefreshTest {
    @Test fun repeatedPingClearsOldResultsIgnoresCancelledProbeAndSettlesFailures() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val repo = ProfileRepository.getInstance(app)
        val savedProfiles = repo.loadProfiles(); val savedSubs = repo.loadSubscriptions()
        val savedSelected = repo.getSelectedProfileId()
        val firstStarted = CountDownLatch(1); val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1); val releaseSecond = CountDownLatch(1)
        val calls = AtomicInteger(); val store = ViewModelStore()
        val a = VpnProfile(id = "ping-a", name = "France", serverAddress = "example.com", latency = 700)
        val b = a.copy(id = "ping-b", name = "Germany", latency = 800)
        lateinit var vm: VpnViewModel
        fun waitUntil(condition: () -> Boolean) {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (!condition() && System.nanoTime() < end) Thread.sleep(30)
            assertTrue("Timed out waiting for ping", condition())
        }
        try {
            repo.saveProfiles(listOf(a, b)); repo.saveSubscriptions(emptyList()); repo.setSelectedProfileId(a.id)
            instrumentation.runOnMainSync {
                vm = VpnViewModel(app, latencyTester = { _, profile ->
                    when (calls.incrementAndGet()) {
                        1 -> { firstStarted.countDown(); assertTrue(releaseFirst.await(8, TimeUnit.SECONDS)); 999 }
                        2 -> { secondStarted.countDown(); assertTrue(releaseSecond.await(8, TimeUnit.SECONDS)); 91 }
                        else -> { assertEquals(b.id, profile.id); error("Simulated unreachable server") }
                    }
                })
                store.put("latency", vm)
                vm.measureAllLatencies()
                assertTrue(vm.profiles.value.all { it.latency == null })
                assertNull(vm.selectedProfile.value?.latency)
                assertEquals(setOf(a.id, b.id), vm.latencyTestState.value.pendingIds)
            }
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { vm.measureAllLatencies() }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            releaseFirst.countDown()
            instrumentation.waitForIdleSync()
            assertTrue(vm.profiles.value.all { it.latency == null })
            assertTrue(vm.latencyTestState.value.isRunning)
            releaseSecond.countDown()
            waitUntil { !vm.latencyTestState.value.isRunning }
            assertEquals(listOf(91, -1), vm.profiles.value.map { it.latency })
            assertEquals(91, vm.selectedProfile.value?.latency)
            assertEquals(3, calls.get())
        } finally {
            releaseFirst.countDown(); releaseSecond.countDown()
            instrumentation.runOnMainSync { store.clear() }
            repo.saveProfiles(savedProfiles); repo.saveSubscriptions(savedSubs); repo.setSelectedProfileId(savedSelected)
        }
    }
}
