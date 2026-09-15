package com.shimonhoter.ridelocationshare.util

import com.shimonhoter.ridelocationshare.data.Prefs
import java.util.Calendar

/**
 * "Active days" gate everything else, per docs/SPEC_EN.md 2/3.3: the app
 * must never activate automation outside the configured days/time windows.
 * A user can configure more than one daily window (e.g. morning commute AND
 * evening return) — any one of them matching is enough.
 */
object ActiveWindow {
    fun isNowActive(prefs: Prefs, now: Calendar = Calendar.getInstance()): Boolean {
        val dayOfWeek = now.get(Calendar.DAY_OF_WEEK)
        if (dayOfWeek !in prefs.activeDays) return false

        val minutesNow = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return prefs.activeWindows.any { minutesNow in it.startMinutes..it.endMinutes }
    }
}
