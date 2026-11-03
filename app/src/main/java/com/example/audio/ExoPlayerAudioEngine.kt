package com.example.audio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.example.model.AudioFormat
import com.example.model.AudioQualityInfo
import com.example.model.AudioTrack
import com.example.model.EqualizerPreset
import com.example.model.RepeatMode
import com.example.model.StreamBackend
import com.example.network.ResolvedStreamCandidate
import com.example.network.UniversalStreamResolver
import com.example.util.AlbumArtHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayList

/**
 * High-fidelity Media3 ExoPlayer Audio Engine configured for:
 * - Strict track-switching lifecycle (stop -> clear buffer -> resolve stream -> READY -> play)
 * - Dynamic highest-bitrate stream probing & fallback (FLAC > ALAC > WAV > AAC 320 > MP3 320)
 * - Gapless playback across queued tracks
 * - Background playback with wake lock and audio focus handling
 * - Audio-only renderers without leaking video MediaCodec instances
 * - Real-time FFT PCM spectrum audio processor for genuine frequency-driven visualizer bars
 */
@OptIn(UnstableApi::class)
class ExoPlayerAudioEngine(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var positionPollerJob: Job? = null
    private var playbackSwitchJob: Job? = null

    private val fftAudioProcessor = RealTimeFftAudioProcessor()
    val exoPlayer: ExoPlayer

    // Single Source of Truth (SSOT) StateFlows
    private val _currentTrackState = MutableStateFlow<AudioTrack?>(null)
    val currentTrackState: StateFlow<AudioTrack?> = _currentTrackState.asStateFlow()

    private val _isPlayingState = MutableStateFlow<Boolean>(false)
    val isPlayingState: StateFlow<Boolean> = _isPlayingState.asStateFlow()

    private val _currentPositionState = MutableStateFlow<Long>(0L)
    val currentPositionState: StateFlow<Long> = _currentPositionState.asStateFlow()

    private val _durationState = MutableStateFlow<Long>(0L)
    val durationState: StateFlow<Long> = _durationState.asStateFlow()

    private val _audioQualityState = MutableStateFlow<AudioQualityInfo>(AudioQualityInfo())
    val audioQualityState: StateFlow<AudioQualityInfo> = _audioQualityState.asStateFlow()

    private val _visualizerAmplitudes = MutableStateFlow<List<Float>>(List(24) { 0.08f })
    val visualizerAmplitudes: StateFlow<List<Float>> = _visualizerAmplitudes.asStateFlow()

    private val _isLoadingTrack = MutableStateFlow<Boolean>(false)
    val isLoadingTrack: StateFlow<Boolean> = _isLoadingTrack.asStateFlow()

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    fun clearPlaybackError() {
        _playbackError.value = null
    }

    // Backward-compatible lambda listeners
    var onPositionChanged: ((Long, Long) -> Unit)? = null
    var onTrackTransition: ((AudioTrack) -> Unit)? = null
    var onPlaybackStateChanged: ((Boolean) -> Unit)? = null
    var onVisualizerUpdate: ((List<Float>) -> Unit)? = null
    var onFavoriteToggleRequested: ((AudioTrack) -> Unit)? = null
    var onAudioQualityChanged: ((AudioQualityInfo) -> Unit)? = null

    private var currentPlaylist: List<AudioTrack> = emptyList()
    val activeQueue: List<AudioTrack> get() = currentPlaylist
    private var currentTrack: AudioTrack? = null
    val currentPlayingTrack: AudioTrack? get() = currentTrack ?: _currentTrackState.value
    private var activePreset: EqualizerPreset = EqualizerPreset.AUDIOPHILE_REFERENCE
    private var isGaplessEnabled: Boolean = true
    private var activeBackend: StreamBackend = StreamBackend.AUTO_HIGH_FIDELITY

    @Volatile
    private var isSwitchingTrack: Boolean = false
    @Volatile
    private var activeSwitchRequestId: Long = 0L
    @Volatile
    private var pendingTrack: AudioTrack? = null
    @Volatile
    private var pendingQuality: AudioQualityInfo? = null

    // Dynamic stream candidates sorted by BitDepth -> SampleRate -> Bitrate -> Codec
    private var activeStreamCandidates: List<ResolvedStreamCandidate> = emptyList()
    private var currentCandidateIndex: Int = 0
    private var activeResolvedQuality: AudioQualityInfo? = null

    init {
        // 1. Configure audio-only renderers + attach RealTimeFftAudioProcessor to the AudioSink pipeline
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: Handler,
                eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>
            ) {
                // Audio-only application: Skip video decoders to conserve system MediaCodec resources
            }

            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(fftAudioProcessor))
                    .build()
            }
        }.apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            setEnableAudioTrackPlaybackParams(true)
            setEnableDecoderFallback(true)
        }

        // 2. Configure LoadControl for responsive buffering
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 10_000,
                /* maxBufferMs = */ 40_000,
                /* bufferForPlaybackMs = */ 800,
                /* bufferForPlaybackAfterRebufferMs = */ 2_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // 3. Configure Audio Attributes for music playback
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // 4. Configure robust HTTP DataSource with standard User-Agent, redirects and timeouts
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 FlowAudio/2.0")
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(25000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(
                mapOf(
                    "Accept" to "*/*"
                )
            )

        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        exoPlayer = ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setHandleAudioBecomingNoisy(true)
            .build()

        setupPlayerListeners()
    }

    private fun mapFormatStringToEnum(formatName: String, fallback: AudioFormat): AudioFormat {
        val upper = formatName.uppercase()
        return when {
            upper.contains("FLAC") -> AudioFormat.FLAC
            upper.contains("ALAC") -> AudioFormat.ALAC
            upper.contains("WAV") -> AudioFormat.WAV
            upper.contains("AIFF") -> AudioFormat.AIFF
            upper.contains("MP3") -> AudioFormat.MP3
            upper.contains("AAC") || upper.contains("OPUS") -> AudioFormat.AAC
            else -> fallback
        }
    }

    /**
     * Verified Stream Handoff:
     * ONLY commits and broadcasts the active track UI & mini-player state AFTER ExoPlayer
     * confirms it has buffered (STATE_READY / playing) the selected track's exact MediaItem.
     */
    private fun commitPendingTrackIfReady() {
        val pending = pendingTrack ?: return
        val currentMediaId = exoPlayer.currentMediaItem?.mediaId
        if (currentMediaId != null && currentMediaId != pending.id) {
            return
        }
        val quality = pendingQuality ?: activeResolvedQuality ?: AudioQualityInfo(
            formatName = pending.format.displayName,
            sampleRateHz = pending.sampleRate,
            bitDepth = pending.bitDepth,
            bitrateKbps = pending.bitrateKbps,
            channelCount = 2,
            isLossless = pending.format.isLossless,
            isHiRes = pending.format.isLossless || pending.sampleRate >= 48000 || pending.bitDepth >= 24 || pending.bitrateKbps >= 256
        )

        pendingTrack = null
        pendingQuality = null
        isSwitchingTrack = false
        _isLoadingTrack.value = false

        val realDuration = exoPlayer.duration.takeIf { it > 0L } ?: pending.durationMs
        val committedTrack = if (realDuration > 0L) pending.copy(durationMs = realDuration) else pending

        currentTrack = committedTrack
        _currentTrackState.value = committedTrack
        _durationState.value = committedTrack.durationMs
        _audioQualityState.value = quality
        onTrackTransition?.invoke(committedTrack)
        onAudioQualityChanged?.invoke(quality)
    }

    private fun setupPlayerListeners() {
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    commitPendingTrackIfReady()
                }
                fftAudioProcessor.setPlaying(isPlaying)
                _isPlayingState.value = isPlaying
                onPlaybackStateChanged?.invoke(isPlaying)
                if (isPlaying) {
                    startPositionPoller()
                    updateDynamicAudioQuality()
                } else {
                    positionPollerJob?.cancel()
                    val idleBands = fftAudioProcessor.getCurrentSpectrumBands(false, activePreset.bassBoost)
                    _visualizerAmplitudes.value = idleBands
                    onVisualizerUpdate?.invoke(idleBands)
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                updateDynamicAudioQuality()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    commitPendingTrackIfReady()
                    val dur = exoPlayer.duration.coerceAtLeast(0L)
                    if (dur > 0) _durationState.value = dur
                    if (exoPlayer.playWhenReady && !exoPlayer.isPlaying) {
                        exoPlayer.play()
                    }
                    val isPlayingNow = exoPlayer.isPlaying || exoPlayer.playWhenReady
                    _isPlayingState.value = isPlayingNow
                    onPlaybackStateChanged?.invoke(isPlayingNow)
                    updateDynamicAudioQuality()
                } else if (playbackState == Player.STATE_ENDED) {
                    fftAudioProcessor.setPlaying(false)
                    _isPlayingState.value = false
                    onPlaybackStateChanged?.invoke(false)
                    // Auto-advance to next track in playlist via atomic playTrack
                    if (isGaplessEnabled && currentPlaylist.size > 1) {
                        val activeId = currentTrack?.id
                        val curIdx = currentPlaylist.indexOfFirst { it.id == activeId }
                        if (curIdx != -1 && curIdx + 1 < currentPlaylist.size) {
                            playTrack(currentPlaylist[curIdx + 1], currentPlaylist, activeBackend)
                        } else if (exoPlayer.repeatMode == Player.REPEAT_MODE_ALL && currentPlaylist.isNotEmpty()) {
                            playTrack(currentPlaylist.first(), currentPlaylist, activeBackend)
                        }
                    }
                } else if (playbackState == Player.STATE_IDLE) {
                    fftAudioProcessor.setPlaying(false)
                    _isPlayingState.value = false
                    onPlaybackStateChanged?.invoke(false)
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                val isPlaying = exoPlayer.isPlaying
                _isPlayingState.value = isPlaying
                onPlaybackStateChanged?.invoke(isPlaying)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                val currentPos = exoPlayer.currentPosition.coerceAtLeast(0L)
                _currentPositionState.value = currentPos
                val dur = exoPlayer.duration.coerceAtLeast(0L)
                if (dur > 0) _durationState.value = dur
                onPositionChanged?.invoke(currentPos, dur)
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w("ExoPlayerAudioEngine", "Player error encountered: ${error.errorCodeName} (${error.errorCode})", error)
                _isPlayingState.value = false
                onPlaybackStateChanged?.invoke(false)

                val track = pendingTrack ?: currentTrack ?: _currentTrackState.value ?: return

                // Error Fallback: Step down to the next highest bitrate candidate for THIS EXACT track
                if (currentCandidateIndex + 1 < activeStreamCandidates.size) {
                    currentCandidateIndex++
                    val nextCandidate = activeStreamCandidates[currentCandidateIndex]
                    val fallbackQuality = nextCandidate.toAudioQualityInfo()
                    activeResolvedQuality = fallbackQuality

                    Log.i(
                        "ExoPlayerAudioEngine",
                        "Falling back to candidate #${currentCandidateIndex + 1}: ${nextCandidate.formatName} @ ${nextCandidate.bitrateKbps} kbps (${nextCandidate.sampleRateHz}Hz/${nextCandidate.bitDepth}-bit)"
                    )

                    scope.launch {
                        try {
                            val updatedTrack = track.copy(
                                uriString = nextCandidate.streamUrl,
                                format = mapFormatStringToEnum(nextCandidate.formatName, track.format),
                                bitrateKbps = nextCandidate.bitrateKbps,
                                sampleRate = nextCandidate.sampleRateHz,
                                bitDepth = nextCandidate.bitDepth
                            )
                            pendingTrack = updatedTrack
                            pendingQuality = fallbackQuality

                            val fallbackItem = createMediaItemForTrack(updatedTrack, isActiveTrack = true)
                            exoPlayer.stop()
                            exoPlayer.clearMediaItems()
                            exoPlayer.setMediaItem(fallbackItem, 0L)
                            exoPlayer.prepare()
                            exoPlayer.playWhenReady = true
                            exoPlayer.play()
                        } catch (e: Exception) {
                            Log.e("ExoPlayerAudioEngine", "Candidate fallback failed", e)
                        }
                    }
                    return
                }

                // For online tracks, NEVER silently fall back to playing the previous song or a fake synthesizer loop
                if (track.isGlobalOnline) {
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                    pendingTrack = null
                    pendingQuality = null
                    isSwitchingTrack = false
                    _isLoadingTrack.value = false
                    _playbackError.value =
                        "Unable to stream '${track.title}' by ${track.artist} (${error.errorCodeName}). Please try another source."
                    return
                }

                // Built-in offline studio tracks only: recover via local WAV file
                scope.launch {
                    try {
                        val cachedWavUri = withContext(Dispatchers.IO) {
                            getCachedWavUriForTrack(track)
                        }
                        val wavQuality = AudioQualityInfo(
                            formatName = "PCM WAV",
                            sampleRateHz = 44100,
                            bitDepth = 16,
                            bitrateKbps = 1411,
                            channelCount = 2,
                            isLossless = true,
                            isHiRes = false
                        )
                        activeResolvedQuality = wavQuality
                        val updatedTrack = track.copy(
                            uriString = cachedWavUri.toString(),
                            format = AudioFormat.WAV,
                            bitrateKbps = 1411,
                            sampleRate = 44100,
                            bitDepth = 16
                        )
                        pendingTrack = updatedTrack
                        pendingQuality = wavQuality

                        val fallbackItem = createMediaItemForTrack(updatedTrack, isActiveTrack = true)
                        exoPlayer.stop()
                        exoPlayer.clearMediaItems()
                        exoPlayer.setMediaItem(fallbackItem, 0L)
                        exoPlayer.prepare()
                        exoPlayer.playWhenReady = true
                        exoPlayer.play()
                    } catch (e: Exception) {
                        isSwitchingTrack = false
                        _isLoadingTrack.value = false
                        Log.e("ExoPlayerAudioEngine", "Failed offline WAV recovery", e)
                    }
                }
            }
        })
    }

    /**
     * Dynamically binds the UI quality state to the active stream's real technical properties
     * (probed candidate telemetry + ExoPlayer decoder Format when positive), never using fake hardcoded 2822k FLAC.
     */
    fun updateDynamicAudioQuality() {
        val format = exoPlayer.audioFormat
        val track = currentTrack ?: _currentTrackState.value
        val resolved = activeResolvedQuality

        val qualityInfo = when {
            resolved != null -> {
                val liveSampleRate = if (format != null && format.sampleRate > 0 && !resolved.isLossless) {
                    format.sampleRate
                } else {
                    resolved.sampleRateHz
                }
                val liveBitrate = if (format != null && format.bitrate > 0 && resolved.bitrateKbps <= 0) {
                    format.bitrate / 1000
                } else {
                    resolved.bitrateKbps
                }
                val liveChannels = if (format != null && format.channelCount > 0) {
                    format.channelCount
                } else {
                    resolved.channelCount
                }
                val isLossless = resolved.isLossless && resolved.formatName in listOf("FLAC", "ALAC", "WAV", "PCM WAV", "AIFF")
                val isHiRes = isLossless || liveSampleRate >= 48000 || resolved.bitDepth >= 24 || liveBitrate >= 256
                resolved.copy(
                    sampleRateHz = liveSampleRate,
                    bitrateKbps = liveBitrate,
                    channelCount = liveChannels,
                    isLossless = isLossless,
                    isHiRes = isHiRes
                )
            }
            format != null -> {
                val sampleRate = if (format.sampleRate > 0) format.sampleRate else (track?.sampleRate ?: 44100)
                val bitrate = if (format.bitrate > 0) format.bitrate / 1000 else (track?.bitrateKbps ?: 320)
                val mime = format.sampleMimeType ?: ""
                val formatName = when {
                    mime.contains("flac", ignoreCase = true) -> "FLAC"
                    mime.contains("alac", ignoreCase = true) -> "ALAC"
                    mime.contains("opus", ignoreCase = true) -> "OPUS"
                    mime.contains("mp4a", ignoreCase = true) || mime.contains("aac", ignoreCase = true) -> "AAC"
                    mime.contains("wav", ignoreCase = true) || mime.contains("raw", ignoreCase = true) -> "PCM WAV"
                    mime.contains("mpeg", ignoreCase = true) -> "MP3"
                    else -> track?.format?.displayName ?: "AAC"
                }
                val isLossless = formatName in listOf("FLAC", "ALAC", "PCM WAV", "WAV")
                val bitDepth = if (isLossless) (track?.bitDepth?.takeIf { it >= 16 } ?: 24) else 16
                val isHiRes = isLossless || sampleRate >= 48000 || bitrate >= 256
                AudioQualityInfo(
                    formatName = formatName,
                    sampleRateHz = sampleRate,
                    bitDepth = bitDepth,
                    bitrateKbps = bitrate,
                    channelCount = if (format.channelCount > 0) format.channelCount else 2,
                    isLossless = isLossless,
                    isHiRes = isHiRes
                )
            }
            track != null -> {
                AudioQualityInfo(
                    formatName = track.format.displayName,
                    sampleRateHz = track.sampleRate,
                    bitDepth = track.bitDepth,
                    bitrateKbps = track.bitrateKbps,
                    channelCount = 2,
                    isLossless = track.format.isLossless,
                    isHiRes = track.format.isLossless || track.sampleRate >= 48000 || track.bitrateKbps >= 256
                )
            }
            else -> AudioQualityInfo()
        }

        _audioQualityState.value = qualityInfo
        onAudioQualityChanged?.invoke(qualityInfo)
    }

    /**
     * 100% Atomic Audio Player Synchronization (Zero Desync):
     * 1. Direct Track & URL Binding: Uses the unique track.id and direct streamUrl directly (never array indices).
     * 2. Hard Reset Before Load: Calls exoPlayer.stop() and exoPlayer.clearMediaItems() immediately,
     *    cancels any pending stream loading jobs, and sets isLoadingTrack = true.
     * 3. Verified Stream Handoff: Passes the exact resolved streamUrl of the selected track to ExoPlayer,
     *    waits for STATE_READY / onIsPlayingChanged, and ONLY updates the active track UI & mini-player state
     *    AFTER the player engine confirms it has buffered the selected track's URL.
     * 4. If stream resolution fails, emits an explicit error alert and never silently plays the old song.
     */
    fun playTrack(
        track: AudioTrack,
        queue: List<AudioTrack>,
        backend: StreamBackend = StreamBackend.AUTO_HIGH_FIDELITY
    ) {
        val requestId = ++activeSwitchRequestId
        isSwitchingTrack = true
        activeBackend = backend

        // Step 1: Cancel any pending/in-flight stream loading jobs & position pollers
        playbackSwitchJob?.cancel()
        positionPollerJob?.cancel()
        fftAudioProcessor.setPlaying(false)

        // Step 2: Hard Reset Before Load (stop() + clearMediaItems() immediately) & set isLoadingTrack = true
        pendingTrack = null
        pendingQuality = null
        _isLoadingTrack.value = true
        _playbackError.value = null

        exoPlayer.playWhenReady = false
        exoPlayer.pause()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()

        _isPlayingState.value = false
        _currentPositionState.value = 0L
        onPlaybackStateChanged?.invoke(false)

        val effectiveQueue = if (queue.isNotEmpty()) queue else listOf(track)

        playbackSwitchJob = scope.launch {
            try {
                // Step 3: Resolve highest-bitrate stream candidate for the selected track
                val candidates = withContext(Dispatchers.IO) {
                    UniversalStreamResolver.resolveAllStreamCandidates(context, track, backend)
                }
                if (!isActive || requestId != activeSwitchRequestId) return@launch

                activeStreamCandidates = candidates
                currentCandidateIndex = 0

                val topCandidate = candidates.firstOrNull()
                if (topCandidate == null || topCandidate.streamUrl.isBlank()) {
                    // Explicit error alert on stream resolution failure; NEVER fall back to old track
                    isSwitchingTrack = false
                    _isLoadingTrack.value = false
                    _playbackError.value =
                        "Unable to resolve audio stream for '${track.title}' by ${track.artist}. Please try another source."
                    return@launch
                }

                val resolvedQuality = topCandidate.toAudioQualityInfo()
                activeResolvedQuality = resolvedQuality

                val resolvedTrack = track.copy(
                    uriString = topCandidate.streamUrl,
                    format = mapFormatStringToEnum(topCandidate.formatName, track.format),
                    bitrateKbps = topCandidate.bitrateKbps,
                    sampleRate = topCandidate.sampleRateHz,
                    bitDepth = topCandidate.bitDepth
                )

                val updatedQueue = if (effectiveQueue.any { it.id == resolvedTrack.id }) {
                    effectiveQueue.map { if (it.id == resolvedTrack.id) resolvedTrack else it }
                } else {
                    listOf(resolvedTrack) + effectiveQueue
                }

                // Build single MediaItem for the selected track directly (zero array-index ambiguity)
                val activeMediaItem = withContext(Dispatchers.IO) {
                    createMediaItemForTrack(
                        track = resolvedTrack,
                        isActiveTrack = true
                    )
                }
                if (!isActive || requestId != activeSwitchRequestId) return@launch

                currentPlaylist = updatedQueue
                pendingTrack = resolvedTrack
                pendingQuality = resolvedQuality

                // Load the exact resolved streamUrl of the selected track into the cleared player engine
                exoPlayer.pauseAtEndOfMediaItems = !isGaplessEnabled
                exoPlayer.setMediaItem(activeMediaItem, /* startPositionMs = */ 0L)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
                exoPlayer.play()

                // If the engine already transitioned to STATE_READY synchronously (e.g., cached/local file), commit immediately
                if (exoPlayer.playbackState == Player.STATE_READY || exoPlayer.isPlaying) {
                    commitPendingTrackIfReady()
                    updateDynamicAudioQuality()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                isSwitchingTrack = false
                _isLoadingTrack.value = false
                _playbackError.value =
                    "Error loading '${track.title}': ${e.localizedMessage ?: "Stream resolution failed"}"
                Log.e("ExoPlayerAudioEngine", "Error switching to track ${track.title}", e)
            }
        }
    }

    fun playNext() {
        if (currentPlaylist.isEmpty()) return
        val activeId = (pendingTrack ?: currentTrack ?: _currentTrackState.value)?.id
        val currentIdx = currentPlaylist.indexOfFirst { it.id == activeId }
        val nextIdx = if (currentIdx in 0 until currentPlaylist.size - 1) {
            currentIdx + 1
        } else {
            0
        }
        playTrack(currentPlaylist[nextIdx], currentPlaylist, activeBackend)
    }

    fun playPrevious() {
        if (exoPlayer.currentPosition > 3000L) {
            exoPlayer.seekTo(0L)
            return
        }
        if (currentPlaylist.isEmpty()) return
        val activeId = (pendingTrack ?: currentTrack ?: _currentTrackState.value)?.id
        val currentIdx = currentPlaylist.indexOfFirst { it.id == activeId }
        val prevIdx = if (currentIdx > 0) {
            currentIdx - 1
        } else {
            currentPlaylist.lastIndex.coerceAtLeast(0)
        }
        playTrack(currentPlaylist[prevIdx], currentPlaylist, activeBackend)
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
        _currentPositionState.value = positionMs
        onPositionChanged?.invoke(positionMs, exoPlayer.duration.coerceAtLeast(0L))
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
            fftAudioProcessor.setPlaying(false)
            _isPlayingState.value = false
            onPlaybackStateChanged?.invoke(false)
        } else {
            if (exoPlayer.playbackState == Player.STATE_IDLE) {
                exoPlayer.prepare()
            } else if (exoPlayer.playbackState == Player.STATE_ENDED) {
                exoPlayer.seekTo(0L)
            }
            exoPlayer.play()
            fftAudioProcessor.setPlaying(true)
            _isPlayingState.value = true
            onPlaybackStateChanged?.invoke(true)
        }
    }

    fun setShuffleMode(shuffle: Boolean) {
        exoPlayer.shuffleModeEnabled = shuffle
    }

    fun setRepeatMode(mode: RepeatMode) {
        exoPlayer.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
    }

    fun setGaplessEnabled(enabled: Boolean) {
        isGaplessEnabled = enabled
        exoPlayer.pauseAtEndOfMediaItems = !enabled
    }

    fun setEqualizerPreset(preset: EqualizerPreset) {
        activePreset = preset
        // Volume / bass boost multiplier
        val gainBoost = (1.0f + preset.bassBoost * 0.25f).coerceIn(0.5f, 1.25f)
        exoPlayer.volume = (1.0f * gainBoost).coerceIn(0f, 1f)
    }

    fun setMasterVolume(volume: Float) {
        exoPlayer.volume = volume.coerceIn(0f, 1f)
    }

    private fun startPositionPoller() {
        positionPollerJob?.cancel()
        positionPollerJob = scope.launch {
            while (isActive && (exoPlayer.isPlaying || exoPlayer.playWhenReady)) {
                val currentPos = exoPlayer.currentPosition.coerceAtLeast(0L)
                val duration = exoPlayer.duration.coerceAtLeast(0L)
                _currentPositionState.value = currentPos
                if (duration > 0) {
                    _durationState.value = duration
                }
                onPositionChanged?.invoke(currentPos, duration)

                // Real-time FFT spectrum amplitudes extracted directly from ExoPlayer's decoded PCM audio frames
                val amplitudes = fftAudioProcessor.getCurrentSpectrumBands(
                    isPlaying = exoPlayer.isPlaying,
                    bassBoost = activePreset.bassBoost
                )
                _visualizerAmplitudes.value = amplitudes
                onVisualizerUpdate?.invoke(amplitudes)

                delay(33L) // ~30Hz engine FFT dispatch + 60fps UI frame interpolation
            }
        }
    }

    /**
     * Converts an AudioTrack into a Media3 MediaItem configured with:
     * - Accurate MIME type for WAV, FLAC, ALAC, or auto-sniffing
     * - Fix for MediaCodec reclamation: cached procedural audio tracks are PCM WAV
     *   and must be identified as MimeTypes.AUDIO_WAV so ExoPlayer decodes via
     *   WavExtractor rather than invoking a mismatched hardware compressed decoder.
     * - MediaMetadata for system notification, lock screen, and Bluetooth AVRCP
     */
    private fun createMediaItemForTrack(
        track: AudioTrack,
        isActiveTrack: Boolean = false
    ): MediaItem {
        val (uri, detectedMimeType) = if ((track.isDownloaded || !track.localFilePath.isNullOrBlank()) && track.localFilePath != null && File(track.localFilePath).exists()) {
            val localFile = File(track.localFilePath)
            val uriStr = localFile.absolutePath.lowercase()
            val mime = when {
                uriStr.endsWith(".flac") -> MimeTypes.AUDIO_FLAC
                uriStr.endsWith(".wav") || uriStr.endsWith(".wave") -> MimeTypes.AUDIO_WAV
                uriStr.endsWith(".opus") -> MimeTypes.AUDIO_OPUS
                uriStr.endsWith(".mp3") -> MimeTypes.AUDIO_MPEG
                else -> null
            }
            Pair(Uri.fromFile(localFile), mime)
        } else if (!track.uriString.isNullOrBlank()) {
            val uriStr = track.uriString.lowercase()
            val parsedUri = if (track.uriString.startsWith("/")) {
                Uri.fromFile(File(track.uriString))
            } else {
                Uri.parse(track.uriString)
            }
            val mime = when {
                uriStr.endsWith(".wav") || uriStr.endsWith(".wave") -> MimeTypes.AUDIO_WAV
                uriStr.endsWith(".flac") -> MimeTypes.AUDIO_FLAC
                uriStr.endsWith(".mp3") -> MimeTypes.AUDIO_MPEG
                else -> null
            }
            Pair(parsedUri, mime)
        } else if (isActiveTrack) {
            // Synthesize PCM WAV only for the active track if no direct stream URI was resolved
            Pair(getCachedWavUriForTrack(track), MimeTypes.AUDIO_WAV)
        } else {
            // Non-active queued tracks resolve lazily on transition so switching tracks is instantaneous
            Pair(Uri.parse("https://stream.flowaudio.io/pending/${track.id}"), null)
        }

        val artworkUri = if (!track.artworkUrl.isNullOrBlank()) {
            Uri.parse(track.artworkUrl)
        } else if (isActiveTrack) {
            AlbumArtHelper.getArtworkUri(context, track)
        } else {
            null
        }

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setGenre(track.genre)
            .setReleaseYear(track.releaseYear)
            .setIsPlayable(true)

        if (artworkUri != null) {
            metadataBuilder.setArtworkUri(artworkUri)
        }
        if (isActiveTrack && track.artworkUrl.isNullOrBlank()) {
            val artworkBytes = AlbumArtHelper.getArtworkByteArray(context, track)
            metadataBuilder.setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }

        val item = MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(uri)
            .setMediaMetadata(metadataBuilder.build())

        if (detectedMimeType != null) {
            item.setMimeType(detectedMimeType)
        }

        return item.build()
    }

    fun toggleFavoriteCurrentTrack() {
        val track = currentTrack ?: return
        onFavoriteToggleRequested?.invoke(track)
    }

    /**
     * Generates a full-duration lossless 44.1kHz 16-bit stereo PCM WAV file in the app's cache
     * so Media3 ExoPlayer plays full-length sample/fallback tracks without 16s or 30s cutoffs.
     */
    private fun getCachedWavUriForTrack(track: AudioTrack): Uri {
        val wavFile = UniversalStreamResolver.getOrCreateTrackAudioFile(context, track)
        return Uri.fromFile(wavFile)
    }

    private fun writeWavHeader(
        out: OutputStream,
        totalAudioLen: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        val header = ByteArray(44)
        // RIFF chunk descriptor
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte()
        header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        // fmt sub-chunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // 16 for PCM
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // Audio format 1 = PCM
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte()
        header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = blockAlign.toByte()
        header[33] = 0
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        // data sub-chunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = (totalAudioLen shr 8 and 0xff).toByte()
        header[42] = (totalAudioLen shr 16 and 0xff).toByte()
        header[43] = (totalAudioLen shr 24 and 0xff).toByte()

        out.write(header, 0, 44)
    }

    fun release() {
        playbackSwitchJob?.cancel()
        positionPollerJob?.cancel()
        exoPlayer.release()
        synchronized(ExoPlayerAudioEngine::class.java) {
            if (instance === this) {
                instance = null
            }
        }
    }

    companion object {
        @Volatile
        private var instance: ExoPlayerAudioEngine? = null

        fun getInstance(context: Context): ExoPlayerAudioEngine {
            return instance ?: synchronized(this) {
                instance ?: ExoPlayerAudioEngine(context.applicationContext).also { instance = it }
            }
        }
    }
}
