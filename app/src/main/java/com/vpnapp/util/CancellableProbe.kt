package com.vpnapp.util

import kotlinx.coroutines.*
import java.io.Closeable

/** Close blocking HTTP/socket/native resources on cancellation, not only after their timeout expires. */
internal class ProbeResources : Closeable {
    private var closed = false
    private val actions = mutableListOf<() -> Unit>()

    fun register(close: () -> Unit) {
        val closeNow = synchronized(this) {
            if (closed) true else { actions.add(close); false }
        }
        if (closeNow) runCatching(close)
    }

    override fun close() {
        val pending = synchronized(this) {
            if (closed) return
            closed = true
            // Close the native transport/raw socket first to unblock higher-level HTTP/TLS wrappers.
            actions.toList().also { actions.clear() }
        }
        pending.forEach { runCatching(it) }
    }
}

internal suspend fun <T> cancellableProbe(block: suspend (ProbeResources) -> T): T = coroutineScope {
    val resources = ProbeResources()
    // This child remains cancellable even while the probe's worker is in a blocking responseCode/read.
    val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() }
        finally { withContext(NonCancellable + Dispatchers.IO) { resources.close() } }
    }
    try { block(resources) }
    catch (error: Exception) { coroutineContext.ensureActive(); throw error }
    finally { withContext(NonCancellable) { closer.cancelAndJoin() } }
}
