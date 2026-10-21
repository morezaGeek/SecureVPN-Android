package com.vpnapp.ads

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transform

/** Separate process-safe local counter; stores no profile, server, IP or credential. */
class AdCycleStore(context: Context) {
    private val application = context.applicationContext
    private val directory = File(context.noBackupFilesDir, "ad-cycles")
    private val file = AtomicFile(File(directory, "state.json"))
    companion object {
        private val localLock = Any()
        const val ACTION_CHANGED = "com.vpnapp.AD_CYCLE_CHANGED"
    }
    private fun notifyChanged() = application.sendBroadcast(Intent(ACTION_CHANGED).setPackage(application.packageName))

    suspend fun awaitReservation() {
        callbackFlow<Unit> {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) { trySend(Unit) }
            }
            ContextCompat.registerReceiver(application, receiver, IntentFilter(ACTION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            trySend(Unit)
            awaitClose { application.unregisterReceiver(receiver) }
        // Do not cancel a reservation already being committed when another event arrives.
        }.conflate().transform {
            while (true) {
                val state = withContext(Dispatchers.IO) { read() }
                val wait = AdCyclePolicy.delayUntilEligible(state, System.currentTimeMillis()) ?: break
                if (wait > 0) delay(wait)
                if (withContext(Dispatchers.IO) { reserve(System.currentTimeMillis()) }) {
                    emit(Unit)
                    break
                }
            }
        }.first()
    }

    private fun <T> locked(action: () -> T): T = synchronized(localLock) {
        directory.mkdirs()
        RandomAccessFile(File(directory, "state.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use { action() }
        }
    }
    private fun readUnlocked(): AdCycleState = try {
        val json = JSONObject(file.readFully().toString(Charsets.UTF_8))
        AdCycleState(json.optLong("successfulSession"), json.optInt("completedCycles").coerceIn(0, 2),
            json.optLong("lastAttemptMillis"))
    } catch (_: Exception) { AdCycleState() }

    private fun writeUnlocked(state: AdCycleState) {
        val output = file.startWrite()
        try {
            output.write(JSONObject().put("successfulSession", state.successfulSession)
                .put("completedCycles", state.completedCycles).put("lastAttemptMillis", state.lastAttemptMillis)
                .toString().toByteArray())
            file.finishWrite(output)
        } catch (error: Exception) { file.failWrite(output); throw error }
    }
    fun read(): AdCycleState = locked { readUnlocked() }
    fun connected(session: Long) {
        locked { writeUnlocked(AdCyclePolicy.connected(readUnlocked(), session)) }
        notifyChanged()
    }
    fun disconnected(session: Long) {
        locked { writeUnlocked(AdCyclePolicy.disconnected(readUnlocked(), session)) }
        notifyChanged()
    }
    fun reserve(now: Long): Boolean = locked {
        val state = readUnlocked()
        if (!AdCyclePolicy.eligible(state, now, true, true, true)) false
        else { writeUnlocked(state.copy(lastAttemptMillis = now)); true }
    }
    // A failure does not consume the two cycles; an actual impression does.
    fun shown() {
        locked { writeUnlocked(readUnlocked().copy(completedCycles = 0)) }
        notifyChanged()
    }
}
