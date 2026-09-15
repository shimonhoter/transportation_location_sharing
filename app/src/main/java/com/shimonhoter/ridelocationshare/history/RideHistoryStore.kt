package com.shimonhoter.ridelocationshare.history

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.shimonhoter.ridelocationshare.config.RideConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class HistorySample(val timestampMillis: Long, val lat: Double, val lon: Double, val speedKmh: Double)

/**
 * On-device-only rolling log of the shared ride location, kept for
 * [RideConfig.HISTORY_RETENTION_DAYS] days and used by [com.shimonhoter.ridelocationshare.history.RideEtaEstimator]
 * to estimate when the ride typically reaches a configured stop. Opt-in via
 * Prefs.historyEnabled. Nothing here is ever sent to the server — this is a
 * purely local statistics cache, unrelated to the "no server-side route
 * history" privacy principle in docs/SPEC_EN.md section 5 (which is about
 * the backend, not the device).
 */
class RideHistoryStore(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE $TABLE (" +
                "$COL_TIMESTAMP INTEGER NOT NULL, " +
                "$COL_LAT REAL NOT NULL, " +
                "$COL_LON REAL NOT NULL, " +
                "$COL_SPEED_KMH REAL NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_$COL_TIMESTAMP ON $TABLE ($COL_TIMESTAMP)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    suspend fun record(timestampMillis: Long, lat: Double, lon: Double, speedKmh: Double) = withContext(Dispatchers.IO) {
        writableDatabase.insert(
            TABLE, null,
            ContentValues().apply {
                put(COL_TIMESTAMP, timestampMillis)
                put(COL_LAT, lat)
                put(COL_LON, lon)
                put(COL_SPEED_KMH, speedKmh)
            }
        )
        val cutoff = timestampMillis - RETENTION_MILLIS
        writableDatabase.delete(TABLE, "$COL_TIMESTAMP < ?", arrayOf(cutoff.toString()))
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE, null, null)
    }

    /** All samples from the last [RideConfig.HISTORY_RETENTION_DAYS] days, oldest first. */
    suspend fun allSamples(): List<HistorySample> = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - RETENTION_MILLIS
        val samples = mutableListOf<HistorySample>()
        readableDatabase.query(
            TABLE, null, "$COL_TIMESTAMP >= ?", arrayOf(cutoff.toString()), null, null, "$COL_TIMESTAMP ASC"
        ).use { cursor ->
            val tsIdx = cursor.getColumnIndexOrThrow(COL_TIMESTAMP)
            val latIdx = cursor.getColumnIndexOrThrow(COL_LAT)
            val lonIdx = cursor.getColumnIndexOrThrow(COL_LON)
            val speedIdx = cursor.getColumnIndexOrThrow(COL_SPEED_KMH)
            while (cursor.moveToNext()) {
                samples.add(
                    HistorySample(cursor.getLong(tsIdx), cursor.getDouble(latIdx), cursor.getDouble(lonIdx), cursor.getDouble(speedIdx))
                )
            }
        }
        samples
    }

    companion object {
        private const val DB_NAME = "ride_history.db"
        private const val DB_VERSION = 1
        private const val TABLE = "samples"
        private const val COL_TIMESTAMP = "timestamp"
        private const val COL_LAT = "lat"
        private const val COL_LON = "lon"
        private const val COL_SPEED_KMH = "speed_kmh"
        private val RETENTION_MILLIS = RideConfig.HISTORY_RETENTION_DAYS * 24 * 60 * 60 * 1000L
    }
}
