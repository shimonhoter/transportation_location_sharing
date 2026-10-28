package com.shimonhoter.ridelocationshare

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.shimonhoter.ridelocationshare.data.Prefs
import com.shimonhoter.ridelocationshare.databinding.ActivityPermissionsBinding

/**
 * Full-screen onboarding shown on first run, walking the user through the
 * required permission sequence in order: foreground location -> background
 * location -> notifications -> battery optimization exemption
 * (docs/SPEC_EN.md 3.7), plus — on detected Xiaomi/MIUI devices only — a
 * self-reported checklist for OS-level autostart/battery/notification
 * settings Android itself has no API to query (see isMiuiDevice). Skipped
 * entirely once everything the current API level (and device) requires is
 * already granted/confirmed.
 */
class PermissionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionsBinding
    private lateinit var prefs: Prefs
    private var lastDeniedPermission: String? = null

    private val requestForegroundLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onPermissionResult(Manifest.permission.ACCESS_FINE_LOCATION, granted)
    }
    private val requestBackgroundLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onPermissionResult(Manifest.permission.ACCESS_BACKGROUND_LOCATION, granted)
    }
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onPermissionResult(Manifest.permission.POST_NOTIFICATIONS, granted)
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
        binding.btnOpenSettings.setOnClickListener { openBestSettingsScreen() }
        binding.btnContinue.setOnClickListener { goToMain() }

        binding.btnOpenNotificationSettings.setOnClickListener { openNotificationSettings() }
        binding.btnGrantBatteryOptimization.setOnClickListener { requestIgnoreBatteryOptimizations() }

        binding.cardMiuiSettings.visibility = if (isMiuiDevice()) android.view.View.VISIBLE else android.view.View.GONE
        binding.cbMiuiSettingsConfirmed.isChecked = prefs.miuiBackgroundSettingsConfirmed
        binding.cbMiuiSettingsConfirmed.setOnCheckedChangeListener { _, isChecked ->
            prefs.miuiBackgroundSettingsConfirmed = isChecked
        }
        binding.btnOpenMiuiAutostart.setOnClickListener { openMiuiAutostartSettings() }
        binding.btnOpenMiuiSettings.setOnClickListener { openAppSettings() }

        refreshCardState()
    }

    /** Covers Redmi/POCO too — all Xiaomi sub-brands report this same manufacturer string. */
    private fun isMiuiDevice(): Boolean = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)

    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true

    /** Standard Android API (not MIUI-specific): pops a direct system grant
     * dialog, no settings navigation required. */
    private fun requestIgnoreBatteryOptimizations() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            openAppSettings()
        }
    }

    /** Exact notification-settings screen for this app — unlike location,
     * Android does expose a direct deep-link for this one. */
    private fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            openAppSettings()
        }
    }

    /** Best-effort only: these component names are undocumented/unofficial
     * and MIUI has changed them across versions before — falls back to the
     * generic app settings screen if none resolve on this device. */
    private fun openMiuiAutostartSettings() {
        val attempts = listOf(
            Intent().setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ),
            Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT)
        )
        for (intent in attempts) {
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // try the next known component, then fall back below
            }
        }
        openAppSettings()
    }

    private fun openBestSettingsScreen() {
        if (lastDeniedPermission == Manifest.permission.POST_NOTIFICATIONS) {
            openNotificationSettings()
        } else {
            openAppSettings()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshCardState()
    }

    private fun onPermissionResult(permission: String, granted: Boolean) {
        refreshCardState()
        if (!granted) {
            lastDeniedPermission = permission
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

        binding.btnGrantBatteryOptimization.isEnabled = !isIgnoringBatteryOptimizations()
    }

    private fun allRequiredPermissionsGranted(): Boolean {
        val foreground = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            isGranted(Manifest.permission.POST_NOTIFICATIONS)
        val batteryOk = isIgnoringBatteryOptimizations()
        val miuiSettings = !isMiuiDevice() || prefs.miuiBackgroundSettingsConfirmed
        return foreground && background && notifications && batteryOk && miuiSettings
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
