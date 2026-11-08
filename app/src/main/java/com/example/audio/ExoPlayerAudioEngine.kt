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
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.example.model.AudioFormat
import com.example.model.AudioQualityInfo
import com.example.model.AudioTrack
import com.example.model.EqualizerPreset
import com.example.model.RepeatMode
import com.example.model.StreamBackend
import com.example.network.MultiProviderStreamResolver
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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap

/**
 * High-Fidelity Media3 ExoPlayer Audio Engine configured with:
 * 1. Singleton 200MB LRU Disk Cache (SimpleCache + LeastRecentlyUsedCacheEvictor + CacheDataSource.Factory)
 *    for instant zero-latency replay and reduced network load.
 * 2. Strengthened `DefaultHttpDataSource.Factory` (25s timeouts, desktop Chrome User-Agent,
 *    `Range: bytes=0-`, `Referer: https://www.youtube.com/`, `Origin: https://www.youtube.com`).
 * 3. Custom `DefaultLoadControl` (`minBufferMs = 30000`, `maxBufferMs = 90000`,
 *    `bufferForPlaybackMs = 2500`, `bufferForPlaybackAfterRebufferMs = 5000`).
 * 4. Multi-Provider Auto-Retry on `HttpDataSourceException` (403 Forbidden / server reset) via `MultiProviderStreamResolver`.
 * 5. High-frequency 50ms playback position polling for lock-step synchronized lyrics.
 */
@OptIn(UnstableApi::class)
class ExoPlayerAudioEngine(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var positionPollerJob: Job? = null
    private var playbackSwitchJob: Job? = null
    private var prebufferJob: Job? = null

    private val fftAudioProcessor = RealTimeFftAudioProcessor()
    private val cacheDataSourceFactory: CacheDataSource.Factory
    val exoPlayer: ExoPlayer

    // Track IDs that have already been pre-buffered during the current session
    private val prebufferedTrackIds = ConcurrentHashMap.newKeySet<String>()

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

    private val _availableStreamCandidates = MutableStateFlow<List<ResolvedStreamCandidate>>(emptyList())
    val availableStreamCandidates: StateFlow<List<ResolvedStreamCandidate>> = _availableStreamCandidates.asStateFlow()

    private val _activeStreamCandidate = MutableStateFlow<ResolvedStreamCandidate?>(null)
    val activeStreamCandidate: StateFlow<ResolvedStreamCandidate?> = _activeStreamCandidate.asStateFlow()

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
    var onQueueNearEnd: (() -> Unit)? = null

    private var currentPlaylist: List<AudioTrack> = emptyList()
    val activeQueue: List<AudioTrack> get() = currentPlaylist
    fun updateQueue(newQueue: List<AudioTrack>) {
        currentPlaylist = newQueue
    }
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
    @Volatile
    private var isCascadingFallback: Boolean = false

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

        // 2. Configure Custom DefaultLoadControl for Audio (30s minBuffer, 90s maxBuffer, 2.5s start, 5s rebuffer)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30000,
                /* maxBufferMs = */ 90000,
                /* bufferForPlaybackMs = */ 2500,
                /* bufferForPlaybackAfterRebufferMs = */ 5000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // 3. Configure Audio Attributes for music playback
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // 4. Configure HTTP DataSource with 25s timeouts, desktop Chrome User-Agent & YouTube streaming headers
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(25000)
            .setReadTimeoutMs(25000)
            .setDefaultRequestProperties(
                mapOf(
                    "Range" to "bytes=0-",
                    "Referer" to "https://www.youtube.com/",
                    "Origin" to "https://www.youtube.com"
                )
            )

        val upstreamDataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val simpleCache = getOrCreateSimpleCache(context)

        cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(upstreamDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(cacheDataSourceFactory)

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
     * Verified Stream Handoff & Preview Guard:
     * 1. Verifies the playing MediaItem's mediaId matches the pending track via AudioPlayerService.
     * 2. Rejects 30-second preview clips (< 60s when expected >= 60s) and cascades to the next provider.
     * 3. Commits and broadcasts the active track UI, mini-player, and notification state immediately on preparation.
     */
    private fun commitPendingTrackIfReady() {
        val pending = pendingTrack ?: return
        if (!AudioPlayerService.isPlayerBoundToTrack(exoPlayer, pending.id)) {
            return
        }

        val playerDurationMs = exoPlayer.duration
        if (
            playerDurationMs in 1_000L..59_999L &&
            (pending.durationMs >= 60_000L || pending.durationMs == 0L) &&
            !pending.isLocalImport &&
            !pending.isDownloaded &&
            currentCandidateIndex + 1 < activeStreamCandidates.size
        ) {
            Log.w(
                "ExoPlayerAudioEngine",
                "Rejected <60s snippet preview (${playerDurationMs}ms vs expected ${pending.durationMs}ms). Cascading."
            )
            triggerCandidateFallback("PreviewClipRejected")
            return
        }

        val realDuration = playerDurationMs.takeIf { it > 0L } ?: pending.durationMs
        val committedTrack = if (realDuration > 0L) pending.copy(durationMs = realDuration) else pending

        val quality = AudioMetadataProber.extractRealTimeQuality(
            exoFormat = exoPlayer.audioFormat,
            track = committedTrack,
            probedQuality = pendingQuality ?: activeResolvedQuality
        )

        pendingTrack = null
        pendingQuality = null
        isCascadingFallback = false
        isSwitchingTrack = false
        _isLoadingTrack.value = false

        currentTrack = committedTrack
        _currentTrackState.value = committedTrack
        _durationState.value = committedTrack.durationMs
        _audioQualityState.value = quality
        onTrackTransition?.invoke(committedTrack)
        onAudioQualityChanged?.invoke(quality)

        // Deep probe container header in background to refine exact bitrate/sampleRate if ExoPlayer Format.bitrate is unset
        val streamUrl = committedTrack.effectiveStreamUrl
        if (!streamUrl.isNullOrBlank()) {
            scope.launch {
                val probed = AudioPlayerService.probeStreamContainer(
                    url = streamUrl,
                    durationMs = committedTrack.durationMs,
                    fallbackFormat = quality.formatName,
                    fallbackBitrate = quality.bitrateKbps,
                    fallbackSampleRate = quality.sampleRateHz,
                    fallbackBitDepth = quality.bitDepth
                )
                if (currentTrack?.id == committedTrack.id) {
                    activeResolvedQuality = probed
                    val merged = AudioMetadataProber.extractRealTimeQuality(
                        exoFormat = exoPlayer.audioFormat,
                        track = committedTrack,
                        probedQuality = probed
                    )
                    _audioQualityState.value = merged
                    onAudioQualityChanged?.invoke(merged)
                }
            }
        }

        val curIdx = currentPlaylist.indexOfFirst { it.id == committedTrack.id }
        if (curIdx != -1 && currentPlaylist.size - (curIdx + 1) <= 3) {
            onQueueNearEnd?.invoke()
        }
    }

    /**
     * Cascades to the next highest-quality stream candidate or invokes `MultiProviderStreamResolver`
     * when a candidate fails (e.g., HTTP 403 Forbidden / `HttpDataSourceException`) or is a preview clip.
     */
    private fun triggerCandidateFallback(reason: String) {
        if (isCascadingFallback) return
        val track = pendingTrack ?: currentTrack ?: _currentTrackState.value ?: return
        isCascadingFallback = true

        val failedUrl = track.effectiveStreamUrl.orEmpty()
        val videoId = MultiProviderStreamResolver.extractCleanVideoId(track.id)

        if (failedUrl.isNotEmpty()) {
            MultiProviderStreamResolver.markUrlFailed(videoId.ifEmpty { track.id }, failedUrl)
        }

        if (currentCandidateIndex + 1 < activeStreamCandidates.size) {
            currentCandidateIndex++
            val nextCandidate = activeStreamCandidates[currentCandidateIndex]
            val fallbackQuality = nextCandidate.toAudioQualityInfo()
            activeResolvedQuality = fallbackQuality
            _activeStreamCandidate.value = nextCandidate

            Log.i(
                "ExoPlayerAudioEngine",
                "Cascading after ($reason) to candidate #${currentCandidateIndex + 1}: ${nextCandidate.formatName} @ ${nextCandidate.bitrateKbps} kbps (${nextCandidate.sampleRateHz}Hz/${nextCandidate.bitDepth}-bit)"
            )

            scope.launch {
                try {
                    val updatedTrack = track.copy(
                        uriString = nextCandidate.directStreamUrl,
                        streamUrl = nextCandidate.directStreamUrl,
                        format = mapFormatStringToEnum(nextCandidate.formatName, track.format),
                        bitrateKbps = nextCandidate.bitrateKbps,
                        sampleRate = nextCandidate.sampleRateHz,
                        bitDepth = nextCandidate.bitDepth,
                        sourceProvider = nextCandidate.providerName
                    )
                    pendingTrack = updatedTrack
                    pendingQuality = fallbackQuality

                    AudioPlayerService.switchStreamAtomically(
                        exoPlayer = exoPlayer,
                        context = context,
                        track = updatedTrack,
                        candidate = nextCandidate,
                        preservePosition = true,
                        scope = scope,
                        onStateVerifiedReady = { _, _ -> commitPendingTrackIfReady() }
                    )
                } catch (e: Exception) {
                    Log.e("ExoPlayerAudioEngine", "Candidate fallback failed", e)
                } finally {
                    isCascadingFallback = false
                }
            }
            return
        }

        // Query MultiProviderStreamResolver for the next provider's stream URL (skipping failed URLs)
        scope.launch {
            try {
                val excludedSet = activeStreamCandidates.map { it.directStreamUrl }.toSet() + setOf(failedUrl)
                val nextProviderCandidate = withContext(Dispatchers.IO) {
                    MultiProviderStreamResolver.resolveBestStreamCandidate(
                        videoId = videoId,
                        track = track,
                        excludedUrls = excludedSet
                    ).getOrNull()
                }

                if (nextProviderCandidate != null &&
                    nextProviderCandidate.directStreamUrl.isNotBlank() &&
                    nextProviderCandidate.directStreamUrl !in excludedSet
                ) {
                    activeStreamCandidates = activeStreamCandidates + nextProviderCandidate
                    currentCandidateIndex = activeStreamCandidates.lastIndex
                    val fallbackQuality = nextProviderCandidate.toAudioQualityInfo()
                    activeResolvedQuality = fallbackQuality
                    _activeStreamCandidate.value = nextProviderCandidate

                    val updatedTrack = track.copy(
                        uriString = nextProviderCandidate.directStreamUrl,
                        streamUrl = nextProviderCandidate.directStreamUrl,
                        format = mapFormatStringToEnum(nextProviderCandidate.formatName, track.format),
                        bitrateKbps = nextProviderCandidate.bitrateKbps,
                        sampleRate = nextProviderCandidate.sampleRateHz,
                        bitDepth = nextProviderCandidate.bitDepth,
                        sourceProvider = nextProviderCandidate.providerName
                    )
                    pendingTrack = updatedTrack
                    pendingQuality = fallbackQuality

                    AudioPlayerService.switchStreamAtomically(
                        exoPlayer = exoPlayer,
                        context = context,
                        track = updatedTrack,
                        candidate = nextProviderCandidate,
                        preservePosition = true,
                        scope = scope,
                        onStateVerifiedReady = { _, _ -> commitPendingTrackIfReady() }
                    )
                    return@launch
                }

                val cdnFallback = UniversalStreamResolver.resolveCanonicalCdnFallback(track)
                if (cdnFallback.directStreamUrl.isNotBlank() && cdnFallback.directStreamUrl !in excludedSet) {
                    activeStreamCandidates = activeStreamCandidates + cdnFallback
                    currentCandidateIndex = activeStreamCandidates.lastIndex
                    val fallbackQuality = cdnFallback.toAudioQualityInfo()
                    activeResolvedQuality = fallbackQuality
                    _activeStreamCandidate.value = cdnFallback

                    val updatedTrack = track.copy(
                        uriString = cdnFallback.directStreamUrl,
                        streamUrl = cdnFallback.directStreamUrl,
                        format = mapFormatStringToEnum(cdnFallback.formatName, track.format),
                        bitrateKbps = cdnFallback.bitrateKbps,
                        sampleRate = cdnFallback.sampleRateHz,
                        bitDepth = cdnFallback.bitDepth,
                        sourceProvider = cdnFallback.providerName
                    )
                    pendingTrack = updatedTrack
                    pendingQuality = fallbackQuality

                    AudioPlayerService.switchStreamAtomically(
                        exoPlayer = exoPlayer,
                        context = context,
                        track = updatedTrack,
                        candidate = cdnFallback,
                        preservePosition = false,
                        scope = scope,
                        onStateVerifiedReady = { _, _ -> commitPendingTrackIfReady() }
                    )
                    return@launch
                }

                // Online candidates exhausted
                isSwitchingTrack = false
                _isLoadingTrack.value = false
                _playbackError.value = "Playback unavailable for '${track.title}'. Stream could not be reached."
                Log.e("ExoPlayerAudioEngine", "All stream candidates exhausted for track: ${track.title} ($reason)")
            } finally {
                isCascadingFallback = false
            }
        }
    }

    private fun setupPlayerListeners() {
        // Bind AudioMetadataProber directly to ExoPlayer's onTracksChanged & onPlaybackParametersChanged
        val formatProberListener = AudioMetadataProber.createExoPlayerFormatListener(
            exoPlayerProvider = { exoPlayer },
            activeTrackProvider = { currentTrack ?: _currentTrackState.value },
            probedQualityProvider = { activeResolvedQuality },
            onQualityExtracted = { qualityInfo ->
                _audioQualityState.value = qualityInfo
                onAudioQualityChanged?.invoke(qualityInfo)
            }
        )
        exoPlayer.addListener(formatProberListener)

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
                if (exoPlayer.playbackState == Player.STATE_READY) {
                    updateDynamicAudioQuality()
                }
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                if (exoPlayer.playbackState == Player.STATE_READY) {
                    updateDynamicAudioQuality()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_BUFFERING) {
                    val pending = pendingTrack
                    if (pending != null && AudioPlayerService.isPlayerBoundToTrack(exoPlayer, pending.id)) {
                        _isLoadingTrack.value = true
                    }
                } else if (playbackState == Player.STATE_READY) {
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
                    if (isGaplessEnabled) {
                        playNext()
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
                val isHttpSourceError = isHttpDataSourceException(error)
                Log.w(
                    "ExoPlayerAudioEngine",
                    "Player error encountered (isHttpDataSourceException=$isHttpSourceError): ${error.errorCodeName} (${error.errorCode})",
                    error
                )
                _isPlayingState.value = false
                onPlaybackStateChanged?.invoke(false)

                if (isHttpSourceError) {
                    val active = pendingTrack ?: currentTrack ?: _currentTrackState.value
                    val videoId = MultiProviderStreamResolver.extractCleanVideoId(active?.id.orEmpty())
                    val failedUrl = active?.effectiveStreamUrl.orEmpty()
                    if (videoId.isNotEmpty() && failedUrl.isNotEmpty()) {
                        MultiProviderStreamResolver.markUrlFailed(videoId, failedUrl)
                    }
                }
                triggerCandidateFallback(error.errorCodeName)
            }
        })
    }

    private fun isHttpDataSourceException(error: PlaybackException): Boolean {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is HttpDataSource.HttpDataSourceException) {
                return true
            }
            cause = cause.cause
        }
        return error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
    }

    /**
     * Dynamically binds the UI quality state to the active stream's real technical properties via AudioMetadataProber.
     * Only emits when ExoPlayer is in STATE_READY with a valid format to prevent codec/bitrate flickering.
     */
    fun updateDynamicAudioQuality() {
        val format = exoPlayer.audioFormat ?: return
        if (exoPlayer.playbackState != Player.STATE_READY) return
        val track = currentTrack ?: _currentTrackState.value
        val resolved = activeResolvedQuality

        val qualityInfo = AudioMetadataProber.extractRealTimeQuality(
            exoFormat = format,
            track = track,
            probedQuality = resolved
        )

        if (_audioQualityState.value != qualityInfo) {
            _audioQualityState.value = qualityInfo
            onAudioQualityChanged?.invoke(qualityInfo)
        }
    }

    /**
     * Pre-Buffering Pipeline:
     * Triggered when the active track playback exceeds 80% progress.
     * Asynchronously resolves the next track in the queue and pre-fetches its first 2MB
     * into the 200MB SimpleCache for instant (<150ms) gapless transition.
     */
    private fun triggerNextTrackPrebufferIfNeeded(currentPositionMs: Long, durationMs: Long) {
        if (durationMs <= 15_000L || currentPositionMs < (durationMs * 0.80).toLong()) return
        if (currentPlaylist.size <= 1) {
            onQueueNearEnd?.invoke()
            return
        }

        val activeId = (currentTrack ?: _currentTrackState.value)?.id ?: return
        val curIdx = currentPlaylist.indexOfFirst { it.id == activeId }
        val nextIdx = if (curIdx != -1 && curIdx + 1 < currentPlaylist.size) {
            curIdx + 1
        } else if (exoPlayer.repeatMode == Player.REPEAT_MODE_ALL) {
            0
        } else {
            return
        }

        val nextTrack = currentPlaylist.getOrNull(nextIdx) ?: return
        if (!prebufferedTrackIds.add(nextTrack.id)) return

        prebufferJob?.cancel()
        prebufferJob = scope.launch(Dispatchers.IO) {
            try {
                val videoId = MultiProviderStreamResolver.extractCleanVideoId(nextTrack.id)
                val bestCandidate = if (videoId.isNotEmpty()) {
                    MultiProviderStreamResolver.resolveBestStreamCandidate(videoId, nextTrack).getOrNull()
                } else {
                    withTimeoutOrNull(AudioPlayerService.STREAM_RESOLUTION_TIMEOUT_MS) {
                        UniversalStreamResolver.resolvePrimaryStream(
                            context = context,
                            track = nextTrack,
                            backend = activeBackend
                        )
                    }
                } ?: return@launch

                val streamUrl = bestCandidate.directStreamUrl.ifBlank { bestCandidate.streamUrl }
                if (streamUrl.isBlank() || streamUrl.startsWith("/") || streamUrl.startsWith("file://")) {
                    return@launch
                }

                // Pre-fetch and warm the first 2MB (2 * 1024 * 1024 bytes) into SimpleCache
                val dataSpec = DataSpec.Builder()
                    .setUri(Uri.parse(streamUrl))
                    .setPosition(0L)
                    .setLength(PREBUFFER_BYTES_LIMIT)
                    .build()

                val cacheDataSource = cacheDataSourceFactory.createDataSource()
                val cacheWriter = CacheWriter(
                    cacheDataSource,
                    dataSpec,
                    ByteArray(64 * 1024)
                ) { _, bytesCached, _ ->
                    if (!isActive || bytesCached >= PREBUFFER_BYTES_LIMIT) {
                        throw CancellationException("Pre-buffer quota reached")
                    }
                }
                runCatching { cacheWriter.cache() }
                Log.d("ExoPlayerAudioEngine", "Pre-buffered first 2MB for upcoming track: ${nextTrack.title}")
            } catch (_: CancellationException) {
                // Quota reached or cancelled cleanly
            } catch (e: Exception) {
                Log.d("ExoPlayerAudioEngine", "Pre-buffer skipped for ${nextTrack.title}: ${e.message}")
            }
        }
    }

    /**
     * Click-to-Play Pipeline with Multi-Provider Stream Resolution:
     * 1. Immediate Atomic Reset: Synchronously calls exoPlayer.stop() and exoPlayer.clearMediaItems(),
     *    updates _currentTrackState = track immediately (<50ms), and sets _isLoadingTrack = true.
     * 2. Executes MultiProviderStreamResolver cascade (IOS -> ANDROID_MUSIC -> Piped -> Invidious)
     *    or UniversalStreamResolver fallback.
     * 3. Feeds the resolved stream URI directly to ExoPlayer without synchronous container probing.
     */
    fun playTrack(
        track: AudioTrack,
        queue: List<AudioTrack>,
        backend: StreamBackend = StreamBackend.AUTO_HIGH_FIDELITY
    ) {
        val requestId = ++activeSwitchRequestId
        isSwitchingTrack = true
        activeBackend = backend

        playbackSwitchJob?.cancel()
        positionPollerJob?.cancel()
        fftAudioProcessor.setPlaying(false)
        AudioMetadataProber.resetSession()

        val effectiveQueue = if (queue.isNotEmpty()) queue else listOf(track)
        currentPlaylist = effectiveQueue
        currentTrack = track
        pendingTrack = track
        pendingQuality = null

        exoPlayer.pauseAtEndOfMediaItems = !isGaplessEnabled

        playbackSwitchJob = AudioPlayerService.resolveAndPlayTrackNonBlocking(
            scope = scope,
            exoPlayer = exoPlayer,
            context = context,
            track = track,
            backend = backend,
            onImmediateTrackSelected = { selectedTrack ->
                _currentTrackState.value = selectedTrack
                _durationState.value = selectedTrack.durationMs
                _currentPositionState.value = 0L
                _isPlayingState.value = false
                _isLoadingTrack.value = true
                _playbackError.value = null
                onTrackTransition?.invoke(selectedTrack)
            },
            onCandidatesResolved = { candidates, primaryCandidate ->
                if (requestId == activeSwitchRequestId) {
                    activeStreamCandidates = candidates
                    _availableStreamCandidates.value = candidates
                    _activeStreamCandidate.value = primaryCandidate
                    currentCandidateIndex = 0
                    activeResolvedQuality = primaryCandidate.toAudioQualityInfo()

                    val resolvedUrl = primaryCandidate.directStreamUrl.ifBlank { primaryCandidate.streamUrl }
                    val updatedTrack = track.copy(
                        uriString = resolvedUrl,
                        streamUrl = resolvedUrl,
                        format = mapFormatStringToEnum(primaryCandidate.formatName, track.format),
                        bitrateKbps = primaryCandidate.bitrateKbps,
                        sampleRate = primaryCandidate.sampleRateHz,
                        bitDepth = primaryCandidate.bitDepth,
                        sourceProvider = primaryCandidate.providerName
                    )
                    currentTrack = updatedTrack
                    _currentTrackState.value = updatedTrack
                    pendingTrack = updatedTrack
                    pendingQuality = primaryCandidate.toAudioQualityInfo()
                }
            },
            onStateVerifiedReady = { _, _ ->
                if (requestId == activeSwitchRequestId) {
                    commitPendingTrackIfReady()
                }
            },
            onError = { errorMsg ->
                if (requestId == activeSwitchRequestId) {
                    isSwitchingTrack = false
                    _isLoadingTrack.value = false
                    _playbackError.value = errorMsg
                }
            }
        )
    }

    /**
     * Real stream switching from picker pill:
     * Plays the exact directStreamUrl for the selected candidate while preserving position.
     */
    fun selectStreamCandidate(candidate: ResolvedStreamCandidate) {
        if (activeStreamCandidates.isEmpty()) return
        val idx = activeStreamCandidates.indexOfFirst { it.streamUrl == candidate.streamUrl }
        if (idx != -1) {
            currentCandidateIndex = idx
        }
        _activeStreamCandidate.value = candidate
        val current = currentPlayingTrack ?: return

        val switchedTrack = current.copy(
            uriString = candidate.directStreamUrl,
            streamUrl = candidate.directStreamUrl,
            format = mapFormatStringToEnum(candidate.formatName, current.format),
            bitrateKbps = candidate.bitrateKbps,
            sampleRate = candidate.sampleRateHz,
            bitDepth = candidate.bitDepth,
            sourceProvider = candidate.providerName
        )
        pendingTrack = switchedTrack
        pendingQuality = candidate.toAudioQualityInfo()
        activeResolvedQuality = candidate.toAudioQualityInfo()

        AudioPlayerService.switchStreamAtomically(
            exoPlayer = exoPlayer,
            context = context,
            track = switchedTrack,
            candidate = candidate,
            preservePosition = true,
            scope = scope,
            onStateVerifiedReady = { _, _ -> commitPendingTrackIfReady() }
        )
    }

    /**
     * Cycles through available real stream candidates.
     */
    fun cycleStreamCandidate() {
        if (activeStreamCandidates.size <= 1) return
        currentCandidateIndex = (currentCandidateIndex + 1) % activeStreamCandidates.size
        val nextCandidate = activeStreamCandidates[currentCandidateIndex]
        selectStreamCandidate(nextCandidate)
    }

    fun playNext() {
        val activeTrack = pendingTrack ?: currentTrack ?: _currentTrackState.value ?: return
        val currentIdx = currentPlaylist.indexOfFirst { it.id == activeTrack.id }

        if (currentPlaylist.size > 1 && currentIdx in 0 until currentPlaylist.size - 1) {
            val nextTrack = currentPlaylist[currentIdx + 1]
            playTrack(nextTrack, currentPlaylist, activeBackend)
            if (currentPlaylist.size - (currentIdx + 2) <= 2) {
                onQueueNearEnd?.invoke()
            }
            return
        }

        if (currentPlaylist.size > 1 && exoPlayer.repeatMode == Player.REPEAT_MODE_ALL) {
            playTrack(currentPlaylist.first(), currentPlaylist, activeBackend)
            return
        }

        // Queue has only 1 song or reached the end: dynamically fetch related songs via TrackRadioRepository
        onQueueNearEnd?.invoke()
        scope.launch {
            _isLoadingTrack.value = true
            val radioTracks = withContext(Dispatchers.IO) {
                runCatching {
                    com.example.network.TrackRadioRepository.generateTrackRadio(
                        seedTrack = activeTrack,
                        limit = 10,
                        excludedTrackIds = currentPlaylist.map { it.id }.toSet()
                    )
                }.getOrNull().orEmpty()
            }
            if (radioTracks.isNotEmpty()) {
                val updatedQueue = (currentPlaylist + radioTracks).distinctBy { it.id }
                currentPlaylist = updatedQueue
                val nextRadioTrack = radioTracks.first()
                playTrack(nextRadioTrack, updatedQueue, activeBackend)
            } else if (currentPlaylist.isNotEmpty()) {
                val fallbackNext = currentPlaylist.first()
                playTrack(fallbackNext, currentPlaylist, activeBackend)
            } else {
                _isLoadingTrack.value = false
            }
        }
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
        val gainBoost = (1.0f + preset.bassBoost * 0.25f).coerceIn(0.5f, 1.25f)
        exoPlayer.volume = (1.0f * gainBoost).coerceIn(0f, 1f)
    }

    fun setMasterVolume(volume: Float) {
        exoPlayer.volume = volume.coerceIn(0f, 1f)
    }

    /**
     * Step 3.1: High-Frequency Polling (50ms) while playing so lyrics highlighting keeps lock-step with vocals.
     */
    private fun startPositionPoller() {
        positionPollerJob?.cancel()
        positionPollerJob = scope.launch {
            while (isActive && (exoPlayer.isPlaying || exoPlayer.playWhenReady)) {
                val currentPos = exoPlayer.currentPosition.coerceAtLeast(0L)
                val duration = exoPlayer.duration.coerceAtLeast(0L)
                _currentPositionState.value = currentPos
                if (duration > 0) {
                    _durationState.value = duration
                    // Check 80% progress threshold to pre-buffer upcoming track's first 2MB
                    triggerNextTrackPrebufferIfNeeded(currentPos, duration)
                }
                onPositionChanged?.invoke(currentPos, duration)

                val amplitudes = fftAudioProcessor.getCurrentSpectrumBands(
                    isPlaying = exoPlayer.isPlaying,
                    bassBoost = activePreset.bassBoost
                )
                _visualizerAmplitudes.value = amplitudes
                onVisualizerUpdate?.invoke(amplitudes)

                delay(50L)
            }
        }
    }

    fun toggleFavoriteCurrentTrack() {
        val track = currentTrack ?: return
        onFavoriteToggleRequested?.invoke(track)
    }

    fun release() {
        playbackSwitchJob?.cancel()
        positionPollerJob?.cancel()
        prebufferJob?.cancel()
        exoPlayer.release()
        synchronized(ExoPlayerAudioEngine::class.java) {
            if (instance === this) {
                instance = null
            }
        }
    }

    companion object {
        private const val MAX_DISK_CACHE_BYTES = 200L * 1024L * 1024L // 200 MB LRU Disk Cache
        private const val PREBUFFER_BYTES_LIMIT = 2L * 1024L * 1024L  // 2 MB Upcoming Track Pre-Buffer

        @Volatile
        private var instance: ExoPlayerAudioEngine? = null

        @Volatile
        private var sharedSimpleCache: SimpleCache? = null

        @Synchronized
        fun getOrCreateSimpleCache(context: Context): SimpleCache {
            sharedSimpleCache?.let { return it }
            val cacheDir = File(context.applicationContext.cacheDir, "flow_exoplayer_lru_cache")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            val evictor = LeastRecentlyUsedCacheEvictor(MAX_DISK_CACHE_BYTES)
            val databaseProvider = StandaloneDatabaseProvider(context.applicationContext)
            return SimpleCache(cacheDir, evictor, databaseProvider).also {
                sharedSimpleCache = it
            }
        }

        fun getInstance(context: Context): ExoPlayerAudioEngine {
            return instance ?: synchronized(this) {
                instance ?: ExoPlayerAudioEngine(context.applicationContext).also { instance = it }
            }
        }
    }
}
