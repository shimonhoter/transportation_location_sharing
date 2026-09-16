package com.shimonhoter.ridelocationshare.data

import android.content.Context
import android.content.SharedPreferences
import com.shimonhoter.ridelocationshare.config.RideConfig
import org.json.JSONArray
import java.util.Calendar

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

    /** The bus line number (or any group-agreed code) that scopes this device's shared Firebase node, so unrelated groups running the app never mix into the same aggregate. Blank falls back to a shared "default" scope. */
    var rideCode: String
        get() = sp.getString(KEY_RIDE_CODE, "") ?: ""
        set(value) = sp.edit().putString(KEY_RIDE_CODE, value).apply()

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

    /** One or more daily active windows (minutes since midnight); ANY match activates automation. */
    var activeWindows: List<TimeWindow>
        get() {
            val raw = sp.getString(KEY_ACTIVE_WINDOWS, null)
            if (raw != null) {
                val array = JSONArray(raw)
                return (0 until array.length()).map { TimeWindow.fromJson(array.getJSONObject(it)) }
            }
            // Migrate a pre-existing single window from before multi-window support, if any.
            if (sp.contains(KEY_WINDOW_START) && sp.contains(KEY_WINDOW_END)) {
                return listOf(TimeWindow("default", sp.getInt(KEY_WINDOW_START, 5 * 60 + 30), sp.getInt(KEY_WINDOW_END, 8 * 60 + 30)))
            }
            return listOf(TimeWindow("default", 5 * 60 + 30, 8 * 60 + 30))
        }
        set(value) {
            val array = JSONArray()
            value.forEach { array.put(it.toJson()) }
            sp.edit().putString(KEY_ACTIVE_WINDOWS, array.toString()).apply()
        }

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

    var locationUpdateIntervalSeconds: Int
        get() = sp.getInt(KEY_LOCATION_UPDATE_INTERVAL, RideConfig.DEFAULT_LOCATION_UPDATE_INTERVAL_SECONDS)
        set(value) = sp.edit().putInt(KEY_LOCATION_UPDATE_INTERVAL, value).apply()

    var corroborationRadiusMeters: Int
        get() = sp.getInt(KEY_CORROBORATION_RADIUS, RideConfig.DEFAULT_CORROBORATION_RADIUS_METERS)
        set(value) = sp.edit().putInt(KEY_CORROBORATION_RADIUS, value).apply()

    /** Opt-in: keep a rolling on-device log of the shared ride location (RideConfig.HISTORY_RETENTION_DAYS) to estimate arrival time at the origin stop. Never sent to the server. */
    var historyEnabled: Boolean
        get() = sp.getBoolean(KEY_HISTORY_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_HISTORY_ENABLED, value).apply()

    /** Encodes the day this device was last marked "not riding" as year*1000+dayOfYear, so it self-clears on any other day without needing a scheduled reset. */
    private var skippedRideDayKey: Int
        get() = sp.getInt(KEY_SKIPPED_RIDE_DAY, 0)
        set(value) = sp.edit().putInt(KEY_SKIPPED_RIDE_DAY, value).apply()

    fun isSkippedToday(now: Calendar = Calendar.getInstance()): Boolean = skippedRideDayKey == dayKey(now)

    fun setSkipToday(skip: Boolean, now: Calendar = Calendar.getInstance()) {
        skippedRideDayKey = if (skip) dayKey(now) else 0
    }

    private fun dayKey(cal: Calendar): Int = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)

    /** How long activating "private car" mode blocks broadcasting for, in minutes, before it auto-reverts. User-configurable via Settings. */
    var privateCarDurationMinutes: Int
        get() = sp.getInt(KEY_PRIVATE_CAR_DURATION, RideConfig.DEFAULT_PRIVATE_CAR_DURATION_MINUTES)
        set(value) = sp.edit().putInt(KEY_PRIVATE_CAR_DURATION, value).apply()

    private var privateCarActiveUntilMillis: Long
        get() = sp.getLong(KEY_PRIVATE_CAR_UNTIL, 0L)
        set(value) = sp.edit().putLong(KEY_PRIVATE_CAR_UNTIL, value).apply()

    /** While active, neither automatic nor manual broadcasting is allowed from this device (a passenger who took their own private car instead of the shared ride). */
    fun isPrivateCarActive(now: Long = System.currentTimeMillis()): Boolean = privateCarActiveUntilMillis > now

    fun activatePrivateCarMode(now: Long = System.currentTimeMillis()) {
        privateCarActiveUntilMillis = now + privateCarDurationMinutes * 60_000L
    }

    fun deactivatePrivateCarMode() {
        privateCarActiveUntilMillis = 0L
    }

    /** Milliseconds until private car mode auto-expires, or 0 if it isn't currently active. */
    fun privateCarRemainingMillis(now: Long = System.currentTimeMillis()): Long =
        (privateCarActiveUntilMillis - now).coerceAtLeast(0L)

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
        private const val KEY_RIDE_CODE = "ride_code"
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
        private const val KEY_ACTIVE_WINDOWS = "active_windows"
        private const val KEY_ACTIVE_DAYS = "active_days"
        private const val KEY_ALERT_ZONES = "alert_zones"
        private const val KEY_ALERTED_ZONE_IDS = "alerted_zone_ids"
        private const val KEY_IS_BROADCASTING = "is_broadcasting"
        private const val KEY_RIDE_START_TIME = "ride_start_time"
        private const val KEY_LOCATION_UPDATE_INTERVAL = "location_update_interval_seconds"
        private const val KEY_CORROBORATION_RADIUS = "corroboration_radius"
        private const val KEY_HISTORY_ENABLED = "history_enabled"
        private const val KEY_SKIPPED_RIDE_DAY = "skipped_ride_day_key"
        private const val KEY_PRIVATE_CAR_DURATION = "private_car_duration_minutes"
        private const val KEY_PRIVATE_CAR_UNTIL = "private_car_active_until"

        // Sunday(1)-Thursday(5): the default Israeli work week.
        private val DEFAULT_ACTIVE_DAYS = setOf("1", "2", "3", "4", "5")
        private val DEFAULT_ACTIVE_DAYS_INT = setOf(1, 2, 3, 4, 5)
    }
}
