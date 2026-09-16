package com.shimonhoter.ridelocationshare

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.shimonhoter.ridelocationshare.alerts.AlertZoneManager
import com.shimonhoter.ridelocationshare.databinding.ActivityAlertZoneAlarmBinding

/**
 * Full-screen popup launched via a notification's full-screen intent when the
 * ride enters a personal alert zone (docs/SPEC_EN.md 3.5) — pops to the
 * foreground even over the lock screen, so the alert can't be missed the way
 * a background notification alone could be. Stays up, with the alarm sound
 * ([AlertZoneManager] looping it independently) until the user dismisses it.
 */
class AlertZoneAlarmActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        val binding = ActivityAlertZoneAlarmBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val zoneName = intent.getStringExtra(EXTRA_ZONE_NAME)
        binding.tvAlarmZoneName.text = if (zoneName.isNullOrBlank()) {
            getString(R.string.alert_alarm_generic_zone)
        } else {
            getString(R.string.alert_alarm_zone_format, zoneName)
        }

        val etaText = intent.getStringExtra(EXTRA_ETA_TEXT)
        if (!etaText.isNullOrBlank()) {
            binding.tvAlarmEta.text = etaText
            binding.tvAlarmEta.visibility = View.VISIBLE
        }

        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        binding.btnDismissAlarm.setOnClickListener {
            AlertZoneManager.stopSound()
            if (notificationId != -1) AlertZoneManager.cancelNotification(this, notificationId)
            finish()
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
    }

    companion object {
        const val EXTRA_ZONE_NAME = "zone_name"
        const val EXTRA_ETA_TEXT = "eta_text"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
