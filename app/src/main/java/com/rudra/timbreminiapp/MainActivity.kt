package com.rudra.timbreminiapp

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.registerForActivityResult
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.material.slider.RangeSlider
import com.rudra.timbreminiapp.databinding.ActivityMainBinding
import com.rudra.timbreminiapp.util.TimeFormatter

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var selectedUri : Uri? = null
    private var isVideo : Boolean = true

    private var player: ExoPlayer? = null

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) {uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
            isVideo = true
            player?.apply {
                setMediaItem(MediaItem.fromUri(selectedUri!!))
                prepare()
                playWhenReady = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        //this handles the padding for status bar and navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                systemBars.left + 16,
                systemBars.top + 16,
                systemBars.right + 16,
                systemBars.bottom + 16
            )
            insets
        }// Hide ExoPlayer's internal time scrubber and duration text so only RangeSlider is used
        binding.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress)?.visibility = View.GONE
        binding.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_position)?.visibility = View.GONE
        binding.playerView.findViewById<View>(androidx.media3.ui.R.id.exo_duration)?.visibility = View.GONE
        binding.btnSelectVideo.setOnClickListener {
            pickVideoLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
            )
        }
        binding.rangeSlider.addOnChangeListener { slider, _, _ ->
            val values = slider.values
            if (values.size >= 2) {
                val startMs = values[0].toLong()
                val endMs = values[1].toLong()
                binding.tvStartTime.text = TimeFormatter.formatMs(startMs)
                binding.tvEndTime.text = TimeFormatter.formatMs(endMs)
            }
        }

        binding.rangeSlider.addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: RangeSlider) {
                player?.pause()
            }

            override fun onStopTrackingTouch(slider: RangeSlider) {
                val values = slider.values
                if (values.size >= 2) {
                    // Seek to the start cut so the user sees the preview frame
                    player?.seekTo(values[0].toLong())
                }
            }
        })
        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            binding.playerView.player = exoPlayer

            exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        val totalDuration = exoPlayer.duration
                        if(totalDuration > 0) {
                            setupRangeSlider(totalDuration)
                        }
                    }

                }

                private fun setupRangeSlider(totalDuration: Long) {
                    val duration = totalDuration.toFloat()

                    binding.rangeSlider.apply {
                        valueFrom = 0f
                        valueTo = duration
                        values = listOf(0f, duration)
                        isEnabled = true
                    }
                    // Set initial text
                    binding.tvStartTime.text = TimeFormatter.formatMs(0L)
                    binding.tvEndTime.text = TimeFormatter.formatMs(totalDuration)
                    binding.btnTrim.isEnabled = true
                }
            })
        }
    }
    override fun onPause() {
        super.onPause()
        player?.pause() // Stop playback if app goes to background
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release() // Free the native decoders
        player = null
    }
}