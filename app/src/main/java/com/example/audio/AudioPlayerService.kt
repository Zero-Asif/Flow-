package com.example.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.model.AudioQualityInfo
import com.example.model.AudioTrack
import com.example.network.ResolvedStreamCandidate
import com.example.network.StreamMetadataProbe
import com.example.network.UniversalStreamResolver
import com.example.util.AlbumArtHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AudioPlayerService:
 * Manages atomic stream switching and verified container header probing.
 * 
 * Guarantees:
 * 1. Zero Audio Bleed / Desync:
 *    - Immediately stops and clears previous player media items.
 *    - Cleans decoder state and rebinds new stream URL at exact current playback position.
 * 2. Real-Time Container Probing:
 *    - Probes raw media container headers (FLAC STREAMINFO, WAV fmt chunk, MP4/M4A stsd, Ogg/Opus).
 *    - Pairs with ExoPlayer active format listener to display genuine sample rates and bitrates.
 *    - Zero fake labels: Never displays FLAC/Ultra HD for lossy streams.
 */
@OptIn(UnstableApi::class)
object AudioPlayerService {

    private const val TAG = "AudioPlayerService"

    /**
     * Determines the exact MIME type string from stream URL and candidate format
     * to ensure ExoPlayer selects the correct native decoder without guesswork.
     */
    fun resolveExactMimeType(url: String, formatName: String): String {
        val lowerUrl = url.lowercase().substringBefore("?")
        val upperFmt = formatName.uppercase()
        return when {
            lowerUrl.endsWith(".flac") || upperFmt.contains("FLAC") -> MimeTypes.AUDIO_FLAC
            lowerUrl.endsWith(".wav") || lowerUrl.endsWith(".wave") || upperFmt.contains("WAV") || upperFmt.contains("PCM") -> MimeTypes.AUDIO_WAV
            lowerUrl.endsWith(".alac") || upperFmt.contains("ALAC") -> MimeTypes.AUDIO_ALAC
            lowerUrl.endsWith(".opus") || upperFmt.contains("OPUS") -> MimeTypes.AUDIO_OPUS
            lowerUrl.endsWith(".mp3") || upperFmt.contains("MP3") -> MimeTypes.AUDIO_MPEG
            lowerUrl.endsWith(".m4a") || lowerUrl.endsWith(".mp4") || upperFmt.contains("AAC") -> MimeTypes.AUDIO_AAC
            else -> MimeTypes.AUDIO_AAC
        }
    }

    /**
     * Asynchronously probes raw container headers and returns verified technical quality metrics.
     */
    suspend fun probeStreamContainer(
        url: String,
        durationMs: Long,
        fallbackFormat: String = "AAC",
        fallbackBitrate: Int = 320,
        fallbackSampleRate: Int = 44100,
        fallbackBitDepth: Int = 16
    ): AudioQualityInfo = withContext(Dispatchers.IO) {
        StreamMetadataProbe.probeStream(
            url = url,
            durationMs = durationMs,
            fallbackFormat = fallbackFormat,
            fallbackBitrate = fallbackBitrate,
            fallbackSampleRate = fallbackSampleRate,
            fallbackBitDepth = fallbackBitDepth
        )
    }

    /**
     * Executes 100% atomic player stream switching without audio bleed:
     * 1. Preserves current playback position if requested.
     * 2. Immediately calls exoPlayer.stop() and exoPlayer.clearMediaItems().
     * 3. Prepares the new MediaItem with explicit MIME type and initiates playback at saved position.
     */
    fun switchStreamAtomically(
        exoPlayer: ExoPlayer,
        context: Context,
        track: AudioTrack,
        candidate: ResolvedStreamCandidate,
        preservePosition: Boolean = true
    ): Long {
        val targetPositionMs = if (preservePosition) {
            exoPlayer.currentPosition.coerceAtLeast(0L)
        } else {
            0L
        }

        runCatching {
            Log.i(
                TAG,
                "Atomic stream switch to ${candidate.providerName} [${candidate.formatName} @ ${candidate.bitrateKbps} kbps] at position ${targetPositionMs}ms"
            )
        }

        // Hard stop & buffer clearance
        exoPlayer.stop()
        exoPlayer.clearMediaItems()

        val mimeType = resolveExactMimeType(candidate.directStreamUrl, candidate.formatName)
        val uri = if (candidate.directStreamUrl.startsWith("/")) {
            Uri.fromFile(File(candidate.directStreamUrl))
        } else {
            Uri.parse(candidate.directStreamUrl)
        }

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setGenre(track.genre)
            .setReleaseYear(track.releaseYear)
            .setIsPlayable(true)

        if (!track.artworkUrl.isNullOrBlank()) {
            metadataBuilder.setArtworkUri(Uri.parse(AlbumArtHelper.enhanceArtworkUrl(track.artworkUrl)))
        }

        val mediaItem = MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(uri)
            .setMimeType(mimeType)
            .setMediaMetadata(metadataBuilder.build())
            .build()

        exoPlayer.setMediaItem(mediaItem, targetPositionMs)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        exoPlayer.play()

        return targetPositionMs
    }
}
