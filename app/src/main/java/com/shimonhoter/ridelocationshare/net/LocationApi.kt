package com.shimonhoter.ridelocationshare.net

import com.shimonhoter.ridelocationshare.config.RideConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class RideLocation(val lat: Double, val lon: Double, val nickname: String?, val ageSeconds: Double)

/**
 * Talks to the stdlib Python location server (server/server.py). No JSON
 * library dependency needed beyond org.json, which ships with Android.
 */
class LocationApi {
    private val config = RideConfig.default()

    fun postLocation(lat: Double, lon: Double, nickname: String?): Boolean {
        val url = URL("${config.baseUrl}/location")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-Ride-Token", config.token)
        }
        return try {
            val body = JSONObject().apply {
                put("lat", lat)
                put("lon", lon)
                if (!nickname.isNullOrBlank()) put("nickname", nickname)
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            connection.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    fun fetchLocation(): RideLocation? {
        val url = URL("${config.baseUrl}/location")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        return try {
            val text = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val json = JSONObject(text)
            if (!json.optBoolean("active", false)) return null
            RideLocation(
                lat = json.getDouble("lat"),
                lon = json.getDouble("lon"),
                nickname = json.optString("nickname", null),
                ageSeconds = json.optDouble("age_seconds", 0.0)
            )
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
