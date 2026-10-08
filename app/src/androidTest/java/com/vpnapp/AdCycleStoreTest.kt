package com.vpnapp

import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.ads.AdCycleStore
import org.junit.Assert.*
import org.junit.Test

class AdCycleStoreTest {
    @Test fun counterSurvivesIndependentInstancesAndFailedLoadsDoNotConsumeCycles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(context.noBackupFilesDir, "ad-cycles")
        val existing = java.io.File(directory, "state.json").takeIf { it.exists() }?.readBytes()
        try {
            directory.deleteRecursively()
            AdCycleStore(context).disconnected(7)
            assertEquals(0, AdCycleStore(context).read().completedCycles)
            AdCycleStore(context).connected(10)
            AdCycleStore(context).disconnected(10)
            AdCycleStore(context).disconnected(10)
            assertEquals(1, AdCycleStore(context).read().completedCycles)
            assertFalse(AdCycleStore(context).reserve(10_000))
            AdCycleStore(context).connected(11)
            AdCycleStore(context).disconnected(11)
            assertTrue(AdCycleStore(context).reserve(10_000))
            assertEquals(2, AdCycleStore(context).read().completedCycles)
            assertFalse(AdCycleStore(context).reserve(10_001))
            assertTrue(AdCycleStore(context).reserve(130_000))
            AdCycleStore(context).shown()
            assertEquals(0, AdCycleStore(context).read().completedCycles)
            assertFalse(AdCycleStore(context).reserve(250_000))
        } finally {
            directory.deleteRecursively()
            if (existing != null) { directory.mkdirs(); java.io.File(directory,"state.json").writeBytes(existing) }
        }
    }
}
