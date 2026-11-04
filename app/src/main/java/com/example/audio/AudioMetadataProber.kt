package com.example.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import com.example.model.AudioQualityInfo
import com.example.model.AudioTrack
import com.example.network.StreamMetadataProbe
import com.example.network.UniversalStreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * AudioMetadataProber:
 * Uses Android's MediaMetadataRetriever, ExoPlayer's active Format object, and raw container header
 * inspection to calculate real-time bitrate, sample rate, and bit depth from the active audio source.
 * Includes a dedicated helper to derive accurate bitrates for FLAC/ALAC/WAV containers from file size
 * and duration when raw format metadata is unavailable.
 */
object AudioMetadataProber {

    private const val TAG = "AudioMetadataProber"

    /**
     * Derives the true bitrate in kbps for FLAC, ALAC, WAV, or compressed containers based on
     * exact byte length and track duration when raw decoder Format.bitrate is unavailable (<= 0).
     *
     * Rules:
     * - Uncompressed PCM / WAV: strictly (sampleRateHz * channels * bitDepth) / 1000
     *   (e.g., 44100 * 2 * 16 / 1000 = 1411 kbps).
     * - Lossless compressed (FLAC / ALAC): derived from (fileSizeBytes * 8) / durationSeconds / 1000
     *   when fileSizeBytes and durationMs are valid; otherwise derived from PCM bit rate * typical
     *   lossless compression ratio (~0.65 for 16-bit/44.1kHz -> ~900-1411 kbps, 24-bit/96kHz -> 2822 kbps,
     *   24-bit/192kHz -> 4608 kbps).
     */
    fun deriveBitrateFromSizeAndDuration(
        fileSizeBytes: Long,
        durationMs: Long,
        formatName: String,
        sampleRateHz: Int = 44100,
        bitDepth: Int = 16,
        channels: Int = 2
    ): Int {
        val cleanFormat = formatName.uppercase().trim()
        val safeSr = sampleRateHz.takeIf { it > 0 } ?: 44100
        val safeBd = bitDepth.takeIf { it > 0 } ?: 16
        val safeCh = channels.takeIf { it > 0 } ?: 2

        // 1. Uncompressed PCM / WAV is strictly SampleRate * Channels * BitDepth
        if (cleanFormat == "WAV" || cleanFormat == "PCM WAV" || cleanFormat == "PCM" || cleanFormat == "AIFF") {
            return (safeSr * safeCh * safeBd) / 1000
        }

        // 2. If exact byte size and duration are known, compute exact container bitrate: (bytes * 8) / seconds / 1000
        val durationSec = durationMs / 1000.0
        if (fileSizeBytes > 8_192L && durationSec >= 10.0) {
            val computedKbps = ((fileSizeBytes * 8.0) / durationSec / 1000.0).roundToInt()
            if (computedKbps in 48..9600) {
                return computedKbps
            }
        }

        // 3. If file size is unknown on a live stream, derive from lossless PCM parameters
        val isLossless = cleanFormat.contains("FLAC") || cleanFormat.contains("ALAC")
        return if (isLossless) {
            when {
                safeSr >= 192000 && safeBd >= 24 -> 4608
                safeSr >= 96000 && safeBd >= 24 -> 2822
                safeSr >= 48000 && safeBd >= 24 -> 2304
                else -> (safeSr * safeCh * safeBd) / 1000
            }
        } else {
            320
        }
    }

    /**
     * Synchronous real-time extractor combining ExoPlayer's active Format object with probed stream
     * telemetry and local file size inspection.
     */
    @OptIn(UnstableApi::class)
    fun extractRealTimeQuality(
        exoFormat: Format?,
        track: AudioTrack?,
        probedQuality: AudioQualityInfo?
    ): AudioQualityInfo {
        val mime = exoFormat?.sampleMimeType.orEmpty().lowercase()
        val detectedFormat = when {
            probedQuality != null && probedQuality.formatName.isNotBlank() &&
                (probedQuality.isLossless || mime.isBlank()) -> probedQuality.formatName
            mime.contains("flac") -> "FLAC"
            mime.contains("alac") -> "ALAC"
            mime.contains("wav") || mime.contains("raw") || mime.contains("pcm") -> "PCM WAV"
            mime.contains("opus") -> "OPUS"
            mime.contains("mp4a") || mime.contains("aac") -> "AAC"
            mime.contains("mpeg") || mime.contains("mp3") -> "MP3"
            probedQuality != null && probedQuality.formatName.isNotBlank() -> probedQuality.formatName
            track != null -> track.format.displayName
            else -> "AAC"
        }

        val isPcmWav = detectedFormat.equals("WAV", ignoreCase = true) ||
            detectedFormat.equals("PCM WAV", ignoreCase = true) ||
            detectedFormat.equals("PCM", ignoreCase = true)

        val isLossless = isPcmWav ||
            detectedFormat.equals("FLAC", ignoreCase = true) ||
            detectedFormat.equals("ALAC", ignoreCase = true) ||
            detectedFormat.equals("AIFF", ignoreCase = true)

        val sampleRate = when {
            isLossless && probedQuality != null && probedQuality.sampleRateHz >= 44100 -> probedQuality.sampleRateHz
            exoFormat != null && exoFormat.sampleRate > 0 -> exoFormat.sampleRate
            probedQuality != null && probedQuality.sampleRateHz > 0 -> probedQuality.sampleRateHz
            track != null && track.sampleRate > 0 -> track.sampleRate
            else -> 44100
        }

        val channels = when {
            exoFormat != null && exoFormat.channelCount > 0 -> exoFormat.channelCount
            probedQuality != null && probedQuality.channelCount > 0 -> probedQuality.channelCount
            else -> 2
        }

        val exoPcmBitDepth = when (exoFormat?.pcmEncoding) {
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
            else -> 0
        }

        val bitDepth = when {
            isLossless && probedQuality != null && probedQuality.bitDepth >= 16 -> probedQuality.bitDepth
            exoPcmBitDepth >= 16 -> exoPcmBitDepth
            isLossless && track != null && track.bitDepth >= 16 -> track.bitDepth
            else -> 16
        }

        val localFileLength = track?.localFilePath?.let { path ->
            val f = File(path)
            if (f.exists()) f.length() else 0L
        } ?: track?.uriString?.takeIf { it.startsWith("/") || it.startsWith("file://") }?.let { uriStr ->
            val f = File(uriStr.removePrefix("file://"))
            if (f.exists()) f.length() else 0L
        } ?: 0L

        val durationMs = track?.durationMs ?: 0L

        val bitrateKbps = when {
            isPcmWav -> (sampleRate * channels * bitDepth) / 1000
            exoFormat != null && exoFormat.bitrate > 0 -> exoFormat.bitrate / 1000
            localFileLength > 8_192L && durationMs > 10_000L -> deriveBitrateFromSizeAndDuration(
                fileSizeBytes = localFileLength,
                durationMs = durationMs,
                formatName = detectedFormat,
                sampleRateHz = sampleRate,
                bitDepth = bitDepth,
                channels = channels
            )
            probedQuality != null && probedQuality.bitrateKbps > 0 -> probedQuality.bitrateKbps
            track != null && track.bitrateKbps > 0 -> track.bitrateKbps
            else -> deriveBitrateFromSizeAndDuration(
                fileSizeBytes = 0L,
                durationMs = durationMs,
                formatName = detectedFormat,
                sampleRateHz = sampleRate,
                bitDepth = bitDepth,
                channels = channels
            )
        }

        val isHiRes = isLossless || sampleRate >= 48000 || bitDepth >= 24 || bitrateKbps >= 256

        return AudioQualityInfo(
            formatName = detectedFormat,
            sampleRateHz = sampleRate,
            bitDepth = bitDepth,
            bitrateKbps = bitrateKbps,
            channelCount = channels,
            isLossless = isLossless,
            isHiRes = isHiRes
        )
    }

    /**
     * Deep asynchronous prober using Android's MediaMetadataRetriever (for local/cached files or direct URIs)
     * combined with StreamMetadataProbe container header inspection.
     */
    suspend fun probeWithMediaMetadataRetriever(
        context: Context,
        uriString: String,
        durationMs: Long = 0L,
        fallbackFormat: String = "AAC",
        fallbackBitrateKbps: Int = 320,
        fallbackSampleRateHz: Int = 44100,
        fallbackBitDepth: Int = 16
    ): AudioQualityInfo = withContext(Dispatchers.IO) {
        if (uriString.isBlank()) {
            return@withContext AudioQualityInfo(
                formatName = fallbackFormat,
                sampleRateHz = fallbackSampleRateHz,
                bitDepth = fallbackBitDepth,
                bitrateKbps = fallbackBitrateKbps,
                isLossless = fallbackFormat in listOf("FLAC", "ALAC", "WAV", "PCM WAV")
            )
        }

        // First probe container header bytes via StreamMetadataProbe
        val headerProbed = StreamMetadataProbe.probeStream(
            url = uriString,
            durationMs = durationMs,
            fallbackFormat = fallbackFormat,
            fallbackBitrate = fallbackBitrateKbps,
            fallbackSampleRate = fallbackSampleRateHz,
            fallbackBitDepth = fallbackBitDepth
        )

        // Enhance with MediaMetadataRetriever when inspecting local/downloaded files
        val isLocal = uriString.startsWith("/") || uriString.startsWith("file://") || uriString.startsWith("content://")
        if (!isLocal) {
            return@withContext headerProbed
        }

        val retriever = MediaMetadataRetriever()
        try {
            when {
                uriString.startsWith("content://") -> retriever.setDataSource(context, Uri.parse(uriString))
                else -> {
                    val file = File(uriString.removePrefix("file://"))
                    if (file.exists()) {
                        retriever.setDataSource(file.absolutePath)
                    } else {
                        return@withContext headerProbed
                    }
                }
            }

            val mmrBitrateBps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            val mmrDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: durationMs
            val mmrMime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty().lowercase()
            val mmrSampleRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
            } else 0
            val mmrBitsPerSample = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: 0
            } else 0

            val resolvedFormat = when {
                mmrMime.contains("flac") || headerProbed.formatName == "FLAC" -> "FLAC"
                mmrMime.contains("alac") || headerProbed.formatName == "ALAC" -> "ALAC"
                mmrMime.contains("wav") || mmrMime.contains("raw") || headerProbed.formatName.contains("WAV") -> "WAV"
                mmrMime.contains("opus") -> "OPUS"
                mmrMime.contains("mpeg") || mmrMime.contains("mp3") -> "MP3"
                mmrMime.contains("mp4") || mmrMime.contains("aac") -> "AAC"
                else -> headerProbed.formatName
            }

            val sr = mmrSampleRate.takeIf { it >= 8000 } ?: headerProbed.sampleRateHz
            val bd = mmrBitsPerSample.takeIf { it >= 8 } ?: headerProbed.bitDepth
            val isLossless = resolvedFormat in listOf("FLAC", "ALAC", "WAV", "PCM WAV")

            val fileLen = File(uriString.removePrefix("file://")).takeIf { it.exists() }?.length() ?: 0L
            val bitrateKbps = when {
                resolvedFormat == "WAV" || resolvedFormat == "PCM WAV" -> (sr * 2 * bd) / 1000
                mmrBitrateBps > 0 -> mmrBitrateBps / 1000
                fileLen > 8_192L && mmrDurationMs > 10_000L -> deriveBitrateFromSizeAndDuration(
                    fileSizeBytes = fileLen,
                    durationMs = mmrDurationMs,
                    formatName = resolvedFormat,
                    sampleRateHz = sr,
                    bitDepth = bd,
                    channels = headerProbed.channelCount
                )
                else -> headerProbed.bitrateKbps
            }

            return@withContext headerProbed.copy(
                formatName = resolvedFormat,
                sampleRateHz = sr,
                bitDepth = bd,
                bitrateKbps = bitrateKbps,
                isLossless = isLossless,
                isHiRes = isLossless || sr >= 48000 || bd >= 24 || bitrateKbps >= 256
            )
        } catch (e: Exception) {
            runCatching { Log.d(TAG, "MediaMetadataRetriever fallback for $uriString: ${e.message}") }
            return@withContext headerProbed
        } finally {
            runCatching { retriever.release() }
        }
    }
}
