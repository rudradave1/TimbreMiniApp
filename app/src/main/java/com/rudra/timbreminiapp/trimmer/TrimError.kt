package com.rudra.timbreminiapp.trimmer

sealed class TrimError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class UnreadableSource(cause: Throwable? = null) : TrimError(
        "Failed to read input file",
        cause
    )

    class InvalidRange : TrimError("Invalid trim range")

    class ConversionFailed(detail: String?, cause: Throwable? = null) : TrimError(
        "FFmpeg trim failed",
        cause
    ) {
        val detail: String = detail?.takeLast(300).orEmpty()
    }

    class EmptyOutput : TrimError("Trimmed file is empty")

    class StorageWriteFailed(cause: Throwable? = null) : TrimError(
        "Failed to save trimmed file to storage",
        cause
    )

    class Cancelled : TrimError("Trim cancelled")
}