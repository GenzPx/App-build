package com.monitorcheck.data.battery

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One persisted battery sample. All fields are real measurements; nulls mean "not reported". */
data class BatteryHistoryEntry(
    val timestamp: Long,
    val levelPercent: Int,
    val temperatureCelsius: Double?,
    val voltageMv: Int?,
    val currentUa: Int?,
    val charging: Boolean
)

/** Selectable history ranges for the battery graphs. */
enum class HistoryRange(val label: String, val millis: Long) {
    H1("1 hour", 3_600_000L),
    H6("6 hours", 21_600_000L),
    H24("24 hours", 86_400_000L),
    D7("7 days", 604_800_000L),
    D30("30 days", 2_592_000_000L)
}

/**
 * Local-only battery history.
 *
 * Uses plain SQLite (no extra dependency, tiny footprint). Samples are written at
 * most once per minute and pruned to 30 days so the database stays small on low-end
 * devices. Nothing ever leaves the device.
 */
class BatteryHistoryStore(context: Context) {

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    ts INTEGER PRIMARY KEY,
                    level INTEGER NOT NULL,
                    temp REAL,
                    voltage INTEGER,
                    current INTEGER,
                    charging INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_ts ON $TABLE(ts)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    private val helper = Helper(context.applicationContext)

    @Volatile
    private var lastWriteMs = 0L

    /**
     * Records a sample, rate-limited to [MIN_WRITE_INTERVAL_MS]. Returns true if written.
     */
    suspend fun record(snapshot: BatterySnapshot, force: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            val now = snapshot.timestamp
            if (!force && now - lastWriteMs < MIN_WRITE_INTERVAL_MS) return@withContext false
            try {
                val values = ContentValues().apply {
                    put("ts", now)
                    put("level", snapshot.levelPercent)
                    snapshot.temperatureCelsius?.let { put("temp", it) }
                    snapshot.voltageMv?.let { put("voltage", it) }
                    snapshot.currentNowUa?.let { put("current", it) }
                    put("charging", if (snapshot.isCharging) 1 else 0)
                }
                helper.writableDatabase.insertWithOnConflict(
                    TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE
                )
                lastWriteMs = now
                true
            } catch (_: Throwable) {
                false
            }
        }

    suspend fun query(range: HistoryRange): List<BatteryHistoryEntry> = withContext(Dispatchers.IO) {
        val since = System.currentTimeMillis() - range.millis
        val out = ArrayList<BatteryHistoryEntry>()
        try {
            helper.readableDatabase.rawQuery(
                "SELECT ts, level, temp, voltage, current, charging FROM $TABLE " +
                    "WHERE ts >= ? ORDER BY ts ASC",
                arrayOf(since.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        BatteryHistoryEntry(
                            timestamp = c.getLong(0),
                            levelPercent = c.getInt(1),
                            temperatureCelsius = if (c.isNull(2)) null else c.getDouble(2),
                            voltageMv = if (c.isNull(3)) null else c.getInt(3),
                            currentUa = if (c.isNull(4)) null else c.getInt(4),
                            charging = c.getInt(5) == 1
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            // Return whatever was collected; the UI shows an empty-history message.
        }
        out
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        try {
            helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        } catch (_: Throwable) { 0 }
    }

    /** Deletes samples older than 30 days. Called periodically by the engine. */
    suspend fun prune() = withContext(Dispatchers.IO) {
        try {
            val cutoff = System.currentTimeMillis() - HistoryRange.D30.millis
            helper.writableDatabase.delete(TABLE, "ts < ?", arrayOf(cutoff.toString()))
        } catch (_: Throwable) { 0 }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        try { helper.writableDatabase.delete(TABLE, null, null) } catch (_: Throwable) { 0 }
    }

    private companion object {
        const val DB_NAME = "battery_history.db"
        const val DB_VERSION = 1
        const val TABLE = "battery_history"
        const val MIN_WRITE_INTERVAL_MS = 60_000L
    }
}
