package com.vpnapp.ads

import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.google.android.gms.ads.*
import com.vpnapp.data.ConnectionState
import com.vpnapp.ui.theme.CurrentTextMuted
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

// Google demo identifiers only. Production requires AdMob IDs and the consent flow.
object TestAds {
    const val BANNER_ID = "ca-app-pub-3940256099942544/6300978111"
    private val initialization = Mutex()
    @Volatile private var initialized = false
    suspend fun initialize(context: android.content.Context) = initialization.withLock {
        if (!initialized) suspendCancellableCoroutine<Unit> { continuation ->
            MobileAds.initialize(context.applicationContext) {
                initialized = true
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }
}

/** Called only by the Home destination. Removing it pauses/destroys the ad, never the VPN. */
@Composable
fun ConnectionBanner(connectionState: ConnectionState) {
    val owner = LocalLifecycleOwner.current
    var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ -> resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    if (resumed && connectionState == ConnectionState.DISCONNECTED) TestConnectionBanner()
}

@Composable
private fun TestConnectionBanner() {
    val context = LocalContext.current
    val store = remember(context) { AdCycleStore(context.applicationContext) }
    var loaded by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var disposed by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<AdView?>(null) }
    fun destroyBanner() {
        val current = view ?: return
        view = null
        current.adListener = object : AdListener() {}
        (current.parent as? ViewGroup)?.removeView(current)
        current.pause(); current.destroy()
    }
    DisposableEffect(Unit) {
        onDispose {
            disposed = true
            destroyBanner()
        }
    }
    LaunchedEffect(Unit) {
        try {
            while (!withContext(Dispatchers.IO) { store.reserve(System.currentTimeMillis()) }) delay(1000)
            val adView = AdView(context).apply { adUnitId = TestAds.BANNER_ID; setAdSize(AdSize.BANNER) }
            view = adView
            adView.adListener = object : AdListener() {
                override fun onAdLoaded() { if (!disposed && !finished) loaded = true }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    if (!disposed) finished = true
                    Log.d("ConnectionBanner", "Test banner unavailable (${error.code}); VPN unaffected")
                }
                override fun onAdImpression() {
                    if (!disposed && !finished) launch(Dispatchers.IO) { runCatching { store.shown() } }
                }
            }
            withContext(Dispatchers.IO) {
                TestAds.initialize(context.applicationContext)
            }
            withContext(Dispatchers.Main.immediate) {
                if (!disposed) adView.loadAd(AdRequest.Builder().build())
            }
            // No loading placeholder or endless retries; never wait on this from connection controls.
            withTimeoutOrNull(15_000) { while (!loaded && !finished) delay(100) }
            if (loaded && !finished) delay(45_000)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Log.w("ConnectionBanner", "Test banner skipped", error) }
        finally {
            finished = true; loaded = false
            withContext(NonCancellable + Dispatchers.Main.immediate) { destroyBanner() }
        }
    }
    if (loaded && !finished) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Advertisement", style = MaterialTheme.typography.labelSmall, color = CurrentTextMuted)
            Spacer(Modifier.height(8.dp))
            AndroidView(factory = { checkNotNull(view) }, modifier = Modifier.width(320.dp).height(50.dp))
        }
    }
}
