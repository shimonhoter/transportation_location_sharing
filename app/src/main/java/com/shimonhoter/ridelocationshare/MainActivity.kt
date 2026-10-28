package com.shimonhoter.ridelocationshare

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.firebase.database.ValueEventListener
import com.shimonhoter.ridelocationshare.config.RideConfig
import com.shimonhoter.ridelocationshare.data.AlertZone
import com.shimonhoter.ridelocationshare.data.GeoPoint
import com.shimonhoter.ridelocationshare.data.Prefs
import com.shimonhoter.ridelocationshare.data.StopCandidate
import com.shimonhoter.ridelocationshare.databinding.ActivityMainBinding
import com.shimonhoter.ridelocationshare.history.RideEtaEstimator
import com.shimonhoter.ridelocationshare.history.RideHistoryStore
import com.shimonhoter.ridelocationshare.remote.ActiveHandRaise
import com.shimonhoter.ridelocationshare.remote.FirebaseLocationRepository
import com.shimonhoter.ridelocationshare.remote.RideLocation
import com.shimonhoter.ridelocationshare.remote.SharedStop
import com.shimonhoter.ridelocationshare.remote.SharedStopsObservation
import com.shimonhoter.ridelocationshare.remote.StopsRepository
import com.shimonhoter.ridelocationshare.service.BroadcastService
import com.shimonhoter.ridelocationshare.service.RideSessionState
import com.shimonhoter.ridelocationshare.ui.MapBridge
import com.shimonhoter.ridelocationshare.ui.UiKit
import com.shimonhoter.ridelocationshare.ui.loadRideMap
import com.shimonhoter.ridelocationshare.util.ActiveWindow
import com.shimonhoter.ridelocationshare.util.GeoUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var historyStore: RideHistoryStore
    private val repository = FirebaseLocationRepository()
    private val stopsRepository = StopsRepository()
    private var locationListener: ValueEventListener? = null
    private var locationListenerRideCode: String? = null
    private var sharedStopsObservation: SharedStopsObservation? = null
    private var handRaisesListener: ValueEventListener? = null
    private var todayRouteStartListener: ValueEventListener? = null
    private var stopsListenerRideCode: String? = null
    private var latestSharedStops: List<SharedStop> = emptyList()
    private var latestHandRaises: List<ActiveHandRaise> = emptyList()
    private var todayRouteStartMillis: Long? = null
    private val recordedStopArrivalKeysToday = mutableSetOf<String>()
    private val todayDateKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
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
                pushOriginDestZonesToMap()
                pushMyStopCandidatesToMap()
                pushSharedStopsToMap()
                RideSessionState.currentLocation.value?.let { renderRideLocation(it) }
            }
        }
        // Alert zones (docs/SPEC_EN.md 3.5) are now added and edited directly
        // on this map via its own "edit zones" control (see map.html), rather
        // than a separate screen — these callbacks are how it reports back.
        val mapBridge = MapBridge(
            onZoneAdded = { lat, lon -> runOnUiThread { addAlertZone(lat, lon) } },
            onZoneMoved = { id, lat, lon -> runOnUiThread { moveAlertZone(id, lat, lon) } },
            onZoneResized = { id, radiusMeters -> runOnUiThread { resizeAlertZone(id, radiusMeters) } },
            onZoneDeleted = { id -> runOnUiThread { deleteAlertZone(id) } },
            onStopAdded = { lat, lon -> runOnUiThread { addStopCandidate(lat, lon) } },
            onStopMoved = { id, lat, lon -> runOnUiThread { moveStopCandidate(id, lat, lon) } },
            onStopDeleted = { id -> runOnUiThread { deleteStopCandidate(id) } }
        )
        binding.webViewMap.loadRideMap(
            bridge = mapBridge,
            mode = "view",
            centerLat = center?.lat ?: 32.0853,
            centerLon = center?.lon ?: 34.7818
        )

        binding.btnPrivateCar.setOnClickListener { togglePrivateCarMode() }
        binding.btnSettings.setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
        binding.btnHelp.setOnClickListener { showHelp() }
        binding.btnAddStopHere.setOnClickListener { addStopCandidateAtCurrentLocation() }
        binding.btnRaiseHand.setOnClickListener { toggleHandRaise() }
        renderRaiseHandButton()

        // Don't make the user wait for the next ~15-minute RideCheckWorker
        // tick — if automation is currently allowed to run at all, start
        // monitoring the moment the app is opened. This only arms the
        // geofence/movement check; BroadcastService still decides whether to
        // actually start broadcasting.
        if (!prefs.isPrivateCarActive() && ActiveWindow.isNowActive(prefs)) {
            BroadcastService.startAutomatic(this)
        }

        renderPrivateCarToggle()
        schedulePrivateCarAutoOffRefresh()

        RideSessionState.currentLocation.observe(this) { location ->
            renderStatus(location)
            refreshEta()
            checkHandRaiseAutoClear(location)
            recordStopArrivalsIfNeeded(location)
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
        if (mapReady) {
            pushOriginDestZonesToMap()
            pushMyStopCandidatesToMap()
        }

        stopsListenerRideCode = rideCode
        sharedStopsObservation = stopsRepository.observeSharedStops(
            rideCode,
            nameFormat = { n -> getString(R.string.stop_name_format, n) }
        ) { stops ->
            latestSharedStops = stops
            pushSharedStopsToMap()
            checkHandRaiseAutoClear(RideSessionState.currentLocation.value)
        }
        handRaisesListener = stopsRepository.observeHandRaises(rideCode) { raises ->
            latestHandRaises = raises
            pushSharedStopsToMap()
            renderHandRaiseBanners()
        }
        todayRouteStartListener = stopsRepository.observeTodayRouteStart(rideCode) { millis ->
            todayRouteStartMillis = millis
            recordStopArrivalsIfNeeded(RideSessionState.currentLocation.value)
        }
    }

    override fun onStop() {
        super.onStop()
        val rideCode = locationListenerRideCode
        if (rideCode != null) {
            locationListener?.let { repository.removeListener(rideCode, it) }
        }
        locationListener = null
        locationListenerRideCode = null

        val stopsRideCode = stopsListenerRideCode
        if (stopsRideCode != null) {
            sharedStopsObservation?.let { stopsRepository.removeSharedStopsObservation(stopsRideCode, it) }
            handRaisesListener?.let { stopsRepository.removeHandRaisesListener(stopsRideCode, it) }
            todayRouteStartListener?.let { stopsRepository.removeTodayRouteStartListener(stopsRideCode, it) }
        }
        sharedStopsObservation = null
        handRaisesListener = null
        todayRouteStartListener = null
        stopsListenerRideCode = null
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
            if (ActiveWindow.isNowActive(prefs)) {
                BroadcastService.startAutomatic(this)
            }
        } else {
            // Stop the service outright rather than leaving it polling GPS in
            // the background just to have checkAutoStartCondition() bail on
            // isPrivateCarActive() every fix — "stay home" means genuinely off.
            if (BroadcastService.isRunning) {
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
            .setMessage(HtmlCompat.fromHtml(getString(R.string.help_body), HtmlCompat.FROM_HTML_MODE_LEGACY))
            .setPositiveButton(R.string.help_close_button, null)
            .show()
    }

    /** Permanent, lightly-styled reference circles for the configured origin/destination (docs/SPEC_EN.md 3.4) — distinct from the red alert-zone circles. */
    private fun pushOriginDestZonesToMap() {
        fun zoneJson(point: GeoPoint?, radiusMeters: Int): String {
            if (point == null) return "null"
            return JSONObject().apply {
                put("lat", point.lat)
                put("lon", point.lon)
                put("radiusMeters", radiusMeters)
            }.toString()
        }
        val originJson = zoneJson(prefs.origin, prefs.geofenceRadiusMeters).replace("'", "\\'")
        val destJson = zoneJson(prefs.destination, prefs.destinationRadiusMeters).replace("'", "\\'")
        binding.webViewMap.evaluateJavascript("setOriginDestZones('$originJson', '$destJson')", null)
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

    /** Tapped an empty spot on the map while the 📍 edit-zones control is active — places a new zone there, unnamed, at the default radius (drag its resize handle to adjust). */
    private fun addAlertZone(lat: Double, lon: Double) {
        prefs.alertZones = prefs.alertZones + AlertZone(
            id = UUID.randomUUID().toString(),
            name = "",
            lat = lat,
            lon = lon,
            radiusMeters = RideConfig.DEFAULT_ALERT_ZONE_RADIUS_METERS
        )
        pushAlertZonesToMap()
    }

    private fun moveAlertZone(id: String, lat: Double, lon: Double) {
        prefs.alertZones = prefs.alertZones.map { if (it.id == id) it.copy(lat = lat, lon = lon) else it }
        pushAlertZonesToMap()
    }

    private fun resizeAlertZone(id: String, radiusMeters: Double) {
        prefs.alertZones = prefs.alertZones.map { if (it.id == id) it.copy(radiusMeters = radiusMeters) else it }
        pushAlertZonesToMap()
    }

    private fun deleteAlertZone(id: String) {
        prefs.alertZones = prefs.alertZones.filterNot { it.id == id }
        prefs.alertedZoneIds = prefs.alertedZoneIds - id
        pushAlertZonesToMap()
    }

    // --- Pickup stops (docs/SPEC_EN.md 3.9) ---

    private fun pushMyStopCandidatesToMap() {
        if (!mapReady) return
        val array = JSONArray()
        prefs.myStopCandidates.forEach { candidate ->
            array.put(JSONObject().apply {
                put("id", candidate.id)
                put("lat", candidate.lat)
                put("lon", candidate.lon)
            })
        }
        binding.webViewMap.evaluateJavascript("setMyStopCandidates('${array.toString().replace("'", "\\'")}')", null)
    }

    private fun syncMyStopCandidates() {
        pushMyStopCandidatesToMap()
        val rideCode = prefs.rideCode
        val candidates = prefs.myStopCandidates
        lifecycleScope.launch { stopsRepository.syncOwnCandidates(rideCode, candidates) }
    }

    /** Tapped an empty spot on the map while the 🚏 edit-stops control is active. */
    private fun addStopCandidate(lat: Double, lon: Double) {
        prefs.myStopCandidates = prefs.myStopCandidates + StopCandidate(UUID.randomUUID().toString(), lat, lon)
        syncMyStopCandidates()
    }

    private fun moveStopCandidate(id: String, lat: Double, lon: Double) {
        prefs.myStopCandidates = prefs.myStopCandidates.map { if (it.id == id) it.copy(lat = lat, lon = lon) else it }
        syncMyStopCandidates()
    }

    private fun deleteStopCandidate(id: String) {
        prefs.myStopCandidates = prefs.myStopCandidates.filterNot { it.id == id }
        syncMyStopCandidates()
    }

    private fun addStopCandidateAtCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.permissions_title, Toast.LENGTH_SHORT).show()
            return
        }
        LocationServices.getFusedLocationProviderClient(this).lastLocation
            .addOnSuccessListener { location ->
                if (location == null) return@addOnSuccessListener
                addStopCandidate(location.latitude, location.longitude)
                Toast.makeText(this, R.string.stop_added_here_message, Toast.LENGTH_SHORT).show()
            }
    }

    /** Logs, once per stop per day, how long after today's route start
     * (StopsRepository.observeTodayRouteStart) the shared ride was
     * observed entering a stop's zone — the raw data behind ordering
     * stops by their actual observed timeline rather than straight-line
     * distance (docs/SPEC_EN.md 3.9). Runs on every shared-location
     * update, same as the other per-location checks. */
    private fun recordStopArrivalsIfNeeded(location: RideLocation?) {
        val startMillis = todayRouteStartMillis ?: return
        if (location == null) return
        val elapsedMillis = System.currentTimeMillis() - startMillis
        if (elapsedMillis < 0) return
        val todayKey = todayDateKeyFormat.format(Date())
        latestSharedStops.forEach { stop ->
            val dedupeKey = "$todayKey|${stop.id}"
            if (dedupeKey in recordedStopArrivalKeysToday) return@forEach
            val distanceMeters = GeoUtil.distanceMeters(location.lat, location.lon, stop.lat, stop.lon)
            if (distanceMeters <= RideConfig.STOP_MERGE_RADIUS_METERS) {
                recordedStopArrivalKeysToday.add(dedupeKey)
                val rideCode = prefs.rideCode
                lifecycleScope.launch { stopsRepository.recordStopArrival(rideCode, stop.lat, stop.lon, elapsedMillis) }
            }
        }
    }

    private fun pushSharedStopsToMap() {
        if (!mapReady) return
        val array = JSONArray()
        latestSharedStops.forEach { stop ->
            val raise = latestHandRaises.firstOrNull { it.stopId == stop.id }
            array.put(JSONObject().apply {
                put("id", stop.id)
                put("name", stop.name)
                put("lat", stop.lat)
                put("lon", stop.lon)
                put("handRaiseText", raise?.let { handRaiseBannerText(it) } ?: JSONObject.NULL)
            })
        }
        binding.webViewMap.evaluateJavascript("setSharedStops('${array.toString().replace("'", "\\'")}')", null)
    }

    // --- Raise hand (docs/SPEC_EN.md 3.9) ---

    private fun handRaiseBannerText(raise: ActiveHandRaise): String {
        val stopName = latestSharedStops.firstOrNull { it.id == raise.stopId }?.name ?: ""
        val who = raise.nickname?.takeIf { it.isNotBlank() } ?: getString(R.string.raise_hand_anonymous_rider)
        return getString(R.string.raise_hand_banner_format, who, stopName)
    }

    private fun renderHandRaiseBanners() {
        binding.handRaiseBannerContainer.removeAllViews()
        latestHandRaises.forEach { raise ->
            val banner = TextView(this).apply {
                text = handRaiseBannerText(raise)
                setBackgroundColor(getColor(R.color.status_warning))
                setTextColor(getColor(android.R.color.white))
                setPadding(24, 16, 24, 16)
                textSize = 14f
            }
            binding.handRaiseBannerContainer.addView(banner)
        }
        binding.handRaiseBannerContainer.visibility = if (latestHandRaises.isEmpty()) View.GONE else View.VISIBLE
    }

    /** This device's own candidates, resolved to whichever shared (merged) stop each currently belongs to, deduplicated. */
    private fun myMatchingSharedStops(): List<SharedStop> {
        val mine = prefs.myStopCandidates
        if (mine.isEmpty()) return emptyList()
        return latestSharedStops.filter { stop ->
            mine.any { candidate -> GeoUtil.distanceMeters(candidate.lat, candidate.lon, stop.lat, stop.lon) <= RideConfig.STOP_MERGE_RADIUS_METERS }
        }
    }

    private fun toggleHandRaise() {
        if (prefs.myHandRaiseStopId != null) {
            cancelHandRaise()
            return
        }
        val myStops = myMatchingSharedStops()
        when {
            myStops.isEmpty() -> Toast.makeText(this, R.string.raise_hand_no_stop_message, Toast.LENGTH_LONG).show()
            myStops.size == 1 -> raiseHandFor(myStops.first())
            else -> showStopChooserDialog(myStops)
        }
    }

    private fun showStopChooserDialog(stops: List<SharedStop>) {
        val names = stops.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.raise_hand_choose_stop_title)
            .setItems(names) { _, index -> raiseHandFor(stops[index]) }
            .show()
    }

    private fun raiseHandFor(stop: SharedStop) {
        prefs.myHandRaiseStopId = stop.id
        prefs.myHandRaiseEnteredStopZone = false
        val nickname = prefs.nickname.takeIf { prefs.showNickname && it.isNotBlank() }
        lifecycleScope.launch { stopsRepository.setMyHandRaise(prefs.rideCode, stop.id, nickname) }
        Toast.makeText(this, R.string.raise_hand_activated_message, Toast.LENGTH_SHORT).show()
        renderRaiseHandButton()
    }

    private fun cancelHandRaise() {
        prefs.myHandRaiseStopId = null
        prefs.myHandRaiseEnteredStopZone = false
        lifecycleScope.launch { stopsRepository.clearMyHandRaise(prefs.rideCode) }
        Toast.makeText(this, R.string.raise_hand_deactivated_message, Toast.LENGTH_SHORT).show()
        renderRaiseHandButton()
    }

    private fun renderRaiseHandButton() {
        val active = prefs.myHandRaiseStopId != null
        binding.btnRaiseHand.backgroundTintList = ColorStateList.valueOf(
            getColor(if (active) R.color.brand_accent else R.color.status_idle)
        )
    }

    /** Auto-clears this device's own raised hand once the shared ride
     * location is observed entering [Prefs.myHandRaiseStopId]'s zone and
     * then leaving it again — never just because it hasn't arrived yet
     * (docs/SPEC_EN.md 3.9). Runs on every shared-location AND every
     * shared-stops update, since either can be what's needed to resolve
     * the stop id to a position. */
    private fun checkHandRaiseAutoClear(location: RideLocation?) {
        val stopId = prefs.myHandRaiseStopId ?: return
        val stop = latestSharedStops.firstOrNull { it.id == stopId } ?: return
        if (location == null) return
        val distanceMeters = GeoUtil.distanceMeters(location.lat, location.lon, stop.lat, stop.lon)
        if (distanceMeters <= RideConfig.STOP_MERGE_RADIUS_METERS) {
            prefs.myHandRaiseEnteredStopZone = true
        } else if (prefs.myHandRaiseEnteredStopZone) {
            cancelHandRaise()
        }
    }
}
