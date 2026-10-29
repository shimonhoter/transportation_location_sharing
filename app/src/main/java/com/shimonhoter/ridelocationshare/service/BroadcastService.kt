package com.shimonhoter.ridelocationshare.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.database.ValueEventListener
import com.shimonhoter.ridelocationshare.MainActivity
import com.shimonhoter.ridelocationshare.alerts.AlertZoneManager
import com.shimonhoter.ridelocationshare.config.RideConfig
import com.shimonhoter.ridelocationshare.data.Prefs
import com.shimonhoter.ridelocationshare.remote.FirebaseLocationRepository
import com.shimonhoter.ridelocationshare.remote.StopsRepository
import com.shimonhoter.ridelocationshare.util.ActiveWindow
import com.shimonhoter.ridelocationshare.util.AppLog
import com.shimonhoter.ridelocationshare.util.GeoUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns this device's participation in the current ride: decides (in
 * automatic mode) when to start broadcasting based on the active window +
 * speed condition (crossing [RideConfig.MOVING_SPEED_THRESHOLD_KMH], or a
 * detected stop-and-go traffic jam — see [isStopAndGoTrafficJam]), and when
 * to stop it (safety timer, sustained low speed, or reaching the
 * destination zone — whichever comes first); writes this device's location
 * to Firebase while broadcasting, and separately listens for the shared
 * ride location for the UI and for on-device alert-zone checks
 * (docs/SPEC_EN.md sections 3.1-3.6). Also detects automatic pickup-stop
 * candidates from its own continuous full stops while broadcasting (see
 * [checkAutoStopDetection], docs/SPEC_EN.md 3.10).
 *
 * Every caller must go through [startAutomatic] or [stop] — a single call
 * path per action.
 */
class BroadcastService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var fusedClient: FusedLocationProviderClient
    private val locationRepository = FirebaseLocationRepository()
    private val stopsRepository = StopsRepository()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var locationCallback: LocationCallback? = null
    private var sharedLocationListener: ValueEventListener? = null
    private var sharedLocationListenerRideCode: String? = null
    private var isBroadcastingLocally = false
    private var lastMovingAtMillis: Long = 0L
    private val recentSpeedSamplesKmh = ArrayDeque<Pair<Long, Double>>()
    private var fullStopStartedAtMillis: Long = 0L
    private var fullStopRecorded: Boolean = false

    override fun onCreate() {
        super.onCreate()
        AppLog.i(TAG, "Service created")
        isRunning = true
        prefs = Prefs(this)
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        ensureServiceChannel()
        AlertZoneManager.ensureChannel(this)
        attachSharedLocationListener()
    }

    /** A single persistent listener for the shared ride location, independent
     * of this device's own GPS fix cadence — covers alert-zone checks even
     * when this device never itself qualifies to broadcast. */
    private fun attachSharedLocationListener() {
        val rideCode = prefs.rideCode
        sharedLocationListenerRideCode = rideCode
        sharedLocationListener = locationRepository.observeLocation(rideCode, { prefs.corroborationRadiusMeters }) { shared ->
            val wasActive = RideSessionState.currentLocation.value != null
            RideSessionState.currentLocation.value = shared

            if (shared != null) {
                AlertZoneManager.check(applicationContext, prefs, shared.lat, shared.lon)
            } else if (wasActive) {
                AppLog.i(TAG, "Ride reported inactive, resetting alert flags")
                AlertZoneManager.resetForNewRide(prefs)
            }
        }
    }

    private fun detachSharedLocationListener() {
        val rideCode = sharedLocationListenerRideCode ?: return
        sharedLocationListener?.let { locationRepository.removeListener(rideCode, it) }
        sharedLocationListener = null
        sharedLocationListenerRideCode = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        AppLog.i(TAG, "onStartCommand action=${intent?.action}")

        when (intent?.action) {
            ACTION_STOP -> {
                stopBroadcasting()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_AUTO_START -> Unit // fall through to ensure monitoring below
            ACTION_REFRESH_SETTINGS -> applyRefreshedSettings()
        }

        ensureMonitoring()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        detachSharedLocationListener()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Swiping the app away from Recents removes its task — on stock Android
     * and most OEMs this then kills this foreground service along with it
     * (a plain `startForegroundService()` from an Activity doesn't survive
     * that the way a genuinely independent background app would), which is
     * exactly the "stops working once the app is closed" symptom. If
     * automation should still be running, schedule an immediate restart via
     * AlarmManager — more reliable than restarting synchronously here,
     * since this process may already be mid-teardown by the time this
     * runs. This can only reduce, not fully eliminate, OEM-specific
     * aggressive background killing (Xiaomi/Huawei/Samsung etc.), which
     * still needs the user to whitelist the app in battery settings. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val shouldKeepRunning = isBroadcastingLocally ||
            (!prefs.isPrivateCarActive() && ActiveWindow.isNowActive(prefs))
        if (!shouldKeepRunning) {
            AppLog.i(TAG, "App task removed, automation not due to run — not scheduling a restart")
            return
        }
        AppLog.i(TAG, "App task removed, scheduling restart in ${RESTART_DELAY_MILLIS}ms")

        val restartIntent = Intent(applicationContext, BroadcastService::class.java)
            .setAction(ACTION_AUTO_START)
            .setPackage(packageName)
        val pendingIntent = android.app.PendingIntent.getForegroundService(
            applicationContext,
            RESTART_REQUEST_CODE,
            restartIntent,
            android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(android.app.AlarmManager::class.java)
        alarmManager?.set(
            android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + RESTART_DELAY_MILLIS,
            pendingIntent
        )
    }

    /** Tears down the current location callback and rebuilds it, so a changed
     * [Prefs.locationUpdateIntervalSeconds] takes effect immediately rather than
     * only on the next broadcast session start; also re-subscribes the shared
     * ride listener if [Prefs.rideCode] changed, since a Firebase listener
     * can't be re-pointed at a different reference in place. */
    private fun applyRefreshedSettings() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        locationCallback = null
        ensureMonitoring()

        if (prefs.rideCode != sharedLocationListenerRideCode) {
            detachSharedLocationListener()
            attachSharedLocationListener()
        }
    }

    private fun ensureMonitoring() {
        if (locationCallback != null) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            AppLog.e(TAG, "Missing ACCESS_FINE_LOCATION, cannot monitor ride")
            stopSelf()
            return
        }

        val intervalMillis = prefs.locationUpdateIntervalSeconds * 1_000L
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
            .setMinUpdateIntervalMillis(intervalMillis)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { handleLocationUpdate(it) }
            }
        }
        locationCallback = callback
        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())

        // A cold GPS fix can take far longer than locationUpdateIntervalSeconds
        // to arrive (tens of seconds), which is exactly the delay noticed right
        // after the service (re)starts from nothing — boot, a WorkManager tick,
        // or the app being reopened. The fused provider's last-known (cached)
        // location is usually available instantly; using it here — if fresh
        // enough to trust — lets the auto-start condition be evaluated right
        // away instead of waiting for the first live update.
        fusedClient.lastLocation.addOnSuccessListener { cached ->
            if (cached != null && System.currentTimeMillis() - cached.time <= LAST_LOCATION_MAX_AGE_MILLIS) {
                handleLocationUpdate(cached)
            }
        }
    }

    private fun handleLocationUpdate(location: Location) {
        AppLog.d(TAG, "fix lat=${location.latitude} lon=${location.longitude} broadcasting=$isBroadcastingLocally")

        if (!isBroadcastingLocally) {
            checkAutoStartCondition(location)
        }

        if (isBroadcastingLocally) {
            val nickname = prefs.nickname.takeIf { prefs.showNickname && it.isNotBlank() }
            val speedKmh = if (location.hasSpeed()) location.speed * 3.6 else 0.0
            if (speedKmh > RideConfig.MOVING_SPEED_THRESHOLD_KMH) {
                lastMovingAtMillis = System.currentTimeMillis()
            }
            checkAutoStopDetection(location, speedKmh)
            val rideCode = prefs.rideCode
            serviceScope.launch {
                locationRepository.postLocation(rideCode, location.latitude, location.longitude, speedKmh, nickname, lastMovingAtMillis)
            }

            // Stop condition: time, sustained low speed, or reaching the
            // destination zone — whichever comes first. The safety timer is
            // checked first since it's the cheapest comparison, but any of
            // the three can end the broadcast.
            if (System.currentTimeMillis() >= rideEndDeadlineMillis()) {
                AppLog.i(TAG, "Safety timer expired, ending broadcast for this device")
                stopBroadcasting()
                stopSelf()
                return
            }

            val stoppedMovingMillis = System.currentTimeMillis() - lastMovingAtMillis
            if (lastMovingAtMillis > 0 && stoppedMovingMillis >= RideConfig.RECENTLY_MOVING_GRACE_SECONDS * 1000) {
                AppLog.i(TAG, "No movement above threshold for ${RideConfig.RECENTLY_MOVING_GRACE_SECONDS.toInt()}s, ending broadcast for this device")
                stopBroadcasting()
                stopSelf()
                return
            }

            val destination = prefs.destination
            if (destination != null) {
                val distanceToDestination = GeoUtil.distanceMeters(location.latitude, location.longitude, destination.lat, destination.lon)
                if (distanceToDestination <= prefs.destinationRadiusMeters) {
                    AppLog.i(TAG, "Entered destination zone, ending broadcast for this device")
                    stopBroadcasting()
                    stopSelf()
                    return
                }
            }
        }

        if (!isBroadcastingLocally && !ActiveWindow.isNowActive(prefs)) {
            AppLog.i(TAG, "Outside the active window with no ride detected, stopping monitor")
            stopSelf()
        }
    }

    /** Start condition: moving faster than [RideConfig.MOVING_SPEED_THRESHOLD_KMH]
     * (filtered against single-fix GPS speed glitches, see
     * [isReliablyAboveMovingThreshold]) OR a stop-and-go traffic jam pattern
     * (see [isStopAndGoTrafficJam]) — no longer gated on being near a
     * configured origin point, so broadcasting can start anywhere, purely
     * from the active window (this method is only reached while the service
     * is running, which already requires the window to be active — see
     * ActiveWindow.isNowActive callers) and speed. */
    private fun checkAutoStartCondition(location: Location) {
        if (prefs.isPrivateCarActive()) return
        val speedKmh = if (location.hasSpeed()) location.speed * 3.6 else 0.0
        recordRecentSpeedSample(speedKmh)

        if (isReliablyAboveMovingThreshold(location, speedKmh)) {
            AppLog.i(TAG, "Movement threshold reached, starting broadcast")
            beginBroadcasting(location)
        } else if (isStopAndGoTrafficJam()) {
            AppLog.i(TAG, "Stop-and-go traffic pattern detected, starting broadcast")
            beginBroadcasting(location)
        }
    }

    /** Crossing [RideConfig.MOVING_SPEED_THRESHOLD_KMH] always starts the
     * broadcast — but a single isolated GPS fix can misreport speed well
     * above the real value (multipath reflections, a bad Doppler read),
     * which is indistinguishable from genuine movement on that one fix
     * alone and was observed starting a broadcast while the device was
     * actually stationary. [Location.getSpeedAccuracyMetersPerSecond] (API
     * 26+, always present at this app's minSdk) gives that same fix's own
     * reported uncertainty for its speed estimate — requiring the reading to
     * clear the threshold even after subtracting that margin rejects a
     * low-confidence glitch without adding any delay for genuinely fast
     * movement, whose reported accuracy is normally tight. Devices/fixes
     * that don't report speed accuracy at all fall back to the plain
     * threshold check, unchanged from before. */
    private fun isReliablyAboveMovingThreshold(location: Location, speedKmh: Double): Boolean {
        if (speedKmh <= RideConfig.MOVING_SPEED_THRESHOLD_KMH) return false
        if (!location.hasSpeedAccuracy()) return true
        val speedAccuracyKmh = location.speedAccuracyMetersPerSecond * 3.6
        return speedKmh - speedAccuracyKmh > RideConfig.MOVING_SPEED_THRESHOLD_KMH
    }

    private fun recordRecentSpeedSample(speedKmh: Double) {
        val now = System.currentTimeMillis()
        recentSpeedSamplesKmh.addLast(now to speedKmh)
        val windowStart = now - RideConfig.TRAFFIC_JAM_WINDOW_SECONDS.toLong() * 1000
        while (recentSpeedSamplesKmh.isNotEmpty() && recentSpeedSamplesKmh.first().first < windowStart) {
            recentSpeedSamplesKmh.removeFirst()
        }
    }

    /** Stop-and-go traffic: counts full stop-then-resume transitions within
     * the recent window — not a high peak speed, which a sample at/above
     * [RideConfig.MOVING_SPEED_THRESHOLD_KMH] would already satisfy via the
     * main check on its own, making a peak-based test nearly unreachable
     * for the sub-threshold crawl this is meant to catch (see
     * RideConfig.TRAFFIC_JAM_WINDOW_SECONDS doc for the full rationale).
     * Walking continuously essentially never oscillates between a
     * near-complete stop and resumed motion several times in under two
     * minutes the way traffic-light-paced crawling does. */
    private fun isStopAndGoTrafficJam(): Boolean {
        var cycles = 0
        var stoppedSinceLastCycle = false
        for ((_, speedKmh) in recentSpeedSamplesKmh) {
            if (speedKmh <= RideConfig.TRAFFIC_JAM_STOP_SPEED_KMH) {
                stoppedSinceLastCycle = true
            } else if (stoppedSinceLastCycle && speedKmh >= RideConfig.TRAFFIC_JAM_RESUME_SPEED_KMH) {
                cycles++
                stoppedSinceLastCycle = false
            }
        }
        return cycles >= RideConfig.TRAFFIC_JAM_MIN_STOP_GO_CYCLES
    }

    /**
     * Automatic pickup-stop detection (docs/SPEC_EN.md 3.10): while
     * broadcasting, a continuous full stop (speed at/below
     * [RideConfig.TRAFFIC_JAM_STOP_SPEED_KMH]) lasting at least
     * [Prefs.autoStopDetectionSeconds] is recorded exactly once per stop —
     * the instant the threshold is reached, rather than waiting for motion
     * to resume — so it fires even if the vehicle stays stopped (dropping
     * someone off) well past that point. Resetting [fullStopStartedAtMillis]
     * on every resumed-motion fix means the next full stop, however soon
     * after, starts its own fresh timer and can be recorded again.
     */
    private fun checkAutoStopDetection(location: Location, speedKmh: Double) {
        if (speedKmh > RideConfig.TRAFFIC_JAM_STOP_SPEED_KMH) {
            fullStopStartedAtMillis = 0L
            fullStopRecorded = false
            return
        }
        if (fullStopStartedAtMillis == 0L) {
            fullStopStartedAtMillis = System.currentTimeMillis()
            return
        }
        if (fullStopRecorded) return
        val stoppedSeconds = (System.currentTimeMillis() - fullStopStartedAtMillis) / 1000.0
        if (stoppedSeconds >= prefs.autoStopDetectionSeconds) {
            fullStopRecorded = true
            val rideCode = prefs.rideCode
            serviceScope.launch { stopsRepository.recordAutoDetectedStop(rideCode, location.latitude, location.longitude) }
        }
    }

    /** Computed live from current prefs on every check, rather than cached at
     * broadcast start, so a changed trip duration / safety margin applies
     * immediately mid-ride instead of only on the next ride. */
    private fun rideEndDeadlineMillis(): Long =
        prefs.rideStartTimeMillis + (prefs.tripDurationMinutes + prefs.safetyMarginMinutes) * 60_000L

    /** [location] is only used to try claiming today's route-origin
     * (docs/SPEC_EN.md 3.9) — a best-effort, fire-and-forget Firebase
     * write that only actually takes effect the first time any device
     * does this on a given day. */
    private fun beginBroadcasting(location: Location) {
        isBroadcastingLocally = true
        lastMovingAtMillis = 0L
        recentSpeedSamplesKmh.clear()
        fullStopStartedAtMillis = 0L
        fullStopRecorded = false
        prefs.isBroadcasting = true
        prefs.rideStartTimeMillis = System.currentTimeMillis()
        serviceScope.launch { stopsRepository.claimRouteOriginOfDay(prefs.rideCode, location.latitude, location.longitude) }
        RideSessionState.isThisDeviceBroadcasting.postValue(true)
        updateNotification()
        ensureMonitoring()
    }

    private fun stopBroadcasting() {
        isBroadcastingLocally = false
        prefs.isBroadcasting = false
        RideSessionState.isThisDeviceBroadcasting.postValue(false)
        // Best-effort: drop this device out of the aggregate immediately rather
        // than waiting up to STALE_AFTER_SECONDS for it to age out on its own.
        val rideCode = prefs.rideCode
        serviceScope.launch { locationRepository.clearOwnLocation(rideCode) }
    }

    private fun ensureServiceChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID_SERVICE) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID_SERVICE, "שידור מיקום הסעה", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        val openApp = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (isBroadcastingLocally) "משדר את מיקום ההסעה" else "עוקב אחרי ההסעה"
        return NotificationCompat.Builder(this, CHANNEL_ID_SERVICE)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("שיתוף מיקום הסעה")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        private const val TAG = "BroadcastService"
        private const val CHANNEL_ID_SERVICE = "ride_broadcast_service"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_AUTO_START = "com.shimonhoter.ridelocationshare.action.AUTO_START"
        private const val ACTION_STOP = "com.shimonhoter.ridelocationshare.action.STOP"
        private const val ACTION_REFRESH_SETTINGS = "com.shimonhoter.ridelocationshare.action.REFRESH_SETTINGS"

        /** A cached last-known location older than this is too stale to trust for an immediate auto-start check (see ensureMonitoring). */
        private const val LAST_LOCATION_MAX_AGE_MILLIS = 30_000L

        private const val RESTART_REQUEST_CODE = 1001
        private const val RESTART_DELAY_MILLIS = 1_000L

        /** Whether an instance of this service is currently alive. Lets callers
         * (e.g. the Settings screen) decide whether it's meaningful to push a
         * live settings refresh, without spuriously starting the service. */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun startAutomatic(context: Context) = dispatch(context, ACTION_AUTO_START)
        fun stop(context: Context) = dispatch(context, ACTION_STOP)

        /** Applies changed Settings (location update interval, trip duration,
         * safety margin) to an already-running service immediately, instead of
         * waiting for the next broadcast session or service restart. No-op if
         * the service isn't currently running. */
        fun refreshSettings(context: Context) {
            if (!isRunning) return
            dispatch(context, ACTION_REFRESH_SETTINGS)
        }

        /**
         * Called right after Settings are saved (SettingsActivity.save()):
         * re-evaluates the active day/time window + speed criterion
         * immediately, instead of waiting for RideCheckWorker's next
         * ~15-minute tick, and starts or stops monitoring accordingly —
         * e.g. widening the window to cover "now" starts monitoring right
         * away, and shrinking it past "now" stops an idle monitor that was
         * only running because of the old window. Never interrupts a
         * broadcast already in progress: the window only ever gates idle
         * *monitoring* this way too, exactly like
         * [handleLocationUpdate]'s own `stopSelf()` call already does when
         * the window lapses mid-monitoring; a mid-ride broadcast still
         * ends only via its own stop conditions there.
         */
        fun applySettingsChange(context: Context) {
            val prefs = Prefs(context)
            val shouldBeActive = !prefs.isPrivateCarActive() && ActiveWindow.isNowActive(prefs)
            if (isRunning) {
                refreshSettings(context)
                if (!shouldBeActive && RideSessionState.isThisDeviceBroadcasting.value != true) {
                    stop(context)
                }
            } else if (shouldBeActive) {
                startAutomatic(context)
            }
        }

        private fun dispatch(context: Context, action: String) {
            // A foreground service declared with foregroundServiceType="location" must
            // already hold the permission at the moment startForeground() runs, or the
            // system throws — so guard here rather than let onStartCommand crash.
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                AppLog.e(TAG, "Cannot dispatch $action: ACCESS_FINE_LOCATION not granted")
                return
            }
            val intent = Intent(context, BroadcastService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
