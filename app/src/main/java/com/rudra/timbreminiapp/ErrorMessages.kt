package com.rudra.timbreminiapp

import androidx.annotation.StringRes
import com.rudra.timbreminiapp.trimmer.TrimError

internal fun Throwable.toUserMessageRes(): Int {
    return when (this) {
        is TrimError.UnreadableSource -> R.string.error_read_failed
        is TrimError.InvalidRange -> R.string.error_min_length
        is TrimError.ConversionFailed -> R.string.error_unsupported
        is TrimError.EmptyOutput -> R.string.error_empty_output
        is TrimError.StorageWriteFailed -> R.string.error_storage_write
        is TrimError.Cancelled -> R.string.error_cancelled
        else -> R.string.error_generic
    }
}