package com.vpnapp.vpn

import org.junit.Assert.*
import org.junit.Test

class SessionClockTest {
    @Test fun wallClockChangesDoNotChangeDurationAndSleepTimeIsIncluded() {
        var elapsed = 1000L
        var wall = 1_000_000L
        val clock = SessionClock({ elapsed }, { wall })
        clock.start()
        wall -= 3_600_000L
        elapsed += 7_200_000L
        assertEquals(7200L, clock.durationSeconds())
        assertEquals(1_000_000L, clock.startedWallMillis)
        wall += 24 * 3_600_000L
        assertEquals(7200L, clock.durationSeconds())
        clock.clear()
        assertEquals(0L, clock.durationSeconds())
        clock.start()
        elapsed += 3000
        assertEquals(3L, clock.durationSeconds())
        assertEquals(wall, clock.startedWallMillis)
    }
}
