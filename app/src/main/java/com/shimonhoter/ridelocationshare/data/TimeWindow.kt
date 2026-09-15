package com.shimonhoter.ridelocationshare.data

import org.json.JSONObject

/** Minutes since midnight for both bounds; a single daily active window (docs/SPEC_EN.md 3.3/3.8). */
data class TimeWindow(
    val id: String,
    val startMinutes: Int,
    val endMinutes: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("startMinutes", startMinutes)
        put("endMinutes", endMinutes)
    }

    companion object {
        fun fromJson(json: JSONObject): TimeWindow = TimeWindow(
            id = json.getString("id"),
            startMinutes = json.getInt("startMinutes"),
            endMinutes = json.getInt("endMinutes")
        )
    }
}
