package com.shimonhoter.ridelocationshare.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.shimonhoter.ridelocationshare.AlertZoneAlarmActivity
import com.shimonhoter.ridelocationshare.MainActivity
import com.shimonhoter.ridelocationshare.R
import android.app.PendingIntent
import android.content.Intent
import com.shimonhoter.ridelocationshare.data.AlertZone
import com.shimonhoter.ridelocationshare.data.Prefs
import com.shimonhoter.ridelocationshare.history.RideEtaEstimator
import com.shimonhoter.ridelocationshare.history.RideHistoryStore
import com.shimonhoter.ridelocationshare.util.AppLog
import com.shimonhoter.ridelocationshare.util.GeoUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Personal alert zones are checked exclusively on-device (docs/SPEC_EN.md
 * 3.5): the server never sees zone definitions, only the ride's current
 * shared location, which is compared here against locally-stored zones.
 *
 * Firing a zone alert does three things, not just posting a notification:
 * pops a full-screen popup to the foreground ([AlertZoneAlarmActivity], via
 * a full-screen intent, so it isn't missed the way a background
 * notification could be), loops an alarm sound until the user dismisses it
 * (capped by Prefs.alertSoundDurationSeconds as a safety net), and includes
 * the local-history-based estimated arrival time at the user's stop.
 */
object AlertZoneManager {
    private const val TAG = "AlertZoneManager"
    private const val CHANNEL_ID = "ride_alert_zones"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaPlayer: MediaPlayer? = null
    private var autoStopJob: Job? = null

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "התראות אזור הסעה",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "התראה כשההסעה נכנסת לאזור שסימנת"
            enableVibration(true)
            // The channel's own sound is skipped (setSound(null, ...) below) —
            // the looping alarm sound is played separately so it can keep
            // ringing until dismissed instead of the single short blip a
            // channel sound would give.
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    /** Call on every fresh ride-location sample; fires at most one notification per zone per ride. */
    fun check(context: Context, prefs: Prefs, rideLat: Double, rideLon: Double) {
        val zones = prefs.alertZones
        if (zones.isEmpty()) return
        val alerted = prefs.alertedZoneIds.toMutableSet()
        var changed = false

        for (zone in zones) {
            if (zone.id in alerted) continue
            val distance = GeoUtil.distanceMeters(rideLat, rideLon, zone.lat, zone.lon)
            if (distance <= zone.radiusMeters) {
                notify(context.applicationContext, prefs, zone)
                alerted += zone.id
                changed = true
            }
        }
        if (changed) prefs.alertedZoneIds = alerted
    }

    /** Reset the "already alerted" flags; call when a ride ends (docs/SPEC_EN.md 3.5). */
    fun resetForNewRide(prefs: Prefs) {
        prefs.clearAlertedZones()
    }

    /** Stops the looping alarm sound; called when the user dismisses the full-screen popup, or when a new alert supersedes it. */
    fun stopSound() {
        autoStopJob?.cancel()
        autoStopJob = null
        mediaPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (_: IllegalStateException) {
                // Already stopped/released from a race with the auto-stop timer.
            }
            it.release()
        }
        mediaPlayer = null
    }

    fun cancelNotification(context: Context, notificationId: Int) {
        context.getSystemService(NotificationManager::class.java)?.cancel(notificationId)
    }

    private fun notify(context: Context, prefs: Prefs, zone: AlertZone) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            AppLog.i(TAG, "Skipping zone notification, POST_NOTIFICATIONS not granted")
            return
        }
        ensureChannel(context)
        playSound(context, prefs.alertSoundDurationSeconds)

        // The ETA needs a history-store query, so the notification (and the
        // full-screen popup it launches) is built once that's ready rather
        // than blocking the caller (a Firebase listener callback) on it.
        scope.launch {
            val etaText = estimateEtaText(context, prefs)
            postNotification(context, zone, etaText)
        }
    }

    private suspend fun estimateEtaText(context: Context, prefs: Prefs): String? {
        val origin = prefs.origin ?: return null
        if (!prefs.historyEnabled) return null
        val history = RideHistoryStore(context).allSamples()
        val eta = RideEtaEstimator.estimate(
            history = history,
            stop = origin,
            arrivalRadiusMeters = prefs.geofenceRadiusMeters,
            currentLat = null,
            currentLon = null,
            currentAvgSpeedKmh = null
        ) ?: return null
        return context.getString(
            R.string.eta_format,
            "%02d:%02d".format(eta.get(Calendar.HOUR_OF_DAY), eta.get(Calendar.MINUTE))
        )
    }

    private fun postNotification(context: Context, zone: AlertZone, etaText: String?) {
        val notificationId = zone.id.hashCode()
        val zoneMessage = if (zone.name.isNotBlank()) {
            context.getString(R.string.alert_alarm_zone_format, zone.name)
        } else {
            context.getString(R.string.alert_alarm_generic_zone)
        }
        val contentText = if (etaText != null) "$zoneMessage · $etaText" else zoneMessage

        val fullScreenIntent = Intent(context, AlertZoneAlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AlertZoneAlarmActivity.EXTRA_ZONE_NAME, zone.name)
            putExtra(AlertZoneAlarmActivity.EXTRA_ETA_TEXT, etaText)
            putExtra(AlertZoneAlarmActivity.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, notificationId, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(context.getString(R.string.alert_alarm_title))
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .build()

        context.getSystemService(NotificationManager::class.java)?.notify(notificationId, notification)
        AppLog.i(TAG, "Fired alert for zone ${zone.id} (${zone.name})")
    }

    /** Loops an alarm sound until [stopSound] is called or [durationSeconds] elapses, whichever comes first. */
    private fun playSound(context: Context, durationSeconds: Int) {
        stopSound()
        val soundUri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, soundUri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to start alert sound", e)
        }
        autoStopJob = scope.launch {
            delay(durationSeconds * 1_000L)
            stopSound()
        }
    }
}
