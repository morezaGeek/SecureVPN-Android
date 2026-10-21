package com.vpnapp

import androidx.test.platform.app.InstrumentationRegistry
import com.vpnapp.ads.AdCycleStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AdCycleAwaitTest {
    @Test fun secondCompletedSessionWakesReservationWithoutPolling() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.noBackupFilesDir, "ad-cycles/state.json")
        file.parentFile!!.mkdirs()
        val previous = file.takeIf { it.exists() }?.readBytes()
        val store = AdCycleStore(context)
        file.writeText("{\"successfulSession\":0,\"completedCycles\":0,\"lastAttemptMillis\":0}")
        val reservation = async(Dispatchers.Default) { store.awaitReservation() }
        try {
            store.connected(1001); store.disconnected(1001)
            delay(200)
            assertFalse(reservation.isCompleted)
            store.connected(1002); store.disconnected(1002)
            withTimeout(3000) { reservation.await() }
            assertTrue(store.read().lastAttemptMillis > 0)
            assertFalse("The reservation must retain its cooldown", store.reserve(System.currentTimeMillis()))
        } finally {
            reservation.cancelAndJoin()
            if (previous == null) file.delete() else file.writeBytes(previous)
        }
    }
}
