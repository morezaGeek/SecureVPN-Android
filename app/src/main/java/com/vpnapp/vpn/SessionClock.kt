package com.vpnapp.vpn

/** Wall time labels history; monotonic time measures duration, including device sleep. */
internal class SessionClock(private val elapsedNow: () -> Long, private val wallNow: () -> Long) {
    private var startedElapsed: Long? = null
    var startedWallMillis: Long = 0L
        private set

    @Synchronized fun start() {
        startedElapsed = elapsedNow()
        startedWallMillis = wallNow()
    }
    @Synchronized fun durationSeconds(): Long = startedElapsed?.let {
        ((elapsedNow() - it).coerceAtLeast(0L)) / 1000
    } ?: 0L
    @Synchronized fun clear() { startedElapsed = null; startedWallMillis = 0L }
}
