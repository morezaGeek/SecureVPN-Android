package com.vpnapp.data

/** Bytes per second from cumulative counters; ignores duplicate snapshots and idle UI gaps. */
internal class TrafficRateTracker(private val elapsedMillis: () -> Long) {
    private var previous: Triple<Long, Long, Long>? = null
    private var rate = 0L to 0L
    @Synchronized fun reset() { previous = null; rate = 0L to 0L }
    @Synchronized fun sample(rx: Long, tx: Long): Pair<Long, Long> {
        val now = elapsedMillis()
        val old = previous
        if (old != null && now >= old.third && now - old.third < 250 && rx >= old.first && tx >= old.second) return rate
        val duration = old?.let { now - it.third } ?: 0
        rate = if (old != null && duration in 250..5000 && rx >= old.first && tx >= old.second)
            ((rx - old.first).toDouble() * 1000 / duration).toLong() to
                ((tx - old.second).toDouble() * 1000 / duration).toLong()
        else 0L to 0L
        previous = Triple(rx, tx, now)
        return rate
    }
}
