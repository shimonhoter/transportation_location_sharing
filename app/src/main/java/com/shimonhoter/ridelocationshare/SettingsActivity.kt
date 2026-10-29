package com.shimonhoter.ridelocationshare

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.text.HtmlCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.shimonhoter.ridelocationshare.data.GeoPoint
import com.shimonhoter.ridelocationshare.data.Prefs
import com.shimonhoter.ridelocationshare.data.TimeWindow
import com.shimonhoter.ridelocationshare.history.RideHistoryStore
import com.shimonhoter.ridelocationshare.service.BroadcastService
import com.shimonhoter.ridelocationshare.databinding.ActivitySettingsBinding
import com.shimonhoter.ridelocationshare.util.AppLog
import kotlinx.coroutines.launch
import java.util.Calendar

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs
    private lateinit var historyStore: RideHistoryStore

    private var pendingOrigin: GeoPoint? = null
    private var pendingDestination: GeoPoint? = null
    private var pendingOriginRadius: Int = 150
    private var pendingDestinationRadius: Int = 150
    private var mapPickerTarget: PickTarget? = null

    private enum class PickTarget { ORIGIN, DESTINATION }

    private val pickOnMap = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@registerForActivityResult
        val lat = data.getDoubleExtra(MapPickerActivity.EXTRA_RESULT_LAT, Double.NaN)
        val lon = data.getDoubleExtra(MapPickerActivity.EXTRA_RESULT_LON, Double.NaN)
        val radius = data.getDoubleExtra(MapPickerActivity.EXTRA_RESULT_RADIUS, Double.NaN)
        if (lat.isNaN() || lon.isNaN() || radius.isNaN()) return@registerForActivityResult
        when (mapPickerTarget) {
            PickTarget.ORIGIN -> setOrigin(GeoPoint(lat, lon), radius.toInt())
            PickTarget.DESTINATION -> setDestination(GeoPoint(lat, lon), radius.toInt())
            null -> Unit
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)
        historyStore = RideHistoryStore(this)

        loadFromPrefs()

        binding.btnOriginCurrentLocation.setOnClickListener { useCurrentLocation(PickTarget.ORIGIN) }
        binding.btnDestinationCurrentLocation.setOnClickListener { useCurrentLocation(PickTarget.DESTINATION) }
        binding.btnOriginPickMap.setOnClickListener { openMapPicker(PickTarget.ORIGIN) }
        binding.btnDestinationPickMap.setOnClickListener { openMapPicker(PickTarget.DESTINATION) }

        binding.etWindowStart.setOnClickListener { pickTime(binding.etWindowStart) }
        binding.etWindowEnd.setOnClickListener { pickTime(binding.etWindowEnd) }

        binding.seekLocationUpdateInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLocationUpdateIntervalLabel(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.seekCorroborationRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateCorroborationRadiusLabel(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.seekAutoStopDetection.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateAutoStopDetectionLabel(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.btnSave.setOnClickListener { save() }
        binding.btnSettingsHelp.setOnClickListener { showHelp() }
        binding.btnClearHistory.setOnClickListener {
            lifecycleScope.launch {
                historyStore.clearAll()
                Toast.makeText(this@SettingsActivity, R.string.history_cleared_message, Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnShowDiagnosticLog.setOnClickListener { showDiagnosticLog() }
        binding.btnShareDiagnosticLog.setOnClickListener { shareDiagnosticLog() }
    }

    private fun showDiagnosticLog() {
        val text = AppLog.readAll().takeLast(8000).ifBlank { getString(R.string.diagnostic_log_empty) }
        val textView = TextView(this).apply {
            setText(text)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setPadding(32, 32, 32, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.diagnostic_log_title)
            .setView(ScrollView(this).apply { addView(textView) })
            .setPositiveButton(R.string.help_close_button, null)
            .show()
    }

    private fun shareDiagnosticLog() {
        val file = AppLog.fileOrNull()
        if (file == null) {
            Toast.makeText(this, R.string.diagnostic_log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.diagnostic_log_share_chooser_title)))
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_help_title)
            .setMessage(HtmlCompat.fromHtml(getString(R.string.settings_help_body), HtmlCompat.FROM_HTML_MODE_LEGACY))
            .setPositiveButton(R.string.help_close_button, null)
            .show()
    }

    private fun loadFromPrefs() {
        binding.etRideCode.setText(prefs.rideCode)
        binding.etNickname.setText(prefs.nickname)
        binding.switchShowNickname.isChecked = prefs.showNickname

        pendingOrigin = prefs.origin
        pendingDestination = prefs.destination
        pendingOriginRadius = prefs.geofenceRadiusMeters
        pendingDestinationRadius = prefs.destinationRadiusMeters
        updateOriginLabel()
        updateDestinationLabel()

        binding.seekLocationUpdateInterval.progress = prefs.locationUpdateIntervalSeconds
        updateLocationUpdateIntervalLabel(prefs.locationUpdateIntervalSeconds)

        binding.seekCorroborationRadius.progress = prefs.corroborationRadiusMeters
        updateCorroborationRadiusLabel(prefs.corroborationRadiusMeters)

        binding.seekAutoStopDetection.progress = prefs.autoStopDetectionSeconds
        updateAutoStopDetectionLabel(prefs.autoStopDetectionSeconds)

        binding.switchHistoryEnabled.isChecked = prefs.historyEnabled

        binding.etAlertSoundDuration.setText(prefs.alertSoundDurationSeconds.toString())

        binding.etTripDuration.setText(prefs.tripDurationMinutes.toString())
        binding.etSafetyMargin.setText(prefs.safetyMarginMinutes.toString())

        val window = prefs.activeWindow
        binding.etWindowStart.setText(minutesToTime(window.startMinutes))
        binding.etWindowEnd.setText(minutesToTime(window.endMinutes))

        val activeDays = prefs.activeDays
        binding.cbSunday.isChecked = Calendar.SUNDAY in activeDays
        binding.cbMonday.isChecked = Calendar.MONDAY in activeDays
        binding.cbTuesday.isChecked = Calendar.TUESDAY in activeDays
        binding.cbWednesday.isChecked = Calendar.WEDNESDAY in activeDays
        binding.cbThursday.isChecked = Calendar.THURSDAY in activeDays
        binding.cbFriday.isChecked = Calendar.FRIDAY in activeDays
        binding.cbSaturday.isChecked = Calendar.SATURDAY in activeDays
    }

    private fun updateLocationUpdateIntervalLabel(seconds: Int) {
        binding.tvLocationUpdateIntervalValue.text = getString(R.string.location_update_interval_label) + ": ${seconds}s"
    }

    private fun updateCorroborationRadiusLabel(radius: Int) {
        binding.tvCorroborationRadiusValue.text = getString(R.string.corroboration_radius_label) + ": ${radius}m"
    }

    private fun updateAutoStopDetectionLabel(seconds: Int) {
        binding.tvAutoStopDetectionValue.text = getString(R.string.auto_stop_detection_label) + ": ${seconds}s"
    }

    private fun setOrigin(point: GeoPoint, radiusMeters: Int = pendingOriginRadius) {
        pendingOrigin = point
        pendingOriginRadius = radiusMeters
        updateOriginLabel()
    }

    private fun setDestination(point: GeoPoint, radiusMeters: Int = pendingDestinationRadius) {
        pendingDestination = point
        pendingDestinationRadius = radiusMeters
        updateDestinationLabel()
    }

    private fun updateOriginLabel() {
        binding.tvOriginValue.text = pendingOrigin?.let {
            "%.5f, %.5f (רדיוס %dמ')".format(it.lat, it.lon, pendingOriginRadius)
        } ?: "לא הוגדר"
    }

    private fun updateDestinationLabel() {
        binding.tvDestinationValue.text = pendingDestination?.let {
            "%.5f, %.5f (רדיוס %dמ')".format(it.lat, it.lon, pendingDestinationRadius)
        } ?: "לא הוגדר"
    }

    private fun useCurrentLocation(target: PickTarget) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.permissions_title, Toast.LENGTH_SHORT).show()
            return
        }
        LocationServices.getFusedLocationProviderClient(this).lastLocation
            .addOnSuccessListener { location ->
                if (location == null) {
                    Toast.makeText(this, "לא נמצא מיקום נוכחי", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                val point = GeoPoint(location.latitude, location.longitude)
                when (target) {
                    PickTarget.ORIGIN -> setOrigin(point)
                    PickTarget.DESTINATION -> setDestination(point)
                }
            }
    }

    private fun openMapPicker(target: PickTarget) {
        mapPickerTarget = target
        val initialPoint: GeoPoint?
        val initialRadius: Int
        when (target) {
            PickTarget.ORIGIN -> { initialPoint = pendingOrigin; initialRadius = pendingOriginRadius }
            PickTarget.DESTINATION -> { initialPoint = pendingDestination; initialRadius = pendingDestinationRadius }
        }
        val intent = Intent(this, MapPickerActivity::class.java).apply {
            initialPoint?.let {
                putExtra(MapPickerActivity.EXTRA_INITIAL_LAT, it.lat)
                putExtra(MapPickerActivity.EXTRA_INITIAL_LON, it.lon)
            }
            putExtra(MapPickerActivity.EXTRA_INITIAL_RADIUS, initialRadius.toDouble())
        }
        pickOnMap.launch(intent)
    }

    private fun pickTime(target: com.google.android.material.textfield.TextInputEditText) {
        val current = parseTime(target.text?.toString())
        TimePickerDialog(this, { _, hour, minute ->
            target.setText("%02d:%02d".format(hour, minute))
        }, current.first, current.second, true).show()
    }

    private fun parseTime(text: String?): Pair<Int, Int> {
        val parts = text?.split(":")
        val hour = parts?.getOrNull(0)?.toIntOrNull() ?: 6
        val minute = parts?.getOrNull(1)?.toIntOrNull() ?: 0
        return hour to minute
    }

    private fun minutesToTime(totalMinutes: Int): String = "%02d:%02d".format(totalMinutes / 60, totalMinutes % 60)

    private fun timeToMinutes(text: String?, fallback: Int): Int {
        val (hour, minute) = parseTime(text)
        return if (text.isNullOrBlank()) fallback else hour * 60 + minute
    }

    private fun save() {
        val startMinutes = timeToMinutes(binding.etWindowStart.text?.toString(), prefs.activeWindow.startMinutes)
        val endMinutes = timeToMinutes(binding.etWindowEnd.text?.toString(), prefs.activeWindow.endMinutes)
        if (endMinutes <= startMinutes) {
            Toast.makeText(this, R.string.time_window_invalid_error, Toast.LENGTH_SHORT).show()
            return
        }

        prefs.rideCode = binding.etRideCode.text?.toString().orEmpty()
        prefs.nickname = binding.etNickname.text?.toString().orEmpty()
        prefs.showNickname = binding.switchShowNickname.isChecked
        prefs.origin = pendingOrigin
        prefs.destination = pendingDestination
        prefs.geofenceRadiusMeters = pendingOriginRadius
        prefs.destinationRadiusMeters = pendingDestinationRadius
        prefs.locationUpdateIntervalSeconds = binding.seekLocationUpdateInterval.progress
        prefs.corroborationRadiusMeters = binding.seekCorroborationRadius.progress
        prefs.autoStopDetectionSeconds = binding.seekAutoStopDetection.progress
        prefs.historyEnabled = binding.switchHistoryEnabled.isChecked
        prefs.alertSoundDurationSeconds = binding.etAlertSoundDuration.text?.toString()?.toIntOrNull() ?: prefs.alertSoundDurationSeconds
        prefs.tripDurationMinutes = binding.etTripDuration.text?.toString()?.toIntOrNull() ?: prefs.tripDurationMinutes
        prefs.safetyMarginMinutes = binding.etSafetyMargin.text?.toString()?.toIntOrNull() ?: prefs.safetyMarginMinutes
        prefs.activeWindow = TimeWindow(startMinutes, endMinutes)

        val days = mutableSetOf<Int>()
        addDayIfChecked(days, binding.cbSunday, Calendar.SUNDAY)
        addDayIfChecked(days, binding.cbMonday, Calendar.MONDAY)
        addDayIfChecked(days, binding.cbTuesday, Calendar.TUESDAY)
        addDayIfChecked(days, binding.cbWednesday, Calendar.WEDNESDAY)
        addDayIfChecked(days, binding.cbThursday, Calendar.THURSDAY)
        addDayIfChecked(days, binding.cbFriday, Calendar.FRIDAY)
        addDayIfChecked(days, binding.cbSaturday, Calendar.SATURDAY)
        prefs.activeDays = days

        BroadcastService.applySettingsChange(this)

        Toast.makeText(this, R.string.save_button, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun addDayIfChecked(set: MutableSet<Int>, checkBox: CheckBox, day: Int) {
        if (checkBox.isChecked) set += day
    }
}
