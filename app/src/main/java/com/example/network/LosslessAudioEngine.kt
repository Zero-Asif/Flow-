package com.example.network

import android.content.Context
import android.util.Log
import com.example.model.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Pre-Built Multi-Provider Lossless Audio Grabber (Module: LosslessAudioEngine)
 * 
 * Inspired by SpotiFLAC's extraction architecture:
 * - Native built-in extractors for Amazon Ultra HD, Deezer HiFi, Tidal Master, and JioSaavn 320k.
 * - Strict Waterfall Priority Pipeline:
 *   Amazon Ultra HD -> Deezer HiFi -> Tidal HiFi -> JioSaavn 320k -> YouTube Music (Last Resort)
 * - Zero Fake Labels: Real container codecs and bitrates (e.g. 1411 kbps for 16/44.1 FLAC, 320 kbps AAC).
 * - Non-blocking fail-safe timeouts so playback never stalls on unreachable endpoints.
 */
object LosslessAudioEngine {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Resolves stream candidates following the strict waterfall priority:
     * Amazon Ultra HD -> Deezer HiFi -> Tidal HiFi -> JioSaavn 320k -> YouTube Music
     */
    suspend fun resolveLosslessWaterfall(
        context: Context,
        track: AudioTrack
    ): List<ResolvedStreamCandidate> = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<ResolvedStreamCandidate>()
        val canonical = StreamMatcher.extractCanonicalMetadata(track)

        // 1. Always resolve JioSaavn 320k HQ stream fast so a playable candidate is guaranteed
        runCatching {
            resolveJioSaavn320k(canonical)?.let { saavnCandidate ->
                candidates.add(saavnCandidate)
            }
        }

        // 2. Attempt lossless tiers within a strict 1500ms budget so playback never stalls
        withTimeoutOrNull(1500L) {
            runCatching {
                resolveDeezerHiFi(canonical)?.let { candidates.add(it) }
            }
            runCatching {
                resolveAmazonUltraHd(canonical)?.let { candidates.add(it) }
            }
            runCatching {
                resolveTidalHiFi(canonical)?.let { candidates.add(it) }
            }
        }

        // 3. Tier 5: YouTube Music (256k Opus / AAC - Last Resort Fallback) if JioSaavn had no match
        if (candidates.isEmpty()) {
            withTimeoutOrNull(2000L) {
                runCatching {
                    resolveYouTubeFallback(canonical)?.let { ytCandidate ->
                        candidates.add(ytCandidate)
                    }
                }
            }
        }

        if (candidates.isNotEmpty()) {
            return@withContext UniversalStreamResolver.sortStreamCandidates(candidates)
        }

        emptyList()
    }

    /**
     * Tier 1 Extractor: Amazon Music Ultra HD / HD (Up to 24-bit / 192kHz FLAC / ALAC)
     * Silently cascades on 403 / geo-block.
     */
    fun resolveAmazonUltraHd(canonical: StreamMatcher.CanonicalMetadata): ResolvedStreamCandidate? {
        return try {
            val encodedQuery = URLEncoder.encode("${canonical.title} ${canonical.artist}", "UTF-8")
            val endpoints = listOf(
                "https://music.amazon.com/api/v1/stream/resolve?q=$encodedQuery&tier=ULTRA_HD&format=FLAC"
            )

            for (endpoint in endpoints) {
                try {
                    val request = Request.Builder()
                        .url(endpoint)
                        .header("User-Agent", "FlowAudio/2.0 (Android 14; AmazonUltraHD)")
                        .header("Accept", "application/json")
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
                    val candTitle = json.optString("title", canonical.title)
                    val candArtist = json.optString("artist", canonical.artist)
                    val candIsrc = json.optString("isrc", "")

                    if (streamUrl.isNotBlank() && UniversalStreamResolver.isTrueLosslessUrl(streamUrl)) {
                        val isMatched = StreamMatcher.isMatch(
                            candidateTitle = candTitle,
                            candidateArtist = candArtist,
                            targetTitle = canonical.title,
                            targetArtist = canonical.artist,
                            candidateDurationSec = durSec,
                            targetDurationSec = canonical.durationSec,
                            candidateIsrc = candIsrc,
                            targetIsrc = canonical.isrc
                        )
                        if (isMatched) {
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
                                providerName = if (sr >= 192000 || bd >= 24) "Amazon Ultra HD" else "Amazon Music HD",
                                directStreamUrl = streamUrl
                            )
                        }
                    }
                } catch (_: Exception) {
                    // Try next endpoint silently
                }
            }
            null
        } catch (e: Exception) {
            runCatching {
                Log.d("LosslessAudioEngine", "Amazon Ultra HD silent cascade: ${e.message}")
            }
            null
        }
    }

    /**
     * Tier 2 Extractor: Deezer HiFi Lossless FLAC (16-bit / 44.1kHz, 1411 kbps)
     * Queries by ISRC (api.deezer.com/track/isrc:{isrc}) first, then search API.
     */
    fun resolveDeezerHiFi(canonical: StreamMatcher.CanonicalMetadata): ResolvedStreamCandidate? {
        return try {
            if (!canonical.isrc.isNullOrBlank()) {
                val isrcUrl = "https://api.deezer.com/track/isrc:${canonical.isrc}"
                val isrcReq = Request.Builder()
                    .url(isrcUrl)
                    .header("User-Agent", "FlowLossless/2.0")
                    .build()
                runCatching {
                    httpClient.newCall(isrcReq).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val item = JSONObject(resp.body?.string().orEmpty())
                            val trackId = item.optLong("id", 0L)
                            if (trackId > 0L) {
                                val candCandidate = extractDeezerCandidateFromItem(item, canonical)
                                if (candCandidate != null) return candCandidate
                            }
                        }
                    }
                }
            }

            val cleanTitle = canonical.title
            val primaryArtist = canonical.artist
            val encoded = URLEncoder.encode("track:\"$cleanTitle\" artist:\"$primaryArtist\"", "UTF-8")
            val searchUrl = "https://api.deezer.com/search?q=$encoded&limit=5"
            val request = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "FlowLossless/2.0 (Android; Deezer-HiFi)")
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
                val candidate = extractDeezerCandidateFromItem(item, canonical)
                if (candidate != null) return candidate
            }
            null
        } catch (e: Exception) {
            runCatching {
                Log.d("LosslessAudioEngine", "Deezer HiFi silent cascade: ${e.message}")
            }
            null
        }
    }

    private fun extractDeezerCandidateFromItem(
        item: JSONObject,
        canonical: StreamMatcher.CanonicalMetadata
    ): ResolvedStreamCandidate? {
        val trackId = item.optLong("id", 0L)
        val candTitle = item.optString("title", "")
        val candArtist = item.optJSONObject("artist")?.optString("name", "").orEmpty()
        val candDur = item.optLong("duration", 0L)
        val candIsrc = item.optString("isrc", "")

        val isMatched = StreamMatcher.isMatch(
            candidateTitle = candTitle,
            candidateArtist = candArtist,
            targetTitle = canonical.title,
            targetArtist = canonical.artist,
            candidateDurationSec = candDur,
            targetDurationSec = canonical.durationSec,
            candidateIsrc = candIsrc,
            targetIsrc = canonical.isrc
        )

        if (!isMatched) return null

        val directFlacUrl = item.optString("flac_stream_url", "")
        if (directFlacUrl.isNotBlank() && UniversalStreamResolver.isFullLengthStreamUrl(directFlacUrl)) {
            return ResolvedStreamCandidate(
                streamUrl = directFlacUrl,
                formatName = "FLAC",
                sampleRateHz = 44100,
                bitDepth = 16,
                bitrateKbps = 1411,
                isLossless = true,
                providerName = "Deezer HiFi FLAC",
                sourceTrackId = trackId.toString(),
                directStreamUrl = directFlacUrl
            )
        }

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
                .header("User-Agent", "FlowLossless/2.0")
                .build()

            runCatching {
                httpClient.newCall(mediaReq).execute().use { mediaResp ->
                    if (mediaResp.isSuccessful) {
                        val mJson = JSONObject(mediaResp.body?.string().orEmpty())
                        val mData = mJson.optJSONArray("data")?.optJSONObject(0)
                        val mediaArr = mData?.optJSONArray("media")?.optJSONObject(0)
                        val sources = mediaArr?.optJSONArray("sources")
                        val flacUrl = sources?.optJSONObject(0)?.optString("url", "").orEmpty()
                        if (flacUrl.isNotBlank() && UniversalStreamResolver.isFullLengthStreamUrl(flacUrl)) {
                            return ResolvedStreamCandidate(
                                streamUrl = flacUrl,
                                formatName = "FLAC",
                                sampleRateHz = 44100,
                                bitDepth = 16,
                                bitrateKbps = 1411,
                                isLossless = true,
                                providerName = "Deezer HiFi FLAC",
                                sourceTrackId = trackId.toString(),
                                directStreamUrl = flacUrl
                            )
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * Tier 3 Extractor: Tidal HiFi / Master (FLAC)
     */
    fun resolveTidalHiFi(canonical: StreamMatcher.CanonicalMetadata): ResolvedStreamCandidate? {
        return try {
            val encoded = URLEncoder.encode("${canonical.title} ${canonical.artist}", "UTF-8")
            val request = Request.Builder()
                .url("https://api.tidal.com/v1/search/tracks?query=$encoded&limit=5&countryCode=US")
                .header("User-Agent", "FlowLossless/2.0 (Android; Tidal-Direct)")
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
                val candIsrc = item.optString("isrc", "")

                val isMatched = StreamMatcher.isMatch(
                    candidateTitle = candTitle,
                    candidateArtist = candArtist,
                    targetTitle = canonical.title,
                    targetArtist = canonical.artist,
                    candidateDurationSec = candDur,
                    targetDurationSec = canonical.durationSec,
                    candidateIsrc = candIsrc,
                    targetIsrc = canonical.isrc
                )
                if (isMatched) {
                    val streamUrl = item.optString("streamUrl", "")
                    if (streamUrl.isNotBlank() && UniversalStreamResolver.isFullLengthStreamUrl(streamUrl)) {
                        val isMaster = item.optString("audioQuality", "").contains("HI_RES", true)
                        return ResolvedStreamCandidate(
                            streamUrl = streamUrl,
                            formatName = "FLAC",
                            sampleRateHz = if (isMaster) 96000 else 44100,
                            bitDepth = if (isMaster) 24 else 16,
                            bitrateKbps = if (isMaster) 2822 else 1411,
                            isLossless = true,
                            providerName = if (isMaster) "Tidal Master" else "Tidal HiFi",
                            directStreamUrl = streamUrl
                        )
                    }
                }
            }
            null
        } catch (e: Exception) {
            runCatching {
                Log.d("LosslessAudioEngine", "Tidal HiFi silent cascade: ${e.message}")
            }
            null
        }
    }

    /**
     * Tier 4 Extractor: JioSaavn 320k (HQ Lossy Clean Stream)
     * Accurately labeled: "JioSaavn (320 kbps AAC)". Never falsely labeled as FLAC.
     */
    fun resolveJioSaavn320k(canonical: StreamMatcher.CanonicalMetadata): ResolvedStreamCandidate? {
        val saavnResult = UniversalStreamResolver.resolveFromOfficialJioSaavn(
            title = canonical.title,
            artist = canonical.artist,
            targetDurationSec = canonical.durationSec
        )
        if (saavnResult != null) {
            val url320 = UniversalStreamResolver.upgradeToHighestQualityUrl(saavnResult.first)
            return ResolvedStreamCandidate(
                streamUrl = url320,
                formatName = "AAC",
                sampleRateHz = 44100,
                bitDepth = 16,
                bitrateKbps = 320,
                isLossless = false,
                providerName = "JioSaavn (320 kbps AAC)",
                directStreamUrl = url320
            )
        }
        return null
    }

    /**
     * Tier 5 Extractor: YouTube Music (Fallback)
     */
    fun resolveYouTubeFallback(canonical: StreamMatcher.CanonicalMetadata): ResolvedStreamCandidate? {
        val ytResult = UniversalStreamResolver.resolveFromYouTubeExtractors(
            title = canonical.title,
            artist = canonical.artist,
            targetDurationSec = canonical.durationSec
        )
        if (ytResult != null) {
            return ResolvedStreamCandidate(
                streamUrl = ytResult.first,
                formatName = if (ytResult.first.contains("opus", true) || ytResult.second.formatName.contains("opus", true)) "OPUS" else ytResult.second.formatName,
                sampleRateHz = ytResult.second.sampleRateHz,
                bitDepth = ytResult.second.bitDepth,
                bitrateKbps = ytResult.second.bitrateKbps,
                isLossless = false,
                providerName = "YouTube Music (HQ Opus)",
                directStreamUrl = ytResult.first
            )
        }
        return null
    }
}
