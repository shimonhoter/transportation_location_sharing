package com.shimonhoter.ridelocationshare

import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.database.ValueEventListener
import com.shimonhoter.ridelocationshare.data.Prefs
import com.shimonhoter.ridelocationshare.databinding.ActivityMainBinding
import com.shimonhoter.ridelocationshare.history.RideEtaEstimator
import com.shimonhoter.ridelocationshare.history.RideHistoryStore
import com.shimonhoter.ridelocationshare.remote.FirebaseLocationRepository
import com.shimonhoter.ridelocationshare.remote.RideLocation
import com.shimonhoter.ridelocationshare.service.BroadcastService
import com.shimonhoter.ridelocationshare.service.RideSessionState
import com.shimonhoter.ridelocationshare.ui.UiKit
import com.shimonhoter.ridelocationshare.ui.loadRideMap
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var historyStore: RideHistoryStore
    private val repository = FirebaseLocationRepository()
    private var locationListener: ValueEventListener? = null
    private var locationListenerRideCode: String? = null
    private var mapReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)
        historyStore = RideHistoryStore(this)

        val center = prefs.origin
        binding.webViewMap.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                mapReady = true
                pushAlertZonesToMap()
                RideSessionState.currentLocation.value?.let { renderRideLocation(it) }
            }
        }
        binding.webViewMap.loadRideMap(
            bridge = null,
            mode = "view",
            centerLat = center?.lat ?: 32.0853,
            centerLon = center?.lon ?: 34.7818
        )

        binding.btnBroadcastNow.setOnClickListener {
            BroadcastService.startManualBroadcast(this)
        }
        binding.btnGotOff.setOnClickListener {
            BroadcastService.stop(this)
        }
        binding.btnSettings.setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
        binding.btnAlertZones.setOnClickListener {
            startActivity(android.content.Intent(this, AlertZonesActivity::class.java))
        }
        binding.btnHelp.setOnClickListener { showHelp() }

        binding.switchSkipToday.isChecked = prefs.isSkippedToday()
        binding.switchSkipToday.setOnCheckedChangeListener { _, isChecked ->
            prefs.setSkipToday(isChecked)
        }

        RideSessionState.currentLocation.observe(this) { location ->
            renderStatus(location)
            refreshEta()
        }

        // Reflects whether THIS device is broadcasting, independent of the
        // shared ride-location status above — updates immediately on button
        // press, without waiting for a Firebase round-trip.
        RideSessionState.isThisDeviceBroadcasting.observe(this) { isBroadcasting -> renderSelfBroadcasting(isBroadcasting) }
    }

    override fun onStart() {
        super.onStart()
        // Keep the map live even when BroadcastService isn't running (e.g. a
        // waiting passenger who hasn't broadcast anything themselves yet) —
        // Firebase pushes updates directly, no polling needed.
        val rideCode = prefs.rideCode
        locationListenerRideCode = rideCode
        locationListener = repository.observeLocation(rideCode, { prefs.corroborationRadiusMeters }) { location ->
            RideSessionState.currentLocation.value = location
        }
        // Re-evaluate on every return to this screen too (e.g. after toggling
        // Prefs.historyEnabled or changing the origin in Settings).
        refreshEta()
    }

    override fun onStop() {
        super.onStop()
        val rideCode = locationListenerRideCode
        if (rideCode != null) {
            locationListener?.let { repository.removeListener(rideCode, it) }
        }
        locationListener = null
        locationListenerRideCode = null
    }

    private fun renderStatus(location: RideLocation?) {
        if (location == null) {
            binding.tvStatus.text = getString(R.string.status_no_data)
            binding.tvStatusDetail.text = ""
            binding.tvStatus.setTextColor(UiKit.statusColor(this, false))
            if (mapReady) binding.webViewMap.evaluateJavascript("clearRideLocation()", null)
        } else {
            binding.tvStatus.text = getString(R.string.status_broadcasting)
            binding.tvStatus.setTextColor(UiKit.statusColor(this, true))
            binding.tvStatusDetail.text = buildString {
                append(UiKit.formatAge(location.ageSeconds))
                if (!location.nickname.isNullOrBlank()) append(" · ${location.nickname}")
            }
            renderRideLocation(location)
        }
    }

    private fun renderSelfBroadcasting(isBroadcasting: Boolean) {
        binding.tvSelfBroadcastBadge.visibility = if (isBroadcasting) View.VISIBLE else View.GONE
        binding.btnBroadcastNow.isEnabled = !isBroadcasting
    }

    private fun renderRideLocation(location: RideLocation) {
        if (!mapReady) return
        binding.webViewMap.evaluateJavascript("updateRideLocation(${location.lat}, ${location.lon})", null)
    }

    /**
     * Recomputes the estimated arrival time at the user's stop (origin) from
     * locally recorded history (docs/SPEC_EN.md 4.5), preferring a live
     * distance/speed extrapolation over the historical median once the ride
     * is actually moving. Opt-in via Prefs.historyEnabled; hidden entirely
     * otherwise or when no origin is configured.
     */
    private fun refreshEta() {
        val origin = prefs.origin
        if (origin == null || !prefs.historyEnabled) {
            binding.cardEta.visibility = View.GONE
            return
        }
        val location = RideSessionState.currentLocation.value
        val geofenceRadiusMeters = prefs.geofenceRadiusMeters
        lifecycleScope.launch {
            val history = historyStore.allSamples()
            val eta = RideEtaEstimator.estimate(
                history = history,
                stop = origin,
                arrivalRadiusMeters = geofenceRadiusMeters,
                currentLat = location?.lat,
                currentLon = location?.lon,
                currentAvgSpeedKmh = location?.avgSpeedKmh
            )
            if (eta != null) {
                binding.tvEta.text = getString(
                    R.string.eta_format,
                    "%02d:%02d".format(eta.get(Calendar.HOUR_OF_DAY), eta.get(Calendar.MINUTE))
                )
                binding.cardEta.visibility = View.VISIBLE
            } else {
                binding.cardEta.visibility = View.GONE
            }
        }
    }

    private fun showHelp() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.help_title)
            .setMessage(R.string.help_body)
            .setPositiveButton(R.string.help_close_button, null)
            .show()
    }

    private fun pushAlertZonesToMap() {
        val zonesArray = JSONArray()
        prefs.alertZones.forEach { zone ->
            zonesArray.put(
                JSONObject().apply {
                    put("id", zone.id)
                    put("name", zone.name)
                    put("lat", zone.lat)
                    put("lon", zone.lon)
                    put("radiusMeters", zone.radiusMeters)
                }
            )
        }
        binding.webViewMap.evaluateJavascript("setAlertZones('${zonesArray.toString().replace("'", "\\'")}')", null)
    }
}
