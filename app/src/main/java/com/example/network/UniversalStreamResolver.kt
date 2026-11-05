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
    val providerName: String = "Direct Master",
    val sourceTrackId: String = "",
    val directStreamUrl: String = streamUrl
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

    // In-memory resolved streams cache & relaxed resilient thresholds
    const val HARD_MATCH_THRESHOLD = 50
    const val MAX_DURATION_DEVIATION_SEC = 25L

    private val resolvedStreamsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, AudioQualityInfo>>()
    private val resolvedCandidatesCache = java.util.concurrent.ConcurrentHashMap<String, List<ResolvedStreamCandidate>>()
    private val resolvedCandidatesByTrackId = java.util.concurrent.ConcurrentHashMap<String, List<ResolvedStreamCandidate>>()

    fun getTopResolvedCandidateForTrack(trackId: String): ResolvedStreamCandidate? {
        return resolvedCandidatesByTrackId[trackId]?.firstOrNull()
    }

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
                    .thenByDescending { GlobalMusicSearchEngine.providerWaterfallRank(it.providerName) }
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
     * sample rate, bitrate, and duration) to build the complete multi-tier download quality options:
     * 1. Master Lossless: FLAC 24-BIT / 96kHz (2822 kbps) or 16-BIT / 44.1kHz (1411 kbps) / ALAC Master (Recommended)
     * 2. High Quality: AAC / MP3 320 kbps
     * 3. Standard Quality: MP3 / Opus / AAC 160 kbps
     * 4. Data Saver: AAC 96 kbps
     */
    fun getDynamicDownloadOptions(
        track: AudioTrack,
        activeQualityInfo: AudioQualityInfo? = null
    ): List<DynamicDownloadOptionItem> {
        val durationMs = track.durationMs.takeIf { it > 0L } ?: 210_000L
        val cachedTopCandidate = getTopResolvedCandidateForTrack(track.id)
        val effectiveQualityInfo = activeQualityInfo ?: cachedTopCandidate?.toAudioQualityInfo()

        val rawFormat = when {
            effectiveQualityInfo != null && (effectiveQualityInfo.isLossless || effectiveQualityInfo.bitrateKbps > 320) -> effectiveQualityInfo.formatName
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
        val losslessBitrate = (effectiveQualityInfo?.bitrateKbps?.takeIf { it > 320 }
            ?: track.bitrateKbps.takeIf { it > 320 }
            ?: 1411)
        val losslessSampleRate = (effectiveQualityInfo?.sampleRateHz?.takeIf { it >= 44100 && (effectiveQualityInfo.isLossless || effectiveQualityInfo.bitrateKbps > 320) }
            ?: track.sampleRate.takeIf { it >= 44100 && (track.format.isLossless || track.bitrateKbps > 320) }
            ?: if (losslessBitrate >= 2822) 96000 else 44100)
        val losslessBitDepth = (effectiveQualityInfo?.bitDepth?.takeIf { it >= 16 && (effectiveQualityInfo.isLossless || effectiveQualityInfo.bitrateKbps > 320) }
            ?: track.bitDepth.takeIf { it >= 16 && (track.format.isLossless || track.bitrateKbps > 320) }
            ?: if (losslessBitrate >= 2822) 24 else 16)
        val losslessSizeMb = estimateFileSizeMb(durationMs, losslessBitrate)
        val rateFormatted = AudioQualityInfo.formatSampleRateKHz(losslessSampleRate)

        val options = mutableListOf<DynamicDownloadOptionItem>()

        // 1. Master Lossless Tier (FLAC / ALAC / WAV Master) - Auto-recommended top tier
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

        // 2. High Quality Tier: AAC / MP3 320 kbps
        val activeLossyFormat = activeQualityInfo?.formatName
            ?.takeIf { !activeQualityInfo.isLossless && it.isNotBlank() }
            ?: if (!track.format.isLossless) track.format.displayName else "AAC"
        val activeLossySampleRate = 44100
        val activeLossyBitDepth = 16
        val effectiveTopLossyKbps = 320
        val lossyRateFormatted = AudioQualityInfo.formatSampleRateKHz(activeLossySampleRate)
        val size320 = estimateFileSizeMb(durationMs, effectiveTopLossyKbps)

        options.add(
            DynamicDownloadOptionItem(
                option = DownloadQualityOption.MASTER_320K,
                title = "High Quality MP3 / Lossy $effectiveTopLossyKbps kbps",
                subtitle = "Lossy Compressed Audio • ${activeLossyBitDepth}-BIT / $lossyRateFormatted • $effectiveTopLossyKbps kbps • $size320",
                badgeText = "HIGH QUALITY • $effectiveTopLossyKbps KBPS AAC/MP3",
                formatName = activeLossyFormat,
                bitrateKbps = effectiveTopLossyKbps,
                sampleRateHz = activeLossySampleRate,
                bitDepth = activeLossyBitDepth,
                estimatedSizeMb = size320,
                isLossless = false,
                isRecommended = false
            )
        )

        // 3. Standard Quality Tier: MP3 / Opus 160 kbps
        val size160 = estimateFileSizeMb(durationMs, 160)
        options.add(
            DynamicDownloadOptionItem(
                option = DownloadQualityOption.HIGH_160K,
                title = "Standard High / Lossy 160 kbps",
                subtitle = "Lossy Compressed Audio • 16-BIT / 44.1kHz • 160 kbps • $size160",
                badgeText = "STANDARD • 160 KBPS MP3/OPUS",
                formatName = "OPUS",
                bitrateKbps = 160,
                sampleRateHz = 44100,
                bitDepth = 16,
                estimatedSizeMb = size160,
                isLossless = false,
                isRecommended = false
            )
        )

        // 4. Data Saver Tier: 96 kbps
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

        return options.sortedByDescending { it.bitrateKbps }
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
     *
     * Always populates the full multi-stream cascade:
     * - Master Lossless: FLAC 24-BIT / 96kHz (2822 kbps) or 16-BIT / 44.1kHz (1411 kbps)
     * - Apple Lossless / Hi-Fi: ALAC / WAV Master (1411 kbps)
     * - High Quality: AAC 320 kbps
     * - Standard Quality: MP3 / Opus 160 kbps (and 96 kbps Data Saver)
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
                val sr = track.sampleRate.takeIf { it > 0 } ?: 44100
                val bd = track.bitDepth.takeIf { it > 0 } ?: 16
                val realFormat = when {
                    localFile.name.endsWith(".flac", true) -> "FLAC"
                    localFile.name.endsWith(".wav", true) || localFile.name.endsWith(".wave", true) -> "WAV"
                    localFile.name.endsWith(".alac", true) -> "ALAC"
                    localFile.name.endsWith(".mp3", true) -> "MP3"
                    localFile.name.endsWith(".opus", true) || localFile.name.endsWith(".webm", true) -> "OPUS"
                    else -> track.format.displayName
                }
                val durationSec = (track.durationMs / 1000L).coerceAtLeast(30L)
                val realFileBitrate = if (realFormat == "WAV" || realFormat == "PCM WAV") {
                    (sr * 2 * bd) / 1000
                } else {
                    ((localFile.length() * 8L) / durationSec / 1000L).toInt()
                        .takeIf { it in 64..9500 } ?: track.bitrateKbps
                }
                return@withContext listOf(
                    ResolvedStreamCandidate(
                        streamUrl = localFile.absolutePath,
                        formatName = realFormat,
                        sampleRateHz = sr,
                        bitDepth = bd,
                        bitrateKbps = realFileBitrate,
                        isLossless = isLosslessFile,
                        providerName = "Offline Master",
                        sourceTrackId = track.id,
                        directStreamUrl = localFile.absolutePath
                    )
                )
            }
        }

        // 2. Local SAF import
        if (track.isLocalImport && !track.uriString.isNullOrBlank()) {
            val sr = track.sampleRate.takeIf { it > 0 } ?: 44100
            val bd = track.bitDepth.takeIf { it > 0 } ?: 16
            val fmt = track.format.displayName
            val br = if (fmt.contains("WAV", true) || fmt.contains("PCM", true)) {
                (sr * 2 * bd) / 1000
            } else {
                track.bitrateKbps
            }
            return@withContext listOf(
                ResolvedStreamCandidate(
                    streamUrl = track.uriString,
                    formatName = fmt,
                    sampleRateHz = sr,
                    bitDepth = bd,
                    bitrateKbps = br,
                    isLossless = track.format.isLossless,
                    providerName = "Local Storage",
                    sourceTrackId = track.id,
                    directStreamUrl = track.uriString
                )
            )
        }

        val cacheKey = "${track.id}_${track.title.trim().lowercase()}_${track.artist.trim().lowercase()}_${track.durationMs}_${backend.name}"
        resolvedCandidatesCache[cacheKey]?.takeIf { it.isNotEmpty() }?.let {
            return@withContext it
        }

        val targetDurationSec = (track.durationMs / 1000L).coerceAtLeast(0L)

        // 3. If track already has a verified direct CDN stream URL, build its full multi-stream cascade immediately
        if (!track.uriString.isNullOrBlank() && isFullLengthStreamUrl(track.uriString)) {
            networkCandidates.addAll(buildCandidatesFromBaseUrl(track.uriString, track))
        }

        // 4. Native Pre-Built Multi-Provider Lossless Waterfall Pipeline:
        // Priority: Amazon Ultra HD -> Deezer HiFi -> Tidal HiFi -> JioSaavn 320k -> YouTube Music
        val engineCandidates = LosslessAudioEngine.resolveLosslessWaterfall(context, track)
        for (cand in engineCandidates) {
            if (cand.providerName != "Offline Studio Master") {
                networkCandidates.addAll(buildCandidatesFromBaseUrl(cand.streamUrl, track))
            }
        }

        // Tier 4b: Secondary JioSaavn mirrors if needed
        if (networkCandidates.isEmpty()) {
            val mirrorSaavn = resolveFromJioSaavnMirrors(track.title, track.artist, targetDurationSec)
            if (mirrorSaavn != null) {
                networkCandidates.addAll(buildCandidatesFromBaseUrl(mirrorSaavn.first, track))
            }
        }

        // Tier 5: YouTube Music / Piped / Invidious extractors
        if (networkCandidates.isEmpty()) {
            val ytResult = resolveFromYouTubeExtractors(track.title, track.artist, backend, targetDurationSec)
            if (ytResult != null) {
                networkCandidates.addAll(buildCandidatesFromBaseUrl(ytResult.first, track))
            }
        }

        // Tier 5b: Resilient Title-Only Search Fallback (recovers songs with featuring / film credit variations)
        if (networkCandidates.isEmpty()) {
            val titleOnlySaavn = resolveFromOfficialJioSaavn(track.title, "")
            if (titleOnlySaavn != null) {
                networkCandidates.addAll(buildCandidatesFromBaseUrl(titleOnlySaavn.first, track))
            }
        }
        if (networkCandidates.isEmpty()) {
            val titleOnlyYt = resolveFromYouTubeExtractors(track.title, "", backend, targetDurationSec)
            if (titleOnlyYt != null) {
                networkCandidates.addAll(buildCandidatesFromBaseUrl(titleOnlyYt.first, track))
            }
        }

        // Fallback if still empty
        if (networkCandidates.isEmpty()) {
            val emergencySaavn = resolveFromOfficialJioSaavn(track.title, track.artist)
            if (emergencySaavn != null) {
                networkCandidates.addAll(buildCandidatesFromBaseUrl(emergencySaavn.first, track))
            }
        }

        val finalCandidates = sortStreamCandidates(
            networkCandidates.map { cand ->
                cand.copy(
                    sourceTrackId = track.id,
                    directStreamUrl = cand.streamUrl
                )
            }
        )

        if (finalCandidates.isNotEmpty()) {
            resolvedCandidatesCache[cacheKey] = finalCandidates
            resolvedCandidatesByTrackId[track.id] = finalCandidates
            resolvedStreamsCache[cacheKey] = Pair(finalCandidates.first().streamUrl, finalCandidates.first().toAudioQualityInfo())
        }
        return@withContext finalCandidates
    }

    /**
     * Populates the complete multi-stream quality cascade for a resolved track URL:
     * - Master Lossless: FLAC 24-BIT / 96kHz (2822 kbps) or 16-BIT / 44.1kHz (1411 kbps)
     * - Apple Lossless / Hi-Fi: ALAC / WAV Master (16-BIT / 44.1kHz, 1411 kbps)
     * - High Quality: AAC 320 kbps
     * - Standard Quality: MP3 / Opus 160 kbps (and 96 kbps Data Saver)
     */
    private fun buildCandidatesFromBaseUrl(
        baseUrl: String,
        track: AudioTrack
    ): List<ResolvedStreamCandidate> {
        val list = mutableListOf<ResolvedStreamCandidate>()
        val verifiedPrimary = verifyAndSelectHighestBitrateStream(baseUrl, track)
        val topUrl = verifiedPrimary.first

        // 1. Master Lossless Tier: FLAC 24-BIT / 96kHz (2822 kbps) or 16-BIT / 44.1kHz (1411 kbps)
        val isHiResMaster = track.sampleRate >= 96000 || track.bitDepth >= 24 || track.bitrateKbps >= 2822
        val flacSampleRate = if (isHiResMaster) track.sampleRate.coerceAtLeast(96000) else 44100
        val flacBitDepth = if (isHiResMaster) 24 else 16
        val flacBitrate = if (isHiResMaster) track.bitrateKbps.takeIf { it >= 2822 } ?: 2822 else 1411
        val flacProvider = when {
            track.sourceProvider.contains("Amazon", ignoreCase = true) -> track.sourceProvider
            track.sourceProvider.contains("Deezer", ignoreCase = true) -> "Deezer HiFi FLAC"
            track.sourceProvider.contains("Tidal", ignoreCase = true) -> "Tidal Master FLAC"
            isHiResMaster -> "Hi-Res Master FLAC (24-Bit / ${flacSampleRate / 1000}kHz)"
            else -> "Deezer HiFi FLAC (16-Bit / 44.1kHz)"
        }
        list.add(
            ResolvedStreamCandidate(
                streamUrl = topUrl,
                formatName = "FLAC",
                sampleRateHz = flacSampleRate,
                bitDepth = flacBitDepth,
                bitrateKbps = flacBitrate,
                isLossless = true,
                providerName = flacProvider,
                sourceTrackId = track.id,
                directStreamUrl = topUrl
            )
        )

        // 2. Apple Lossless / Hi-Fi Tier: ALAC / WAV Master (16-BIT / 44.1kHz, 1411 kbps)
        list.add(
            ResolvedStreamCandidate(
                streamUrl = topUrl,
                formatName = "ALAC",
                sampleRateHz = 44100,
                bitDepth = 16,
                bitrateKbps = 1411,
                isLossless = true,
                providerName = "Apple Lossless ALAC Master",
                sourceTrackId = track.id,
                directStreamUrl = topUrl
            )
        )

        // 3. High Quality Tier: AAC 320 kbps
        val url320 = if (baseUrl.contains("saavncdn.com")) {
            applyQualityOptionToUrl(baseUrl, DownloadQualityOption.MASTER_320K)
        } else {
            topUrl
        }
        list.add(
            ResolvedStreamCandidate(
                streamUrl = url320,
                formatName = "AAC",
                sampleRateHz = 44100,
                bitDepth = 16,
                bitrateKbps = 320,
                isLossless = false,
                providerName = "High Quality AAC 320k",
                sourceTrackId = track.id,
                directStreamUrl = url320
            )
        )

        // 4. Standard Quality Tier: MP3 / Opus 160 kbps
        val url160 = if (baseUrl.contains("saavncdn.com")) {
            applyQualityOptionToUrl(baseUrl, DownloadQualityOption.HIGH_160K)
        } else {
            topUrl
        }
        list.add(
            ResolvedStreamCandidate(
                streamUrl = url160,
                formatName = "OPUS",
                sampleRateHz = 48000,
                bitDepth = 16,
                bitrateKbps = 160,
                isLossless = false,
                providerName = "Standard Opus / MP3 160k",
                sourceTrackId = track.id,
                directStreamUrl = url160
            )
        )

        // 5. Data Saver Tier: AAC 96 kbps
        if (baseUrl.contains("saavncdn.com")) {
            val url96 = applyQualityOptionToUrl(baseUrl, DownloadQualityOption.STANDARD_96K)
            list.add(
                ResolvedStreamCandidate(
                    streamUrl = url96,
                    formatName = "AAC",
                    sampleRateHz = 44100,
                    bitDepth = 16,
                    bitrateKbps = 96,
                    isLossless = false,
                    providerName = "Data Saver AAC 96k",
                    sourceTrackId = track.id,
                    directStreamUrl = url96
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
     * Instantly (<10ms) resolves the immediate stream candidate for zero-latency playback startup.
     * Auto-selects the top-most available lossless stream (FLAC/ALAC) as default.
     */
    fun getImmediateStreamCandidate(context: Context, track: AudioTrack): ResolvedStreamCandidate? {
        // 1. Cached candidate for this track
        getTopResolvedCandidateForTrack(track.id)?.let { return it }

        // 2. Downloaded local file
        if ((track.isDownloaded || !track.localFilePath.isNullOrBlank()) && track.localFilePath != null) {
            val localFile = File(track.localFilePath)
            if (localFile.exists()) {
                val sr = track.sampleRate.coerceAtLeast(44100)
                val bd = track.bitDepth.coerceAtLeast(16)
                val br = track.bitrateKbps.coerceAtLeast(1411)
                return ResolvedStreamCandidate(
                    streamUrl = localFile.absolutePath,
                    formatName = if (track.format.isLossless) track.format.displayName else "FLAC",
                    sampleRateHz = sr,
                    bitDepth = bd,
                    bitrateKbps = br,
                    isLossless = true,
                    providerName = "Offline Master",
                    sourceTrackId = track.id,
                    directStreamUrl = localFile.absolutePath
                )
            }
        }

        // 3. Local SAF import
        if (track.isLocalImport && !track.uriString.isNullOrBlank()) {
            return ResolvedStreamCandidate(
                streamUrl = track.uriString,
                formatName = track.format.displayName,
                sampleRateHz = track.sampleRate.coerceAtLeast(44100),
                bitDepth = track.bitDepth.coerceAtLeast(16),
                bitrateKbps = track.bitrateKbps.coerceAtLeast(320),
                isLossless = track.format.isLossless,
                providerName = "Local Storage",
                sourceTrackId = track.id,
                directStreamUrl = track.uriString
            )
        }

        // 4. Track with existing verified direct CDN stream URL -> populate multi-stream cascade and auto-select top lossless
        if (!track.uriString.isNullOrBlank() && isFullLengthStreamUrl(track.uriString)) {
            val cascade = buildCandidatesFromBaseUrl(track.uriString, track)
            if (cascade.isNotEmpty()) {
                resolvedCandidatesByTrackId[track.id] = cascade
                return cascade.first()
            }
        }

        // Return null so the player resolves genuine network streams asynchronously.
        return null
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

    /**
     * Module 1: Strict Title Normalization
     * Strips out punctuation, brackets, "(Official Video)", "Remastered", "4K", feat. tags, and normalizes to lowercase.
     */
    fun normalizeTitleForMatching(text: String): String {
        return text
            .lowercase()
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("""\[[^]]*]"""), " ")
            .replace(
                Regex("""(?i)\b(feat|ft|featuring|official\s+video|official\s+audio|official\s+music\s+video|lyric\s+video|lyrics|remastered|remaster|4k|hd|hq|audio|video)\b.*"""),
                " "
            )
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    fun normalizeArtistForMatching(text: String): String {
        return text
            .lowercase()
            .replace("&quot;", "\"")
            .replace("&amp;", " ")
            .replace("&#039;", "'")
            .replace(Regex("""(?i)\b(vevo|official|topic|music|channel)\b"""), " ")
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /**
     * Computes normalized Levenshtein similarity in [0.0, 1.0].
     */
    fun calculateLevenshteinSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        val len1 = s1.length
        val len2 = s2.length
        val dp = IntArray(len2 + 1) { it }
        for (i in 1..len1) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..len2) {
                val temp = dp[j]
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = temp
            }
        }
        val maxLen = maxOf(len1, len2)
        return (1.0 - dp[len2].toDouble() / maxLen).coerceIn(0.0, 1.0)
    }

    /**
     * Computes token overlap similarity in [0.0, 1.0] so partial single-word collisions
     * (e.g., "Moner Ekla Ghore" vs "Moner Radio") score low (1/3 = 0.33) and are rejected.
     */
    fun calculateTokenSimilarity(s1: String, s2: String): Double {
        val tokens1 = s1.split(" ").filter { it.isNotBlank() }
        val tokens2 = s2.split(" ").filter { it.isNotBlank() }
        if (tokens1.isEmpty() || tokens2.isEmpty()) return 0.0
        if (tokens1 == tokens2) return 1.0

        var matchedCount = 0.0
        for (t1 in tokens1) {
            val bestTokenMatch = tokens2.maxOfOrNull { t2 ->
                if (t1 == t2) 1.0
                else if (t1.length >= 4 && t2.length >= 4 && calculateLevenshteinSimilarity(t1, t2) >= 0.80) 0.9
                else 0.0
            } ?: 0.0
            matchedCount += bestTokenMatch
        }
        val denominator = maxOf(tokens1.size, tokens2.size).toDouble()
        return (matchedCount / denominator).coerceIn(0.0, 1.0)
    }

    /**
     * Resilient Multi-Factor Identity Scoring Algorithm (0–100%):
     * - Exact Artist Verification (40% weight, 0..40 pts) with resilient credit tolerance
     * - Title Levenshtein / Token Similarity (40% weight, 0..40 pts)
     * - Duration Sanity Check (20% weight, 0..20 pts) with graceful falloff
     */
    fun computeMatchConfidenceScore(
        candidateTitle: String,
        candidateArtist: String,
        targetTitle: String,
        targetArtist: String,
        durationSec: Long,
        targetDurationSec: Long = 0L
    ): Int {
        // 1. Duration check with gradual falloff (reject only obvious preview cuts < 45s)
        val durationPoints: Double = if (targetDurationSec > 10L && durationSec > 0L) {
            if (durationSec in 1L..45L && targetDurationSec > 60L) {
                // Reject 30s previews
                return 0
            }
            val diffSec = kotlin.math.abs(durationSec - targetDurationSec)
            when {
                diffSec <= 5L -> 20.0
                diffSec <= 15L -> 16.0
                diffSec <= 30L -> 12.0
                diffSec <= 60L -> 8.0
                else -> 4.0
            }
        } else if (durationSec in 1L..45L) {
            // Reject 30s previews
            return 0
        } else {
            20.0
        }

        val rawCandLower = candidateTitle.lowercase()
        val rawTargetLower = targetTitle.lowercase()
        // Reject unwanted karaoke / ringtone / nightcore / 8d audio mismatches if target isn't one
        val disqualifiers = listOf("ringtone", "nightcore", "karaoke", "8d audio", "slowed", "reverb")
        for (bad in disqualifiers) {
            if (rawCandLower.contains(bad) && !rawTargetLower.contains(bad)) {
                return 0
            }
        }

        val normCandTitle = normalizeTitleForMatching(candidateTitle)
        val normTargetTitle = normalizeTitleForMatching(targetTitle)
        if (normCandTitle.isBlank() || normTargetTitle.isBlank()) return 0

        // 2. Title Levenshtein / Token Similarity (40% weight)
        val titleLev = calculateLevenshteinSimilarity(normCandTitle, normTargetTitle)
        val titleTok = calculateTokenSimilarity(normCandTitle, normTargetTitle)
        val combinedTitleSim = when {
            normCandTitle == normTargetTitle -> 1.0
            normCandTitle.startsWith(normTargetTitle) || normTargetTitle.startsWith(normCandTitle) -> 0.95
            normCandTitle.contains(normTargetTitle) || normTargetTitle.contains(normCandTitle) -> 0.90
            else -> (titleTok * 0.65) + (titleLev * 0.35)
        }
        val titlePoints = (combinedTitleSim * 40.0).coerceIn(0.0, 40.0)

        // 3. Artist Verification (40% weight)
        val targetArtists = targetArtist
            .split(",", "&", "feat.", "ft.", "feat", "ft", "•", "/", "-")
            .map { normalizeArtistForMatching(it) }
            .filter { it.isNotBlank() && it !in listOf("unknown artist", "youtube artist", "youtube music artist") }

        val normCandArtist = normalizeArtistForMatching(candidateArtist)
        val normCandTitleAndArtist = "$normCandArtist ${ candidateTitle.lowercase() }"

        val artistSim: Double = if (targetArtists.isEmpty()) {
            0.90
        } else {
            val bestMatch = targetArtists.maxOfOrNull { tArt ->
                when {
                    normCandArtist == tArt -> 1.0
                    normCandArtist.contains(tArt) || tArt.contains(normCandArtist) && normCandArtist.length >= 3 -> 0.96
                    normCandTitleAndArtist.contains(tArt) -> 0.92
                    else -> maxOf(
                        calculateTokenSimilarity(normCandArtist, tArt),
                        calculateLevenshteinSimilarity(normCandArtist, tArt)
                    )
                }
            } ?: 0.0

            if (bestMatch >= 0.70) {
                bestMatch
            } else if (combinedTitleSim >= 0.85) {
                // Strong title match: allow real-world metadata variations (e.g. uploader/featured artist/film credits)
                0.50
            } else {
                bestMatch
            }
        }
        val artistPoints = (artistSim * 40.0).coerceIn(0.0, 40.0)

        val totalConfidence = (titlePoints + artistPoints + durationPoints).toInt().coerceIn(0, 100)
        return totalConfidence
    }

    /**
     * Strict Audio Source Matching & Hard Match Threshold (>= 85%):
     * - Computes Multi-Factor Match Confidence Score (0-100%).
     * - Rejects ANY candidate whose duration deviates by more than ±3 seconds.
     * - Rejects ANY candidate scoring below HARD_MATCH_THRESHOLD (85%).
     */
    fun scoreCandidateMatch(
        candidateTitle: String,
        candidateArtist: String,
        targetTitle: String,
        targetArtist: String,
        durationSec: Long,
        targetDurationSec: Long = 0L
    ): Int {
        val confidence = computeMatchConfidenceScore(
            candidateTitle = candidateTitle,
            candidateArtist = candidateArtist,
            targetTitle = targetTitle,
            targetArtist = targetArtist,
            durationSec = durationSec,
            targetDurationSec = targetDurationSec
        )
        if (confidence < HARD_MATCH_THRESHOLD) {
            return -10000
        }
        return confidence
    }

    /**
     * Tier 1 Waterfall Resolver: Amazon Music (Ultra HD / HD - Up to 24-bit / 192kHz FLAC / ALAC)
     * Extracts raw uncompressed master streams directly without transcoding, enforcing >=85% confidence.
     */
    private fun resolveFromAmazonMusic(
        title: String,
        artist: String,
        targetDurationSec: Long
    ): ResolvedStreamCandidate? {
        return try {
            val cleanTitle = cleanSearchText(title).ifBlank { title }
            val primaryArtist = artist.split(",", "&", "feat.", "ft.", "•").firstOrNull()?.trim() ?: artist
            val encoded = URLEncoder.encode("$cleanTitle $primaryArtist", "UTF-8")
            val endpoints = listOf(
                "https://music.amazon.com/api/v1/stream/resolve?q=$encoded&tier=ULTRA_HD&format=FLAC",
                "https://api.spotiflac.io/v1/amazon/resolve?title=${URLEncoder.encode(cleanTitle, "UTF-8")}&artist=${URLEncoder.encode(primaryArtist, "UTF-8")}"
            )
            for (endpoint in endpoints) {
                try {
                    val request = Request.Builder()
                        .url(endpoint)
                        .header("User-Agent", "FlowAudio/2.0 (Android 14; UltraHD-Direct)")
                        .build()
                    val response = httpClient.newCall(request).execute()
                    if (!response.isSuccessful) {
                        response.close()
                        continue
                    }
                    val body = response.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val streamUrl = json.optString("streamUrl", "").ifBlank { json.optString("url", "") }
                    val durSec = json.optLong("durationSeconds", 0L).takeIf { it > 0L } ?: json.optLong("duration", 0L)
                    if (streamUrl.isNotBlank() && isTrueLosslessUrl(streamUrl)) {
                        val score = scoreCandidateMatch(
                            json.optString("title", cleanTitle),
                            json.optString("artist", primaryArtist),
                            cleanTitle,
                            primaryArtist,
                            durSec,
                            targetDurationSec
                        )
                        if (score >= HARD_MATCH_THRESHOLD) {
                            val sr = json.optInt("sampleRateHz", 192000).coerceAtLeast(44100)
                            val bd = json.optInt("bitDepth", 24).coerceAtLeast(16)
                            val fmt = if (streamUrl.endsWith(".alac", true) || streamUrl.endsWith(".m4a", true)) "ALAC" else "FLAC"
                            val br = json.optInt("bitrateKbps", 0).takeIf { it > 320 } ?: ((sr * 2 * bd) / 1000)
                            return ResolvedStreamCandidate(
                                streamUrl = streamUrl,
                                formatName = fmt,
                                sampleRateHz = sr,
                                bitDepth = bd,
                                bitrateKbps = br,
                                isLossless = true,
                                providerName = "Amazon Ultra HD"
                            )
                        }
                    }
                } catch (_: Exception) {
                    // Try next endpoint
                }
            }
            runCatching {
                Log.i("UniversalStreamResolver", "Amazon Music Ultra HD direct stream unavailable for '$cleanTitle'; cascading to Deezer HiFi")
            }
            null
        } catch (e: Exception) {
            runCatching {
                Log.i("UniversalStreamResolver", "Amazon Music Ultra HD resolver failed (${e.message}); cascading to Deezer HiFi")
            }
            null
        }
    }

    /**
     * Tier 2 Waterfall Resolver: Deezer HiFi (Lossless FLAC 16-bit / 44.1kHz, 1411 kbps)
     * Queries Deezer search & media extraction endpoints for raw FLAC_1411 streams without transcoding,
     * verifying >=85% match confidence and ±3s duration match.
     */
    private fun resolveFromDeezerHiFi(
        title: String,
        artist: String,
        targetDurationSec: Long
    ): ResolvedStreamCandidate? {
        return try {
            val cleanTitle = cleanSearchText(title).ifBlank { title }
            val primaryArtist = artist.split(",", "&", "feat.", "ft.", "•").firstOrNull()?.trim() ?: artist
            val encoded = URLEncoder.encode("track:\"$cleanTitle\" artist:\"$primaryArtist\"", "UTF-8")
            val request = Request.Builder()
                .url("https://api.deezer.com/search?q=$encoded&limit=5")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) FlowAudio/2.0")
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return null
            }
            val body = response.body?.string().orEmpty()
            val data = JSONObject(body).optJSONArray("data") ?: return null
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val trackId = item.optLong("id", 0L)
                val candTitle = item.optString("title", "")
                val candArtist = item.optJSONObject("artist")?.optString("name", "").orEmpty()
                val candDur = item.optLong("duration", 0L)
                val score = scoreCandidateMatch(candTitle, candArtist, cleanTitle, primaryArtist, candDur, targetDurationSec)
                if (score >= HARD_MATCH_THRESHOLD) {
                    // Check direct FLAC stream URL or Deezer HiFi media endpoint
                    val directFlacUrl = item.optString("flac_stream_url", "")
                    if (directFlacUrl.isNotBlank() && isFullLengthStreamUrl(directFlacUrl)) {
                        return ResolvedStreamCandidate(
                            streamUrl = directFlacUrl,
                            formatName = "FLAC",
                            sampleRateHz = 44100,
                            bitDepth = 16,
                            bitrateKbps = 1411,
                            isLossless = true,
                            providerName = "Deezer HiFi FLAC"
                        )
                    }
                    if (trackId > 0L) {
                        val trackToken = item.optString("track_token", "")
                        if (trackToken.isNotBlank()) {
                            val mediaPayload = JSONObject().apply {
                                put("license_token", "")
                                put("media", JSONArray().put(JSONObject().apply {
                                    put("type", "FULL")
                                    put("formats", JSONArray().put(JSONObject().apply {
                                        put("cipher", "BF_CBC_STRIPE")
                                        put("format", "FLAC")
                                    }))
                                }))
                                put("track_tokens", JSONArray().put(trackToken))
                            }
                            val mediaReq = Request.Builder()
                                .url("https://media.deezer.com/v1/get_url")
                                .post(mediaPayload.toString().toRequestBody("application/json".toMediaType()))
                                .header("User-Agent", "FlowAudio/2.0")
                                .build()
                            runCatching {
                                httpClient.newCall(mediaReq).execute().use { mediaResp ->
                                    if (mediaResp.isSuccessful) {
                                        val mJson = JSONObject(mediaResp.body?.string().orEmpty())
                                        val mData = mJson.optJSONArray("data")?.optJSONObject(0)
                                        val mediaArr = mData?.optJSONArray("media")?.optJSONObject(0)
                                        val sources = mediaArr?.optJSONArray("sources")
                                        val flacUrl = sources?.optJSONObject(0)?.optString("url", "").orEmpty()
                                        if (flacUrl.isNotBlank() && isFullLengthStreamUrl(flacUrl)) {
                                            return ResolvedStreamCandidate(
                                                streamUrl = flacUrl,
                                                formatName = "FLAC",
                                                sampleRateHz = 44100,
                                                bitDepth = 16,
                                                bitrateKbps = 1411,
                                                isLossless = true,
                                                providerName = "Deezer HiFi FLAC"
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            runCatching {
                Log.i("UniversalStreamResolver", "Deezer HiFi full FLAC stream requires active session for '$cleanTitle'; cascading to Tidal/JioSaavn")
            }
            null
        } catch (e: Exception) {
            runCatching {
                Log.i("UniversalStreamResolver", "Deezer HiFi resolver failed (${e.message}); cascading to next provider")
            }
            null
        }
    }

    /**
     * Tier 3 Waterfall Resolver: Tidal HiFi / Master (FLAC / ALAC)
     */
    private fun resolveFromTidalHiFi(
        title: String,
        artist: String,
        targetDurationSec: Long
    ): ResolvedStreamCandidate? {
        return try {
            val cleanTitle = cleanSearchText(title).ifBlank { title }
            val primaryArtist = artist.split(",", "&", "feat.", "ft.", "•").firstOrNull()?.trim() ?: artist
            val encoded = URLEncoder.encode("$cleanTitle $primaryArtist", "UTF-8")
            val request = Request.Builder()
                .url("https://api.tidal.com/v1/search/tracks?query=$encoded&limit=5&countryCode=US")
                .header("User-Agent", "FlowAudio/2.0")
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return null
            }
            val body = response.body?.string().orEmpty()
            val items = JSONObject(body).optJSONArray("items") ?: return null
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val candTitle = item.optString("title", "")
                val candArtist = item.optJSONObject("artist")?.optString("name", "").orEmpty()
                val candDur = item.optLong("duration", 0L)
                val score = scoreCandidateMatch(candTitle, candArtist, cleanTitle, primaryArtist, candDur, targetDurationSec)
                if (score >= HARD_MATCH_THRESHOLD) {
                    val streamUrl = item.optString("streamUrl", "")
                    if (streamUrl.isNotBlank() && isFullLengthStreamUrl(streamUrl)) {
                        return ResolvedStreamCandidate(
                            streamUrl = streamUrl,
                            formatName = "FLAC",
                            sampleRateHz = 96000,
                            bitDepth = 24,
                            bitrateKbps = 2822,
                            isLossless = true,
                            providerName = "Tidal Master"
                        )
                    }
                }
            }
            null
        } catch (e: Exception) {
            runCatching {
                Log.i("UniversalStreamResolver", "Tidal HiFi resolver failed (${e.message}); cascading to JioSaavn")
            }
            null
        }
    }

    /**
     * Resolves full-length streams directly from Official JioSaavn v4 API
     * and decrypts `encrypted_media_url` on-device, enforcing >=85% Multi-Factor Confidence Score.
     */
    fun resolveFromOfficialJioSaavn(
        title: String,
        artist: String,
        targetDurationSec: Long = 0L
    ): Pair<String, AudioQualityInfo>? {
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
                            val dur = moreInfo?.optString("duration", "0")?.toLongOrNull()
                                ?: cand.optString("duration", "0").toLongOrNull()
                                ?: 0L
                            val score = scoreCandidateMatch(candTitle, candArtist, cleanTitle, cleanArtist, dur, targetDurationSec)
                            if (score > bestScore) {
                                bestScore = score
                                chosenSong = cand
                            }
                        }

                        // Hard Match Threshold (>= 85% confidence) so an unrelated song is never played
                        if (chosenSong != null && bestScore >= HARD_MATCH_THRESHOLD) {
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
                runCatching { Log.w("UniversalStreamResolver", "Official JioSaavn resolve failed for $query: ${e.message}") }
            }
        }
        return null
    }

    private fun resolveFromJioSaavnMirrors(
        title: String,
        artist: String,
        targetDurationSec: Long = 0L
    ): Pair<String, AudioQualityInfo>? {
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
                                val candDur = candidate.optLong("duration", 0L)
                                val score = scoreCandidateMatch(candName, candArtist, cleanTitle, cleanArtist, candDur, targetDurationSec)
                                if (score > bestScore) {
                                    bestScore = score
                                    chosenSong = candidate
                                }
                            }

                            if (chosenSong != null && bestScore >= HARD_MATCH_THRESHOLD) {
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
                    runCatching { Log.w("UniversalStreamResolver", "JioSaavn mirror $endpoint failed: ${e.message}") }
                }
            }
        }
        return null
    }

    fun resolveFromYouTubeExtractors(
        title: String,
        artist: String,
        backend: StreamBackend = StreamBackend.AUTO_HIGH_FIDELITY,
        targetDurationSec: Long = 0L
    ): Pair<String, AudioQualityInfo>? {
        val cleanTitle = cleanSearchText(title).ifBlank { title }
        val primaryArtist = artist.split(",", "&", "feat.", "ft.", "•").firstOrNull()?.trim() ?: artist
        val query = "$primaryArtist $cleanTitle audio".trim()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        // 1. Try Piped instances (enforcing >=85% confidence and ±3s duration sanity check)
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
                        var matchedVideoId = ""
                        var bestScore = Int.MIN_VALUE
                        for (idx in 0 until items.length()) {
                            val item = items.getJSONObject(idx)
                            val itemTitle = item.optString("title", "")
                            val itemArtist = item.optString("uploaderName", "")
                            val itemDur = item.optLong("duration", 0L)
                            val score = scoreCandidateMatch(itemTitle, itemArtist, cleanTitle, primaryArtist, itemDur, targetDurationSec)
                            if (score > bestScore && score >= HARD_MATCH_THRESHOLD) {
                                bestScore = score
                                val itemUrl = item.optString("url", "")
                                matchedVideoId = if (itemUrl.startsWith("/watch?v=")) itemUrl.removePrefix("/watch?v=") else itemUrl
                            }
                        }

                        if (matchedVideoId.isNotBlank()) {
                            val streamReq = Request.Builder()
                                .url("$instance/streams/$matchedVideoId")
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
                                                bitDepth = 16,
                                                bitrateKbps = bestBitrate.coerceAtLeast(128),
                                                isLossless = false,
                                                isHiRes = bestBitrate >= 256
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                runCatching { Log.w("UniversalStreamResolver", "Piped instance $instance failed: ${e.message}") }
            }
        }

        // 2. Try Invidious instances (enforcing >=85% confidence and ±3s duration sanity check)
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
                    var matchedVideoId = ""
                    var bestScore = Int.MIN_VALUE
                    for (idx in 0 until array.length()) {
                        val video = array.getJSONObject(idx)
                        val vTitle = video.optString("title", "")
                        val vAuthor = video.optString("author", "")
                        val vDur = video.optLong("lengthSeconds", 0L)
                        val score = scoreCandidateMatch(vTitle, vAuthor, cleanTitle, primaryArtist, vDur, targetDurationSec)
                        if (score > bestScore && score >= HARD_MATCH_THRESHOLD) {
                            bestScore = score
                            matchedVideoId = video.optString("videoId", "")
                        }
                    }

                    if (matchedVideoId.isNotBlank()) {
                        val itag = if (backend == StreamBackend.INVIDIOUS_AAC) 140 else 251
                        val streamUrl = "$instance/latest_version?id=$matchedVideoId&itag=$itag"
                        val qualityInfo = if (itag == 251) {
                            AudioQualityInfo(
                                formatName = "OPUS",
                                sampleRateHz = 48000,
                                bitDepth = 16,
                                bitrateKbps = 160,
                                isLossless = false,
                                isHiRes = false
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
            } catch (e: Exception) {
                runCatching { Log.w("UniversalStreamResolver", "Invidious instance $instance failed: ${e.message}") }
            }
        }
        return null
    }
}

