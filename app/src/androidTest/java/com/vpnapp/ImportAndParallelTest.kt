package com.vpnapp

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.data.*
import com.vpnapp.viewmodel.VpnViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ImportAndParallelTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private fun waitUntil(condition: () -> Boolean) {
        val end = System.nanoTime() + 40_000_000_000L
        while (!condition() && System.nanoTime() < end) Thread.sleep(25)
        assertTrue("Timed out waiting for result", condition())
    }
    private fun isolated(block: (ProfileRepository, ViewModelStore) -> Unit) {
        val repo = ProfileRepository.getInstance(app)
        val profiles = repo.loadProfiles(); val subscriptions = repo.loadSubscriptions()
        val selected = repo.getSelectedProfileId(); val filter = repo.getSelectedSubscriptionId()
        val store = ViewModelStore()
        try { block(repo, store) } finally {
            instrumentation.runOnMainSync { store.clear() }
            repo.saveProfiles(profiles); repo.saveSubscriptions(subscriptions)
            repo.setSelectedProfileId(selected); repo.setSelectedSubscriptionId(filter)
        }
    }

    @Test fun importErrorsAreVisibleAndNeverAddAnEmptySubscription() = isolated { repo, store ->
        val before = repo.loadProfiles(); val subs = repo.loadSubscriptions()
        val releaseFetch = java.util.concurrent.CountDownLatch(1)
        lateinit var vm: VpnViewModel
        try {
            instrumentation.runOnMainSync {
                vm = VpnViewModel(app, subscriptionFetcher = { _, _, _ -> releaseFetch.await(5, java.util.concurrent.TimeUnit.SECONDS); null })
                store.put("error", vm); vm.addSubscription("https://example.invalid/sub")
                assertTrue(vm.subscriptionImport.value.loading)
            }
        } finally { releaseFetch.countDown() }
        waitUntil { vm.subscriptionImport.value.error != null }
        assertEquals(before, vm.profiles.value); assertEquals(subs, vm.subscriptions.value)
        instrumentation.runOnMainSync { vm.clearSubscriptionImportResult() }
        assertNull(vm.subscriptionImport.value.error)
    }

    @Test fun batchHasEightWorkersAndIgnoresOtherSubscriptions() = isolated { repo, store ->
        val profiles = (0 until 16).map { VpnProfile(id = "parallel-$it", name = "Server $it", serverAddress = "example.invalid", subscriptionId = "parallel") }
        repo.saveProfiles(profiles + VpnProfile(id = "other", name = "Other", serverAddress = "example.invalid", subscriptionId = "other", latency = 321))
        repo.saveSubscriptions(listOf(VpnSubscription(id = "parallel", name = "Parallel", url = "https://example.invalid")))
        repo.setSelectedSubscriptionId("parallel")
        val active = AtomicInteger(); val peak = AtomicInteger(); val count = AtomicInteger()
        lateinit var vm: VpnViewModel
        instrumentation.runOnMainSync {
            vm = VpnViewModel(app, latencyTester = { _, profile ->
                val now = active.incrementAndGet(); peak.accumulateAndGet(now, ::maxOf)
                try { delay(150); count.incrementAndGet(); if (profile.id == "other") 321 else 42 } finally { active.decrementAndGet() }
            }); store.put("parallel", vm)
            vm.measureLatency(vm.profiles.value.single { it.id == "other" })
        }
        waitUntil { count.get() == 1 && !vm.latencyTestState.value.isRunning }
        count.set(0); peak.set(0)
        instrumentation.runOnMainSync { vm.measureAllLatencies() }
        waitUntil { count.get() == 16 && !vm.latencyTestState.value.isRunning }
        assertEquals(8, peak.get())
        assertEquals(321, vm.profiles.value.single { it.id == "other" }.latency)
        assertTrue(vm.profiles.value.filter { it.subscriptionId == "parallel" }.all { it.latency == 42 })
    }

    @Test fun stoppingALargeBatchKeepsCompletedResultsAndDoesNotStartTheQueue() = isolated { repo, store ->
        repo.saveProfiles((0 until 150).map { VpnProfile(id = "stop-$it", name = "Server $it", serverAddress = "example.invalid") })
        repo.saveSubscriptions(emptyList()); repo.setSelectedSubscriptionId(null)
        val started = AtomicInteger(); val active = AtomicInteger()
        val hold = java.util.concurrent.atomic.AtomicBoolean(true)
        lateinit var vm: VpnViewModel
        instrumentation.runOnMainSync {
            vm = VpnViewModel(app, latencyTester = { _, profile ->
                started.incrementAndGet(); active.incrementAndGet()
                try {
                    if (hold.get() && profile.id != "stop-0") awaitCancellation()
                    42
                } finally { active.decrementAndGet() }
            }); store.put("stop", vm); vm.measureAllLatencies()
        }
        waitUntil { active.get() == 8 && vm.profiles.value.any { it.latency == 42 } }
        val before = vm.profiles.value.map { it.latency }
        instrumentation.runOnMainSync { vm.stopLatencyTests(); assertFalse(vm.latencyTestState.value.isRunning) }
        waitUntil { active.get() == 0 }
        val callsAtStop = started.get()
        Thread.sleep(200)
        assertEquals(callsAtStop, started.get())
        assertTrue("Pending profiles must never start after Stop", callsAtStop < 150)
        assertEquals(before, vm.profiles.value.map { it.latency })
        hold.set(false)
        instrumentation.runOnMainSync { vm.measureAllLatencies() }
        waitUntil { !vm.latencyTestState.value.isRunning }
        assertTrue(vm.profiles.value.all { it.latency == 42 })
    }

    @Test fun sortChoiceSurvivesViewModelRecreation() = isolated { _, store ->
        val prefs = app.getSharedPreferences("vpn_app_prefs", 0)
        val previous = prefs.getString(ProfileSort.PREFERENCE_KEY, null)
        try {
            instrumentation.runOnMainSync {
                val first = VpnViewModel(app); store.put("sort", first)
                first.setProfileSort(ProfileSort.PING)
                store.clear()
                val second = VpnViewModel(app); store.put("sort", second)
                assertEquals(ProfileSort.PING, second.profileSort.value)
            }
        } finally {
            prefs.edit().apply { if (previous == null) remove(ProfileSort.PREFERENCE_KEY) else putString(ProfileSort.PREFERENCE_KEY, previous) }.commit()
        }
    }

    @Test fun cancelingAnImportCannotEraseTheNextImportState() = isolated { _, store ->
        val oldStarted = java.util.concurrent.CountDownLatch(1)
        val oldRelease = java.util.concurrent.CountDownLatch(1)
        val newStarted = java.util.concurrent.CountDownLatch(1)
        val newRelease = java.util.concurrent.CountDownLatch(1)
        val oldFinished = java.util.concurrent.CountDownLatch(1)
        lateinit var vm: VpnViewModel
        try {
            instrumentation.runOnMainSync {
                vm = VpnViewModel(app, subscriptionFetcher = { url, id, _ ->
                    if (url.endsWith("/old")) {
                        oldStarted.countDown()
                        try { oldRelease.await(10, java.util.concurrent.TimeUnit.SECONDS); throw SubscriptionImportException("Old download failed") }
                        finally { oldFinished.countDown() }
                    } else {
                        newStarted.countDown(); newRelease.await(10, java.util.concurrent.TimeUnit.SECONDS)
                        VpnSubscription(id, "New", url) to listOf(VpnProfile(name = "New", serverAddress = "example.invalid", subscriptionId = id))
                    }
                })
                store.put("cancel", vm); vm.addSubscription("https://example.invalid/old")
            }
            assertTrue(oldStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.runOnMainSync { vm.cancelSubscriptionImport(); vm.addSubscription("https://example.invalid/new") }
            assertTrue(newStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            oldRelease.countDown(); assertTrue(oldFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertTrue(vm.subscriptionImport.value.loading); assertNull(vm.subscriptionImport.value.error)
            newRelease.countDown()
            waitUntil { vm.subscriptionImport.value.imported == 1 }
            assertNull(vm.subscriptionImport.value.error)
        } finally { oldRelease.countDown(); newRelease.countDown() }
    }

    @Test fun liveSubscriptionAndQrPayloadImportAndNativeParallelProbes() = isolated { repo, store ->
        val fixture = File(app.cacheDir, "import-subscription.private.json")
        val supplied = InstrumentationRegistry.getArguments().getString("subscription_url")
        assumeTrue("Opt-in subscription URL required", supplied != null || fixture.exists())
        try {
            val url = supplied ?: org.json.JSONObject(fixture.readText()).getString("url")
            val first = ShareLinkCodec.parseImport("[$url]($url)") as LinkImport.Subscription
            val qr = com.google.zxing.qrcode.QRCodeWriter().encode(url, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
            val pixels = IntArray(512 * 512) { index -> if (qr[index % 512, index / 512]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
            val bitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(com.google.zxing.RGBLuminanceSource(512, 512, pixels)))
            val scanned = com.google.zxing.qrcode.QRCodeReader().decode(bitmap).text
            assertEquals(first, ShareLinkCodec.parseImport(scanned))
            val downloaded = V2RayLinkParser.fetchSubscriptionOrThrow(first.url, "live-diagnostic")
            assertTrue("Live server must yield profiles", downloaded.second.isNotEmpty())
            lateinit var vm: VpnViewModel
            instrumentation.runOnMainSync {
                vm = VpnViewModel(app, latencyTester = { _, _ -> error("Import must not ping") })
                store.put("live", vm); vm.addSubscription(scanned, "Import diagnostic")
            }
            waitUntil { !vm.subscriptionImport.value.loading }
            assertNull(vm.subscriptionImport.value.error)
            assertEquals(downloaded.second.size, vm.subscriptionImport.value.imported)
            val profiles = vm.profiles.value.filter { it.subscriptionId == vm.selectedSubscriptionId.value }
            assertEquals(downloaded.second.size, profiles.size)
            val probes = profiles.filter { it.protocol in listOf(VpnProtocol.VLESS, VpnProtocol.VMESS, VpnProtocol.TROJAN, VpnProtocol.SHADOWSOCKS, VpnProtocol.HYSTERIA2) }.take(3)
            val results = runBlocking { probes.map { profile -> async(Dispatchers.IO) {
                com.vpnapp.vpn.singbox.SingBoxDelayTester.testProfileDelay(app, profile)
            } }.awaitAll() }
            assertTrue("At least one real proxy must pass", results.any { it > 0 })
            val report = org.json.JSONObject()
                .put("imported_profiles", profiles.size).put("qr_roundtrip", true)
                .put("native_parallel_results_ms", org.json.JSONArray(results)).toString()
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("public_results", report) })
        } finally { fixture.delete() }
    }
}
