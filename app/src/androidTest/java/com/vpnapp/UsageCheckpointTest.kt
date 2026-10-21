package com.vpnapp

import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.data.UsageHistory
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class UsageCheckpointTest {
    @Test fun idleCheckpointsDoNotWriteAndNextTrafficUsesLatestIdleBoundary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "usage-checkpoint-diagnostic.db"
        context.deleteDatabase(name)
        val day = LocalDate.of(2026, 10, 21)
        val midnight = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            UsageHistory(context, name).use { history ->
                assertFalse(history.record("session", 0, 0, midnight - 120_000))
                assertTrue(history.record("session", 100, 20, midnight - 60_000))
                fun savedAt(): Long = history.readableDatabase.rawQuery(
                    "SELECT at FROM usage_session WHERE id='session'", null).use { it.moveToFirst(); it.getLong(0) }
                val before = savedAt()
                assertFalse(history.record("session", 100, 20, midnight + 1000))
                assertEquals("Idle sampling wrote the database", before, savedAt())
                assertTrue(history.record("session", 150, 30, midnight + 2000))
                assertFalse(history.record("session", 120, 25, midnight + 2500))
                val rows = history.days(day.minusDays(1), day)
                assertEquals(100L, rows.single { it.date == day.minusDays(1) }.downloaded)
                assertEquals(50L, rows.single { it.date == day }.downloaded)
            }
            UsageHistory(context, name).use { history ->
                assertFalse(history.record("session", 150, 30, midnight + 3000))
                assertTrue(history.record("session", 160, 32, midnight + 4000))
                val rows = history.days(day.minusDays(1), day)
                assertEquals(160L, rows.sumOf { it.downloaded })
                assertEquals(32L, rows.sumOf { it.uploaded })
            }
        } finally { context.deleteDatabase(name) }
    }
}
