package com.shimonhoter.ridelocationshare

import android.app.Application
import com.shimonhoter.ridelocationshare.data.Prefs
import com.shimonhoter.ridelocationshare.service.RideSessionState
import com.shimonhoter.ridelocationshare.util.AppLog
import com.shimonhoter.ridelocationshare.work.RideCheckWorker

class RideApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        // Seed from the persisted flag so a freshly (re)started process shows
        // the correct "am I broadcasting" state immediately, before
        // BroadcastService (if still running) has a chance to report in.
        RideSessionState.isThisDeviceBroadcasting.value = Prefs(this).isBroadcasting
        RideCheckWorker.schedule(this)
    }
}
