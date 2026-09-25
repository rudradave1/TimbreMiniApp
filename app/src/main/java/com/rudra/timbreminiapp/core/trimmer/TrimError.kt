package com.rudra.timbreminiapp.core.trimmer

import androidx.annotation.StringRes
import com.rudra.timbreminiapp.R

class TrimError(@StringRes val messageRes: Int, detail: String? = null) : Exception(detail)