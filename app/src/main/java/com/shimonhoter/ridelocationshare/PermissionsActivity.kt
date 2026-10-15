package com.shimonhoter.ridelocationshare

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.shimonhoter.ridelocationshare.data.Prefs
import com.shimonhoter.ridelocationshare.databinding.ActivityPermissionsBinding

/**
 * Full-screen onboarding shown on first run, walking the user through the
 * required permission sequence in order: foreground location -> background
 * location -> notifications (docs/SPEC_EN.md 3.7), plus — on detected
 * Xiaomi/MIUI devices only — a self-reported checklist for OS-level
 * autostart/battery/notification settings Android itself has no API to
 * request or query (see isMiuiDevice). Skipped entirely once everything
 * the current API level (and device) requires is already granted/confirmed.
 */
class PermissionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionsBinding
    private lateinit var prefs: Prefs

    private val requestForegroundLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onPermissionResult(granted)
    }
    private val requestBackgroundLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onPermissionResult(granted)
    }
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onPermissionResult(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        if (allRequiredPermissionsGranted()) {
            goToMain()
            return
        }

        binding.btnGrantForegroundLocation.setOnClickListener {
            requestForegroundLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        binding.btnGrantBackgroundLocation.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
        binding.btnGrantNotifications.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        binding.btnOpenSettings.setOnClickListener { openAppSettings() }
        binding.btnContinue.setOnClickListener { goToMain() }

        binding.cardMiuiSettings.visibility = if (isMiuiDevice()) android.view.View.VISIBLE else android.view.View.GONE
        binding.cbMiuiSettingsConfirmed.isChecked = prefs.miuiBackgroundSettingsConfirmed
        binding.cbMiuiSettingsConfirmed.setOnCheckedChangeListener { _, isChecked ->
            prefs.miuiBackgroundSettingsConfirmed = isChecked
        }
        binding.btnOpenMiuiSettings.setOnClickListener { openAppSettings() }

        refreshCardState()
    }

    /** Covers Redmi/POCO too — all Xiaomi sub-brands report this same manufacturer string. */
    private fun isMiuiDevice(): Boolean = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)

    override fun onResume() {
        super.onResume()
        refreshCardState()
    }

    private fun onPermissionResult(granted: Boolean) {
        refreshCardState()
        if (!granted) {
            binding.tvDeniedHint.visibility = android.view.View.VISIBLE
            binding.btnOpenSettings.visibility = android.view.View.VISIBLE
        }
    }

    private fun refreshCardState() {
        binding.btnGrantForegroundLocation.isEnabled = !isGranted(Manifest.permission.ACCESS_FINE_LOCATION)

        val backgroundGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        binding.btnGrantBackgroundLocation.isEnabled = !backgroundGranted

        val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            isGranted(Manifest.permission.POST_NOTIFICATIONS)
        binding.btnGrantNotifications.isEnabled = !notificationsGranted
    }

    private fun allRequiredPermissionsGranted(): Boolean {
        val foreground = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            isGranted(Manifest.permission.POST_NOTIFICATIONS)
        val miuiSettings = !isMiuiDevice() || prefs.miuiBackgroundSettingsConfirmed
        return foreground && background && notifications && miuiSettings
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
