package com.vpnapp.ads

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** Separate process-safe local counter; stores no profile, server, IP or credential. */
class AdCycleStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "ad-cycles")
    private val file = AtomicFile(File(directory, "state.json"))
    companion object { private val localLock = Any() }

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
    fun connected(session: Long) = locked { writeUnlocked(AdCyclePolicy.connected(readUnlocked(), session)) }
    fun disconnected(session: Long) = locked { writeUnlocked(AdCyclePolicy.disconnected(readUnlocked(), session)) }
    fun reserve(now: Long): Boolean = locked {
        val state = readUnlocked()
        if (!AdCyclePolicy.eligible(state, now, true, true, true)) false
        else { writeUnlocked(state.copy(lastAttemptMillis = now)); true }
    }
    // A failure does not consume the two cycles; an actual impression does.
    fun shown() = locked { writeUnlocked(readUnlocked().copy(completedCycles = 0)) }
}
