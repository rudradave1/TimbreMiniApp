package com.rudra.timbreminiapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.RangeSlider
import com.rudra.timbreminiapp.databinding.ActivityMainBinding
import com.rudra.timbreminiapp.util.TimeFormatter
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var player: ExoPlayer? = null
    private val viewModel: MainViewModel by viewModels()

    private val positionHandler = Handler(Looper.getMainLooper())

    private val boundaryCheckRunnable = object : Runnable {
        override fun run() {
            val p = player
            val session = viewModel.sessionState
            if (p != null && session != null && p.isPlaying) {
                if (p.currentPosition >= session.endMs) {
                    p.pause()
                    p.seekTo(session.startMs)
                } else {
                    positionHandler.postDelayed(this, 100)
                }
            }
        }
    }

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let { loadMedia(it, isVideoMedia = true) }
    }

    private val pickAudioLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadMedia(it, isVideoMedia = false) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWindowInsets()
        initPlayer()
        setupListeners()
        observeUiState()

        viewModel.sessionState?.let { restoredSession ->
            restoreMediaSession(restoredSession)
        }
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                systemBars.left + 16,
                systemBars.top + 16,
                systemBars.right + 16,
                systemBars.bottom + 16
            )
            insets
        }
    }

    private fun initPlayer() {
        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            binding.playerView.player = exoPlayer

            exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        val duration = exoPlayer.duration
                        val session = viewModel.sessionState

                        if (duration > 0 && session != null && session.totalDurationMs == 0L) {
                            viewModel.updateDuration(duration)
                            setupRangeSlider(
                                totalDurationMs = duration,
                                startMs = 0L,
                                endMs = duration
                            )
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        val session = viewModel.sessionState
                        if (session != null && session.endMs > session.startMs) {
                            if (exoPlayer.currentPosition >= session.endMs || exoPlayer.currentPosition < session.startMs) {
                                exoPlayer.seekTo(session.startMs)
                            }
                        }
                        positionHandler.post(boundaryCheckRunnable)
                    } else {
                        positionHandler.removeCallbacks(boundaryCheckRunnable)
                    }
                }
            })
        }
    }

    private fun setupListeners() {
        binding.btnSelectVideo.setOnClickListener {
            pickVideoLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
            )
        }

        binding.btnSelectAudio.setOnClickListener {
            pickAudioLauncher.launch("audio/*")
        }

        binding.rangeSlider.addOnChangeListener { slider, _, fromUser ->
            val values = slider.values
            if (values.size >= 2) {
                val start = values[0].toLong()
                val end = values[1].toLong()
                val selectedDuration = end - start

                if (selectedDuration < 1000L) {
                    binding.btnTrim.isEnabled = false
                    binding.tvSelectedDuration.text = "Min 1s"
                    if (fromUser) {
                        slider.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                } else {
                    binding.btnTrim.isEnabled = true
                    binding.tvStartTime.text = "Start: ${TimeFormatter.formatMs(start)}"
                    binding.tvEndTime.text = "End: ${TimeFormatter.formatMs(end)}"
                    binding.tvSelectedDuration.text = "Clip: ${TimeFormatter.formatMs(selectedDuration)}"
                }
            }
        }

        binding.rangeSlider.addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: RangeSlider) {
                player?.pause()
            }

            override fun onStopTrackingTouch(slider: RangeSlider) {
                val values = slider.values
                if (values.size >= 2) {
                    val start = values[0].toLong()
                    val end = values[1].toLong()

                    viewModel.updateTrimBounds(start, end)
                    player?.seekTo(start)
                }
            }
        })

        binding.btnTrim.setOnClickListener {
            player?.pause()
            viewModel.trim()
        }
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.layoutLoading.isVisible = state is TrimUiState.Loading
                    binding.btnTrim.isEnabled = state !is TrimUiState.Loading

                    when (state) {
                        is TrimUiState.Success -> {
                            val isVideo = viewModel.sessionState?.isVideo ?: true
                            showCompletionDialog(state, isVideo)
                            viewModel.resetState()
                        }
                        is TrimUiState.Error -> {
                            Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_SHORT).show()
                            viewModel.resetState()
                        }
                        TrimUiState.Idle -> Unit
                        TrimUiState.Loading -> Unit
                    }
                }
            }
        }
    }

    private fun showCompletionDialog(state: TrimUiState.Success, isVideo: Boolean) {
        val sizeLabel = if (state.sizeBytes >= 1_048_576) {
            String.format(Locale.US, "%.1f MB", state.sizeBytes / 1_048_576.0)
        } else {
            String.format(Locale.US, "%.0f KB", state.sizeBytes / 1024.0)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Trim Complete")
            .setMessage("File exported: ${state.displayName} ($sizeLabel)\nLocation: ${state.pathDescription}\n\nWhat would you like to do?")
            .setPositiveButton("Share") { _, _ ->
                shareFile(state.publicUri, isVideo)
            }
            .setNegativeButton("Preview") { _, _ ->
                loadMedia(state.publicUri, isVideo)
            }
            .setNeutralButton("Dismiss", null)
            .show()
    }

    private fun shareFile(contentUri: Uri, isVideo: Boolean) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (isVideo) "video/*" else "audio/*"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share Trimmed Media"))
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to share: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadMedia(uri: Uri, isVideoMedia: Boolean) {
        viewModel.onMediaLoaded(uri, isVideoMedia)

        binding.layoutEmptyState.visibility = View.GONE

        if (isVideoMedia) {
            binding.playerView.visibility = View.VISIBLE
            binding.layoutAudioState.visibility = View.GONE
        } else {
            binding.playerView.visibility = View.VISIBLE
            binding.layoutAudioState.visibility = View.VISIBLE
        }

        player?.apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    private fun restoreMediaSession(session: MediaSessionState) {
        binding.layoutEmptyState.visibility = View.GONE
        binding.layoutAudioState.isVisible = !session.isVideo

        player?.apply {
            setMediaItem(MediaItem.fromUri(session.uri))
            prepare()
            seekTo(session.playbackPositionMs)
            playWhenReady = false
        }

        if (session.totalDurationMs > 0L) {
            setupRangeSlider(
                totalDurationMs = session.totalDurationMs,
                startMs = session.startMs,
                endMs = session.endMs
            )
        }
    }

    private fun setupRangeSlider(totalDurationMs: Long, startMs: Long, endMs: Long) {
        val durationFloat = totalDurationMs.toFloat()
        binding.rangeSlider.apply {
            valueFrom = 0f
            valueTo = durationFloat
            values = listOf(startMs.toFloat(), endMs.toFloat())
            isEnabled = true
        }
        val clipDuration = endMs - startMs
        binding.tvStartTime.text = "Start: ${TimeFormatter.formatMs(startMs)}"
        binding.tvEndTime.text = "End: ${TimeFormatter.formatMs(endMs)}"
        binding.tvSelectedDuration.text = "Clip: ${TimeFormatter.formatMs(clipDuration)}"
        binding.btnTrim.isEnabled = (clipDuration >= 1000L)
    }

    override fun onPause() {
        super.onPause()
        player?.let {
            viewModel.savePlaybackPosition(it.currentPosition)
            it.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        positionHandler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }
}