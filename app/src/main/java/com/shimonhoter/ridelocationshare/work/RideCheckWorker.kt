package com.shimonhoter.ridelocationshare.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shimonhoter.ridelocationshare.config.RideConfig
import com.shimonhoter.ridelocationshare.data.Prefs
import com.shimonhoter.ridelocationshare.service.BroadcastService
import com.shimonhoter.ridelocationshare.util.ActiveWindow
import com.shimonhoter.ridelocationshare.util.AppLog
import java.util.concurrent.TimeUnit

/**
 * Runs roughly every 15 minutes (docs/SPEC_EN.md 3.8): if "now" falls
 * inside the configured active day/time window, starts BroadcastService in
 * automatic mode. Outside the window this is a no-op — active days gate
 * everything (docs/SPEC_EN.md 2/3.3).
 */
class RideCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        if (ActiveWindow.isNowActive(prefs)) {
            AppLog.i(TAG, "Within active window, starting automatic monitoring")
            BroadcastService.startAutomatic(applicationContext)
        } else {
            AppLog.d(TAG, "Outside active window, no-op")
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "RideCheckWorker"
        private const val UNIQUE_WORK_NAME = "ride_check_worker"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RideCheckWorker>(
                RideConfig.WORK_CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
