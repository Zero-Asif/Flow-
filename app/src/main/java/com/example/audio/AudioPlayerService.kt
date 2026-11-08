package com.example.audio

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.model.AudioQualityInfo
import com.example.model.AudioTrack
import com.example.model.StreamBackend
import com.example.network.MultiProviderStreamResolver
import com.example.network.ResolvedStreamCandidate
import com.example.network.UniversalStreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Atomic Stream Handshake, Multi-Provider Fallback Coordinator, and HTTP DataSource Builder for Media3 ExoPlayer.
 *
 * Implements:
 * 1. Strengthened `DefaultHttpDataSource.Factory` (25s connect/read timeouts, desktop Chrome User-Agent,
 *    `Range: bytes=0-`, `Referer: https://www.youtube.com/`, `Origin: https://www.youtube.com`).
 * 2. Resilient `DefaultLoadControl` (`minBufferMs = 30000`, `maxBufferMs = 90000`, `bufferForPlaybackMs = 2500`,
 *    `bufferForPlaybackAfterRebufferMs = 5000`).
 * 3. Automatic Multi-Provider Fallback on `HttpDataSource.HttpDataSourceException` (e.g., HTTP 403 Forbidden or stream reset)
 *    via `MultiProviderStreamResolver`.
 */
@OptIn(UnstableApi::class)
object AudioPlayerService {
    private const val TAG = "AudioPlayerService"

    const val STREAM_RESOLUTION_TIMEOUT_MS = 6_000L
    private const val MAX_PROVIDER_RETRIES_PER_TRACK = 4

    private var activePlaybackListener: Player.Listener? = null
    private var activeResolutionJob: Job? = null

    /**
     * Step 2.1: Configure `DefaultHttpDataSource.Factory` to prevent 30-second streaming stalls and connection drops.
     */
    fun createHttpDataSourceFactory(): DefaultHttpDataSource.Factory {
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
        return httpDataSourceFactory
    }

    /**
     * Step 2.2: Configure Custom `DefaultLoadControl` for Audio:
     * - minBufferMs = 30000 (30 seconds)
     * - maxBufferMs = 90000 (90 seconds)
     * - bufferForPlaybackMs = 2500 (fast 2.5s start)
     * - bufferForPlaybackAfterRebufferMs = 5000
     */
    fun createLoadControl(): DefaultLoadControl {
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30000,
                /* maxBufferMs = */ 90000,
                /* bufferForPlaybackMs = */ 2500,
                /* bufferForPlaybackAfterRebufferMs = */ 5000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    /**
     * Wraps ExoPlayer `DefaultMediaSourceFactory` with the YouTube-compatible `httpDataSourceFactory`.
     */
    fun createMediaSourceFactory(context: Context): DefaultMediaSourceFactory {
        val httpDataSourceFactory = createHttpDataSourceFactory()
        return DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpDataSourceFactory)
    }

    /**
     * Non-blocking multi-provider stream resolution and immediate ExoPlayer preparation.
     */
    fun resolveAndPlayTrackNonBlocking(
        scope: CoroutineScope,
        exoPlayer: ExoPlayer,
        context: Context,
        track: AudioTrack,
        backend: StreamBackend = StreamBackend.AUTO_HIGH_FIDELITY,
        onImmediateTrackSelected: (AudioTrack) -> Unit,
        onCandidatesResolved: (List<ResolvedStreamCandidate>, ResolvedStreamCandidate) -> Unit,
        onStateVerifiedReady: (AudioTrack, AudioQualityInfo) -> Unit,
        onError: (String) -> Unit
    ): Job {
        activeResolutionJob?.cancel()

        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        onImmediateTrackSelected(track)

        val videoId = MultiProviderStreamResolver.extractCleanVideoId(track.id)
            .ifEmpty { MultiProviderStreamResolver.extractCleanVideoId(track.streamUrl.orEmpty()) }
            .ifEmpty { MultiProviderStreamResolver.extractCleanVideoId(track.uriString.orEmpty()) }

        if (videoId.isNotEmpty()) {
            MultiProviderStreamResolver.clearFailedUrls(videoId)
        }

        val job = scope.launch(Dispatchers.IO) {
            try {
                // 1. Resolve via MultiProviderStreamResolver first when a YouTube videoId is present
                var primaryCandidate: ResolvedStreamCandidate? = null
                if (videoId.isNotEmpty() && !track.isLocalImport && !track.isDownloaded) {
                    primaryCandidate = MultiProviderStreamResolver.resolveBestStreamCandidate(
                        videoId = videoId,
                        track = track
                    ).getOrNull()

                    if (primaryCandidate == null) {
                        val directUrl = MultiProviderStreamResolver.resolveBestStreamUrl(videoId).getOrNull()
                        if (!directUrl.isNullOrBlank()) {
                            val isOpus = directUrl.contains("mime=audio%2Fwebm") || directUrl.contains("mime=audio/webm")
                            primaryCandidate = ResolvedStreamCandidate(
                                streamUrl = directUrl,
                                directStreamUrl = directUrl,
                                formatName = if (isOpus) "OPUS" else "AAC",
                                codec = if (isOpus) "OPUS" else "AAC-LC",
                                bitrateKbps = if (isOpus) 160 else 128,
                                sampleRateHz = if (isOpus) 48000 else 44100,
                                bitDepth = 16,
                                channelCount = 2,
                                isLossless = false,
                                isHiRes = false,
                                providerName = "YouTube Multi-Stream (${if (isOpus) "OPUS 160kbps" else "AAC 128kbps"})",
                                qualityLabel = "${if (isOpus) "OPUS" else "AAC"} • ${if (isOpus) 160 else 128} kbps",
                                canonicalTitle = track.title,
                                canonicalArtist = track.artist,
                                canonicalAlbum = track.album,
                                canonicalArtworkUrl = track.artworkUrl.orEmpty(),
                                verifiedDurationMs = track.durationMs,
                                matchConfidenceScore = 115
                            )
                        }
                    }
                }

                // 2. Fall back to UniversalStreamResolver if needed (e.g. local/downloaded tracks or non-YouTube IDs)
                if (primaryCandidate == null) {
                    primaryCandidate = withTimeoutOrNull(STREAM_RESOLUTION_TIMEOUT_MS) {
                        UniversalStreamResolver.resolvePrimaryStream(
                            context = context,
                            track = track,
                            backend = backend
                        )
                    } ?: UniversalStreamResolver.resolveDirectFallback(context, track)
                }

                val resolvedCandidate = primaryCandidate
                var streamUrl = resolvedCandidate.directStreamUrl.ifBlank { resolvedCandidate.streamUrl }

                if (streamUrl.isBlank()) {
                    val resolvedPair = runCatching {
                        UniversalStreamResolver.resolveFullAudioStream(context, track, backend)
                    }.getOrNull()
                    if (resolvedPair != null && resolvedPair.first.isNotBlank()) {
                        streamUrl = resolvedPair.first
                    }
                }

                if (streamUrl.isBlank()) {
                    withContext(Dispatchers.Main) {
                        onError("Stream unavailable for '${track.title}'. Please try another song.")
                    }
                    return@launch
                }

                val finalCandidate = resolvedCandidate.copy(
                    streamUrl = streamUrl,
                    directStreamUrl = streamUrl
                )

                withContext(Dispatchers.Main) {
                    onCandidatesResolved(listOf(finalCandidate), finalCandidate)
                    switchStreamAtomically(
                        exoPlayer = exoPlayer,
                        context = context,
                        track = track.copy(
                            uriString = streamUrl,
                            streamUrl = streamUrl
                        ),
                        candidate = finalCandidate,
                        preservePosition = false,
                        scope = scope,
                        retryCount = 0,
                        onStateVerifiedReady = onStateVerifiedReady,
                        onError = onError
                    )
                }

                // Populate secondary quality candidates asynchronously AFTER ExoPlayer is already preparing/playing
                launch(Dispatchers.IO) {
                    val allCandidates = if (videoId.isNotEmpty()) {
                        MultiProviderStreamResolver.resolveAllProviderCandidates(videoId, track)
                    } else {
                        withTimeoutOrNull(3_500L) {
                            UniversalStreamResolver.resolveAllStreamCandidates(
                                context = context,
                                track = track,
                                backend = backend
                            )
                        }.orEmpty()
                    }
                    if (allCandidates.size > 1) {
                        withContext(Dispatchers.Main) {
                            if (isPlayerBoundToTrack(exoPlayer, track.id)) {
                                onCandidatesResolved(allCandidates, finalCandidate)
                            }
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Log.e(TAG, "Stream resolution error for ${track.title}", e)
                withContext(Dispatchers.Main) {
                    onError("Unable to start playback for '${track.title}': ${e.localizedMessage ?: "Network error"}")
                }
            }
        }

        activeResolutionJob = job
        return job
    }

    /**
     * Atomically binds ExoPlayer to the resolved candidate stream URI, starts playback immediately,
     * and attaches an auto-retry listener for `HttpDataSource.HttpDataSourceException` (e.g. HTTP 403 or server reset).
     */
    fun switchStreamAtomically(
        exoPlayer: ExoPlayer,
        context: Context,
        track: AudioTrack,
        candidate: ResolvedStreamCandidate,
        preservePosition: Boolean = false,
        scope: CoroutineScope? = null,
        retryCount: Int = 0,
        onStateVerifiedReady: (AudioTrack, AudioQualityInfo) -> Unit,
        onError: ((String) -> Unit)? = null
    ) {
        val resumePositionMs = if (preservePosition) exoPlayer.currentPosition.coerceAtLeast(0L) else 0L

        activePlaybackListener?.let { oldListener ->
            exoPlayer.removeListener(oldListener)
            activePlaybackListener = null
        }

        exoPlayer.stop()
        exoPlayer.clearMediaItems()

        val resolvedStreamUrl = candidate.directStreamUrl
            .ifBlank { candidate.streamUrl }
            .ifBlank { track.streamUrl.orEmpty() }
            .ifBlank { track.uriString.orEmpty() }

        val resolvedArtwork = track.artworkUrl?.takeIf { it.isNotBlank() }
            ?: candidate.canonicalArtworkUrl.takeIf { it.isNotBlank() }

        val resolvedIsrc = track.effectiveCanonicalIsrc
            ?: candidate.canonicalIsrc.takeIf { it.isNotBlank() }

        val boundTrack = track.copy(
            uriString = resolvedStreamUrl,
            streamUrl = resolvedStreamUrl,
            bitrateKbps = candidate.bitrateKbps,
            sampleRate = candidate.sampleRateHz,
            bitDepth = candidate.bitDepth,
            sourceProvider = candidate.providerName,
            isrc = resolvedIsrc,
            canonicalIsrc = resolvedIsrc,
            artworkUrl = resolvedArtwork
        )

        val effectiveCandidate = candidate.copy(
            streamUrl = resolvedStreamUrl,
            directStreamUrl = resolvedStreamUrl
        )

        val mediaItem = buildVerifiedMediaItem(context, boundTrack, effectiveCandidate)
        val initialQuality = effectiveCandidate.toAudioQualityInfo()

        val verificationListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val currentMediaId = exoPlayer.currentMediaItem?.mediaId
                    if (currentMediaId == boundTrack.id) {
                        val realTimeQuality = AudioMetadataProber.extractRealTimeQuality(
                            exoFormat = exoPlayer.audioFormat,
                            track = boundTrack,
                            probedQuality = initialQuality
                        )
                        onStateVerifiedReady(boundTrack, realTimeQuality)
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    val currentMediaId = exoPlayer.currentMediaItem?.mediaId
                    if (currentMediaId == boundTrack.id) {
                        val realTimeQuality = AudioMetadataProber.extractRealTimeQuality(
                            exoFormat = exoPlayer.audioFormat,
                            track = boundTrack,
                            probedQuality = initialQuality
                        )
                        onStateVerifiedReady(boundTrack, realTimeQuality)
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if ( isHttpDataSourceOrNetworkError(error) && scope != null && retryCount < MAX_PROVIDER_RETRIES_PER_TRACK) {
                    val videoId = MultiProviderStreamResolver.extractCleanVideoId(boundTrack.id)
                    if (videoId.isNotEmpty()) {
                        Log.w(
                            TAG,
                            "HttpDataSourceException (${error.errorCodeName}) on provider ${effectiveCandidate.providerName}. Auto-retrying provider #${retryCount + 2} for $videoId"
                        )
                        MultiProviderStreamResolver.markUrlFailed(videoId, resolvedStreamUrl)
                        exoPlayer.removeListener(this)
                        if (activePlaybackListener === this) {
                            activePlaybackListener = null
                        }

                        scope.launch(Dispatchers.IO) {
                            val nextCandidate = MultiProviderStreamResolver.resolveBestStreamCandidate(
                                videoId = videoId,
                                track = boundTrack,
                                excludedUrls = setOf(resolvedStreamUrl)
                            ).getOrNull()

                            if (nextCandidate != null && nextCandidate.directStreamUrl.isNotBlank()) {
                                withContext(Dispatchers.Main) {
                                    switchStreamAtomically(
                                        exoPlayer = exoPlayer,
                                        context = context,
                                        track = boundTrack.copy(
                                            uriString = nextCandidate.directStreamUrl,
                                            streamUrl = nextCandidate.directStreamUrl
                                        ),
                                        candidate = nextCandidate,
                                        preservePosition = true,
                                        scope = scope,
                                        retryCount = retryCount + 1,
                                        onStateVerifiedReady = onStateVerifiedReady,
                                        onError = onError
                                    )
                                }
                            } else {
                                val nextUrlResult = MultiProviderStreamResolver.resolveBestStreamUrl(videoId)
                                val nextUrl = nextUrlResult.getOrNull()
                                if (!nextUrl.isNullOrBlank() && nextUrl != resolvedStreamUrl) {
                                    val fallbackCand = effectiveCandidate.copy(
                                        streamUrl = nextUrl,
                                        directStreamUrl = nextUrl,
                                        providerName = "Fallback Stream #${retryCount + 2}"
                                    )
                                    withContext(Dispatchers.Main) {
                                        switchStreamAtomically(
                                            exoPlayer = exoPlayer,
                                            context = context,
                                            track = boundTrack.copy(
                                                uriString = nextUrl,
                                                streamUrl = nextUrl
                                            ),
                                            candidate = fallbackCand,
                                            preservePosition = true,
                                            scope = scope,
                                            retryCount = retryCount + 1,
                                            onStateVerifiedReady = onStateVerifiedReady,
                                            onError = onError
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        activePlaybackListener = verificationListener
        exoPlayer.addListener(verificationListener)

        exoPlayer.setMediaItem(mediaItem, resumePositionMs)
        exoPlayer.playWhenReady = true
        exoPlayer.prepare()
        exoPlayer.play()
    }

    /**
     * Checks whether a [PlaybackException] was caused by an [HttpDataSource.HttpDataSourceException]
     * (such as HTTP 403 Forbidden or stream reset) or network source error.
     */
    fun isHttpDataSourceOrNetworkError(error: PlaybackException): Boolean {
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (cause is HttpDataSource.HttpDataSourceException) {
                return true
            }
            cause = cause.cause
        }
        return error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
    }

    /**
     * Builds a Media3 `MediaItem` with canonical metadata and MIME type hints for fast extractor startup.
     */
    fun buildVerifiedMediaItem(
        context: Context,
        track: AudioTrack,
        candidate: ResolvedStreamCandidate
    ): MediaItem {
        val streamUrlToUse = candidate.directStreamUrl
            .ifBlank { candidate.streamUrl }
            .ifBlank { track.streamUrl.orEmpty() }
            .ifBlank { track.uriString.orEmpty() }

        val playbackUri = resolvePlaybackUri(context, track, streamUrlToUse)
        val mimeType = inferMimeType(candidate.formatName, streamUrlToUse)

        val extras = Bundle().apply {
            putString("CANONICAL_TRACK_ID", track.id)
            putString("CANONICAL_ISRC", track.effectiveCanonicalIsrc ?: candidate.canonicalIsrc)
            putString("STREAM_FORMAT", candidate.formatName)
            putString("STREAM_CODEC", candidate.codec)
            putInt("STREAM_BITRATE_KBPS", candidate.bitrateKbps)
            putInt("STREAM_SAMPLE_RATE_HZ", candidate.sampleRateHz)
            putInt("STREAM_BIT_DEPTH", candidate.bitDepth)
            putString("STREAM_PROVIDER", candidate.providerName)
            putString("ALBUM_ART_URL", track.artworkUrl.orEmpty())
        }

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setGenre(track.genre)
            .setReleaseYear(track.releaseYear)
            .setExtras(extras)

        if (!track.artworkUrl.isNullOrBlank()) {
            runCatching { Uri.parse(track.artworkUrl) }.getOrNull()?.let { artUri ->
                metadataBuilder.setArtworkUri(artUri)
            }
        }

        val itemBuilder = MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(playbackUri)
            .setMediaMetadata(metadataBuilder.build())

        if (mimeType != null) {
            itemBuilder.setMimeType(mimeType)
        }

        return itemBuilder.build()
    }

    private fun resolvePlaybackUri(
        context: Context,
        track: AudioTrack,
        candidateStreamUrl: String
    ): Uri {
        val localPath = track.localFilePath
        if (!localPath.isNullOrBlank()) {
            if (localPath.startsWith("content://") || localPath.startsWith("file://")) {
                return Uri.parse(localPath)
            }
            val localFile = File(localPath)
            if (localFile.exists() && localFile.length() > 1024L) {
                return Uri.fromFile(localFile)
            }
        }

        val downloadsDir = File(context.filesDir, "downloads")
        val sanitizedId = track.id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        for (ext in listOf("flac", "m4a", "wav", "mp3", "opus")) {
            val f = File(downloadsDir, "$sanitizedId.$ext")
            if (f.exists() && f.length() > 1024L) {
                return Uri.fromFile(f)
            }
        }

        return when {
            candidateStreamUrl.startsWith("http://") ||
                candidateStreamUrl.startsWith("https://") ||
                candidateStreamUrl.startsWith("content://") ||
                candidateStreamUrl.startsWith("file://") -> Uri.parse(candidateStreamUrl)
            candidateStreamUrl.startsWith("/") -> Uri.fromFile(File(candidateStreamUrl))
            else -> Uri.parse(candidateStreamUrl)
        }
    }

    private fun inferMimeType(formatName: String, url: String): String? {
        val cleanUrl = url.substringBefore("?").lowercase()
        return when {
            formatName.equals("FLAC", ignoreCase = true) || cleanUrl.endsWith(".flac") -> MimeTypes.AUDIO_FLAC
            formatName.equals("ALAC", ignoreCase = true) -> MimeTypes.AUDIO_ALAC
            formatName.equals("WAV", ignoreCase = true) || cleanUrl.endsWith(".wav") -> MimeTypes.AUDIO_WAV
            formatName.equals("OPUS", ignoreCase = true) || cleanUrl.endsWith(".webm") || cleanUrl.endsWith(".opus") || url.contains("mime=audio%2Fwebm") || url.contains("mime=audio/webm") -> MimeTypes.AUDIO_WEBM
            cleanUrl.endsWith(".mp3") -> MimeTypes.AUDIO_MPEG
            cleanUrl.endsWith(".m4a") || cleanUrl.endsWith(".mp4") || cleanUrl.endsWith(".aac") || url.contains("mime=audio%2Fmp4") || url.contains("mime=audio/mp4") -> MimeTypes.AUDIO_MP4
            else -> null
        }
    }

    fun isPlayerBoundToTrack(exoPlayer: ExoPlayer, expectedTrackId: String): Boolean {
        val currentId = exoPlayer.currentMediaItem?.mediaId ?: return false
        return currentId == expectedTrackId
    }

    suspend fun probeStreamContainer(
        url: String,
        durationMs: Long,
        fallbackFormat: String = "AAC",
        fallbackBitrate: Int = 320,
        fallbackSampleRate: Int = 44100,
        fallbackBitDepth: Int = 16
    ): AudioQualityInfo {
        return AudioMetadataProber.probeStream(
            url = url,
            durationMs = durationMs,
            fallbackFormat = fallbackFormat,
            fallbackBitrate = fallbackBitrate,
            fallbackSampleRate = fallbackSampleRate,
            fallbackBitDepth = fallbackBitDepth
        )
    }
}
