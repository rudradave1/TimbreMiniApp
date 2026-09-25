package com.rudra.timbreminiapp.presentation

import android.net.Uri
import androidx.annotation.StringRes

// One state for the whole screen, errors carry a string resource
sealed interface TrimUiState {
    data object Idle : TrimUiState
    data class Loading(
        val fraction: Float = 0f,
        val clipDurationMs: Long = 0L
    ) : TrimUiState
    data class Success(
        val displayName: String,
        val publicUri: Uri,
        val mimeType: String,
        val pathDescription: String,
        val sizeBytes: Long
    ) : TrimUiState

    data class Error(@StringRes val messageRes: Int) : TrimUiState
}