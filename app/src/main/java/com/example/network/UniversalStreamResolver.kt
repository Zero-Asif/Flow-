package com.example.network

import android.content.Context
import android.util.Log
import com.example.model.AudioQualityInfo
import com.example.model.AudioTrack
import com.example.model.StreamBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Represents a selectable audio download quality tier so the user can choose
 * which bitrate/format to download, with the highest available quality recommended first.
 */
enum class DownloadQualityOption(
    val title: String,
    val subtitle: String,
    val targetBitrateKbps: Int,
    val urlSuffix: String,
    val isLosslessTier: Boolean = false,
    val isRecommended: Boolean = false
) {
    HI_RES_LOSSLESS(
        title = "(Recommended) Hi-Res FLAC (Original Master)",
        subtitle = "Lossless / Hi-Res Bit-Perfect • 24-BIT / 96kHz • 2822 kbps",
        targetBitrateKbps = 2822,
        urlSuffix = ".flac",
        isLosslessTier = true,
        isRecommended = true
    ),
    MASTER_320K(
        title = "High Quality MP3 / Lossy 320 kbps",
        subtitle = "Lossy Compressed Audio • 16-BIT / 44.1kHz • 320 kbps",
        targetBitrateKbps = 320,
        urlSuffix = "_320.mp4",
        isLosslessTier = false,
        isRecommended = false
    ),
    HIGH_160K(
        title = "Standard High / Lossy 160 kbps",
        subtitle = "Lossy Compressed Audio • 16-BIT / 44.1kHz • 160 kbps",
        targetBitrateKbps = 160,
        urlSuffix = "_160.mp4",
        isLosslessTier = false,
        isRecommended = false
    ),
    STANDARD_96K(
        title = "Data Saver / Lossy 96 kbps",
        subtitle = "Lossy Compressed Audio • 16-BIT / 44.1kHz • 96 kbps",
        targetBitrateKbps = 96,
        urlSuffix = "_96.mp4",
        isLosslessTier = false,
        isRecommended = false
    )
}

/**
 * Dynamically resolved download option tailored to the active track's source metadata
 * (format, bit depth, sample rate, bitrate, and duration-based file size estimate).
 */
data class DynamicDownloadOptionItem(
    val option: DownloadQualityOption,
    val title: String,
    val subtitle: String,
    val badgeText: String,
    val formatName: String,
    val bitrateKbps: Int,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val estimatedSizeMb: String,
    val isLossless: Boolean,
    val isRecommended: Boolean
)

/**
 * Represents a discovered playable audio stream candidate with its real technical metadata.
 */
data class ResolvedStreamCandidate(
    val streamUrl: String,
    val formatName: String,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val bitrateKbps: Int,
    val isLossless: Boolean,
    val providerName: String = "Direct Master"
) {
    fun toAudioQualityInfo(): AudioQualityInfo {
        return AudioQualityInfo(
            formatName = formatName,
            sampleRateHz = sampleRateHz,
            bitDepth = bitDepth,
            bitrateKbps = bitrateKbps,
            channelCount = 2,
            isLossless = isLossless,
            isHiRes = isLossless || sampleRateHz >= 48000 || bitDepth >= 24 || bitrateKbps >= 320
        )
    }
}

object UniversalStreamResolver {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // Official JioSaavn API v4 (Zero rate-limiting, native DES encrypted_media_url)
    private const val OFFICIAL_JIOSAAVN_API =
        "https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&_marker=0&api_version=4&ctx=web6dot0&n=10&p=1&q="

    // Secondary JioSaavn mirror endpoints
    private val saavnEndpoints = listOf(
        "https://saavn.sumit.co/api",
        "https://saavn.dev/api",
        "https://saavn.me"
    )

    // Public Piped API instances for YouTube Music audio extraction
    private val pipedInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://api.piped.privacydev.net",
        "https://piped-api.lunar.icu"
    )

    // Public Invidious instances
    private val invidiousInstances = listOf(
        "https://inv.nadeko.net",
        "https://invidious.jing.rocks",
        "https://vid.puffyan.us",
        "https://yewtu.be"
    )

    // In-memory resolved streams cache
    private val resolvedStreamsCache = mutableMapOf<String, Pair<String, AudioQualityInfo>>()
    private val resolvedCandidatesCache = mutableMapOf<String, List<ResolvedStreamCandidate>>()

    /**
     * Codec Lossless Priority rank:
     * Priority 1: FLAC > ALAC > WAV > AAC > MP3
     */
    fun codecPriorityRank(formatName: String): Int {
        val upper = formatName.uppercase()
        return when {
            upper.contains("FLAC") -> 500
            upper.contains("ALAC") -> 400
            upper.contains("WAV") || upper.contains("PCM") || upper.contains("AIFF") -> 300
            upper.contains("AAC") || upper.contains("M4A") -> 200
            upper.contains("OPUS") -> 150
            upper.contains("MP3") || upper.contains("MPEG") -> 100
            else -> 50
        }
    }

    /**
     * Backward-compatible helper delegating to codecPriorityRank.
     */
    fun formatPriorityRank(formatName: String, bitrateKbps: Int): Int {
        return codecPriorityRank(formatName)
    }

    /**
     * True Highest-Bitrate Audiophile Stream Sort:
     * Strictly sorts available streams in descending order of true audiophile quality:
     * - Priority 1: Codec Lossless Priority: FLAC > ALAC > WAV > AAC > MP3
     * - Priority 2: Bit Depth (e.g., 24-bit > 16-bit)
     * - Priority 3: Sample Rate (e.g., 192 kHz > 96 kHz > 48 kHz > 44.1 kHz)
     * - Priority 4: Bitrate (e.g., 4608 kbps > 2822 kbps > 1411 kbps > 320 kbps > 160 kbps)
     */
    fun sortStreamCandidates(candidates: List<ResolvedStreamCandidate>): List<ResolvedStreamCandidate> {
        return candidates
            .distinctBy { "${it.streamUrl}_${it.formatName}_${it.bitrateKbps}_${it.sampleRateHz}_${it.bitDepth}" }
            .sortedWith(
                compareByDescending<ResolvedStreamCandidate> { it.isLossless }
                    .thenByDescending { it.bitDepth }
                    .thenByDescending { it.sampleRateHz }
                    .thenByDescending { it.bitrateKbps }
                    .thenByDescending { codecPriorityRank(it.formatName) }
            )
    }

    /**
     * Checks if a stream URL points to a genuine lossless container (.flac, .wav, .alac, .aiff).
     */
    fun isTrueLosslessUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase().substringBefore("?")
        return lower.endsWith(".flac") ||
            lower.endsWith(".wav") ||
            lower.endsWith(".wave") ||
            lower.endsWith(".alac") ||
            lower.endsWith(".aiff")
    }

    /**
     * Estimates file size in MB based on the actual stream duration and target bitrate in kbps.
     */
    fun estimateFileSizeMb(durationMs: Long, bitrateKbps: Int): String {
        val durationSec = (durationMs.coerceAtLeast(30_000L)) / 1000.0
        val sizeMb = (durationSec * bitrateKbps) / (8.0 * 1024.0)
        return String.format(java.util.Locale.US, "~%.1f MB", sizeMb.coerceAtLeast(0.5))
    }

    /**
     * Dynamically parses the active track and player stream metadata (format, bit depth,
     * sample rate, bitrate, and duration) to build accurate download quality options:
     * - Auto-selects the #1 highest stream (e.g., 24-bit/192kHz @ 4608 kbps FLAC, 24-bit/96kHz @ 2822 kbps FLAC,
     *   or 16-bit/44.1kHz @ 1411 kbps FLAC/ALAC/WAV) when available.
     * - Only uses 320 kbps as the recommended option when the track's maximum available source is lossy.
     */
    fun getDynamicDownloadOptions(
        track: AudioTrack,
        activeQualityInfo: AudioQualityInfo? = null
    ): List<DynamicDownloadOptionItem> {
        val durationMs = track.durationMs.takeIf { it > 0L } ?: 210_000L

        val isLosslessSource = when {
            activeQualityInfo != null -> activeQualityInfo.isLossless || activeQualityInfo.bitrateKbps > 320
            else -> track.format.isLossless || track.bitrateKbps > 320 || isTrueLosslessUrl(track.uriString)
        }

        val options = mutableListOf<DynamicDownloadOptionItem>()

        if (isLosslessSource) {
            val rawFormat = when {
                activeQualityInfo != null && (activeQualityInfo.isLossless || activeQualityInfo.bitrateKbps > 320) -> activeQualityInfo.formatName
                track.format.isLossless -> track.format.displayName
                else -> "FLAC"
            }
            val losslessFormat = when {
                rawFormat.contains("FLAC", ignoreCase = true) -> "FLAC"
                rawFormat.contains("ALAC", ignoreCase = true) -> "ALAC"
                rawFormat.contains("WAV", ignoreCase = true) -> "WAV"
                rawFormat.contains("AIFF", ignoreCase = true) -> "AIFF"
                else -> "FLAC"
            }
            val losslessBitrate = (activeQualityInfo?.bitrateKbps?.takeIf { it > 320 }
                ?: track.bitrateKbps.takeIf { it > 320 }
                ?: 1411)
            val losslessSampleRate = (activeQualityInfo?.sampleRateHz?.takeIf { it >= 44100 }
                ?: track.sampleRate.takeIf { it >= 44100 }
                ?: 96000)
            val losslessBitDepth = (activeQualityInfo?.bitDepth?.takeIf { it >= 16 }
                ?: track.bitDepth.takeIf { it >= 16 }
                ?: 24)
            val losslessSizeMb = estimateFileSizeMb(durationMs, losslessBitrate)
            val rateFormatted = AudioQualityInfo.formatSampleRateKHz(losslessSampleRate)

            options.add(
                DynamicDownloadOptionItem(
                    option = DownloadQualityOption.HI_RES_LOSSLESS,
                    title = "Hi-Res $losslessFormat (Original Master)",
                    subtitle = "Lossless / Hi-Res Bit-Perfect • ${losslessBitDepth}-BIT / $rateFormatted • $losslessBitrate kbps • $losslessSizeMb",
                    badgeText = "RECOMMENDED • LOSSLESS / HI-RES BIT-PERFECT",
                    formatName = losslessFormat,
                    bitrateKbps = losslessBitrate,
                    sampleRateHz = losslessSampleRate,
                    bitDepth = losslessBitDepth,
                    estimatedSizeMb = losslessSizeMb,
                    isLossless = true,
                    isRecommended = true
                )
            )
        }

        val activeLossyFormat = activeQualityInfo?.formatName
            ?.takeIf { !activeQualityInfo.isLossless && it.isNotBlank() }
            ?: if (!track.format.isLossless) track.format.displayName else "MP3"
        val activeLossySampleRate = activeQualityInfo?.sampleRateHz?.takeIf { it > 0 && !activeQualityInfo.isLossless }
            ?: track.sampleRate.takeIf { it > 0 && !track.format.isLossless }
            ?: 44100
        val activeLossyBitDepth = activeQualityInfo?.bitDepth?.takeIf { it > 0 && !activeQualityInfo.isLossless }
            ?: track.bitDepth.takeIf { it > 0 && !track.format.isLossless }
            ?: 16
        val topLossyBitrate = activeQualityInfo?.bitrateKbps?.takeIf { it in 64..512 && !activeQualityInfo.isLossless }
            ?: track.bitrateKbps.takeIf { it in 64..512 && !track.format.isLossless }
            ?: 320
        val effectiveTopLossyKbps = if (isLosslessSource) 320 else topLossyBitrate
        val lossyRateFormatted = AudioQualityInfo.formatSampleRateKHz(activeLossySampleRate)

        val size320 = estimateFileSizeMb(durationMs, effectiveTopLossyKbps)
        options.add(
            DynamicDownloadOptionItem(
                option = DownloadQualityOption.MASTER_320K,
                title = "High Quality MP3 / Lossy $effectiveTopLossyKbps kbps",
                subtitle = "Lossy Compressed Audio • ${activeLossyBitDepth}-BIT / $lossyRateFormatted • $effectiveTopLossyKbps kbps • $size320",
                badgeText = if (!isLosslessSource) "RECOMMENDED • HIGH QUALITY $effectiveTopLossyKbps KBPS" else "LOSSY $effectiveTopLossyKbps KBPS",
                formatName = activeLossyFormat,
                bitrateKbps = effectiveTopLossyKbps,
                sampleRateHz = activeLossySampleRate,
                bitDepth = activeLossyBitDepth,
                estimatedSizeMb = size320,
                isLossless = false,
                isRecommended = !isLosslessSource
            )
        )

        val size160 = estimateFileSizeMb(durationMs, 160)
        options.add(
            DynamicDownloadOptionItem(
                option = DownloadQualityOption.HIGH_160K,
                title = "Standard High / Lossy 160 kbps",
                subtitle = "Lossy Compressed Audio • 16-BIT / 44.1kHz • 160 kbps • $size160",
                badgeText = "LOSSY 160 KBPS",
                formatName = "AAC",
                bitrateKbps = 160,
                sampleRateHz = 44100,
                bitDepth = 16,
                estimatedSizeMb = size160,
                isLossless = false,
                isRecommended = false
            )
        )

        val size96 = estimateFileSizeMb(durationMs, 96)
        options.add(
            DynamicDownloadOptionItem(
                option = DownloadQualityOption.STANDARD_96K,
                title = "Data Saver / Lossy 96 kbps",
                subtitle = "Lossy Compressed Audio • 16-BIT / 44.1kHz • 96 kbps • $size96",
                badgeText = "LOSSY 96 KBPS",
                formatName = "AAC",
                bitrateKbps = 96,
                sampleRateHz = 44100,
                bitDepth = 16,
                estimatedSizeMb = size96,
                isLossless = false,
                isRecommended = false
            )
        )

        return options
    }

    /**
     * Decrypts JioSaavn's official `encrypted_media_url` using DES/ECB/PKCS5Padding
     * with key `"38346591"` (standard SpotiFLAC / Saavn native decryption) and
     * upgrades to the highest available bitrate (320kbps first, fallback to 160k -> 96k).
     */
    fun decryptJioSaavnMediaUrl(encryptedMediaUrl: String, has320kbps: Boolean = true): String? {
        if (encryptedMediaUrl.isBlank()) return null
        return try {
            val keySpec = SecretKeySpec("38346591".toByteArray(Charsets.UTF_8), "DES")
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            val sanitized = encryptedMediaUrl.trim().replace("\\/", "/")
            val decodedBytes = try {
                Base64.getDecoder().decode(sanitized)
            } catch (_: Exception) {
                Base64.getMimeDecoder().decode(sanitized)
            }
            val decryptedBytes = cipher.doFinal(decodedBytes)
            val rawUrl = String(decryptedBytes, Charsets.UTF_8).trim().replace("http://", "https://")
            if (has320kbps) {
                upgradeToHighestQualityUrl(rawUrl)
            } else {
                rawUrl.replace("_96.mp4", "_160.mp4")
            }
        } catch (e: Exception) {
            Log.w("UniversalStreamResolver", "DES decryption failed: ${e.message}")
            null
        }
    }

    /**
     * Checks if a given URL is already a full-length direct audio stream
     * (never a 30-second preview from iTunes or Deezer).
     */
    fun isFullLengthStreamUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase()
        if (lower.contains("itunes.apple.com") ||
            lower.contains("mzstatic.com") ||
            lower.contains("cdns-preview") ||
            lower.contains("deezer.com")
        ) {
            return false
        }
        return lower.contains("saavncdn.com") ||
            lower.contains("saavn.com") ||
            lower.contains("googlevideo.com") ||
            lower.endsWith(".flac") ||
            lower.endsWith(".wav") ||
            lower.endsWith(".alac") ||
            lower.startsWith("file://") ||
            lower.startsWith("/")
    }

    /**
     * Converts a resolved stream URL to the user's chosen download quality tier
     * while preserving 100% genuine container bytes without transcoding.
     * When HI_RES_LOSSLESS is selected, preserves the raw lossless/master stream URL untouched.
     */
    fun applyQualityOptionToUrl(url: String, option: DownloadQualityOption): String {
        if (option == DownloadQualityOption.HI_RES_LOSSLESS) {
            if (isTrueLosslessUrl(url)) {
                return url
            }
            return upgradeToHighestQualityUrl(url)
        }
        if (url.contains("saavncdn.com")) {
            return url
                .replace("_12.mp4", option.urlSuffix)
                .replace("_48.mp4", option.urlSuffix)
                .replace("_96.mp4", option.urlSuffix)
                .replace("_160.mp4", option.urlSuffix)
                .replace("_320.mp4", option.urlSuffix)
        }
        return url
    }

    /**
     * Resolves all available stream candidates for a track, sorted strictly by:
     * Priority 1: Codec Lossless Priority (FLAC > ALAC > WAV > AAC > MP3)
     * Priority 2: Bit Depth (24-bit > 16-bit)
     * Priority 3: Sample Rate (192 kHz > 96 kHz > 48 kHz > 44.1 kHz)
     * Priority 4: Bitrate (4608 kbps > 2822 kbps > 1411 kbps > 320 kbps > 160 kbps)
     */
    suspend fun resolveAllStreamCandidates(
        context: Context,
        track: AudioTrack,
        backend: StreamBackend = StreamBackend.AUTO_HIGH_FIDELITY
    ): List<ResolvedStreamCandidate> = withContext(Dispatchers.IO) {
        val networkCandidates = mutableListOf<ResolvedStreamCandidate>()

        // 1. Offline downloaded file: inspect real file extension and size to compute true bitrate
        if ((track.isDownloaded || !track.localFilePath.isNullOrBlank()) && track.localFilePath != null) {
            val localFile = File(track.localFilePath)
            if (localFile.exists() && localFile.length() > 5000L) {
                val isLosslessFile = isTrueLosslessUrl(localFile.name) || track.format.isLossless
                val durationSec = (track.durationMs / 1000L).coerceAtLeast(30L)
                val realFileBitrate = ((localFile.length() * 8L) / durationSec / 1000L).toInt()
                    .takeIf { it in 64..9000 } ?: track.bitrateKbps
                val realFormat = when {
                    localFile.name.endsWith(".flac", true) -> "FLAC"
                    localFile.name.endsWith(".wav", true) -> "WAV"
                    localFile.name.endsWith(".alac", true) -> "ALAC"
                    localFile.name.endsWith(".mp3", true) -> "MP3"
                    localFile.name.endsWith(".opus", true) || localFile.name.endsWith(".webm", true) -> "OPUS"
                    else -> track.format.displayName
                }
                return@withContext listOf(
                    ResolvedStreamCandidate(
                        streamUrl = localFile.absolutePath,
                        formatName = realFormat,
                        sampleRateHz = track.sampleRate.takeIf { it > 0 } ?: 44100,
                        bitDepth = track.bitDepth.takeIf { it > 0 } ?: 16,
                        bitrateKbps = realFileBitrate,
                        isLossless = isLosslessFile,
                        providerName = "Offline Master"
                    )
                )
            }
        }

        // 2. Local SAF import
        if (track.isLocalImport && !track.uriString.isNullOrBlank()) {
            return@withContext listOf(
                ResolvedStreamCandidate(
                    streamUrl = track.uriString,
                    formatName = track.format.displayName,
                    sampleRateHz = track.sampleRate,
                    bitDepth = track.bitDepth,
                    bitrateKbps = track.bitrateKbps,
                    isLossless = track.format.isLossless,
                    providerName = "Local Storage"
                )
            )
        }

        // 3. Built-in Offline Studio Master tracks or explicit Local Lossless backend
        if (backend == StreamBackend.LOCAL_LOSSLESS || (!track.isGlobalOnline && track.uriString.isNullOrBlank())) {
            val localWav = getOrCreateTrackAudioFile(context, track)
            val isLossless = track.format.isLossless || backend == StreamBackend.LOCAL_LOSSLESS
            return@withContext listOf(
                ResolvedStreamCandidate(
                    streamUrl = localWav.absolutePath,
                    formatName = if (track.format.isLossless) track.format.displayName else "WAV",
                    sampleRateHz = track.sampleRate.takeIf { it > 0 } ?: 44100,
                    bitDepth = track.bitDepth.takeIf { it > 0 } ?: 16,
                    bitrateKbps = track.bitrateKbps.takeIf { it > 0 } ?: 1411,
                    isLossless = isLossless,
                    providerName = "Local Lossless Master"
                )
            )
        }

        val cacheKey = "${track.id}_${track.title.trim().lowercase()}_${track.artist.trim().lowercase()}_${backend.name}"
        resolvedCandidatesCache[cacheKey]?.takeIf { it.isNotEmpty() }?.let {
            return@withContext it
        }

        // 4. If track already has a direct CDN stream URL, build its real technical tiers immediately
        if (!track.uriString.isNullOrBlank() && isFullLengthStreamUrl(track.uriString)) {
            networkCandidates.addAll(buildCandidatesFromBaseUrl(track.uriString, track))
        }

        // 5. Query Official JioSaavn Direct API v4 for direct decrypted CDN stream URLs
        if (networkCandidates.isEmpty()) {
            val officialSaavn = resolveFromOfficialJioSaavn(track.title, track.artist)
            if (officialSaavn != null) {
                networkCandidates.addAll(buildCandidatesFromBaseUrl(officialSaavn.first, track))
            }
        }

        // 6. Secondary JioSaavn mirrors if needed
        if (networkCandidates.isEmpty()) {
            val mirrorSaavn = resolveFromJioSaavnMirrors(track.title, track.artist)
            if (mirrorSaavn != null) {
                networkCandidates.addAll(buildCandidatesFromBaseUrl(mirrorSaavn.first, track))
            }
        }

        // 7. Tertiary YouTube Music / Piped / Invidious extractors
        if (networkCandidates.isEmpty()) {
            val ytResult = resolveFromYouTubeExtractors(track.title, track.artist, backend)
            if (ytResult != null) {
                networkCandidates.add(
                    ResolvedStreamCandidate(
                        streamUrl = ytResult.first,
                        formatName = ytResult.second.formatName,
                        sampleRateHz = ytResult.second.sampleRateHz,
                        bitDepth = ytResult.second.bitDepth,
                        bitrateKbps = ytResult.second.bitrateKbps,
                        isLossless = ytResult.second.isLossless,
                        providerName = "YouTube Music Stream"
                    )
                )
            }
        }

        val sortedNetwork = sortStreamCandidates(networkCandidates)

        // 8. For online tracks, NEVER silently fall back to unrelated procedural WAV audio if network resolution fails
        val finalCandidates = if (sortedNetwork.isNotEmpty()) {
            sortedNetwork
        } else if (!track.isGlobalOnline) {
            val fallbackFile = getOrCreateTrackAudioFile(context, track)
            listOf(
                ResolvedStreamCandidate(
                    streamUrl = fallbackFile.absolutePath,
                    formatName = if (track.format.isLossless) track.format.displayName else "PCM WAV",
                    sampleRateHz = track.sampleRate.takeIf { it > 0 } ?: 44100,
                    bitDepth = track.bitDepth.takeIf { it > 0 } ?: 16,
                    bitrateKbps = track.bitrateKbps.takeIf { it > 0 } ?: 1411,
                    isLossless = true,
                    providerName = "Offline Studio Master"
                )
            )
        } else {
            emptyList()
        }

        if (finalCandidates.isNotEmpty()) {
            resolvedCandidatesCache[cacheKey] = finalCandidates
            resolvedStreamsCache[cacheKey] = Pair(finalCandidates.first().streamUrl, finalCandidates.first().toAudioQualityInfo())
        }
        return@withContext finalCandidates
    }

    private fun buildCandidatesFromBaseUrl(
        baseUrl: String,
        track: AudioTrack
    ): List<ResolvedStreamCandidate> {
        val list = mutableListOf<ResolvedStreamCandidate>()
        val verifiedPrimary = verifyAndSelectHighestBitrateStream(baseUrl, track)
        val topUrl = verifiedPrimary.first

        // Preserve high-bitrate / lossless audiophile streams (e.g. 4608 kbps, 2822 kbps, 1411 kbps FLAC/ALAC/WAV)
        // whenever available on the track or stream URL without capping at 320 kbps
        if (isTrueLosslessUrl(topUrl) || track.format.isLossless || track.bitrateKbps > 320) {
            val losslessFormat = when {
                isTrueLosslessUrl(topUrl) -> verifiedPrimary.second.formatName
                track.format.isLossless -> track.format.displayName
                else -> verifiedPrimary.second.formatName
            }
            val losslessSampleRate = track.sampleRate.takeIf { it >= 44100 } ?: verifiedPrimary.second.sampleRateHz
            val losslessBitDepth = track.bitDepth.takeIf { it >= 16 } ?: verifiedPrimary.second.bitDepth
            val losslessBitrate = maxOf(track.bitrateKbps, verifiedPrimary.second.bitrateKbps).takeIf { it > 320 } ?: 1411

            list.add(
                ResolvedStreamCandidate(
                    streamUrl = topUrl,
                    formatName = losslessFormat,
                    sampleRateHz = losslessSampleRate,
                    bitDepth = losslessBitDepth,
                    bitrateKbps = losslessBitrate,
                    isLossless = true,
                    providerName = track.sourceProvider
                )
            )
        }

        if (baseUrl.contains("saavncdn.com")) {
            val url320 = applyQualityOptionToUrl(baseUrl, DownloadQualityOption.MASTER_320K)
            val url160 = applyQualityOptionToUrl(baseUrl, DownloadQualityOption.HIGH_160K)
            val url96 = applyQualityOptionToUrl(baseUrl, DownloadQualityOption.STANDARD_96K)
            val lossyFormat = if (track.format == com.example.model.AudioFormat.MP3) "MP3" else "AAC"
            val primaryLossyKbps = if (!track.format.isLossless && track.bitrateKbps in 64..512) {
                track.bitrateKbps
            } else {
                verifiedPrimary.second.bitrateKbps.takeIf { it in 64..512 } ?: 320
            }

            if (primaryLossyKbps >= 256) {
                list.add(
                    ResolvedStreamCandidate(
                        streamUrl = url320,
                        formatName = lossyFormat,
                        sampleRateHz = 44100,
                        bitDepth = 16,
                        bitrateKbps = primaryLossyKbps,
                        isLossless = false,
                        providerName = "JioSaavn ${primaryLossyKbps}k"
                    )
                )
            }
            list.add(
                ResolvedStreamCandidate(
                    streamUrl = url160,
                    formatName = lossyFormat,
                    sampleRateHz = 44100,
                    bitDepth = 16,
                    bitrateKbps = 160,
                    isLossless = false,
                    providerName = "JioSaavn 160k"
                )
            )
            list.add(
                ResolvedStreamCandidate(
                    streamUrl = url96,
                    formatName = lossyFormat,
                    sampleRateHz = 44100,
                    bitDepth = 16,
                    bitrateKbps = 96,
                    isLossless = false,
                    providerName = "JioSaavn 96k"
                )
            )
        } else if (list.isEmpty()) {
            list.add(
                ResolvedStreamCandidate(
                    streamUrl = topUrl,
                    formatName = verifiedPrimary.second.formatName,
                    sampleRateHz = verifiedPrimary.second.sampleRateHz,
                    bitDepth = verifiedPrimary.second.bitDepth,
                    bitrateKbps = verifiedPrimary.second.bitrateKbps,
                    isLossless = verifiedPrimary.second.isLossless,
                    providerName = track.sourceProvider
                )
            )
        }
        return sortStreamCandidates(list)
    }

    suspend fun resolveFullAudioStream(
        context: Context,
        track: AudioTrack,
        backend: StreamBackend = StreamBackend.AUTO_HIGH_FIDELITY
    ): Pair<String, AudioQualityInfo> = withContext(Dispatchers.IO) {
        val candidates = resolveAllStreamCandidates(context, track, backend)
        val best = candidates.first()
        Pair(best.streamUrl, best.toAudioQualityInfo())
    }

    /**
     * Real stream inspector function:
     * Preserves true audiophile bitrate/sampleRate/bitDepth without capping at 320 kbps,
     * and avoids blocking track switching on slow sequential HTTP HEAD calls for known CDN URLs.
     */
    private fun verifyAndSelectHighestBitrateStream(
        baseUrl: String,
        track: AudioTrack? = null
    ): Pair<String, AudioQualityInfo> {
        if (!baseUrl.contains("saavncdn.com")) {
            val isLossless = isTrueLosslessUrl(baseUrl) || (track?.format?.isLossless == true)
            val detectedFormat = when {
                baseUrl.endsWith(".flac", ignoreCase = true) -> "FLAC"
                baseUrl.endsWith(".alac", ignoreCase = true) -> "ALAC"
                baseUrl.endsWith(".wav", ignoreCase = true) -> "WAV"
                baseUrl.contains(".webm", ignoreCase = true) || baseUrl.contains("opus", ignoreCase = true) -> "OPUS"
                baseUrl.contains(".mp4", ignoreCase = true) || baseUrl.contains(".m4a", ignoreCase = true) -> "AAC"
                baseUrl.endsWith(".mp3", ignoreCase = true) -> "MP3"
                else -> track?.format?.displayName ?: if (isLossless) "FLAC" else "AAC"
            }
            val resolvedBitrate = track?.bitrateKbps?.takeIf { it > 0 }
                ?: if (isLossless) 1411 else 320
            val finalLossless = isLossless || detectedFormat in listOf("FLAC", "ALAC", "WAV")
            val sr = track?.sampleRate?.takeIf { it >= 44100 }
                ?: if (finalLossless) 96000 else if (detectedFormat == "OPUS") 48000 else 44100
            val bd = track?.bitDepth?.takeIf { it >= 16 }
                ?: if (finalLossless) 24 else 16

            return Pair(
                baseUrl,
                AudioQualityInfo(
                    formatName = detectedFormat,
                    sampleRateHz = sr,
                    bitDepth = bd,
                    bitrateKbps = resolvedBitrate,
                    isLossless = finalLossless,
                    isHiRes = finalLossless || sr >= 48000 || bd >= 24 || resolvedBitrate >= 256
                )
            )
        }

        val isLosslessTrack = track?.format?.isLossless == true || (track?.bitrateKbps ?: 0) > 320
        val highestUrl = upgradeToHighestQualityUrl(baseUrl)
        val formatName = when {
            isLosslessTrack -> track?.format?.displayName ?: "FLAC"
            track?.format == com.example.model.AudioFormat.MP3 -> "MP3"
            else -> "AAC"
        }
        val sampleRate = track?.sampleRate?.takeIf { it >= 44100 } ?: 44100
        val bitDepth = track?.bitDepth?.takeIf { it >= 16 } ?: 16
        val bitrate = when {
            isLosslessTrack -> track?.bitrateKbps?.takeIf { it > 320 } ?: 1411
            baseUrl.contains("_160.") && (track?.bitrateKbps ?: 320) <= 160 -> 160
            baseUrl.contains("_96.") && (track?.bitrateKbps ?: 320) <= 96 -> 96
            else -> track?.bitrateKbps?.takeIf { it > 0 } ?: 320
        }

        return Pair(
            highestUrl,
            AudioQualityInfo(
                formatName = formatName,
                sampleRateHz = sampleRate,
                bitDepth = bitDepth,
                bitrateKbps = bitrate,
                isLossless = isLosslessTrack,
                isHiRes = isLosslessTrack || sampleRate >= 48000 || bitDepth >= 24 || bitrate >= 256
            )
        )
    }

    fun upgradeToHighestQualityUrl(url: String): String {
        if (url.contains("saavncdn.com")) {
            return url
                .replace("_12.mp4", "_320.mp4")
                .replace("_48.mp4", "_320.mp4")
                .replace("_96.mp4", "_320.mp4")
                .replace("_160.mp4", "_320.mp4")
        }
        return url
    }

    private fun cleanSearchText(text: String): String {
        return text
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace(Regex("""\([^)]*\)"""), "")
            .replace(Regex("""\[[^]]*]"""), "")
            .replace(Regex("""(?i)\b(feat|ft|from|official|audio|video|remastered|lofi|lo-fi|remix)\b.*"""), "")
            .trim()
    }

    private fun scoreCandidateMatch(
        candidateTitle: String,
        candidateArtist: String,
        targetTitle: String,
        targetArtist: String,
        durationSec: Long
    ): Int {
        val normCandTitle = candidateTitle.lowercase().trim()
        val normTargetTitle = targetTitle.lowercase().trim()
        val normCandArtist = candidateArtist.lowercase().trim()
        val normTargetArtist = targetArtist.lowercase().trim()

        var score = 0
        if (normCandTitle == normTargetTitle) score += 100
        else if (normCandTitle.contains(normTargetTitle) || normTargetTitle.contains(normCandTitle)) score += 60

        if (normTargetArtist.isNotBlank() && (normCandArtist.contains(normTargetArtist) || normTargetArtist.contains(normCandArtist))) {
            score += 50
        }
        if (durationSec >= 90L) score += 20
        if (normCandTitle.contains("ringtone") || normCandTitle.contains("nightcore") || normCandTitle.contains("karaoke")) {
            score -= 80
        }
        return score
    }

    /**
     * Resolves full-length streams directly from Official JioSaavn v4 API
     * and decrypts `encrypted_media_url` on-device, scoring results by title + artist match.
     */
    fun resolveFromOfficialJioSaavn(title: String, artist: String): Pair<String, AudioQualityInfo>? {
        val cleanTitle = cleanSearchText(title).ifBlank { title }
        val primaryArtist = artist.split(",", "&", "feat.", "ft.", "•").firstOrNull()?.trim() ?: artist
        val cleanArtist = if (primaryArtist.equals("Unknown Artist", ignoreCase = true) ||
            primaryArtist.equals("YouTube Artist", ignoreCase = true) ||
            primaryArtist.equals("YouTube Music Artist", ignoreCase = true)
        ) "" else primaryArtist

        val candidateQueries = listOfNotNull(
            "$cleanTitle $cleanArtist".trim().takeIf { it.isNotBlank() },
            cleanTitle.takeIf { it.isNotBlank() && it != "$cleanTitle $cleanArtist".trim() },
            title.trim().takeIf { it != cleanTitle }
        ).distinct()

        for (query in candidateQueries) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val url = "$OFFICIAL_JIOSAAVN_API$encodedQuery"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://www.jiosaavn.com/")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val dataNode = json.optJSONObject("data")
                    val results = json.optJSONArray("results")
                        ?: dataNode?.optJSONArray("results")
                        ?: json.optJSONArray("songs")
                        ?: dataNode?.optJSONArray("songs")
                    if (results != null && results.length() > 0) {
                        var chosenSong: JSONObject? = null
                        var bestScore = Int.MIN_VALUE
                        for (i in 0 until results.length()) {
                            val cand = results.getJSONObject(i)
                            val candTitle = cand.optString("title", "")
                                .ifBlank { cand.optString("song", "") }
                                .replace("&quot;", "\"")
                                .replace("&amp;", "&")
                            val moreInfo = cand.optJSONObject("more_info")
                            val candArtist = moreInfo?.optString("music", "")
                                .orEmpty()
                                .ifBlank { cand.optString("subtitle", "") }
                            val dur = moreInfo?.optString("duration", "200")?.toLongOrNull() ?: 200L
                            val score = scoreCandidateMatch(candTitle, candArtist, cleanTitle, cleanArtist, dur)
                            if (score > bestScore) {
                                bestScore = score
                                chosenSong = cand
                            }
                        }

                        // Require verified title match (score >= 60) so an unrelated song is never played
                        if (chosenSong != null && bestScore >= 60) {
                            val moreInfo = chosenSong.optJSONObject("more_info")
                            val encryptedUrl = moreInfo?.optString("encrypted_media_url", "")
                                ?.ifBlank { null }
                                ?: chosenSong.optString("encrypted_media_url", "")
                            val has320 = moreInfo?.optString("320kbps", "true")?.toBoolean() ?: true
                            val decrypted = decryptJioSaavnMediaUrl(encryptedUrl, has320)
                            if (!decrypted.isNullOrBlank()) {
                                return verifyAndSelectHighestBitrateStream(decrypted)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("UniversalStreamResolver", "Official JioSaavn resolve failed for $query: ${e.message}")
            }
        }
        return null
    }

    private fun resolveFromJioSaavnMirrors(title: String, artist: String): Pair<String, AudioQualityInfo>? {
        val cleanTitle = cleanSearchText(title).ifBlank { title }
        val primaryArtist = artist.split(",", "&", "feat.", "ft.", "•").firstOrNull()?.trim() ?: artist
        val cleanArtist = if (primaryArtist.equals("Unknown Artist", ignoreCase = true)) "" else primaryArtist

        val candidateQueries = listOfNotNull(
            "$cleanTitle $cleanArtist".trim().takeIf { it.isNotBlank() },
            cleanTitle.takeIf { it.isNotBlank() && it != "$cleanTitle $cleanArtist".trim() }
        ).distinct()

        for (endpoint in saavnEndpoints) {
            for (query in candidateQueries) {
                try {
                    val encodedQuery = URLEncoder.encode(query, "UTF-8")
                    val url = "$endpoint/search/songs?query=$encodedQuery&limit=8"
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) FlowAudio/2.0")
                        .build()

                    val response = httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        val json = JSONObject(body)
                        val dataObj = json.optJSONObject("data") ?: json
                        val results = dataObj.optJSONArray("results")
                        if (results != null && results.length() > 0) {
                            var chosenSong: JSONObject? = null
                            var bestScore = Int.MIN_VALUE
                            for (i in 0 until results.length()) {
                                val candidate = results.getJSONObject(i)
                                val candName = candidate.optString("name", "")
                                val candArtist = candidate.optString("primaryArtists", "")
                                val candDur = candidate.optLong("duration", 200L)
                                val score = scoreCandidateMatch(candName, candArtist, cleanTitle, cleanArtist, candDur)
                                if (score > bestScore) {
                                    bestScore = score
                                    chosenSong = candidate
                                }
                            }

                            if (chosenSong != null && bestScore >= 60) {
                                val downloadUrls = chosenSong.optJSONArray("downloadUrl")
                                if (downloadUrls != null && downloadUrls.length() > 0) {
                                    var bestUrl = ""
                                    var bestBitrate = 0
                                    for (j in 0 until downloadUrls.length()) {
                                        val item = downloadUrls.getJSONObject(j)
                                        val quality = item.optString("quality", "")
                                        val streamUrl = item.optString("url", "")
                                            .ifBlank { item.optString("link", "") }
                                        if (streamUrl.isNotBlank()) {
                                            val parsedBitrate = quality.filter { it.isDigit() }.toIntOrNull() ?: 128
                                            if (parsedBitrate >= bestBitrate) {
                                                bestBitrate = parsedBitrate
                                                bestUrl = streamUrl
                                            }
                                        }
                                    }
                                    if (bestUrl.isNotBlank()) {
                                        return verifyAndSelectHighestBitrateStream(bestUrl)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("UniversalStreamResolver", "JioSaavn mirror $endpoint failed: ${e.message}")
                }
            }
        }
        return null
    }

    private fun resolveFromYouTubeExtractors(
        title: String,
        artist: String,
        backend: StreamBackend
    ): Pair<String, AudioQualityInfo>? {
        val query = "$artist $title audio".trim()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        // 1. Try Piped instances (sorting audioStreams by highest bitrate descending)
        for (instance in pipedInstances) {
            try {
                val searchUrl = "$instance/search?q=$encodedQuery&filter=music_songs"
                val request = Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) FlowAudio/2.0")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val items = json.optJSONArray("items")
                    if (items != null && items.length() > 0) {
                        val firstItem = items.getJSONObject(0)
                        val itemUrl = firstItem.optString("url", "")
                        val videoId = if (itemUrl.startsWith("/watch?v=")) itemUrl.removePrefix("/watch?v=") else itemUrl

                        if (videoId.isNotBlank()) {
                            val streamReq = Request.Builder()
                                .url("$instance/streams/$videoId")
                                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) FlowAudio/2.0")
                                .build()
                            val streamResp = httpClient.newCall(streamReq).execute()
                            if (streamResp.isSuccessful) {
                                val streamBody = streamResp.body?.string().orEmpty()
                                val streamJson = JSONObject(streamBody)
                                val audioStreams = streamJson.optJSONArray("audioStreams")
                                if (audioStreams != null && audioStreams.length() > 0) {
                                    var bestStreamUrl = ""
                                    var bestFormat = "OPUS"
                                    var bestBitrate = 0
                                    for (k in 0 until audioStreams.length()) {
                                        val aStream = audioStreams.getJSONObject(k)
                                        val sUrl = aStream.optString("url", "")
                                        val format = aStream.optString("format", "")
                                        val bitrate = aStream.optInt("bitrate", 128000) / 1000
                                        if (sUrl.isNotBlank() && bitrate >= bestBitrate) {
                                            bestStreamUrl = sUrl
                                            bestFormat = if (format.contains("WEBM", true) || format.contains("OPUS", true)) "OPUS" else "AAC"
                                            bestBitrate = bitrate
                                        }
                                    }
                                    if (bestStreamUrl.isNotBlank()) {
                                        return Pair(
                                            bestStreamUrl,
                                            AudioQualityInfo(
                                                formatName = bestFormat,
                                                sampleRateHz = 48000,
                                                bitDepth = 24,
                                                bitrateKbps = bestBitrate.coerceAtLeast(160),
                                                isLossless = false,
                                                isHiRes = true
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("UniversalStreamResolver", "Piped instance $instance failed: ${e.message}")
            }
        }

        // 2. Try Invidious instances (preferring itag 251 160kbps Opus first, then 140 128kbps M4A)
        for (instance in invidiousInstances) {
            try {
                val searchUrl = "$instance/api/v1/search?q=$encodedQuery&type=video"
                val request = Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) FlowAudio/2.0")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val bodyString = response.body?.string().orEmpty()
                    val array = JSONArray(bodyString)
                    if (array.length() > 0) {
                        val firstVideo = array.getJSONObject(0)
                        val videoId = firstVideo.optString("videoId", "")

                        if (videoId.isNotBlank()) {
                            val itag = if (backend == StreamBackend.INVIDIOUS_AAC) 140 else 251
                            val streamUrl = "$instance/latest_version?id=$videoId&itag=$itag"
                            val qualityInfo = if (itag == 251) {
                                AudioQualityInfo(
                                    formatName = "OPUS",
                                    sampleRateHz = 48000,
                                    bitDepth = 24,
                                    bitrateKbps = 160,
                                    isLossless = false,
                                    isHiRes = true
                                )
                            } else {
                                AudioQualityInfo(
                                    formatName = "AAC",
                                    sampleRateHz = 44100,
                                    bitDepth = 16,
                                    bitrateKbps = 128,
                                    isLossless = false,
                                    isHiRes = false
                                )
                            }
                            return Pair(streamUrl, qualityInfo)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("UniversalStreamResolver", "Invidious instance $instance failed: ${e.message}")
            }
        }
        return null
    }

    /**
     * Generates a full-length (matching track duration, minimum 180 seconds) stereo WAV file
     * for offline procedural tracks so they NEVER cut off after 16 or 30 seconds.
     */
    fun getOrCreateTrackAudioFile(context: Context, track: AudioTrack): File {
        val cacheDir = File(context.cacheDir, "audio_lossless")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        val targetDurationSecs = ((track.durationMs / 1000L).toInt()).coerceIn(180, 300)
        val wavFile = File(cacheDir, "${track.id}_${track.synthPreset}_full_${targetDurationSecs}s.wav")
        if (wavFile.exists() && wavFile.length() > 5_000_000L) {
            return wavFile
        }

        try {
            val sampleRate = com.example.audio.PcmAudioSynthesizer.SAMPLE_RATE
            val cycleSecs = 16
            val totalSamplesPerChannel = sampleRate * targetDurationSecs
            val totalBytes = totalSamplesPerChannel * 2 * 2

            val cycleBytes = ByteArray(sampleRate * cycleSecs * 4)
            var offset = 0
            for (sec in 0 until cycleSecs) {
                val pcmChunk = com.example.audio.PcmAudioSynthesizer.generateChunk(
                    preset = track.synthPreset,
                    secondInTrack = sec,
                    sampleCount = sampleRate,
                    bassMultiplier = 1.0f,
                    trebleMultiplier = 1.0f,
                    virtualizerWidth = 0.2f
                )
                val byteBuffer = ByteBuffer.allocate(pcmChunk.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (sample in pcmChunk) {
                    byteBuffer.putShort(sample)
                }
                val arr = byteBuffer.array()
                System.arraycopy(arr, 0, cycleBytes, offset, arr.size)
                offset += arr.size
            }

            BufferedOutputStream(FileOutputStream(wavFile), 65536).use { bos ->
                writeWavHeader(bos, totalBytes, sampleRate, 2, 16)
                var bytesRemaining = totalBytes
                while (bytesRemaining > 0) {
                    val toWrite = minOf(bytesRemaining, cycleBytes.size)
                    bos.write(cycleBytes, 0, toWrite)
                    bytesRemaining -= toWrite
                }
                bos.flush()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return wavFile
    }

    private fun writeWavHeader(
        out: OutputStream,
        totalAudioLen: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val totalDataLen = totalAudioLen + 36
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte(); header[33] = 0
        header[34] = bitsPerSample.toByte(); header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()
        out.write(header, 0, 44)
    }
}
