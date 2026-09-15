package com.shimonhoter.ridelocationshare.data

import org.json.JSONObject

data class AlertZone(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusMeters: Double
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("lat", lat)
        put("lon", lon)
        put("radiusMeters", radiusMeters)
    }

    companion object {
        fun fromJson(json: JSONObject): AlertZone = AlertZone(
            id = json.getString("id"),
            name = json.optString("name", ""),
            lat = json.getDouble("lat"),
            lon = json.getDouble("lon"),
            radiusMeters = json.getDouble("radiusMeters")
        )
    }
}

data class GeoPoint(val lat: Double, val lon: Double)
