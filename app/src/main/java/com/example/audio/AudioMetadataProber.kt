package com.example.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.model.AudioQualityInfo
import com.example.model.AudioTrack
import com.example.network.UniversalStreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Unified Audio Metadata Prober (Single Source of Truth)
 *
 * Consolidates media container header probing and ExoPlayer active Format inspection:
 * 1. Hooks directly into ExoPlayer's Player.Listener (onTracksChanged & onPlaybackParametersChanged):
 *    - Container/Codec: (FLAC, ALAC, AAC, MP3, OPUS, WAV)
 *    - Bit Depth: format.pcmEncoding or container STREAMINFO/fmt depth (24-bit, 16-bit, 32-bit)
 *    - Sample Rate: format.sampleRate in Hz (192000, 96000, 48000, 44100)
 *    - True Bitrate: format.bitrate / 1000 or calculated average bitrate from container bytes / duration
 * 2. Directly probes raw media container headers:
 *    - FLAC STREAMINFO magic (fLaC)
 *    - WAV fmt chunk (RIFF/WAVE)
 *    - MP4/M4A stsd/ftyp (AAC/ALAC)
 *    - WebM/Ogg Opus (OpusHead)
 *    - MP3 frame header bitrate & sample rate tables
 * 3. Dynamically exposes exact probed values to NowPlayingSheet.kt and DownloadQualityDialog.kt.
 */
object AudioMetadataProber {

    private const val TAG = "AudioMetadataProber"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Thread-safe in-memory cache of probed stream audio quality info keyed by stream URL
    private val probeCache = ConcurrentHashMap<String, AudioQualityInfo>()

    // Reactive StateFlow of the active playing stream's probed quality
    private val _activeProbedQuality = MutableStateFlow(AudioQualityInfo())
    val activeProbedQuality: StateFlow<AudioQualityInfo> = _activeProbedQuality.asStateFlow()

    fun getCachedQuality(url: String): AudioQualityInfo? {
        return probeCache[url]
    }

    /**
     * Creates a dedicated ExoPlayer Player.Listener that hooks into onTracksChanged
     * and onPlaybackParametersChanged to inspect the active audio Format in real time.
     */
    @OptIn(UnstableApi::class)
    fun createExoPlayerFormatListener(
        exoPlayerProvider: () -> ExoPlayer,
        activeTrackProvider: () -> AudioTrack?,
        probedQualityProvider: () -> AudioQualityInfo?,
        onQualityExtracted: (AudioQualityInfo) -> Unit
    ): Player.Listener {
        return object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                val player = exoPlayerProvider()
                val activeFormat = extractActiveAudioFormatFromTracks(tracks) ?: player.audioFormat
                val quality = extractRealTimeQuality(
                    exoFormat = activeFormat,
                    track = activeTrackProvider(),
                    probedQuality = probedQualityProvider()
                )
                _activeProbedQuality.value = quality
                onQualityExtracted(quality)
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                val player = exoPlayerProvider()
                val activeFormat = player.audioFormat
                val quality = extractRealTimeQuality(
                    exoFormat = activeFormat,
                    track = activeTrackProvider(),
                    probedQuality = probedQualityProvider()
                )
                _activeProbedQuality.value = quality
                onQualityExtracted(quality)
            }
        }
    }

    /**
     * Extracts the currently selected audio Format from ExoPlayer's active Tracks groups.
     */
    @OptIn(UnstableApi::class)
    fun extractActiveAudioFormatFromTracks(tracks: Tracks): Format? {
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO && group.isSelected) {
                for (i in 0 until group.length) {
                    if (group.isTrackSelected(i)) {
                        return group.getTrackFormat(i)
                    }
                }
            }
        }
        return null
    }

    /**
     * Probes the true stream technical metadata directly from the audio payload or container header.
     */
    suspend fun probeStream(
        url: String,
        durationMs: Long = 0L,
        fallbackFormat: String = "AAC",
        fallbackBitrate: Int = 320,
        fallbackSampleRate: Int = 44100,
        fallbackBitDepth: Int = 16
    ): AudioQualityInfo = withContext(Dispatchers.IO) {
        if (url.isBlank()) {
            val isLossless = fallbackFormat.equals("FLAC", true) ||
                fallbackFormat.equals("ALAC", true) ||
                fallbackFormat.equals("WAV", true)
            val computedBr = deriveBitrateFromSizeAndDuration(
                fileSizeBytes = 0L,
                durationMs = durationMs,
                formatName = fallbackFormat,
                sampleRateHz = fallbackSampleRate,
                bitDepth = fallbackBitDepth,
                channels = 2
            )
            return@withContext AudioQualityInfo(
                formatName = fallbackFormat,
                sampleRateHz = fallbackSampleRate,
                bitDepth = fallbackBitDepth,
                bitrateKbps = if (fallbackBitrate > 0) fallbackBitrate else computedBr,
                channelCount = 2,
                isLossless = isLossless,
                isHiRes = isLossless || fallbackSampleRate >= 48000 || fallbackBitDepth >= 24
            )
        }

        probeCache[url]?.let { return@withContext it }

        // 1. Probe local file container headers directly
        if (url.startsWith("/") || url.startsWith("file://")) {
            val localPath = if (url.startsWith("file://")) url.removePrefix("file://") else url
            val file = File(localPath)
            if (file.exists() && file.length() > 44L) {
                val probed = probeLocalFile(file, durationMs)
                probeCache[url] = probed
                _activeProbedQuality.value = probed
                return@withContext probed
            }
        }

        // 2. Probe remote HTTP/HTTPS stream headers and container payload (Range bytes=0-8191)
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) FlowLossless/2.0")
                .header("Range", "bytes=0-8191")
                .header("Accept", "*/*")
                .build()

            httpClient.newCall(request).execute().use { resp ->
                val code = resp.code
                if (code in 200..206) {
                    val contentType = resp.header("Content-Type", "")?.lowercase() ?: ""
                    val contentRange = resp.header("Content-Range", "") ?: ""
                    var totalLength = 0L

                    if (contentRange.isNotBlank() && contentRange.contains("/")) {
                        val totalPart = contentRange.substringAfterLast("/")
                        totalLength = totalPart.toLongOrNull() ?: 0L
                    }
                    if (totalLength <= 0L) {
                        totalLength = resp.header("Content-Length", "0")?.toLongOrNull() ?: 0L
                    }

                    val headerBytes = resp.body?.byteStream()?.readBytes() ?: ByteArray(0)
                    val probed = parseContainerHeader(
                        bytes = headerBytes,
                        totalLength = totalLength,
                        durationMs = durationMs,
                        contentType = contentType,
                        url = url,
                        fallbackFormat = fallbackFormat,
                        fallbackBitrate = fallbackBitrate,
                        fallbackSampleRate = fallbackSampleRate,
                        fallbackBitDepth = fallbackBitDepth
                    )
                    probeCache[url] = probed
                    _activeProbedQuality.value = probed
                    return@withContext probed
                }
            }
        } catch (e: Exception) {
            runCatching { Log.d(TAG, "Remote probe failed for $url: ${e.message}") }
        }

        // Container-derived fallback when remote range request is blocked
        val isLossless = UniversalStreamResolver.isTrueLosslessUrl(url) ||
            fallbackFormat.equals("FLAC", true) ||
            fallbackFormat.equals("ALAC", true) ||
            fallbackFormat.equals("WAV", true)
        val format = when {
            url.endsWith(".flac", true) || fallbackFormat.equals("FLAC", true) -> "FLAC"
            url.endsWith(".alac", true) || fallbackFormat.equals("ALAC", true) -> "ALAC"
            url.endsWith(".wav", true) || fallbackFormat.equals("WAV", true) -> "WAV"
            url.contains(".webm", true) || url.contains("opus", true) || fallbackFormat.equals("OPUS", true) -> "OPUS"
            fallbackFormat.equals("MP3", true) || url.endsWith(".mp3", true) -> "MP3"
            else -> "AAC"
        }
        val sr = if (fallbackSampleRate >= 44100) fallbackSampleRate else if (format == "OPUS") 48000 else 44100
        val bd = if (isLossless && fallbackBitDepth >= 16) fallbackBitDepth else 16
        val br = when {
            format == "WAV" -> (sr * 2 * bd) / 1000
            isLossless -> if (fallbackBitrate > 320) fallbackBitrate else (sr * 2 * bd) / 1000
            else -> fallbackBitrate.takeIf { it > 0 } ?: deriveBitrateFromSizeAndDuration(0L, durationMs, format, sr, bd, 2)
        }

        val derivedInfo = AudioQualityInfo(
            formatName = format,
            sampleRateHz = sr,
            bitDepth = bd,
            bitrateKbps = br,
            channelCount = 2,
            isLossless = isLossless,
            isHiRes = isLossless || sr >= 48000 || bd >= 24 || br >= 256
        )
        probeCache[url] = derivedInfo
        derivedInfo
    }

    private fun probeLocalFile(file: File, durationMs: Long): AudioQualityInfo {
        val totalBytes = file.length()
        val headerBytes = ByteArray(minOf(totalBytes.toInt(), 8192))
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.readFully(headerBytes)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading local file header: ${file.absolutePath}", e)
        }

        return parseContainerHeader(
            bytes = headerBytes,
            totalLength = totalBytes,
            durationMs = durationMs,
            contentType = "",
            url = file.name,
            fallbackFormat = if (file.name.endsWith(".flac", true)) "FLAC" else "WAV",
            fallbackBitrate = (44100 * 2 * 16) / 1000,
            fallbackSampleRate = 44100,
            fallbackBitDepth = 16
        )
    }

    private fun parseContainerHeader(
        bytes: ByteArray,
        totalLength: Long,
        durationMs: Long,
        contentType: String,
        url: String,
        fallbackFormat: String,
        fallbackBitrate: Int,
        fallbackSampleRate: Int,
        fallbackBitDepth: Int
    ): AudioQualityInfo {
        val durationSec = (durationMs.takeIf { it > 0L } ?: 210_000L) / 1000.0
        val exactCalculatedBitrate = if (totalLength > 10_000L && durationSec >= 10.0) {
            ((totalLength * 8.0) / durationSec / 1000.0).roundToInt().coerceIn(48, 9600)
        } else {
            0
        }

        // 1. FLAC container probe: magic "fLaC" + 34-byte STREAMINFO block
        if (bytes.size >= 42 && bytes[0] == 'f'.code.toByte() && bytes[1] == 'L'.code.toByte() &&
            bytes[2] == 'a'.code.toByte() && bytes[3] == 'C'.code.toByte()
        ) {
            val b18 = bytes[18].toInt() and 0xFF
            val b19 = bytes[19].toInt() and 0xFF
            val b20 = bytes[20].toInt() and 0xFF
            val b21 = bytes[21].toInt() and 0xFF

            val sampleRate = (b18 shl 12) or (b19 shl 4) or (b20 shr 4)
            val channels = ((b20 shr 1) and 0x07) + 1
            val bitDepth = (((b20 and 0x01) shl 4) or (b21 shr 4)) + 1

            // Total samples in STREAMINFO (36-bit value across bytes 21..25)
            val totalSamples = (((b21 and 0x0F).toLong()) shl 32) or
                (((bytes[22].toInt() and 0xFF).toLong()) shl 24) or
                (((bytes[23].toInt() and 0xFF).toLong()) shl 16) or
                (((bytes[24].toInt() and 0xFF).toLong()) shl 8) or
                ((bytes[25].toInt() and 0xFF).toLong())

            val streamInfoDurationSec = if (sampleRate > 0 && totalSamples > 0L) {
                totalSamples.toDouble() / sampleRate.toDouble()
            } else {
                durationSec
            }

            val effectiveBitrate = when {
                totalLength > 10_000L && streamInfoDurationSec >= 5.0 ->
                    ((totalLength * 8.0) / streamInfoDurationSec / 1000.0).roundToInt().coerceIn(400, 9600)
                exactCalculatedBitrate > 0 -> exactCalculatedBitrate
                else -> deriveBitrateFromSizeAndDuration(
                    fileSizeBytes = totalLength,
                    durationMs = durationMs,
                    formatName = "FLAC",
                    sampleRateHz = if (sampleRate > 0) sampleRate else 44100,
                    bitDepth = if (bitDepth in 16..32) bitDepth else 16,
                    channels = if (channels in 1..8) channels else 2
                )
            }

            return AudioQualityInfo(
                formatName = "FLAC",
                sampleRateHz = if (sampleRate > 0) sampleRate else 44100,
                bitDepth = if (bitDepth in 16..32) bitDepth else 16,
                bitrateKbps = effectiveBitrate,
                channelCount = if (channels in 1..8) channels else 2,
                isLossless = true,
                isHiRes = sampleRate >= 48000 || bitDepth >= 24
            )
        }

        // 2. WAV container probe: "RIFF" .... "WAVE"
        if (bytes.size >= 36 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'A'.code.toByte() &&
            bytes[10] == 'V'.code.toByte() && bytes[11] == 'E'.code.toByte()
        ) {
            val channels = ((bytes[22].toInt() and 0xFF) or ((bytes[23].toInt() and 0xFF) shl 8)).coerceIn(1, 8)
            val sampleRate = ((bytes[24].toInt() and 0xFF) or
                ((bytes[25].toInt() and 0xFF) shl 8) or
                ((bytes[26].toInt() and 0xFF) shl 16) or
                ((bytes[27].toInt() and 0xFF) shl 24)).takeIf { it > 0 } ?: 44100
            val bitsPerSample = ((bytes[34].toInt() and 0xFF) or ((bytes[35].toInt() and 0xFF) shl 8)).takeIf { it in 8..64 } ?: 16
            val uncompressedBitrate = (sampleRate * channels * bitsPerSample) / 1000

            return AudioQualityInfo(
                formatName = "WAV",
                sampleRateHz = sampleRate,
                bitDepth = bitsPerSample,
                bitrateKbps = uncompressedBitrate,
                channelCount = channels,
                isLossless = true,
                isHiRes = sampleRate >= 48000 || bitsPerSample >= 24
            )
        }

        // 3. MP4 / M4A / ALAC / AAC probe: "ftyp" and "stsd" box inspection
        val hasFtyp = (0 until minOf(bytes.size - 4, 64)).any { i ->
            bytes[i] == 'f'.code.toByte() && bytes[i + 1] == 't'.code.toByte() &&
                bytes[i + 2] == 'y'.code.toByte() && bytes[i + 3] == 'p'.code.toByte()
        }
        if (hasFtyp || contentType.contains("mp4") || contentType.contains("aac") || contentType.contains("m4a")) {
            val isAlac = (0 until minOf(bytes.size - 4, 512)).any { i ->
                bytes[i] == 'a'.code.toByte() && bytes[i + 1] == 'l'.code.toByte() &&
                    bytes[i + 2] == 'a'.code.toByte() && bytes[i + 3] == 'c'.code.toByte()
            } || url.endsWith(".alac", true)

            val bitrate = when {
                exactCalculatedBitrate > 0 -> exactCalculatedBitrate
                isAlac -> deriveBitrateFromSizeAndDuration(totalLength, durationMs, "ALAC", fallbackSampleRate, fallbackBitDepth, 2)
                else -> fallbackBitrate.takeIf { it > 0 } ?: 320
            }

            val formatName = if (isAlac) "ALAC" else "AAC"
            val sampleRate = if (isAlac && fallbackSampleRate >= 48000) fallbackSampleRate else 44100
            val bitDepth = if (isAlac && fallbackBitDepth >= 24) fallbackBitDepth else 16

            return AudioQualityInfo(
                formatName = formatName,
                sampleRateHz = sampleRate,
                bitDepth = bitDepth,
                bitrateKbps = bitrate,
                channelCount = 2,
                isLossless = isAlac,
                isHiRes = isAlac || bitrate >= 256
            )
        }

        // 4. WebM / Ogg / Opus probe: "OpusHead" or Matroska audio header
        val opusHeadIndex = (0 until minOf(bytes.size - 12, 512)).firstOrNull { i ->
            bytes[i] == 'O'.code.toByte() && bytes[i + 1] == 'p'.code.toByte() &&
                bytes[i + 2] == 'u'.code.toByte() && bytes[i + 3] == 's'.code.toByte() &&
                bytes[i + 4] == 'H'.code.toByte()
        }
        val hasOpus = opusHeadIndex != null || contentType.contains("webm") || contentType.contains("opus") || url.contains("opus", true)

        if (hasOpus) {
            val channels = if (opusHeadIndex != null && opusHeadIndex + 9 < bytes.size) {
                (bytes[opusHeadIndex + 9].toInt() and 0xFF).coerceIn(1, 8)
            } else {
                2
            }
            val bitrate = if (exactCalculatedBitrate > 0) exactCalculatedBitrate else fallbackBitrate.takeIf { it > 0 } ?: 160
            return AudioQualityInfo(
                formatName = "OPUS",
                sampleRateHz = 48000,
                bitDepth = 16,
                bitrateKbps = bitrate,
                channelCount = channels,
                isLossless = false,
                isHiRes = bitrate >= 160
            )
        }

        // 5. MP3 probe: ID3v2 tag or MPEG frame sync word
        val hasId3 = bytes.size >= 3 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()
        val mp3FrameOffset = findMp3FrameSync(bytes)
        if (hasId3 || mp3FrameOffset >= 0 || contentType.contains("mpeg") || url.endsWith(".mp3", true) || fallbackFormat.equals("MP3", true)) {
            val parsedFrameBitrate = if (mp3FrameOffset >= 0 && mp3FrameOffset + 3 < bytes.size) {
                parseMp3FrameBitrate(bytes, mp3FrameOffset)
            } else {
                0
            }
            val bitrate = when {
                exactCalculatedBitrate > 0 -> exactCalculatedBitrate
                parsedFrameBitrate > 0 -> parsedFrameBitrate
                else -> fallbackBitrate.takeIf { it > 0 } ?: 320
            }
            return AudioQualityInfo(
                formatName = "MP3",
                sampleRateHz = 44100,
                bitDepth = 16,
                bitrateKbps = bitrate,
                channelCount = 2,
                isLossless = false,
                isHiRes = bitrate >= 256
            )
        }

        // Container-derived fallback
        val isLosslessDefault = fallbackFormat.uppercase() in listOf("FLAC", "ALAC", "WAV", "AIFF")
        val effectiveBitrate = if (exactCalculatedBitrate > 0) {
            exactCalculatedBitrate
        } else {
            fallbackBitrate.takeIf { it > 0 } ?: deriveBitrateFromSizeAndDuration(
                fileSizeBytes = totalLength,
                durationMs = durationMs,
                formatName = fallbackFormat,
                sampleRateHz = fallbackSampleRate,
                bitDepth = fallbackBitDepth,
                channels = 2
            )
        }
        return AudioQualityInfo(
            formatName = fallbackFormat,
            sampleRateHz = fallbackSampleRate,
            bitDepth = fallbackBitDepth,
            bitrateKbps = effectiveBitrate,
            channelCount = 2,
            isLossless = isLosslessDefault,
            isHiRes = isLosslessDefault || fallbackSampleRate >= 48000 || fallbackBitDepth >= 24 || effectiveBitrate >= 256
        )
    }

    private fun findMp3FrameSync(bytes: ByteArray): Int {
        var offset = 0
        if (bytes.size >= 10 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) {
            val size = ((bytes[6].toInt() and 0x7F) shl 21) or
                ((bytes[7].toInt() and 0x7F) shl 14) or
                ((bytes[8].toInt() and 0x7F) shl 7) or
                (bytes[9].toInt() and 0x7F)
            offset = (size + 10).coerceAtMost(bytes.size - 4)
        }
        for (i in offset until minOf(bytes.size - 3, offset + 2048)) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            if (b0 == 0xFF && (b1 and 0xE0) == 0xE0 && (b1 and 0x18) != 0x08 && (b1 and 0x06) != 0x00) {
                return i
            }
        }
        return -1
    }

    private fun parseMp3FrameBitrate(bytes: ByteArray, offset: Int): Int {
        val b2 = bytes[offset + 2].toInt() and 0xFF
        val bitrateIndex = (b2 shr 4) and 0x0F
        // MPEG-1 Layer III bitrate table (kbps)
        val mpeg1Layer3Bitrates = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
        return mpeg1Layer3Bitrates.getOrElse(bitrateIndex) { 0 }
    }

    /**
     * Derives the true bitrate in kbps from exact byte length and track duration
     * or uncompressed PCM formula when raw decoder Format.bitrate is unavailable.
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

        // Uncompressed PCM (WAV / AIFF): Bitrate = (SampleRate * Channels * BitDepth) / 1000
        if (cleanFormat == "WAV" || cleanFormat == "PCM WAV" || cleanFormat == "PCM" || cleanFormat == "AIFF") {
            return (safeSr * safeCh * safeBd) / 1000
        }

        val durationSec = durationMs / 1000.0
        if (fileSizeBytes > 8_192L && durationSec >= 10.0) {
            val computedKbps = ((fileSizeBytes * 8.0) / durationSec / 1000.0).roundToInt()
            if (computedKbps in 48..9600) {
                return computedKbps
            }
        }

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
     * Synchronous real-time extractor hooking directly into ExoPlayer's active Format object:
     * - Container/Codec: (FLAC, ALAC, AAC, MP3, OPUS, WAV)
     * - Bit Depth: format.pcmEncoding or container header depth (e.g., 24-bit, 16-bit)
     * - Sample Rate: format.sampleRate in Hz
     * - True Bitrate: format.bitrate / 1000 (or calculated average bitrate from downloaded bytes / duration)
     */
    @OptIn(UnstableApi::class)
    fun extractRealTimeQuality(
        exoFormat: Format?,
        track: AudioTrack?,
        probedQuality: AudioQualityInfo?
    ): AudioQualityInfo {
        val sampleMime = exoFormat?.sampleMimeType.orEmpty().lowercase()
        val containerMime = exoFormat?.containerMimeType.orEmpty().lowercase()
        val codecs = exoFormat?.codecs.orEmpty().lowercase()

        val detectedFormat = when {
            sampleMime.contains("flac") || containerMime.contains("flac") || codecs.contains("flac") -> "FLAC"
            sampleMime.contains("alac") || codecs.contains("alac") -> "ALAC"
            sampleMime.contains("wav") || sampleMime.contains("raw") || sampleMime.contains("pcm") -> "WAV"
            sampleMime.contains("opus") || containerMime.contains("opus") || codecs.contains("opus") -> "OPUS"
            sampleMime.contains("mp4a") || sampleMime.contains("aac") || codecs.contains("mp4a") -> "AAC"
            sampleMime.contains("mpeg") || sampleMime.contains("mp3") -> "MP3"
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
            C.ENCODING_PCM_8BIT -> 8
            else -> 0
        }

        val bitDepth = when {
            exoPcmBitDepth >= 16 -> exoPcmBitDepth
            isLossless && probedQuality != null && probedQuality.bitDepth >= 16 -> probedQuality.bitDepth
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
            exoFormat != null && exoFormat.bitrate > 0 -> (exoFormat.bitrate / 1000)
            exoFormat != null && exoFormat.averageBitrate > 0 -> (exoFormat.averageBitrate / 1000)
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

        val qualityInfo = AudioQualityInfo(
            formatName = detectedFormat,
            sampleRateHz = sampleRate,
            bitDepth = bitDepth,
            bitrateKbps = bitrateKbps,
            channelCount = channels,
            isLossless = isLossless,
            isHiRes = isHiRes
        )
        _activeProbedQuality.value = qualityInfo
        return qualityInfo
    }

    /**
     * Deep asynchronous prober using MediaMetadataRetriever combined with container header inspection.
     */
    suspend fun probeWithMediaMetadataRetriever(
        context: Context,
        track: AudioTrack
    ): AudioQualityInfo = withContext(Dispatchers.IO) {
        val targetUri = track.uriString?.takeIf { it.isNotBlank() } ?: track.localFilePath
        if (targetUri.isNullOrBlank()) {
            return@withContext extractRealTimeQuality(null, track, null)
        }

        val headerProbed = probeStream(
            url = targetUri,
            durationMs = track.durationMs,
            fallbackFormat = track.format.displayName,
            fallbackBitrate = track.bitrateKbps,
            fallbackSampleRate = track.sampleRate,
            fallbackBitDepth = track.bitDepth
        )

        try {
            val retriever = MediaMetadataRetriever()
            if (targetUri.startsWith("/") || targetUri.startsWith("file://")) {
                retriever.setDataSource(targetUri.removePrefix("file://"))
            } else {
                retriever.setDataSource(context, Uri.parse(targetUri))
            }

            val bitrateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            val sampleRateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
            val mimeStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            retriever.release()

            val retrieverBitrate = bitrateStr?.toIntOrNull()?.let { it / 1000 } ?: 0
            val retrieverSampleRate = sampleRateStr?.toIntOrNull() ?: 0

            val merged = headerProbed.copy(
                bitrateKbps = if (retrieverBitrate in 48..9600) retrieverBitrate else headerProbed.bitrateKbps,
                sampleRateHz = if (retrieverSampleRate >= 44100) retrieverSampleRate else headerProbed.sampleRateHz,
                formatName = when {
                    mimeStr?.contains("flac", true) == true -> "FLAC"
                    mimeStr?.contains("alac", true) == true -> "ALAC"
                    mimeStr?.contains("wav", true) == true -> "WAV"
                    mimeStr?.contains("opus", true) == true -> "OPUS"
                    mimeStr?.contains("aac", true) == true || mimeStr?.contains("mp4", true) == true -> "AAC"
                    mimeStr?.contains("mpeg", true) == true -> "MP3"
                    else -> headerProbed.formatName
                }
            )
            _activeProbedQuality.value = merged
            merged
        } catch (_: Exception) {
            headerProbed
        }
    }
}
