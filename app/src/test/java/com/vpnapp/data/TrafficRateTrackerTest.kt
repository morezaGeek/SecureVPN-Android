package com.vpnapp.data

import org.junit.Assert.*
import org.junit.Test

class TrafficRateTrackerTest {
    @Test fun ratesUseActualIntervalsAndDoNotSpikeAfterBackgroundOrCounterReset() {
        var now = 0L
        val tracker = TrafficRateTracker { now }
        assertEquals(0L to 0L, tracker.sample(100, 50))
        now += 1000
        assertEquals(1000L to 500L, tracker.sample(1100, 550))
        now += 10
        assertEquals(1000L to 500L, tracker.sample(1100, 550))
        now += 1990
        assertEquals(500L to 250L, tracker.sample(2100, 1050))
        now += 60_000
        assertEquals(0L to 0L, tracker.sample(80_000, 40_000))
        now += 1000
        assertEquals(0L to 0L, tracker.sample(1, 1))
        now += 1000
        assertEquals(100L to 200L, tracker.sample(101, 201))
        tracker.reset()
        assertEquals(0L to 0L, tracker.sample(1000, 1000))
    }
}
