package com.shimonhoter.ridelocationshare

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.shimonhoter.ridelocationshare.databinding.ActivityMapPickerBinding
import com.shimonhoter.ridelocationshare.ui.loadRideMap

/**
 * "Drop a pin" location picker (docs/SPEC_EN.md 3.4): the map pans under a
 * fixed center pin, and confirming reads the map's current center back out
 * of the WebView via JS, rather than tracking a draggable marker.
 */
class MapPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapPickerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val initialLat = intent.getDoubleExtra(EXTRA_INITIAL_LAT, 32.0853)
        val initialLon = intent.getDoubleExtra(EXTRA_INITIAL_LON, 34.7818)

        binding.webViewPicker.loadRideMap(bridge = null, mode = "pick", centerLat = initialLat, centerLon = initialLon, zoom = 15.0)

        binding.btnConfirmLocation.setOnClickListener {
            binding.webViewPicker.evaluateJavascript("getCenter()") { raw ->
                val point = parseCenterResult(raw) ?: return@evaluateJavascript
                val result = Intent().apply {
                    putExtra(EXTRA_RESULT_LAT, point.first)
                    putExtra(EXTRA_RESULT_LON, point.second)
                }
                setResult(RESULT_OK, result)
                finish()
            }
        }
    }

    private fun parseCenterResult(raw: String?): Pair<Double, Double>? {
        val unquoted = raw?.trim('"') ?: return null
        val parts = unquoted.split(",")
        val lat = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val lon = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
        return lat to lon
    }

    companion object {
        const val EXTRA_INITIAL_LAT = "initial_lat"
        const val EXTRA_INITIAL_LON = "initial_lon"
        const val EXTRA_RESULT_LAT = "result_lat"
        const val EXTRA_RESULT_LON = "result_lon"
    }
}
