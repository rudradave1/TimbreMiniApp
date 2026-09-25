package com.rudra.timbreminiapp.presentation

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rudra.timbreminiapp.core.trimmer.MediaTrimmer
import com.rudra.timbreminiapp.core.trimmer.TrimError
import com.rudra.timbreminiapp.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MediaSessionState(
    val uri: Uri,
    val isVideo: Boolean,
    val sourceName: String = "",
    val totalDurationMs: Long = 0L,
    val startMs: Long = 0L,
    val endMs: Long = 0L
)

// App context so the trimmer outlives the activity.
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val trimmer = MediaTrimmer(application)

    private val _uiState = MutableStateFlow<TrimUiState>(TrimUiState.Idle)
    val uiState: StateFlow<TrimUiState> = _uiState.asStateFlow()

    // Loaded file, read straight from the UI and player
    var sessionState: MediaSessionState? = null
        private set

    fun onMediaLoaded(uri: Uri, isVideo: Boolean) {
        sessionState = MediaSessionState(
            uri = uri,
            isVideo = isVideo,
            sourceName = queryDisplayName(uri)
        )
    }

    fun updateDuration(durationMs: Long) {
        sessionState = sessionState?.copy(
            totalDurationMs = durationMs,
            endMs = durationMs
        )
    }

    fun updateTrimBounds(startMs: Long, endMs: Long) {
        sessionState = sessionState?.copy(startMs = startMs, endMs = endMs)
    }

    fun trim() {
        val session = sessionState ?: return
        viewModelScope.launch {
            val clipMs = (session.endMs - session.startMs).coerceAtLeast(0L)
            _uiState.value = TrimUiState.Loading(0f, clipMs)
            val result = trimmer.trimMedia(
                sourceUri = session.uri,
                startMs = session.startMs,
                endMs = session.endMs,
                isVideo = session.isVideo,
                onProgress = { fraction ->
                    // Drop stats that land after the state moves on
                    if (_uiState.value is TrimUiState.Loading) {
                        _uiState.value = TrimUiState.Loading(fraction, clipMs)
                    }
                }
            )
            _uiState.value = result.fold(
                onSuccess = { r ->
                    TrimUiState.Success(
                        displayName = r.displayName,
                        publicUri = r.publicUri,
                        mimeType = r.mimeType,
                        pathDescription = r.pathDescription,
                        sizeBytes = r.sizeBytes
                    )
                },
                onFailure = { e ->
                    TrimUiState.Error((e as? TrimError)?.messageRes ?: R.string.error_generic)
                }
            )
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        // Just a label, never let this block loading
        return try {
            val resolver = getApplication<Application>().contentResolver
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else ""
                } ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    fun resetState() {
        // One-shot, then back to idle
        _uiState.value = TrimUiState.Idle
    }
}