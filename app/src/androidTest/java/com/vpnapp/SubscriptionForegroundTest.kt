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

class SubscriptionForegroundTest {
    @Test fun foregroundRefreshDeduplicatesAndKeepsSelectionAndOfflineProfiles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val repo = ProfileRepository.getInstance(app)
        val savedProfiles = repo.loadProfiles(); val savedSubs = repo.loadSubscriptions()
        val savedSelected = repo.getSelectedProfileId(); val savedFilter = repo.getSelectedSubscriptionId()
        val calls = AtomicInteger(); val release = CountDownLatch(1); val started = CountDownLatch(1)
        val old = VpnProfile(id = "selected", name = "France", serverAddress = "example.com",
            protocol = VpnProtocol.VLESS, subscriptionId = "sale")
        val sub = VpnSubscription("sale", "Sale", "https://example.com/sub")
        val store = ViewModelStore(); lateinit var vm: VpnViewModel
        fun waitUntil(condition: () -> Boolean) {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (!condition() && System.nanoTime() < end) Thread.sleep(50)
            assertTrue("Timed out waiting for refresh", condition())
        }
        try {
            repo.saveProfiles(listOf(old)); repo.saveSubscriptions(listOf(sub))
            repo.setSelectedProfileId(old.id); repo.setSelectedSubscriptionId("sale")
            instrumentation.runOnMainSync {
                vm = VpnViewModel(app, subscriptionFetcher = { _, id, _ ->
                    val number = calls.incrementAndGet()
                    if (number == 1) {
                        started.countDown(); assertTrue(release.await(8, TimeUnit.SECONDS))
                        sub.copy(id = id) to listOf(old.copy(id = "random-fetched-id"))
                    } else null
                })
                store.put("test", vm)
                assertEquals("sale", vm.selectedSubscriptionId.value)
                vm.onAppForeground(); vm.onAppForeground(); vm.refreshAllSubscriptions()
            }
            assertTrue(started.await(5, TimeUnit.SECONDS)); assertEquals(1, calls.get())
            release.countDown(); waitUntil { vm.refreshingSubscriptions.value.isEmpty() }
            assertEquals("selected", vm.selectedProfile.value?.id)
            assertEquals("sale", vm.selectedSubscriptionId.value)
            assertEquals(ConnectionState.DISCONNECTED, vm.connectionInfo.value.state)
            instrumentation.runOnMainSync { vm.onAppBackground(); vm.onAppForeground() }
            waitUntil { calls.get() == 2 && vm.refreshingSubscriptions.value.isEmpty() }
            assertEquals("selected", vm.profiles.value.single().id)
            instrumentation.runOnMainSync { vm.selectSubscription(null); vm.selectSubscription("sale") }
            assertEquals("sale", repo.getSelectedSubscriptionId())
            instrumentation.runOnMainSync { vm.deleteSubscription("sale") }
            assertNull(vm.selectedSubscriptionId.value); assertNull(repo.getSelectedSubscriptionId())
        } finally {
            release.countDown(); instrumentation.runOnMainSync { store.clear() }
            repo.saveProfiles(savedProfiles); repo.saveSubscriptions(savedSubs)
            repo.setSelectedProfileId(savedSelected); repo.setSelectedSubscriptionId(savedFilter)
        }
    }
}
