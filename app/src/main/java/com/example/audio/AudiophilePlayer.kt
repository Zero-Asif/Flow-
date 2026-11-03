package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.util.Log
import com.example.model.AudioTrack as MusicTrack
import com.example.model.EqualizerPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

class AudiophilePlayer(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.Default)

    private var audioTrackInstance: AudioTrack? = null
    private var mediaPlayerInstance: MediaPlayer? = null
    private var playbackJob: Job? = null

    private var currentMusicTrack: MusicTrack? = null
    private var isPlayingState: Boolean = false
    private var playbackPositionMs: Long = 0L
    private var trackDurationMs: Long = 0L

    private var activePreset: EqualizerPreset = EqualizerPreset.FLAT
    private var masterVolume: Float = 1.0f

    // Callback listeners
    var onPositionChanged: ((Long, Long) -> Unit)? = null
    var onTrackFinished: (() -> Unit)? = null
    var onVisualizerUpdate: ((List<Float>) -> Unit)? = null

    fun playTrack(track: MusicTrack, startPositionMs: Long = 0L) {
        stop()
        currentMusicTrack = track
        playbackPositionMs = startPositionMs
        trackDurationMs = track.durationMs
        isPlayingState = true

        if (track.uriString != null) {
            playLocalUri(track.uriString, startPositionMs)
        } else {
            playSynthesizedStream(track, startPositionMs)
        }
    }

    private fun playLocalUri(uriString: String, startPositionMs: Long) {
        try {
            val uri = Uri.parse(uriString)
            mediaPlayerInstance = MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                prepare()
                if (startPositionMs > 0) {
                    seekTo(startPositionMs.toInt())
                }
                start()
                setOnCompletionListener {
                    isPlayingState = false
                    onTrackFinished?.invoke()
                }
            }
            startPositionPoller()
        } catch (e: Exception) {
            Log.e("AudiophilePlayer", "Failed to play local URI, falling back to synth", e)
            playSynthesizedStream(currentMusicTrack!!, startPositionMs)
        }
    }

    private fun playSynthesizedStream(track: MusicTrack, startPositionMs: Long) {
        val sampleRate = PcmAudioSynthesizer.SAMPLE_RATE
        val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(audioFormat)
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .build()

        audioTrackInstance = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrackInstance?.play()

        playbackJob = scope.launch {
            var currentSec = (startPositionMs / 1000).toInt()
            val chunkDurationMs = 250L

            while (isActive && isPlayingState) {
                // Apply Equalizer adjustments to sound synthesis
                val bassBoostVal = activePreset.bassBoost
                val bassMultiplier = (1.0f + bassBoostVal * 1.5f + (activePreset.bandGains.getOrNull(0) ?: 0f) / 10f).coerceIn(0.2f, 3.0f)
                val trebleMultiplier = (1.0f + (activePreset.bandGains.getOrNull(8) ?: 0f) / 12f).coerceIn(0.2f, 2.5f)
                val virtualizerVal = activePreset.virtualizer

                val chunk = PcmAudioSynthesizer.generateChunk(
                    preset = track.synthPreset,
                    secondInTrack = currentSec,
                    sampleCount = sampleRate / 4,
                    bassMultiplier = bassMultiplier,
                    trebleMultiplier = trebleMultiplier,
                    virtualizerWidth = virtualizerVal
                )

                // Master volume scaling
                if (masterVolume < 0.99f) {
                    for (i in chunk.indices) {
                        chunk[i] = (chunk[i] * masterVolume).toInt().toShort()
                    }
                }

                audioTrackInstance?.write(chunk, 0, chunk.size)

                // Advance track position
                playbackPositionMs += chunkDurationMs
                if (playbackPositionMs >= trackDurationMs) {
                    playbackPositionMs = trackDurationMs
                    onPositionChanged?.invoke(playbackPositionMs, trackDurationMs)
                    isPlayingState = false
                    launch(Dispatchers.Main) {
                        onTrackFinished?.invoke()
                    }
                    break
                }

                currentSec = (playbackPositionMs / 1000).toInt()
                onPositionChanged?.invoke(playbackPositionMs, trackDurationMs)

                // Generate simulated 24-band FFT visualizer response
                val amplitudes = List(24) { bandIdx ->
                    val freqFactor = sin(bandIdx * 0.4 + playbackPositionMs * 0.008).toFloat()
                    val pulse = (abs(freqFactor) * 0.7f + 0.15f * (bassBoostVal + 0.3f)).coerceIn(0.1f, 1.0f)
                    pulse * masterVolume
                }
                onVisualizerUpdate?.invoke(amplitudes)
            }
        }
    }

    private fun startPositionPoller() {
        playbackJob = scope.launch {
            while (isActive && isPlayingState) {
                mediaPlayerInstance?.let { mp ->
                    if (mp.isPlaying) {
                        playbackPositionMs = mp.currentPosition.toLong()
                        trackDurationMs = mp.duration.toLong()
                        onPositionChanged?.invoke(playbackPositionMs, trackDurationMs)

                        // Amplitude generator for local files
                        val amplitudes = List(24) { bandIdx ->
                            val freqFactor = sin(bandIdx * 0.3 + playbackPositionMs * 0.01).toFloat()
                            (abs(freqFactor) * 0.65f + 0.2f).coerceIn(0.1f, 0.95f)
                        }
                        onVisualizerUpdate?.invoke(amplitudes)
                    }
                }
                kotlinx.coroutines.delay(200)
            }
        }
    }

    fun pause() {
        isPlayingState = false
        audioTrackInstance?.pause()
        mediaPlayerInstance?.pause()
        playbackJob?.cancel()
    }

    fun resume() {
        if (currentMusicTrack != null && !isPlayingState) {
            isPlayingState = true
            if (mediaPlayerInstance != null) {
                mediaPlayerInstance?.start()
                startPositionPoller()
            } else {
                playSynthesizedStream(currentMusicTrack!!, playbackPositionMs)
            }
        }
    }

    fun seekTo(positionMs: Long) {
        playbackPositionMs = positionMs
        mediaPlayerInstance?.seekTo(positionMs.toInt())
        if (audioTrackInstance != null && isPlayingState) {
            audioTrackInstance?.pause()
            audioTrackInstance?.flush()
            playbackJob?.cancel()
            playSynthesizedStream(currentMusicTrack!!, positionMs)
        }
    }

    fun stop() {
        isPlayingState = false
        playbackJob?.cancel()
        playbackJob = null

        audioTrackInstance?.stop()
        audioTrackInstance?.release()
        audioTrackInstance = null

        mediaPlayerInstance?.stop()
        mediaPlayerInstance?.release()
        mediaPlayerInstance = null
    }

    fun setEqualizerPreset(preset: EqualizerPreset) {
        activePreset = preset
    }

    fun setMasterVolume(volume: Float) {
        masterVolume = volume.coerceIn(0f, 1f)
        mediaPlayerInstance?.setVolume(masterVolume, masterVolume)
    }

    fun release() {
        stop()
    }
}
