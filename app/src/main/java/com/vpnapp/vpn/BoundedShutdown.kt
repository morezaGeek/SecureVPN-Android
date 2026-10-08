package com.vpnapp.vpn

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Native calls cannot be cancelled by coroutine timeouts. The deadline has its own thread. */
internal class BoundedShutdown(private val timeoutMillis: Long = 4000) {
    private val started = AtomicBoolean()
    private val finished = AtomicBoolean()

    fun begin(cleanup: () -> Unit, terminate: (timedOut: Boolean) -> Unit) {
        if (!started.compareAndSet(false, true)) return
        val timer = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "vpn-stop-deadline").apply { isDaemon = true }
        }
        fun finish(timedOut: Boolean) {
            if (finished.compareAndSet(false, true)) {
                timer.shutdownNow()
                terminate(timedOut)
            }
        }
        timer.schedule({ finish(true) }, timeoutMillis, TimeUnit.MILLISECONDS)
        Thread({
            try { cleanup() } finally { finish(false) }
        }, "vpn-stop-cleanup").apply { isDaemon = true }.start()
    }
}
