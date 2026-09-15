package com.shimonhoter.ridelocationshare

import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.shimonhoter.ridelocationshare.data.AlertZone
import com.shimonhoter.ridelocationshare.data.Prefs
import com.shimonhoter.ridelocationshare.databinding.ActivityAlertZonesBinding
import com.shimonhoter.ridelocationshare.databinding.DialogAlertZoneBinding
import com.shimonhoter.ridelocationshare.databinding.ItemAlertZoneBinding
import com.shimonhoter.ridelocationshare.ui.MapBridge
import com.shimonhoter.ridelocationshare.ui.loadRideMap
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Lets the user mark personal alert zones on the map (docs/SPEC_EN.md 3.5).
 * These are stored only in Prefs (on-device) and never sent to the server.
 */
class AlertZonesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlertZonesBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlertZonesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        val center = prefs.origin
        val bridge = MapBridge(
            onTap = { lat, lon -> runOnUiThread { promptNewZone(lat, lon) } },
            onReady = { runOnUiThread { redrawZones() } }
        )
        binding.webViewZones.loadRideMap(
            bridge = bridge,
            mode = "zones",
            centerLat = center?.lat ?: 32.0853,
            centerLon = center?.lon ?: 34.7818
        )

        renderZoneList()
    }

    private fun promptNewZone(lat: Double, lon: Double) {
        val dialogBinding = DialogAlertZoneBinding.inflate(LayoutInflater.from(this))
        AlertDialog.Builder(this)
            .setTitle(R.string.alert_zones_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save_button) { _, _ ->
                val name = dialogBinding.etZoneName.text?.toString().orEmpty()
                val radius = dialogBinding.etZoneRadius.text?.toString()?.toDoubleOrNull() ?: 150.0
                addZone(AlertZone(id = UUID.randomUUID().toString(), name = name, lat = lat, lon = lon, radiusMeters = radius))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addZone(zone: AlertZone) {
        prefs.alertZones = prefs.alertZones + zone
        redrawZones()
        renderZoneList()
    }

    private fun removeZone(zoneId: String) {
        prefs.alertZones = prefs.alertZones.filterNot { it.id == zoneId }
        prefs.alertedZoneIds = prefs.alertedZoneIds - zoneId
        redrawZones()
        renderZoneList()
    }

    private fun redrawZones() {
        val array = JSONArray()
        prefs.alertZones.forEach { zone ->
            array.put(
                JSONObject().apply {
                    put("id", zone.id)
                    put("name", zone.name)
                    put("lat", zone.lat)
                    put("lon", zone.lon)
                    put("radiusMeters", zone.radiusMeters)
                }
            )
        }
        val escaped = array.toString().replace("\\", "\\\\").replace("'", "\\'")
        binding.webViewZones.evaluateJavascript("setAlertZones('$escaped')", null)
    }

    private fun renderZoneList() {
        binding.llZonesList.removeAllViews()
        prefs.alertZones.forEach { zone ->
            val itemBinding = ItemAlertZoneBinding.inflate(layoutInflater, binding.llZonesList, false)
            val label = if (zone.name.isNotBlank()) zone.name else "אזור ללא שם"
            itemBinding.tvZoneLabel.text = "$label (${zone.radiusMeters.toInt()}m)"
            itemBinding.btnDeleteZone.setOnClickListener { removeZone(zone.id) }
            binding.llZonesList.addView(itemBinding.root)
        }
    }
}
