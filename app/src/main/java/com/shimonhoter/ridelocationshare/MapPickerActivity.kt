package com.shimonhoter.ridelocationshare

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.shimonhoter.ridelocationshare.databinding.ActivityMapPickerBinding
import com.shimonhoter.ridelocationshare.ui.loadRideMap

/**
 * Origin/destination zone picker (docs/SPEC_EN.md 3.4): shows a single
 * circle with the same ✛ move / ↔ resize drag handles as alert-zone
 * editing on the main map, so defining a zone here uses the identical
 * tool/interaction — just one always-present zone instead of a list.
 */
class MapPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapPickerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMapPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val initialLat = intent.getDoubleExtra(EXTRA_INITIAL_LAT, 32.0853)
        val initialLon = intent.getDoubleExtra(EXTRA_INITIAL_LON, 34.7818)
        val initialRadius = intent.getDoubleExtra(EXTRA_INITIAL_RADIUS, 150.0)

        binding.webViewPicker.loadRideMap(
            bridge = null,
            mode = "pick_zone",
            centerLat = initialLat,
            centerLon = initialLon,
            zoom = 15.0,
            radiusMeters = initialRadius
        )

        binding.btnConfirmLocation.setOnClickListener {
            binding.webViewPicker.evaluateJavascript("getPickedZone()") { raw ->
                val zone = parsePickedZone(raw) ?: return@evaluateJavascript
                val result = Intent().apply {
                    putExtra(EXTRA_RESULT_LAT, zone.first)
                    putExtra(EXTRA_RESULT_LON, zone.second)
                    putExtra(EXTRA_RESULT_RADIUS, zone.third)
                }
                setResult(RESULT_OK, result)
                finish()
            }
        }
    }

    private fun parsePickedZone(raw: String?): Triple<Double, Double, Double>? {
        val unquoted = raw?.trim('"') ?: return null
        val parts = unquoted.split(",")
        val lat = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val lon = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
        val radius = parts.getOrNull(2)?.toDoubleOrNull() ?: return null
        return Triple(lat, lon, radius)
    }

    companion object {
        const val EXTRA_INITIAL_LAT = "initial_lat"
        const val EXTRA_INITIAL_LON = "initial_lon"
        const val EXTRA_INITIAL_RADIUS = "initial_radius"
        const val EXTRA_RESULT_LAT = "result_lat"
        const val EXTRA_RESULT_LON = "result_lon"
        const val EXTRA_RESULT_RADIUS = "result_radius"
    }
}
