package com.shimonhoter.ridelocationshare.data

/** Minutes since midnight for both bounds; the single daily active window (docs/SPEC_EN.md 3.3/3.8). */
data class TimeWindow(val startMinutes: Int, val endMinutes: Int)
