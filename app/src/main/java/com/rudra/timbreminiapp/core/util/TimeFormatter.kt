package com.rudra.timbreminiapp.core.util

import java.util.Locale
import java.util.concurrent.TimeUnit

object TimeFormatter {
    // mm:ss, or h:mm:ss over an hour. Locale.US keeps it stable
    fun formatMs(ms: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}