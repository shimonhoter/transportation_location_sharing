package com.shimonhoter.ridelocationshare.ui

import android.content.Context
import androidx.core.content.ContextCompat
import com.shimonhoter.ridelocationshare.R

/**
 * Small shared helpers for the glassmorphism-inspired look (translucent
 * MaterialCardView + elevation, see res/values/styles.xml `RideCard` and
 * docs/SPEC_EN.md section 6) so screens stay visually consistent without
 * duplicating color/format logic.
 */
object UiKit {
    fun statusColor(context: Context, isBroadcasting: Boolean): Int = ContextCompat.getColor(
        context,
        if (isBroadcasting) R.color.status_active else R.color.status_idle
    )

    fun formatAge(ageSeconds: Double): String = when {
        ageSeconds < 60 -> "עודכן לפני פחות מדקה"
        ageSeconds < 3600 -> "עודכן לפני ${(ageSeconds / 60).toInt()} דקות"
        else -> "עודכן לפני ${(ageSeconds / 3600).toInt()} שעות"
    }
}
