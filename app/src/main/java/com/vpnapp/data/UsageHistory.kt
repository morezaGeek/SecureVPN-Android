package com.vpnapp.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class UsageDay(val date: LocalDate, val downloaded: Long, val uploaded: Long)

/** Service-owned accounting. SQLite transactions survive UI/process recreation without double counting. */
class UsageHistory(context: Context, databaseName: String = "vpn-usage.db") : SQLiteOpenHelper(context, databaseName, null, 1) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE usage_day(day TEXT PRIMARY KEY, rx INTEGER NOT NULL, tx INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE usage_session(id TEXT PRIMARY KEY, rx INTEGER NOT NULL, tx INTEGER NOT NULL, at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun record(session: String, rx: Long, tx: Long, now: Long = System.currentTimeMillis()) {
        if (rx < 0 || tx < 0) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            var oldRx=0L; var oldTx=0L; var previous=now
            db.rawQuery("SELECT rx,tx,at FROM usage_session WHERE id=?", arrayOf(session)).use {
                if(it.moveToFirst()) { oldRx=it.getLong(0); oldTx=it.getLong(1); previous=it.getLong(2) }
            }
            // A stale status packet must not subtract traffic or cause the next packet to double count.
            val nextRx=maxOf(oldRx,rx); val nextTx=maxOf(oldTx,tx)
            for (part in splitUsage(previous, now, nextRx-oldRx, nextTx-oldTx)) {
                db.execSQL("INSERT OR IGNORE INTO usage_day(day,rx,tx) VALUES(?,0,0)", arrayOf(part.date.toString()))
                db.execSQL("UPDATE usage_day SET rx=rx+?,tx=tx+? WHERE day=?", arrayOf<Any>(part.downloaded,part.uploaded,part.date.toString()))
            }
            db.insertWithOnConflict("usage_session",null,ContentValues().apply {
                put("id",session);put("rx",nextRx);put("tx",nextTx);put("at",maxOf(previous,now))
            },SQLiteDatabase.CONFLICT_REPLACE)
            db.execSQL("DELETE FROM usage_session WHERE at < ?",arrayOf(now-7L*24*3600*1000))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
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
