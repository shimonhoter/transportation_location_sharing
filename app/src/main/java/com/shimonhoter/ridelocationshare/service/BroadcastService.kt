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
 * automatic mode) when to start broadcasting based on the movement + geofence
 * condition, writes this device's location to Firebase while broadcasting,
 * and separately listens for the shared ride location for the UI and for
 * on-device alert-zone checks (docs/SPEC_EN.md sections 3.1-3.6).
 *
 * Every caller must go through [startAutomatic], [startManualBroadcast] or
 * [stop] — a single call path per action. An earlier version fired two
 * separate/racing service-start calls for the manual button, which caused
 * intermittent failures (see docs' bug log, entry 9).
 */
class BroadcastService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var fusedClient: FusedLocationProviderClient
    private val locationRepository = FirebaseLocationRepository()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var locationCallback: LocationCallback? = null
    private var sharedLocationListener: ValueEventListener? = null
    private var isBroadcastingLocally = false
    private var geofenceAnchor: Location? = null
    private var rideEndDeadlineMillis: Long = 0L

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        ensureServiceChannel()
        AlertZoneManager.ensureChannel(this)

        // A single persistent listener for the shared ride location, independent
        // of this device's own GPS fix cadence — covers alert-zone checks even
        // when this device never itself qualifies to broadcast.
        sharedLocationListener = locationRepository.observeLocation { shared ->
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        when (intent?.action) {
            ACTION_STOP -> {
                stopBroadcasting()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_MANUAL_BROADCAST -> beginBroadcasting()
            ACTION_AUTO_START -> Unit // fall through to ensure monitoring below
        }

        ensureMonitoring()
        return START_STICKY
    }

    override fun onDestroy() {
        locationCallback?.let { fusedClient.removeLocationUpdates(it) }
        sharedLocationListener?.let { locationRepository.removeListener(it) }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
    }

    private fun handleLocationUpdate(location: Location) {
        AppLog.d(TAG, "fix lat=${location.latitude} lon=${location.longitude} broadcasting=$isBroadcastingLocally")

        if (!isBroadcastingLocally) {
            checkAutoStartCondition(location)
        }

        if (isBroadcastingLocally) {
            val nickname = prefs.nickname.takeIf { prefs.showNickname && it.isNotBlank() }
            val speedKmh = if (location.hasSpeed()) location.speed * 3.6 else 0.0
            serviceScope.launch { locationRepository.postLocation(location.latitude, location.longitude, speedKmh, nickname) }

            if (System.currentTimeMillis() >= rideEndDeadlineMillis) {
                AppLog.i(TAG, "Safety timer expired, ending broadcast for this device")
                stopBroadcasting()
                stopSelf()
                return
            }
        }

        if (!isBroadcastingLocally && !ActiveWindow.isNowActive(prefs)) {
            AppLog.i(TAG, "Outside the active window with no ride detected, stopping monitor")
            stopSelf()
        }
    }

    /** Start condition: inside the origin geofence AND has moved beyond the minimum threshold since. */
    private fun checkAutoStartCondition(location: Location) {
        val origin = prefs.origin ?: return
        val distanceFromOrigin = GeoUtil.distanceMeters(location.latitude, location.longitude, origin.lat, origin.lon)

        if (distanceFromOrigin > prefs.geofenceRadiusMeters) {
            geofenceAnchor = null
            return
        }

        val anchor = geofenceAnchor
        if (anchor == null) {
            geofenceAnchor = location
            return
        }

        val movedSinceAnchor = GeoUtil.distanceMeters(anchor.latitude, anchor.longitude, location.latitude, location.longitude)
        if (movedSinceAnchor >= RideConfig.MIN_MOVEMENT_METERS) {
            AppLog.i(TAG, "Movement threshold reached inside origin geofence, starting broadcast")
            beginBroadcasting()
        }
    }

    private fun beginBroadcasting() {
        isBroadcastingLocally = true
        prefs.isBroadcasting = true
        prefs.rideStartTimeMillis = System.currentTimeMillis()
        rideEndDeadlineMillis = System.currentTimeMillis() +
            (prefs.tripDurationMinutes + prefs.safetyMarginMinutes) * 60_000L
        RideSessionState.isThisDeviceBroadcasting.postValue(true)
        updateNotification()
        ensureMonitoring()
    }

    private fun stopBroadcasting() {
        isBroadcastingLocally = false
        geofenceAnchor = null
        prefs.isBroadcasting = false
        RideSessionState.isThisDeviceBroadcasting.postValue(false)
        // Best-effort: drop this device out of the aggregate immediately rather
        // than waiting up to STALE_AFTER_SECONDS for it to age out on its own.
        serviceScope.launch { locationRepository.clearOwnLocation() }
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
        private const val ACTION_MANUAL_BROADCAST = "com.shimonhoter.ridelocationshare.action.MANUAL_BROADCAST"
        private const val ACTION_STOP = "com.shimonhoter.ridelocationshare.action.STOP"

        fun startAutomatic(context: Context) = dispatch(context, ACTION_AUTO_START)
        fun startManualBroadcast(context: Context) = dispatch(context, ACTION_MANUAL_BROADCAST)
        fun stop(context: Context) = dispatch(context, ACTION_STOP)

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
