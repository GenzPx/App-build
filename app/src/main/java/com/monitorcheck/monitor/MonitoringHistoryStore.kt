package com.monitorcheck.monitor

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class HistoryPoint(val timestamp: Long, val cpuPercent: Double?, val ramPercent: Double?, val batteryPercent: Double?, val batteryTempC: Double?, val deviceTempC: Double?, val downloadBps: Double?, val uploadBps: Double?)

class MonitoringHistoryStore(context: Context) {
    private val helper = Helper(context.applicationContext)
    private var lastWrite = 0L
    @Synchronized fun record(point: HistoryPoint, force: Boolean = false) {
        if (!force && point.timestamp - lastWrite < 60_000L) return
        helper.writableDatabase.insert("samples", null, ContentValues().apply {
            put("timestamp", point.timestamp); putNullable("cpu", point.cpuPercent); putNullable("ram", point.ramPercent)
            putNullable("battery", point.batteryPercent); putNullable("battery_temp", point.batteryTempC); putNullable("device_temp", point.deviceTempC)
            putNullable("net_down", point.downloadBps); putNullable("net_up", point.uploadBps)
        }); lastWrite = point.timestamp
    }
    fun query(since: Long = System.currentTimeMillis() - 24 * 60 * 60_000L): List<HistoryPoint> {
        val out = ArrayList<HistoryPoint>()
        helper.readableDatabase.query("samples", arrayOf("timestamp","cpu","ram","battery","battery_temp","device_temp","net_down","net_up"), "timestamp >= ?", arrayOf(since.toString()), null, null, "timestamp ASC").use { c ->
            while (c.moveToNext()) out.add(HistoryPoint(c.getLong(0), c.doubleOrNull(1), c.doubleOrNull(2), c.doubleOrNull(3), c.doubleOrNull(4), c.doubleOrNull(5), c.doubleOrNull(6), c.doubleOrNull(7)))
        }; return out
    }
    fun prune(retentionDays: Int = 30) { helper.writableDatabase.delete("samples", "timestamp < ?", arrayOf((System.currentTimeMillis() - retentionDays.coerceIn(7, 90) * 24 * 60 * 60_000L).toString())) }
    fun clear() { helper.writableDatabase.delete("samples", null, null) }
    private fun ContentValues.putNullable(k: String, v: Double?) { if (v == null || v.isNaN() || v.isInfinite()) putNull(k) else put(k, v) }
    private fun android.database.Cursor.doubleOrNull(i: Int): Double? = if (isNull(i)) null else getDouble(i)
    private class Helper(context: Context) : SQLiteOpenHelper(context, "monitoring_history.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE samples(timestamp INTEGER PRIMARY KEY, cpu REAL, ram REAL, battery REAL, battery_temp REAL, device_temp REAL, net_down REAL, net_up REAL)"); db.execSQL("CREATE INDEX samples_timestamp ON samples(timestamp)") }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}

data class AlertEvent(val timestamp: Long, val type: String, val title: String, val detail: String)
class AlertEventStore(context: Context) {
    private val helper = Helper(context.applicationContext)
    @Synchronized fun add(event: AlertEvent) { helper.writableDatabase.insert("events", null, ContentValues().apply { put("timestamp", event.timestamp); put("type", event.type); put("title", event.title); put("detail", event.detail) }); helper.writableDatabase.execSQL("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY timestamp DESC LIMIT 200)") }
    fun recent(limit: Int = 50): List<AlertEvent> { val out = ArrayList<AlertEvent>(); helper.readableDatabase.query("events", arrayOf("timestamp","type","title","detail"), null, null, null, null, "timestamp DESC", limit.coerceIn(1, 200).toString()).use { c -> while (c.moveToNext()) out.add(AlertEvent(c.getLong(0),c.getString(1),c.getString(2),c.getString(3))) }; return out }
    private class Helper(context: Context) : SQLiteOpenHelper(context, "monitoring_alerts.db", null, 1) { override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, type TEXT NOT NULL, title TEXT NOT NULL, detail TEXT NOT NULL)"); db.execSQL("CREATE INDEX events_timestamp ON events(timestamp)") }; override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit }
}
