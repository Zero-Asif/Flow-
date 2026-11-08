package com.example.network

import android.util.Base64
import android.util.Log
import com.example.model.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.DESKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Multi-Provider Stream Resolver implementing a resilient multi-source streaming cascade:
 * - Provider 1: YouTube InnerTube IOS Client (`/youtubei/v1/player`, clientName = "IOS", clientVersion = "19.29.1")
 * - Provider 2: YouTube InnerTube ANDROID_MUSIC & ANDROID_VR Client (`/youtubei/v1/player`)
 * - Provider 3: Piped API Fallback (`https://pipedapi.kavin.rocks/streams/{videoId}` & `https://api.piped.video/streams/{videoId}`)
 * - Provider 4: Invidious Fallback API (`https://inv.nadeko.net/api/v1/videos/{videoId}`)
 * - Provider 5: Direct Full-Length AAC 320kbps CDN Fallback (via YouTube oEmbed + JioSaavn v4 / Saavn CDN Mirrors)
 *   when datacenter/ISP IP blocks YouTube/Piped/Invidious direct video streams.
 *
 * Strictly filters for audio-only MIME types (`audio/webm`, `audio/mp4`), rejects any stream with
 * `approxDurationMs < 60_000L` (eliminating 30-second snippet previews), and sorts candidates descending
 * by bitrate (`candidates.sortedByDescending { it.bitrate }`).
 */
object MultiProviderStreamResolver {
    private const val TAG = "MultiProviderResolver"
    private const val MIN_FULL_TRACK_DURATION_MS = 60_000L
    private const val JIOSAAVN_DES_KEY = "38346591"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val PIPED_ENDPOINTS = listOf(
        "https://pipedapi.kavin.rocks/streams",
        "https://api.piped.video/streams",
        "https://pipedapi.tokhmi.xyz/streams"
    )

    private val INVIDIOUS_ENDPOINTS = listOf(
        "https://inv.nadeko.net/api/v1/videos",
        "https://inv.tux.pizza/api/v1/videos",
        "https://invidious.nerdvpn.de/api/v1/videos"
    )

    private val SAAVN_MIRRORS = listOf(
        "https://saavn.dev/api",
        "https://jiosaavn-api-privatecvc2.vercel.app"
    )

    // Tracks URLs that failed with 403/reset per videoId during the active session so auto-retry advances to the next provider
    private val failedUrlsByVideoId = ConcurrentHashMap<String, MutableSet<String>>()

    // Caches track metadata by videoId so resolveBestStreamUrl(videoId) can also leverage title/artist resolution
    private val trackMetadataByVideoId = ConcurrentHashMap<String, Pair<String, String>>()

    data class AudioFormatCandidate(
        val url: String,
        val mimeType: String,
        val bitrate: Int,
        val sampleRateHz: Int,
        val approxDurationMs: Long,
        val codec: String,
        val providerName: String
    ) {
        val bitrateKbps: Int
            get() = if (bitrate > 1000) (bitrate / 1000).coerceIn(48, 512) else bitrate.coerceIn(48, 512)

        val formatName: String
            get() = if (mimeType.contains("webm", ignoreCase = true) || codec.contains("opus", ignoreCase = true)) {
                "OPUS"
            } else {
                "AAC"
            }

        fun toResolvedStreamCandidate(track: AudioTrack? = null): ResolvedStreamCandidate {
            return ResolvedStreamCandidate(
                streamUrl = url,
                directStreamUrl = url,
                formatName = formatName,
                codec = if (formatName == "OPUS") "OPUS" else "AAC-LC",
                bitrateKbps = bitrateKbps,
                sampleRateHz = sampleRateHz.takeIf { it > 0 } ?: if (formatName == "OPUS") 48000 else 44100,
                bitDepth = 16,
                channelCount = 2,
                isLossless = false,
                isHiRes = false,
                providerName = "$providerName ($formatName ${bitrateKbps}kbps)",
                qualityLabel = "$formatName • $bitrateKbps kbps",
                canonicalTitle = track?.title.orEmpty(),
                canonicalArtist = track?.artist.orEmpty(),
                canonicalAlbum = track?.album.orEmpty(),
                canonicalArtworkUrl = track?.artworkUrl.orEmpty(),
                verifiedDurationMs = approxDurationMs.takeIf { it > 0L } ?: (track?.durationMs ?: 0L),
                matchConfidenceScore = 115
            )
        }
    }

    /**
     * Marks a stream URL as failed for the given [videoId] so subsequent fallback calls skip it.
     */
    fun markUrlFailed(videoId: String, failedUrl: String) {
        if (videoId.isBlank() || failedUrl.isBlank()) return
        val cleanId = extractCleanVideoId(videoId).ifEmpty { videoId.trim() }
        val set = failedUrlsByVideoId.getOrPut(cleanId) { ConcurrentHashMap.newKeySet() }
        set.add(failedUrl)
    }

    fun clearFailedUrls(videoId: String) {
        val cleanId = extractCleanVideoId(videoId).ifEmpty { videoId.trim() }
        failedUrlsByVideoId.remove(cleanId)
    }

    fun registerTrackMetadata(videoId: String, title: String, artist: String) {
        val cleanId = extractCleanVideoId(videoId)
        if (cleanId.isNotEmpty() && title.isNotBlank()) {
            trackMetadataByVideoId[cleanId] = title to artist
        }
    }

    /**
     * Step 1.3: Fallback Execution Loop across all providers in priority order.
     */
    suspend fun resolveBestStreamUrl(videoId: String): Result<String> = withContext(Dispatchers.IO) {
        val cleanVideoId = extractCleanVideoId(videoId)
        if (cleanVideoId.isEmpty()) {
            return@withContext Result.failure(IOException("Invalid videoId: $videoId"))
        }

        val providers: List<suspend (String) -> String?> = listOf(
            ::resolveViaIosClient,
            ::resolveViaAndroidMusicClient,
            ::resolveViaPipedApi,
            ::resolveViaInvidiousApi,
            ::resolveViaJioSaavnFullLengthByVideoId
        )

        for (provider in providers) {
            try {
                val url = provider(cleanVideoId)
                if (!url.isNullOrBlank() && isValidStreamUrl(url, cleanVideoId)) {
                    return@withContext Result.success(url)
                }
            } catch (_: Exception) {
                // Continue to next provider
            }
        }
        return@withContext Result.failure(IOException("All streaming providers exhausted for videoId: $cleanVideoId"))
    }

    /**
     * Resolves the highest-bitrate full-length [ResolvedStreamCandidate] across the multi-provider cascade,
     * skipping any URL previously marked as failed for [videoId].
     */
    suspend fun resolveBestStreamCandidate(
        videoId: String,
        track: AudioTrack? = null,
        excludedUrls: Set<String> = emptySet()
    ): Result<ResolvedStreamCandidate> = withContext(Dispatchers.IO) {
        val cleanVideoId = extractCleanVideoId(videoId)
        if (track != null && cleanVideoId.isNotEmpty()) {
            registerTrackMetadata(cleanVideoId, track.title, track.artist)
        }

        val lookupKey = cleanVideoId.ifEmpty { track?.id.orEmpty() }
        val failedSet = failedUrlsByVideoId[lookupKey].orEmpty() + excludedUrls

        if (cleanVideoId.isNotEmpty()) {
            val providerFetchers: List<Pair<String, suspend (String) -> List<AudioFormatCandidate>>> = listOf(
                "YouTube iOS InnerTube" to ::fetchIosClientCandidates,
                "YouTube Android Music" to ::fetchAndroidMusicClientCandidates,
                "Piped Proxy API" to ::fetchPipedApiCandidates,
                "Invidious API" to ::fetchInvidiousApiCandidates
            )

            for ((providerLabel, fetcher) in providerFetchers) {
                try {
                    val candidates = fetcher(cleanVideoId)
                        .filter { isValidStreamUrl(it.url, lookupKey) && it.url !in failedSet }
                    val best = selectBestAudioFormat(candidates)
                    if (best != null) {
                        Log.i(TAG, "Resolved [$cleanVideoId] via $providerLabel: ${best.formatName} @ ${best.bitrateKbps}kbps")
                        return@withContext Result.success(best.toResolvedStreamCandidate(track))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "$providerLabel failed for $cleanVideoId: ${e.message}")
                }
            }
        }

        // Provider 5: Full-Length Direct CDN Fallback (320kbps / 160kbps AAC) via track metadata or YouTube oEmbed
        try {
            val cdnCandidates = fetchJioSaavnFullLengthCandidates(cleanVideoId, track)
                .filter { isValidStreamUrl(it.url, lookupKey) && it.url !in failedSet }
            val bestCdn = selectBestAudioFormat(cdnCandidates)
            if (bestCdn != null) {
                Log.i(TAG, "Resolved [$lookupKey] via ${bestCdn.providerName}: ${bestCdn.formatName} @ ${bestCdn.bitrateKbps}kbps")
                return@withContext Result.success(bestCdn.toResolvedStreamCandidate(track))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Full-length CDN fallback failed for $lookupKey: ${e.message}")
        }

        return@withContext Result.failure(IOException("All streaming providers exhausted for videoId: $lookupKey"))
    }

    /**
     * Collects all valid sorted stream candidates across providers for the stream selector pill and fallback queue.
     */
    suspend fun resolveAllProviderCandidates(
        videoId: String,
        track: AudioTrack? = null
    ): List<ResolvedStreamCandidate> = withContext(Dispatchers.IO) {
        val cleanVideoId = extractCleanVideoId(videoId)
        if (track != null && cleanVideoId.isNotEmpty()) {
            registerTrackMetadata(cleanVideoId, track.title, track.artist)
        }

        val lookupKey = cleanVideoId.ifEmpty { track?.id.orEmpty() }
        val allFormats = mutableListOf<AudioFormatCandidate>()

        if (cleanVideoId.isNotEmpty()) {
            val fetchers: List<suspend (String) -> List<AudioFormatCandidate>> = listOf(
                ::fetchIosClientCandidates,
                ::fetchAndroidMusicClientCandidates,
                ::fetchPipedApiCandidates,
                ::fetchInvidiousApiCandidates
            )

            for (fetcher in fetchers) {
                try {
                    val list = fetcher(cleanVideoId).filter { isValidStreamUrl(it.url, lookupKey) }
                    allFormats.addAll(list)
                    if (allFormats.size >= 4) break
                } catch (_: Exception) {
                }
            }
        }

        if (allFormats.size < 2) {
            try {
                val cdnFormats = fetchJioSaavnFullLengthCandidates(cleanVideoId, track)
                    .filter { isValidStreamUrl(it.url, lookupKey) }
                allFormats.addAll(cdnFormats)
            } catch (_: Exception) {
            }
        }

        sortAudioFormatsDescending(allFormats)
            .map { it.toResolvedStreamCandidate(track) }
            .distinctBy { "${it.formatName}_${it.bitrateKbps}_${it.directStreamUrl.take(90)}" }
    }

    // =========================================================================
    // PROVIDER 1: YouTube InnerTube IOS Client (/youtubei/v1/player)
    // clientName = "IOS", clientVersion = "19.29.1"
    // =========================================================================

    suspend fun resolveViaIosClient(videoId: String): String? = withContext(Dispatchers.IO) {
        val candidates = fetchIosClientCandidates(videoId)
        selectBestAudioFormat(candidates)?.url
    }

    private suspend fun fetchIosClientCandidates(videoId: String): List<AudioFormatCandidate> = withContext(Dispatchers.IO) {
        val iosVersions = listOf(
            "19.29.1" to "17.5.1.21F90",
            "19.45.4" to "18.1.0.22B83"
        )

        for ((clientVersion, osVersion) in iosVersions) {
            try {
                val payload = JSONObject().apply {
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                    put("context", JSONObject().apply {
                        put("client", JSONObject().apply {
                            put("clientName", "IOS")
                            put("clientVersion", clientVersion)
                            put("deviceMake", "Apple")
                            put("deviceModel", "iPhone16,2")
                            put("osName", "iPhone")
                            put("osVersion", osVersion)
                            put("hl", "en")
                            put("gl", "US")
                        })
                    })
                }

                val request = Request.Builder()
                    .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                    .header("User-Agent", "com.google.ios.youtube/$clientVersion (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)")
                    .header("X-YouTube-Client-Name", "5")
                    .header("X-YouTube-Client-Version", clientVersion)
                    .header("Accept", "application/json")
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        val parsed = parseInnerTubeAdaptiveFormats(body, "YouTube iOS")
                        if (parsed.isNotEmpty()) {
                            return@withContext parsed
                        }
                    }
                }
            } catch (_: Exception) {
                // Continue to next iOS profile
            }
        }
        emptyList()
    }

    // =========================================================================
    // PROVIDER 2: YouTube InnerTube ANDROID_MUSIC & ANDROID_VR Client (/youtubei/v1/player)
    // =========================================================================

    suspend fun resolveViaAndroidMusicClient(videoId: String): String? = withContext(Dispatchers.IO) {
        val candidates = fetchAndroidMusicClientCandidates(videoId)
        selectBestAudioFormat(candidates)?.url
    }

    private suspend fun fetchAndroidMusicClientCandidates(videoId: String): List<AudioFormatCandidate> = withContext(Dispatchers.IO) {
        // 2A. Try ANDROID_VR first (does not require PoToken or cipher descrambling on direct adaptiveFormats)
        try {
            val vrPayload = JSONObject().apply {
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID_VR")
                        put("clientVersion", "1.60.19")
                        put("androidSdkVersion", 32)
                        put("osName", "Android")
                        put("osVersion", "12L")
                        put("deviceMake", "Oculus")
                        put("deviceModel", "Quest 3")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
            }

            val vrRequest = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .header(
                    "User-Agent",
                    "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
                )
                .header("X-YouTube-Client-Name", "28")
                .header("X-YouTube-Client-Version", "1.60.19")
                .header("Accept", "application/json")
                .post(vrPayload.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(vrRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val parsed = parseInnerTubeAdaptiveFormats(body, "YouTube Android")
                    if (parsed.isNotEmpty()) {
                        return@withContext parsed
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 2B. Try ANDROID_MUSIC client
        try {
            val musicPayload = JSONObject().apply {
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID_MUSIC")
                        put("clientVersion", "7.11.50")
                        put("androidSdkVersion", 34)
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
            }

            val musicRequest = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/player?prettyPrint=false")
                .header(
                    "User-Agent",
                    "com.google.android.apps.youtube.music/7.11.50 (Linux; U; Android 14; en_US; Pixel 8 Pro Build/UQ1A.240205.004) gzip"
                )
                .header("X-YouTube-Client-Name", "21")
                .header("X-YouTube-Client-Version", "7.11.50")
                .header("Accept", "application/json")
                .post(musicPayload.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(musicRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val parsed = parseInnerTubeAdaptiveFormats(body, "YouTube Music")
                    if (parsed.isNotEmpty()) {
                        return@withContext parsed
                    }
                }
            }
        } catch (_: Exception) {
        }

        emptyList()
    }

    // =========================================================================
    // PROVIDER 3: Piped API Fallback
    // https://pipedapi.kavin.rocks/streams/{videoId} or https://api.piped.video/streams/{videoId}
    // =========================================================================

    suspend fun resolveViaPipedApi(videoId: String): String? = withContext(Dispatchers.IO) {
        val candidates = fetchPipedApiCandidates(videoId)
        selectBestAudioFormat(candidates)?.url
    }

    private suspend fun fetchPipedApiCandidates(videoId: String): List<AudioFormatCandidate> = withContext(Dispatchers.IO) {
        for (baseUrl in PIPED_ENDPOINTS) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/$videoId")
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                    )
                    .header("Accept", "application/json")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) continue
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) continue

                    val root = JSONObject(body)
                    val videoDurationMs = root.optLong("duration", 0L) * 1000L
                    if (videoDurationMs in 1L until MIN_FULL_TRACK_DURATION_MS) {
                        continue
                    }

                    val audioStreams = root.optJSONArray("audioStreams") ?: continue
                    val candidates = mutableListOf<AudioFormatCandidate>()

                    for (i in 0 until audioStreams.length()) {
                        val streamObj = audioStreams.optJSONObject(i) ?: continue
                        val url = streamObj.optString("url", "").trim()
                        if (url.isEmpty()) continue

                        val mimeType = streamObj.optString("mimeType", "").lowercase(Locale.ROOT)
                        if (!isSupportedAudioMimeType(mimeType)) continue

                        val bitrate = streamObj.optInt("bitrate", 0)
                        val codec = streamObj.optString("codec", if (mimeType.contains("webm")) "opus" else "mp4a")
                        val approxDurationMs = videoDurationMs.takeIf { it > 0L } ?: 180_000L

                        if (approxDurationMs < MIN_FULL_TRACK_DURATION_MS) continue

                        candidates.add(
                            AudioFormatCandidate(
                                url = url,
                                mimeType = mimeType,
                                bitrate = bitrate,
                                sampleRateHz = if (mimeType.contains("webm")) 48000 else 44100,
                                approxDurationMs = approxDurationMs,
                                codec = codec,
                                providerName = "Piped Proxy"
                            )
                        )
                    }

                    if (candidates.isNotEmpty()) {
                        return@withContext sortAudioFormatsDescending(candidates)
                    }
                }
            } catch (_: Exception) {
                // Continue to next Piped mirror
            }
        }
        emptyList()
    }

    // =========================================================================
    // PROVIDER 4: Invidious Fallback API
    // https://inv.nadeko.net/api/v1/videos/{videoId}
    // =========================================================================

    suspend fun resolveViaInvidiousApi(videoId: String): String? = withContext(Dispatchers.IO) {
        val candidates = fetchInvidiousApiCandidates(videoId)
        selectBestAudioFormat(candidates)?.url
    }

    private suspend fun fetchInvidiousApiCandidates(videoId: String): List<AudioFormatCandidate> = withContext(Dispatchers.IO) {
        for (baseUrl in INVIDIOUS_ENDPOINTS) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/$videoId")
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                    )
                    .header("Accept", "application/json")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) continue
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) continue

                    val root = JSONObject(body)
                    val lengthSeconds = root.optLong("lengthSeconds", 0L)
                    val videoDurationMs = lengthSeconds * 1000L
                    if (videoDurationMs in 1L until MIN_FULL_TRACK_DURATION_MS) {
                        continue
                    }

                    val adaptiveFormats = root.optJSONArray("adaptiveFormats") ?: continue
                    val candidates = mutableListOf<AudioFormatCandidate>()

                    for (i in 0 until adaptiveFormats.length()) {
                        val fmt = adaptiveFormats.optJSONObject(i) ?: continue
                        val url = fmt.optString("url", "").trim()
                        if (url.isEmpty()) continue

                        val typeField = fmt.optString("type", "").lowercase(Locale.ROOT)
                        val cleanMime = typeField.substringBefore(";").trim()
                        if (!isSupportedAudioMimeType(cleanMime)) continue

                        val bitrateStr = fmt.optString("bitrate", "0")
                        val bitrate = bitrateStr.toIntOrNull() ?: fmt.optInt("bitrate", 0)
                        val sampleRate = fmt.optString("audioSampleRate", "48000").toIntOrNull() ?: 48000
                        val encoding = fmt.optString("encoding", if (cleanMime.contains("webm")) "opus" else "aac")
                        val approxDurationMs = videoDurationMs.takeIf { it > 0L } ?: 180_000L

                        if (approxDurationMs < MIN_FULL_TRACK_DURATION_MS) continue

                        candidates.add(
                            AudioFormatCandidate(
                                url = url,
                                mimeType = cleanMime,
                                bitrate = bitrate,
                                sampleRateHz = sampleRate,
                                approxDurationMs = approxDurationMs,
                                codec = encoding,
                                providerName = "Invidious Proxy"
                            )
                        )
                    }

                    if (candidates.isNotEmpty()) {
                        return@withContext sortAudioFormatsDescending(candidates)
                    }
                }
            } catch (_: Exception) {
                // Continue to next Invidious instance
            }
        }
        emptyList()
    }

    // =========================================================================
    // PROVIDER 5: High-Bitrate Full-Length Audio CDN Fallback (320kbps / 160kbps AAC)
    // Resolves video metadata via track or YouTube oEmbed and extracts full-length
    // audio/mp4 streams (>= 60,000ms) from JioSaavn v4 & Saavn CDN Mirrors.
    // =========================================================================

    private suspend fun resolveViaJioSaavnFullLengthByVideoId(videoId: String): String? = withContext(Dispatchers.IO) {
        val candidates = fetchJioSaavnFullLengthCandidates(videoId, null)
        selectBestAudioFormat(candidates)?.url
    }

    suspend fun fetchJioSaavnFullLengthCandidates(
        videoId: String,
        track: AudioTrack? = null
    ): List<AudioFormatCandidate> = withContext(Dispatchers.IO) {
        var rawTitle = track?.title?.trim().orEmpty()
        var rawArtist = track?.artist?.trim().orEmpty()

        if (rawTitle.isBlank() && videoId.isNotBlank()) {
            trackMetadataByVideoId[videoId]?.let { (cachedTitle, cachedArtist) ->
                rawTitle = cachedTitle
                rawArtist = cachedArtist
            }
        }

        // If title or artist is missing (e.g. Video items where artist was in user channel run or only videoId was passed),
        // query YouTube's public zero-auth oEmbed endpoint to get the exact video title & author_name!
        if ((rawTitle.isBlank() || rawArtist.isBlank() || rawArtist.equals("Unknown Artist", ignoreCase = true)) && videoId.isNotBlank()) {
            fetchYouTubeOEmbedMetadata(videoId)?.let { (oembedTitle, oembedAuthor) ->
                if (rawTitle.isBlank()) rawTitle = oembedTitle
                if (rawArtist.isBlank() || rawArtist.equals("Unknown Artist", ignoreCase = true)) {
                    rawArtist = oembedAuthor.removeSuffix("- Topic").trim()
                }
            }
        }

        if (rawTitle.isBlank()) return@withContext emptyList()

        // Strip "Artist - " prefix if embedded in video title (e.g. "Ed Sheeran - Shape of You (Official Music Video)")
        var cleanTitle = StreamMatcher.cleanSearchText(rawTitle)
        val cleanArtist = if (rawArtist.equals("Unknown Artist", ignoreCase = true)) {
            ""
        } else {
            StreamMatcher.cleanSearchText(rawArtist.substringBefore("•").trim())
        }

        if (cleanArtist.isNotBlank() && cleanTitle.startsWith("$cleanArtist ")) {
            cleanTitle = cleanTitle.removePrefix("$cleanArtist ").trim()
        }

        val combinedQuery = "$cleanTitle $cleanArtist".trim()
        if (combinedQuery.isBlank()) return@withContext emptyList()

        val queryVariants = listOfNotNull(
            combinedQuery,
            cleanTitle.takeIf { it != combinedQuery && it.isNotBlank() }
        )

        val candidates = mutableListOf<AudioFormatCandidate>()

        // 5A. Official JioSaavn v4 API (Direct 320kbps & 160kbps AAC MP4 streams)
        for (query in queryVariants) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = "https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&_marker=0&api_version=4&ctx=web6dot0&n=8&p=1&q=$encoded"
                val request = Request.Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                    )
                    .header("Accept", "application/json")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        if (body.isNotBlank()) {
                            val root = JSONObject(body)
                            val results = root.optJSONArray("results")
                            if (results != null) {
                                for (i in 0 until results.length()) {
                                    val obj = results.optJSONObject(i) ?: continue
                                    val candTitle = decodeHtml(obj.optString("title", "").ifBlank { obj.optString("song", "") })
                                    val moreInfo = obj.optJSONObject("more_info")
                                    val candArtist = extractSaavnV4Artist(obj, moreInfo)
                                    val candAlbum = decodeHtml(moreInfo?.optString("album", "").orEmpty())
                                    val durationSec = moreInfo?.optLong("duration", 0L)?.takeIf { it > 0L }
                                        ?: obj.optLong("duration", 0L)
                                    val candDurationMs = durationSec * 1000L

                                    if (candDurationMs < MIN_FULL_TRACK_DURATION_MS) continue

                                    if (StreamMatcher.isBannedInstrumentalOrKaraoke(
                                            candidateTitle = candTitle,
                                            candidateArtistOrChannel = candArtist,
                                            candidateDescription = candAlbum,
                                            userQuery = combinedQuery
                                        )
                                    ) {
                                        continue
                                    }

                                    val titleSim = StreamMatcher.normalizedLevenshteinSimilarity(
                                        cleanTitle,
                                        StreamMatcher.cleanSearchText(candTitle)
                                    )
                                    if (titleSim < 0.50) continue

                                    val encryptedMediaUrl = moreInfo?.optString("encrypted_media_url", "").orEmpty()
                                    if (encryptedMediaUrl.isBlank()) continue
                                    val decryptedUrl = decryptSaavnMediaUrl(encryptedMediaUrl) ?: continue
                                    val has320 = moreInfo?.optString("320kbps", "false") == "true" ||
                                        moreInfo?.optBoolean("320kbps", false) == true

                                    if (has320) {
                                        val url320 = decryptedUrl
                                            .replace("_96.mp4", "_320.mp4")
                                            .replace("_160.mp4", "_320.mp4")
                                        candidates.add(
                                            AudioFormatCandidate(
                                                url = url320,
                                                mimeType = "audio/mp4",
                                                bitrate = 320_000,
                                                sampleRateHz = 44100,
                                                approxDurationMs = candDurationMs,
                                                codec = "mp4a.40.2",
                                                providerName = "Hi-Fi CDN"
                                            )
                                        )
                                    }

                                    val url160 = decryptedUrl
                                        .replace("_96.mp4", "_160.mp4")
                                        .replace("_320.mp4", "_160.mp4")
                                    candidates.add(
                                        AudioFormatCandidate(
                                            url = url160,
                                            mimeType = "audio/mp4",
                                            bitrate = 160_000,
                                            sampleRateHz = 44100,
                                            approxDurationMs = candDurationMs,
                                            codec = "mp4a.40.2",
                                            providerName = "Hi-Fi CDN"
                                        )
                                    )

                                    if (candidates.size >= 4) break
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
            if (candidates.isNotEmpty()) break
        }

        if (candidates.isNotEmpty()) {
            return@withContext sortAudioFormatsDescending(candidates)
        }

        // 5B. Saavn Mirrors Fallback
        for (mirrorBase in SAAVN_MIRRORS) {
            try {
                val encoded = URLEncoder.encode(combinedQuery, "UTF-8")
                val request = Request.Builder()
                    .url("$mirrorBase/search/songs?query=$encoded&limit=5")
                    .header("Accept", "application/json")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) continue
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) continue
                    val root = JSONObject(body)
                    val results = root.optJSONObject("data")?.optJSONArray("results")
                        ?: root.optJSONArray("data")
                        ?: continue

                    for (i in 0 until results.length()) {
                        val obj = results.optJSONObject(i) ?: continue
                        val candTitle = decodeHtml(obj.optString("name", "").ifBlank { obj.optString("title", "") })
                        val durationSec = obj.optLong("duration", 0L)
                        val candDurationMs = durationSec * 1000L
                        if (candDurationMs < MIN_FULL_TRACK_DURATION_MS) continue

                        if (StreamMatcher.isBannedInstrumentalOrKaraoke(
                                candidateTitle = candTitle,
                                userQuery = combinedQuery
                            )
                        ) {
                            continue
                        }

                        val downloadUrls = obj.optJSONArray("downloadUrl") ?: continue
                        for (j in 0 until downloadUrls.length()) {
                            val dlObj = downloadUrls.optJSONObject(j) ?: continue
                            val qualityStr = dlObj.optString("quality", "")
                            val link = dlObj.optString("url", "").ifBlank { dlObj.optString("link", "") }.trim()
                            if (link.isEmpty()) continue
                            val kbps = qualityStr.replace("kbps", "").trim().toIntOrNull() ?: 160
                            if (kbps < 96) continue

                            candidates.add(
                                AudioFormatCandidate(
                                    url = link.replace("http://", "https://"),
                                    mimeType = "audio/mp4",
                                    bitrate = kbps * 1000,
                                    sampleRateHz = 44100,
                                    approxDurationMs = candDurationMs,
                                    codec = "mp4a.40.2",
                                    providerName = "Saavn CDN"
                                )
                            )
                        }
                        if (candidates.isNotEmpty()) break
                    }
                }
            } catch (_: Exception) {
            }
            if (candidates.isNotEmpty()) break
        }

        sortAudioFormatsDescending(candidates)
    }

    private fun fetchYouTubeOEmbedMetadata(videoId: String): Pair<String, String>? {
        return try {
            val watchUrl = URLEncoder.encode("https://www.youtube.com/watch?v=$videoId", "UTF-8")
            val request = Request.Builder()
                .url("https://www.youtube.com/oembed?url=$watchUrl&format=json")
                .header("Accept", "application/json")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return null
                val json = JSONObject(body)
                val title = json.optString("title", "").trim()
                val author = json.optString("author_name", "").trim()
                if (title.isNotEmpty()) {
                    trackMetadataByVideoId[videoId] = title to author
                    title to author
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractSaavnV4Artist(obj: JSONObject, moreInfo: JSONObject?): String {
        val primaryArtistsArr = moreInfo?.optJSONObject("artistMap")?.optJSONArray("primary_artists")
        if (primaryArtistsArr != null && primaryArtistsArr.length() > 0) {
            val names = mutableListOf<String>()
            for (j in 0 until primaryArtistsArr.length()) {
                val name = primaryArtistsArr.optJSONObject(j)?.optString("name", "").orEmpty()
                if (name.isNotBlank()) names.add(name)
            }
            if (names.isNotEmpty()) return decodeHtml(names.joinToString(", "))
        }
        return decodeHtml(moreInfo?.optString("music", "")?.ifBlank { obj.optString("subtitle", "") }.orEmpty())
    }

    private fun decryptSaavnMediaUrl(encryptedBase64: String): String? {
        return try {
            val encryptedBytes = Base64.decode(encryptedBase64.trim(), Base64.DEFAULT)
            val keySpec = DESKeySpec(JIOSAAVN_DES_KEY.toByteArray(Charsets.UTF_8))
            val secretKey = SecretKeySpec(keySpec.key, "DES")
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, Charsets.UTF_8).trim().replace("http://", "https://")
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeHtml(input: String): String {
        return input
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }

    // =========================================================================
    // STREAM QUALITY / RESOLUTION SORTER & VALIDATORS
    // =========================================================================

    private fun parseInnerTubeAdaptiveFormats(
        rawJson: String,
        providerName: String
    ): List<AudioFormatCandidate> {
        val root = JSONObject(rawJson)
        val playabilityStatus = root.optJSONObject("playabilityStatus")?.optString("status", "").orEmpty()
        if (playabilityStatus.isNotEmpty() && playabilityStatus != "OK") {
            return emptyList()
        }

        val videoDetails = root.optJSONObject("videoDetails")
        val vidId = videoDetails?.optString("videoId", "").orEmpty()
        val vidTitle = videoDetails?.optString("title", "").orEmpty()
        val vidAuthor = videoDetails?.optString("author", "").orEmpty()
        if (vidId.isNotBlank() && vidTitle.isNotBlank()) {
            trackMetadataByVideoId[vidId] = vidTitle to vidAuthor
        }

        val videoDetailsDurationMs = (videoDetails
            ?.optString("lengthSeconds", "0")
            ?.toLongOrNull() ?: 0L) * 1000L

        val streamingData = root.optJSONObject("streamingData") ?: return emptyList()
        val adaptiveFormats: JSONArray = streamingData.optJSONArray("adaptiveFormats") ?: return emptyList()

        val candidates = mutableListOf<AudioFormatCandidate>()

        for (i in 0 until adaptiveFormats.length()) {
            val formatObj = adaptiveFormats.optJSONObject(i) ?: continue
            val url = formatObj.optString("url", "").trim()
            if (url.isEmpty()) continue

            val rawMimeType = formatObj.optString("mimeType", "").lowercase(Locale.ROOT)
            val baseMimeType = rawMimeType.substringBefore(";").trim()
            if (!isSupportedAudioMimeType(baseMimeType)) continue

            val approxDurationMs = formatObj.optString("approxDurationMs", "")
                .toLongOrNull()
                ?: videoDetailsDurationMs

            // Strictly reject any format where approxDurationMs is less than 60000 (rejecting 30-second snippet previews)
            if (approxDurationMs < MIN_FULL_TRACK_DURATION_MS) {
                continue
            }

            val bitrate = formatObj.optInt("averageBitrate", 0)
                .takeIf { it > 0 }
                ?: formatObj.optInt("bitrate", 0)

            val sampleRateHz = formatObj.optString("audioSampleRate", "48000").toIntOrNull() ?: 48000
            val codec = rawMimeType.substringAfter("codecs=\"", "").substringBefore("\"")
                .ifEmpty { if (baseMimeType == "audio/webm") "opus" else "mp4a.40.2" }

            candidates.add(
                AudioFormatCandidate(
                    url = url,
                    mimeType = baseMimeType,
                    bitrate = bitrate,
                    sampleRateHz = sampleRateHz,
                    approxDurationMs = approxDurationMs,
                    codec = codec,
                    providerName = providerName
                )
            )
        }

        return sortAudioFormatsDescending(candidates)
    }

    /**
     * Filters for audio-only MIME types (`audio/webm`, `audio/mp4`), rejects `< 60000ms` previews,
     * and sorts candidates descending by audio bitrate: `candidates.sortedByDescending { it.bitrate }`.
     */
    fun sortAudioFormatsDescending(candidates: List<AudioFormatCandidate>): List<AudioFormatCandidate> {
        return candidates
            .filter { isSupportedAudioMimeType(it.mimeType) && it.approxDurationMs >= MIN_FULL_TRACK_DURATION_MS }
            .sortedByDescending { it.bitrate }
    }

    fun selectBestAudioFormat(candidates: List<AudioFormatCandidate>): AudioFormatCandidate? {
        return sortAudioFormatsDescending(candidates).firstOrNull()
    }

    private fun isSupportedAudioMimeType(mimeType: String): Boolean {
        val clean = mimeType.substringBefore(";").trim().lowercase(Locale.ROOT)
        return clean == "audio/webm" || clean == "audio/mp4"
    }

    fun isValidStreamUrl(url: String, videoId: String = ""): Boolean {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://") && !trimmed.startsWith("http://")) return false
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower.contains("youtube.com/watch") ||
            lower.contains("music.youtube.com/watch") ||
            lower.contains("itunes.apple.com") ||
            lower.contains("mzstatic.com") ||
            lower.contains("cdns-preview")
        ) {
            return false
        }
        if (videoId.isNotEmpty()) {
            val failedSet = failedUrlsByVideoId[videoId]
            if (failedSet != null && trimmed in failedSet) {
                return false
            }
        }
        return true
    }

    fun extractCleanVideoId(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("ytm_") || trimmed.startsWith("yt_")) {
            val stripped = trimmed.removePrefix("ytm_").removePrefix("yt_").trim()
            if (stripped.length == 11) return stripped
        }
        if (trimmed.contains("v=")) {
            val v = trimmed.substringAfter("v=").substringBefore("&").trim()
            if (v.length == 11) return v
        }
        if (trimmed.contains("youtu.be/")) {
            val v = trimmed.substringAfter("youtu.be/").substringBefore("?").trim()
            if (v.length == 11) return v
        }
        if (trimmed.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
            return trimmed
        }
        return ""
    }
}
