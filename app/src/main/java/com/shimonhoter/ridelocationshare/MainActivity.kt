package com.shimonhoter.ridelocationshare

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
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
import com.shimonhoter.ridelocationshare.util.ActiveWindow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private var lastRenderedBroadcasting: Boolean? = null
    private var privateCarAutoOffJob: Job? = null

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

        binding.btnPrivateCar.setOnClickListener { togglePrivateCarMode() }
        binding.btnSettings.setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
        binding.btnAlertZones.setOnClickListener {
            startActivity(android.content.Intent(this, AlertZonesActivity::class.java))
        }
        binding.btnHelp.setOnClickListener { showHelp() }

        // Don't make the user wait for the next ~15-minute RideCheckWorker
        // tick — if automation is currently allowed to run at all, start
        // monitoring the moment the app is opened. This only arms the
        // geofence/movement check; BroadcastService still decides whether to
        // actually start broadcasting (and still enforces private car mode).
        if (ActiveWindow.isNowActive(prefs)) {
            BroadcastService.startAutomatic(this)
        }

        renderPrivateCarToggle()
        schedulePrivateCarAutoOffRefresh()

        RideSessionState.currentLocation.observe(this) { location ->
            renderStatus(location)
            refreshEta()
        }

        // Reflects whether THIS device is broadcasting, independent of the
        // shared ride-location status above — updates immediately whether
        // triggered by the toggle button or automatically by BroadcastService
        // (geofence/movement start, safety-timer stop), without waiting for a
        // Firebase round-trip.
        RideSessionState.isThisDeviceBroadcasting.observe(this) { isBroadcasting -> renderBroadcastToggle(isBroadcasting) }
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
        // Prefs.historyEnabled or changing the origin in Settings, or private
        // car mode having auto-expired while this screen wasn't visible).
        refreshEta()
        renderPrivateCarToggle()
        renderBroadcastToggle(RideSessionState.isThisDeviceBroadcasting.value == true)
        schedulePrivateCarAutoOffRefresh()
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
            if (mapReady) binding.webViewMap.evaluateJavascript("clearRideLocation()", null)
        } else {
            renderRideLocation(location)
        }
    }

    /**
     * A pure status indicator now, not a control — there's no tap handler on
     * it (see onCreate). Always shows the antenna icon; a red "blocked"
     * overlay appears whenever this device isn't currently broadcasting, for
     * any reason (private car mode, outside the geofence/movement start
     * condition, or the safety-timer/time-window stop) so a single glance
     * always answers "is my location going out right now". A toast fires
     * only on an actual change (never on the initial value delivered when
     * the observer attaches).
     */
    private fun renderBroadcastToggle(isBroadcasting: Boolean) {
        binding.btnBroadcastToggle.backgroundTintList =
            ColorStateList.valueOf(UiKit.statusColor(this, isBroadcasting))
        binding.tvBroadcastBlockedOverlay.visibility = if (isBroadcasting) View.GONE else View.VISIBLE

        if (lastRenderedBroadcasting != null && lastRenderedBroadcasting != isBroadcasting) {
            val message = if (isBroadcasting) R.string.broadcast_started_message else R.string.broadcast_stopped_message
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
        lastRenderedBroadcasting = isBroadcasting
    }

    private fun togglePrivateCarMode() {
        if (prefs.isPrivateCarActive()) {
            prefs.deactivatePrivateCarMode()
            Toast.makeText(this, R.string.private_car_deactivated_message, Toast.LENGTH_SHORT).show()
        } else {
            if (RideSessionState.isThisDeviceBroadcasting.value == true) {
                BroadcastService.stop(this)
            }
            prefs.activatePrivateCarMode()
            Toast.makeText(this, R.string.private_car_activated_message, Toast.LENGTH_SHORT).show()
        }
        renderPrivateCarToggle()
        renderBroadcastToggle(RideSessionState.isThisDeviceBroadcasting.value == true)
        schedulePrivateCarAutoOffRefresh()
    }

    private fun renderPrivateCarToggle() {
        binding.btnPrivateCar.backgroundTintList = ColorStateList.valueOf(
            getColor(if (prefs.isPrivateCarActive()) R.color.brand_accent else R.color.status_idle)
        )
    }

    /** Re-renders the private car and broadcast toggles the moment the mode
     * naturally expires while this screen is open, instead of only on the
     * next onStart(). */
    private fun schedulePrivateCarAutoOffRefresh() {
        privateCarAutoOffJob?.cancel()
        val remainingMillis = prefs.privateCarRemainingMillis()
        if (remainingMillis > 0) {
            privateCarAutoOffJob = lifecycleScope.launch {
                delay(remainingMillis)
                renderPrivateCarToggle()
                renderBroadcastToggle(RideSessionState.isThisDeviceBroadcasting.value == true)
            }
        }
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
