package com.shimonhoter.ridelocationshare.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * All per-user settings and local ride state. Everything here stays on the
 * device only — the server never sees origin/destination/alert zones (see
 * docs/SPEC_EN.md section 5, privacy model).
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("ride_prefs", Context.MODE_PRIVATE)

    var nickname: String
        get() = sp.getString(KEY_NICKNAME, "") ?: ""
        set(value) = sp.edit().putString(KEY_NICKNAME, value).apply()

    var showNickname: Boolean
        get() = sp.getBoolean(KEY_SHOW_NICKNAME, false)
        set(value) = sp.edit().putBoolean(KEY_SHOW_NICKNAME, value).apply()

    var origin: GeoPoint?
        get() = readPoint(KEY_ORIGIN_LAT, KEY_ORIGIN_LON)
        set(value) = writePoint(KEY_ORIGIN_LAT, KEY_ORIGIN_LON, value)

    var destination: GeoPoint?
        get() = readPoint(KEY_DEST_LAT, KEY_DEST_LON)
        set(value) = writePoint(KEY_DEST_LAT, KEY_DEST_LON, value)

    var geofenceRadiusMeters: Int
        get() = sp.getInt(KEY_GEOFENCE_RADIUS, 200)
        set(value) = sp.edit().putInt(KEY_GEOFENCE_RADIUS, value).apply()

    var tripDurationMinutes: Int
        get() = sp.getInt(KEY_TRIP_DURATION, 90)
        set(value) = sp.edit().putInt(KEY_TRIP_DURATION, value).apply()

    var safetyMarginMinutes: Int
        get() = sp.getInt(KEY_SAFETY_MARGIN, 20)
        set(value) = sp.edit().putInt(KEY_SAFETY_MARGIN, value).apply()

    /** Minutes since midnight. Default 05:30-08:30. */
    var activeWindowStartMinutes: Int
        get() = sp.getInt(KEY_WINDOW_START, 5 * 60 + 30)
        set(value) = sp.edit().putInt(KEY_WINDOW_START, value).apply()

    var activeWindowEndMinutes: Int
        get() = sp.getInt(KEY_WINDOW_END, 8 * 60 + 30)
        set(value) = sp.edit().putInt(KEY_WINDOW_END, value).apply()

    /** java.util.Calendar.DAY_OF_WEEK values (1=Sunday..7=Saturday). Default Sun-Thu. */
    var activeDays: Set<Int>
        get() = sp.getStringSet(KEY_ACTIVE_DAYS, DEFAULT_ACTIVE_DAYS)
            ?.mapNotNull { it.toIntOrNull() }?.toSet() ?: DEFAULT_ACTIVE_DAYS_INT
        set(value) = sp.edit().putStringSet(KEY_ACTIVE_DAYS, value.map { it.toString() }.toSet()).apply()

    var alertZones: List<AlertZone>
        get() {
            val raw = sp.getString(KEY_ALERT_ZONES, null) ?: return emptyList()
            val array = JSONArray(raw)
            return (0 until array.length()).map { AlertZone.fromJson(array.getJSONObject(it)) }
        }
        set(value) {
            val array = JSONArray()
            value.forEach { array.put(it.toJson()) }
            sp.edit().putString(KEY_ALERT_ZONES, array.toString()).apply()
        }

    var alertedZoneIds: Set<String>
        get() = sp.getStringSet(KEY_ALERTED_ZONE_IDS, emptySet()) ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_ALERTED_ZONE_IDS, value).apply()

    fun clearAlertedZones() = sp.edit().remove(KEY_ALERTED_ZONE_IDS).apply()

    var isBroadcasting: Boolean
        get() = sp.getBoolean(KEY_IS_BROADCASTING, false)
        set(value) = sp.edit().putBoolean(KEY_IS_BROADCASTING, value).apply()

    var rideStartTimeMillis: Long
        get() = sp.getLong(KEY_RIDE_START_TIME, 0L)
        set(value) = sp.edit().putLong(KEY_RIDE_START_TIME, value).apply()

    private fun readPoint(latKey: String, lonKey: String): GeoPoint? {
        if (!sp.contains(latKey) || !sp.contains(lonKey)) return null
        val lat = sp.getFloat(latKey, 0f).toDouble()
        val lon = sp.getFloat(lonKey, 0f).toDouble()
        return GeoPoint(lat, lon)
    }

    private fun writePoint(latKey: String, lonKey: String, value: GeoPoint?) {
        val editor = sp.edit()
        if (value == null) {
            editor.remove(latKey).remove(lonKey)
        } else {
            editor.putFloat(latKey, value.lat.toFloat()).putFloat(lonKey, value.lon.toFloat())
        }
        editor.apply()
    }

    companion object {
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_SHOW_NICKNAME = "show_nickname"
        private const val KEY_ORIGIN_LAT = "origin_lat"
        private const val KEY_ORIGIN_LON = "origin_lon"
        private const val KEY_DEST_LAT = "dest_lat"
        private const val KEY_DEST_LON = "dest_lon"
        private const val KEY_GEOFENCE_RADIUS = "geofence_radius"
        private const val KEY_TRIP_DURATION = "trip_duration"
        private const val KEY_SAFETY_MARGIN = "safety_margin"
        private const val KEY_WINDOW_START = "window_start"
        private const val KEY_WINDOW_END = "window_end"
        private const val KEY_ACTIVE_DAYS = "active_days"
        private const val KEY_ALERT_ZONES = "alert_zones"
        private const val KEY_ALERTED_ZONE_IDS = "alerted_zone_ids"
        private const val KEY_IS_BROADCASTING = "is_broadcasting"
        private const val KEY_RIDE_START_TIME = "ride_start_time"

        // Sunday(1)-Thursday(5): the default Israeli work week.
        private val DEFAULT_ACTIVE_DAYS = setOf("1", "2", "3", "4", "5")
        private val DEFAULT_ACTIVE_DAYS_INT = setOf(1, 2, 3, 4, 5)
    }
}
