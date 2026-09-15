package com.shimonhoter.ridelocationshare

import android.app.Application
import com.shimonhoter.ridelocationshare.data.Prefs
import com.shimonhoter.ridelocationshare.history.RideHistoryStore
import com.shimonhoter.ridelocationshare.service.RideSessionState
import com.shimonhoter.ridelocationshare.util.AppLog
import com.shimonhoter.ridelocationshare.work.RideCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RideApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        val prefs = Prefs(this)
        // Seed from the persisted flag so a freshly (re)started process shows
        // the correct "am I broadcasting" state immediately, before
        // BroadcastService (if still running) has a chance to report in.
        RideSessionState.isThisDeviceBroadcasting.value = prefs.isBroadcasting
        RideCheckWorker.schedule(this)

        // A single process-wide recorder, independent of whether MainActivity
        // or BroadcastService (or both) is what actually observed this
        // update, so the local ETA history (docs/SPEC_EN.md 4.5) keeps
        // accumulating on any day the ride was seen, opt-in via
        // Prefs.historyEnabled (checked fresh on every update).
        val historyStore = RideHistoryStore(this)
        val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        RideSessionState.currentLocation.observeForever { location ->
            if (location != null && prefs.historyEnabled) {
                historyScope.launch {
                    historyStore.record(System.currentTimeMillis(), location.lat, location.lon, location.avgSpeedKmh)
                }
            }
        }
    }
}
