package com.vpnapp.vpn

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Native calls cannot be cancelled by coroutine timeouts. The deadline has its own thread. */
internal class BoundedShutdown(private val timeoutMillis: Long = 4000) {
    private val started = AtomicBoolean()
    private val finished = AtomicBoolean()

    fun begin(cleanup: () -> Unit, terminate: (timedOut: Boolean) -> Unit,
              forceTerminate: () -> Unit = { terminate(true) }) {
        if (!started.compareAndSet(false, true)) return
        val timer = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "vpn-stop-deadline").apply { isDaemon = true }
        }
        // The deadline also covers terminate(): notification/file IPC there can block.
        timer.schedule({
            if (finished.compareAndSet(false, true)) {
                try { forceTerminate() } finally { timer.shutdown() }
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS)
        Thread({
            try { cleanup() } finally {
                if (!finished.get()) {
                    try { terminate(false) } finally {
                        finished.set(true)
                        timer.shutdownNow()
                    }
                }
            }
        }, "vpn-stop-cleanup").apply { isDaemon = true }.start()
    }
}
