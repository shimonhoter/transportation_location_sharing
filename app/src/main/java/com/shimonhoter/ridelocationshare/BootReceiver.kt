package com.shimonhoter.ridelocationshare

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shimonhoter.ridelocationshare.data.Prefs
import com.shimonhoter.ridelocationshare.service.BroadcastService
import com.shimonhoter.ridelocationshare.util.ActiveWindow

/**
 * Starts automatic monitoring right after the device finishes booting,
 * instead of waiting for the next ~15-minute RideCheckWorker tick
 * (docs/SPEC_EN.md 3.8) or for the user to open the app — mirrors the same
 * "start now" check MainActivity does on launch. BOOT_COMPLETED is one of
 * the broadcasts Android exempts from the background-start restrictions on
 * startForegroundService(), so this is allowed to run without the app's UI
 * having been opened since boot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        if (!prefs.isPrivateCarActive() && ActiveWindow.isNowActive(prefs)) {
            BroadcastService.startAutomatic(context)
        }
    }
}
