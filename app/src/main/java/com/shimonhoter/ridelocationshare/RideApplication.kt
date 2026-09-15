package com.shimonhoter.ridelocationshare

import android.app.Application
import com.shimonhoter.ridelocationshare.util.AppLog
import com.shimonhoter.ridelocationshare.work.RideCheckWorker

class RideApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        RideCheckWorker.schedule(this)
    }
}
