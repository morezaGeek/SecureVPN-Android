package com.vpnapp.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class UsageDay(val date: LocalDate, val downloaded: Long, val uploaded: Long)

/** Service-owned accounting. SQLite transactions survive UI/process recreation without double counting. */
class UsageHistory(context: Context, databaseName: String = "vpn-usage.db") : SQLiteOpenHelper(context, databaseName, null, 1) {
    private val application = context.applicationContext
    private var lastSession: String? = null
    private var lastRx = -1L
    private var lastTx = -1L
    private var lastAt = 0L
    private var lastPrunedAt = 0L
    companion object {
        const val ACTION_CHANGED = "com.vpnapp.USAGE_HISTORY_CHANGED"
        private const val EXTRA_DAYS = "changed_days"
        /** Package-scoped cross-process invalidation; no polling or traffic details. */
        fun changes(context: Context) = callbackFlow<Set<LocalDate>?> {
            val application = context.applicationContext
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val changed = if (intent?.action == ACTION_CHANGED)
                        intent.getStringArrayListExtra(EXTRA_DAYS)?.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }?.toSet()
                    else null
                    trySend(changed)
                }
            }
            ContextCompat.registerReceiver(application, receiver, IntentFilter().apply {
                addAction(ACTION_CHANGED); addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            trySend(null)
            awaitClose { application.unregisterReceiver(receiver) }
        }.conflate()
    }
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE usage_day(day TEXT PRIMARY KEY, rx INTEGER NOT NULL, tx INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE usage_session(id TEXT PRIMARY KEY, rx INTEGER NOT NULL, tx INTEGER NOT NULL, at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun record(session: String, rx: Long, tx: Long, now: Long = System.currentTimeMillis()): Boolean {
        if (rx < 0 || tx < 0) return false
        // Still remember the latest idle sample boundary without opening a write transaction.
        if (lastSession == session && rx <= lastRx && tx <= lastTx) {
            lastAt = maxOf(lastAt, now)
            return false
        }
        var changedDays = emptySet<LocalDate>()
        var committedRx = 0L
        var committedTx = 0L
        val db = writableDatabase
        db.beginTransaction()
        try {
            var oldRx=0L; var oldTx=0L; var previous=now
            db.rawQuery("SELECT rx,tx,at FROM usage_session WHERE id=?", arrayOf(session)).use {
                if(it.moveToFirst()) { oldRx=it.getLong(0); oldTx=it.getLong(1); previous=it.getLong(2) }
            }
            // A stale status packet must not subtract traffic or cause the next packet to double count.
            if (lastSession == session) previous = maxOf(previous, lastAt)
            val nextRx=maxOf(oldRx,rx); val nextTx=maxOf(oldTx,tx)
            committedRx = nextRx; committedTx = nextTx
            val parts = splitUsage(previous, now, nextRx-oldRx, nextTx-oldTx)
                .filter { it.downloaded != 0L || it.uploaded != 0L }
            changedDays = parts.map { it.date }.toSet()
            for (part in parts) {
                db.execSQL("INSERT OR IGNORE INTO usage_day(day,rx,tx) VALUES(?,0,0)", arrayOf(part.date.toString()))
                db.execSQL("UPDATE usage_day SET rx=rx+?,tx=tx+? WHERE day=?", arrayOf<Any>(part.downloaded,part.uploaded,part.date.toString()))
            }
            db.insertWithOnConflict("usage_session",null,ContentValues().apply {
                put("id",session);put("rx",nextRx);put("tx",nextTx);put("at",maxOf(previous,now))
            },SQLiteDatabase.CONFLICT_REPLACE)
            if (lastPrunedAt == 0L || now - lastPrunedAt >= 24L*3600*1000) {
                db.execSQL("DELETE FROM usage_session WHERE at < ? AND id != ?",arrayOf<Any>(now-7L*24*3600*1000, session))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        lastAt = if (lastSession == session) maxOf(lastAt, now) else now
        lastSession = session; lastRx = committedRx; lastTx = committedTx
        if (lastPrunedAt == 0L || now - lastPrunedAt >= 24L*3600*1000) lastPrunedAt = now
        if (changedDays.isNotEmpty()) application.sendBroadcast(Intent(ACTION_CHANGED).setPackage(application.packageName)
            .putStringArrayListExtra(EXTRA_DAYS, ArrayList(changedDays.map { it.toString() })))
        return changedDays.isNotEmpty()
    }

    fun days(from: LocalDate, to: LocalDate): List<UsageDay> = readableDatabase.rawQuery(
        "SELECT day,rx,tx FROM usage_day WHERE day>=? AND day<=? ORDER BY day DESC",
        arrayOf(from.toString(),to.toString())).use { cursor ->
        buildList { while(cursor.moveToNext()) add(UsageDay(LocalDate.parse(cursor.getString(0)),cursor.getLong(1),cursor.getLong(2))) }
    }
}

/** Split samples crossing local midnight, including days shortened/lengthened by timezone rules. */
fun splitUsage(start: Long, end: Long, rx: Long, tx: Long, zone: ZoneId = ZoneId.systemDefault()): List<UsageDay> {
    if(end <= start) return listOf(UsageDay(Instant.ofEpochMilli(end).atZone(zone).toLocalDate(),rx,tx))
    val result=mutableListOf<UsageDay>();var at=start;var allocatedRx=0L;var allocatedTx=0L
    while(at < end) {
        val date=Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val next=minOf(end,date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        val cumulativeRx=if(next==end) rx else (rx.toDouble()*(next-start)/(end-start)).toLong()
        val cumulativeTx=if(next==end) tx else (tx.toDouble()*(next-start)/(end-start)).toLong()
        result.add(UsageDay(date,cumulativeRx-allocatedRx,cumulativeTx-allocatedTx))
        allocatedRx=cumulativeRx;allocatedTx=cumulativeTx;at=next
    }
    return result
}
