package com.rudra.timbreminiapp.util

import java.util.Locale
import java.util.concurrent.TimeUnit

object TimeFormatter {
    fun formatMs(ms: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}