package com.shimonhoter.ridelocationshare

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.shimonhoter.ridelocationshare.config.RideConfig
import com.shimonhoter.ridelocationshare.data.Prefs
import com.shimonhoter.ridelocationshare.databinding.ActivityMainBinding
import com.shimonhoter.ridelocationshare.net.LocationApi
import com.shimonhoter.ridelocationshare.net.RideLocation
import com.shimonhoter.ridelocationshare.service.BroadcastService
import com.shimonhoter.ridelocationshare.service.RideSessionState
import com.shimonhoter.ridelocationshare.ui.UiKit
import com.shimonhoter.ridelocationshare.ui.loadRideMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val locationApi = LocationApi()
    private var mapReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

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

        RideSessionState.currentLocation.observe(this) { location -> renderStatus(location) }

        // Keep the map live even when BroadcastService isn't running (e.g. a
        // waiting passenger who hasn't broadcast anything themselves yet).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    val fetched = withIoContext { locationApi.fetchLocation() }
                    RideSessionState.currentLocation.value = fetched
                    delay(RideConfig.LOCATION_UPDATE_INTERVAL_MS)
                }
            }
        }
    }

    private suspend fun <T> withIoContext(block: () -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }

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

    private fun renderRideLocation(location: RideLocation) {
        if (!mapReady) return
        binding.webViewMap.evaluateJavascript("updateRideLocation(${location.lat}, ${location.lon})", null)
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
