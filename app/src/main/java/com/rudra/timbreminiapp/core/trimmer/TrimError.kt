package com.rudra.timbreminiapp.core.trimmer

import androidx.annotation.StringRes
import com.rudra.timbreminiapp.R

// Localized message id, FFmpeg detail stays in the logs
class TrimError(@StringRes val messageRes: Int, detail: String? = null) : Exception(detail)