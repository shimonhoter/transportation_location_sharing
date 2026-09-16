package com.shimonhoter.ridelocationshare.util

import com.shimonhoter.ridelocationshare.data.Prefs
import java.util.Calendar

/**
 * "Active days" gate everything else, per docs/SPEC_EN.md 2/3.3: the app
 * must never activate automation outside the configured day/time window.
 */
object ActiveWindow {
    fun isNowActive(prefs: Prefs, now: Calendar = Calendar.getInstance()): Boolean {
        val dayOfWeek = now.get(Calendar.DAY_OF_WEEK)
        if (dayOfWeek !in prefs.activeDays) return false

        val minutesNow = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val window = prefs.activeWindow
        return minutesNow in window.startMinutes..window.endMinutes
    }

    /**
     * Today's active-window end time as an absolute timestamp, or null if
     * today isn't even an active day. Used to tie "private car" mode's
     * auto-expiry to the end of the broadcast window rather than a fixed
     * duration (docs/SPEC_EN.md 3.6) — it doesn't check whether the window
     * has already passed today; callers compare the result against `now`.
     */
    fun todaysWindowEndMillis(prefs: Prefs, now: Calendar = Calendar.getInstance()): Long? {
        if (now.get(Calendar.DAY_OF_WEEK) !in prefs.activeDays) return null
        val window = prefs.activeWindow
        val end = now.clone() as Calendar
        end.set(Calendar.HOUR_OF_DAY, window.endMinutes / 60)
        end.set(Calendar.MINUTE, window.endMinutes % 60)
        end.set(Calendar.SECOND, 0)
        end.set(Calendar.MILLISECOND, 0)
        return end.timeInMillis
    }
}
