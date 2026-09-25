package com.rudra.timbreminiapp

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rudra.timbreminiapp.trimmer.MediaTrimmer
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
    val endMs: Long = 0L,
    val playbackPositionMs: Long = 0L
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val trimmer = MediaTrimmer(application)

    private val _uiState = MutableStateFlow<TrimUiState>(TrimUiState.Idle)
    val uiState: StateFlow<TrimUiState> = _uiState.asStateFlow()

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

    fun savePlaybackPosition(positionMs: Long) {
        sessionState = sessionState?.copy(playbackPositionMs = positionMs)
    }

    fun trim() {
        val session = sessionState ?: return
        viewModelScope.launch {
            _uiState.value = TrimUiState.Loading
            val result = trimmer.trimMedia(
                sourceUri = session.uri,
                startMs = session.startMs,
                endMs = session.endMs,
                isVideo = session.isVideo
            )
            _uiState.value = result.fold(
                onSuccess = { r ->
                    TrimUiState.Success(
                        displayName = r.displayName,
                        publicUri = r.publicUri,
                        pathDescription = r.pathDescription,
                        sizeBytes = r.sizeBytes
                    )
                },
                onFailure = { TrimUiState.Error(it.toUserMessageRes()) }
            )
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        return try {
            val resolver = getApplication<Application>().contentResolver
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index).orEmpty() else ""
                }.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    fun resetState() {
        _uiState.value = TrimUiState.Idle
    }
}