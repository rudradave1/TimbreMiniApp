package com.rudra.timbreminiapp

import android.net.Uri

sealed interface TrimUiState {
    data object Idle : TrimUiState
    data object Loading : TrimUiState
    data class Success(
        val displayName: String,
        val publicUri: Uri,
        val pathDescription: String,
        val sizeBytes: Long
    ) : TrimUiState

    data class Error(val message: String) : TrimUiState
}