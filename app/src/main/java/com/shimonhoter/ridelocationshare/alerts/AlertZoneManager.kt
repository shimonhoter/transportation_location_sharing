package com.shimonhoter.ridelocationshare.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.shimonhoter.ridelocationshare.MainActivity
import android.app.PendingIntent
import android.content.Intent
import com.shimonhoter.ridelocationshare.data.AlertZone
import com.shimonhoter.ridelocationshare.data.Prefs
import com.shimonhoter.ridelocationshare.util.AppLog
import com.shimonhoter.ridelocationshare.util.GeoUtil

/**
 * Personal alert zones are checked exclusively on-device (docs/SPEC_EN.md
 * 3.5): the server never sees zone definitions, only the ride's current
 * shared location, which is compared here against locally-stored zones.
 */
object AlertZoneManager {
    private const val TAG = "AlertZoneManager"
    private const val CHANNEL_ID = "ride_alert_zones"

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
                notify(context, zone)
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

    private fun notify(context: Context, zone: AlertZone) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            AppLog.i(TAG, "Skipping zone notification, POST_NOTIFICATIONS not granted")
            return
        }
        ensureChannel(context)

        val contentIntent = PendingIntent.getActivity(
            context,
            zone.id.hashCode(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("ההסעה מתקרבת")
            .setContentText(if (zone.name.isNotBlank()) "ההסעה נכנסה לאזור \"${zone.name}\"" else "ההסעה נכנסה לאזור שסימנת")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.notify(zone.id.hashCode(), notification)
        AppLog.i(TAG, "Fired alert for zone ${zone.id} (${zone.name})")
    }
}
