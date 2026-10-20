package com.vpnapp.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class UsageBucketsTest {
    @Test fun midnightSplitKeepsEveryByte() {
        val zone=ZoneId.of("Asia/Tehran")
        val midnight=LocalDate.of(2026,10,16).atStartOfDay(zone).toInstant().toEpochMilli()
        val rows=splitUsage(midnight-1000,midnight+1000,101,7,zone)
        assertEquals(listOf(LocalDate.of(2026,10,15),LocalDate.of(2026,10,16)),rows.map { it.date })
        assertEquals(101L,rows.sumOf { it.downloaded });assertEquals(7L,rows.sumOf { it.uploaded })
        assertEquals(50L,rows.first().downloaded)
    }
    @Test fun daylightSavingChangeUsesActualDayLength() {
        val zone=ZoneId.of("America/New_York")
        val first=LocalDate.of(2026,3,8);val start=first.atStartOfDay(zone).toInstant().toEpochMilli()
        val end=first.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val rows=splitUsage(start,end,4700,470,zone)
        assertEquals(2300L,rows.first().downloaded);assertEquals(2400L,rows.last().downloaded)
    }
}
