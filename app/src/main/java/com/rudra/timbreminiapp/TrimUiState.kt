package com.rudra.timbreminiapp

import android.net.Uri
import androidx.annotation.StringRes

sealed interface TrimUiState {
    data object Idle : TrimUiState
    data object Loading : TrimUiState
    data class Success(
        val displayName: String,
        val publicUri: Uri,
        val pathDescription: String,
        val sizeBytes: Long
    ) : TrimUiState

    data class Error(@StringRes val messageRes: Int) : TrimUiState
}