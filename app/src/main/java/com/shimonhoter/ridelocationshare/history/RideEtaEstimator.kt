package com.shimonhoter.ridelocationshare.history

import com.shimonhoter.ridelocationshare.config.RideConfig
import com.shimonhoter.ridelocationshare.data.GeoPoint
import com.shimonhoter.ridelocationshare.util.GeoUtil
import java.util.Calendar

/**
 * Estimates when the ride will reach a configured stop (the user's origin),
 * from locally recorded history ([RideHistoryStore]). Two sources, in order
 * of preference:
 *  - **Live**: the ride's current distance to the stop divided by its
 *    current average speed, when the ride is actually moving fast enough to
 *    extrapolate from ([RideConfig.MIN_SPEED_FOR_LIVE_ETA_KMH]) — accurate,
 *    but only available once the ride has actually started and is visible.
 *  - **Historical**: the median time-of-day the ride has reached the stop
 *    on past recorded days (the first sample each day within
 *    [arrivalRadiusMeters] of the stop) — used before the ride is moving
 *    yet, or whenever a live estimate isn't available.
 * Deliberately simple: a straight-line-distance/current-speed estimate, not
 * a route-aware prediction — appropriate given the small, short-lived local
 * dataset this draws from (docs/SPEC_EN.md 4.5).
 */
object RideEtaEstimator {

    /** Returns an estimated arrival [Calendar] (today), or null if there isn't enough data for either estimate. */
    fun estimate(
        history: List<HistorySample>,
        stop: GeoPoint,
        arrivalRadiusMeters: Int,
        currentLat: Double?,
        currentLon: Double?,
        currentAvgSpeedKmh: Double?,
        now: Calendar = Calendar.getInstance()
    ): Calendar? {
        if (currentLat != null && currentLon != null && currentAvgSpeedKmh != null &&
            currentAvgSpeedKmh > RideConfig.MIN_SPEED_FOR_LIVE_ETA_KMH
        ) {
            val distanceMeters = GeoUtil.distanceMeters(currentLat, currentLon, stop.lat, stop.lon)
            val etaMinutes = (distanceMeters / 1000.0) / currentAvgSpeedKmh * 60.0
            return (now.clone() as Calendar).apply { add(Calendar.MINUTE, etaMinutes.toInt()) }
        }

        val historicalMinutesOfDay = historicalArrivalMinutesOfDay(history, stop, arrivalRadiusMeters)
        if (historicalMinutesOfDay.isEmpty()) return null
        val medianMinutes = median(historicalMinutesOfDay)
        return (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, medianMinutes / 60)
            set(Calendar.MINUTE, medianMinutes % 60)
            set(Calendar.SECOND, 0)
        }
    }

    /** For each calendar day represented in [history] (assumed sorted ascending by time), the minutes-since-midnight of the first sample within [radiusMeters] of [stop]. */
    private fun historicalArrivalMinutesOfDay(history: List<HistorySample>, stop: GeoPoint, radiusMeters: Int): List<Int> {
        val results = mutableListOf<Int>()
        var currentDayKey = Int.MIN_VALUE
        var foundForDay = false
        val cal = Calendar.getInstance()
        for (sample in history) {
            cal.timeInMillis = sample.timestampMillis
            val dayKey = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
            if (dayKey != currentDayKey) {
                currentDayKey = dayKey
                foundForDay = false
            }
            if (foundForDay) continue
            if (GeoUtil.distanceMeters(sample.lat, sample.lon, stop.lat, stop.lon) <= radiusMeters) {
                results.add(cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE))
                foundForDay = true
            }
        }
        return results
    }

    private fun median(values: List<Int>): Int {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
    }
}
