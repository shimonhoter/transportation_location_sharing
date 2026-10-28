package com.shimonhoter.ridelocationshare.data

import org.json.JSONObject

/**
 * One of this device's own pickup-stop candidate points (docs/SPEC_EN.md
 * section 3.9) — no radius, unlike AlertZone: merging candidates from
 * different users within RideConfig.STOP_MERGE_RADIUS_METERS of each
 * other into one shared, named, averaged stop (StopsRepository) is what
 * gives it an effective radius, not a per-point setting.
 */
data class StopCandidate(val id: String, val lat: Double, val lon: Double) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("lat", lat)
        put("lon", lon)
    }

    companion object {
        fun fromJson(json: JSONObject): StopCandidate = StopCandidate(
            id = json.getString("id"),
            lat = json.getDouble("lat"),
            lon = json.getDouble("lon")
        )
    }
}
