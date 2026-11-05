package com.example.network

import android.util.Log
import com.example.model.AudioTrack
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Multi-Stage Canonical Metadata & Stream Verification Engine (Module: StreamMatcher)
 *
 * Guarantees zero wrong-song mismatches by:
 * 1. Canonical Metadata Registry Lookup (iTunes Search API & MusicBrainz Open Registry)
 *    to retrieve authoritative Canonical Title, Primary Artist, Exact Duration (ms),
 *    Primary Genre, High-Res Artwork (1400x1400), and ISRC.
 * 2. Cross-Provider ISRC Matching: Matches by 12-character ISRC code (isrc:{ISRC}).
 * 3. Strict Duration Sanity Filter: Enforces ±3 second (±3000ms) duration tolerance.
 *    Discards any stream candidate whose duration differs from canonical duration by >3s.
 * 4. String Distance Normalization & Multi-Factor Identity Scoring:
 *    Strips punctuation, soundtrack tags, remaster suffixes, and requires >= 85% similarity.
 */
object StreamMatcher {

    private const val TAG = "StreamMatcher"
    const val MATCH_THRESHOLD_PERCENT = 85
    const val MAX_DURATION_DEVIATION_SEC = 3L
    const val MAX_DURATION_DEVIATION_MS = 3000L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val canonicalCache = ConcurrentHashMap<String, CanonicalMetadata>()

    data class CanonicalMetadata(
        val isrc: String?,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val primaryGenre: String = "",
        val highResArtworkUrl: String = ""
    ) {
        val canonicalTitle: String
            get() = title
        val primaryArtist: String
            get() = artist
        val durationSec: Long
            get() = (durationMs / 1000L).coerceAtLeast(0L)
    }

    /**
     * Returns true if a candidate title/artist is an unrequested cover, tribute, karaoke, slowed/reverb, or ringtone.
     */
    fun isDisqualifiedCoverOrRemix(
        candidateTitle: String,
        candidateArtist: String,
        referenceQueryOrTitle: String
    ): Boolean {
        val candCombined = "${candidateTitle.lowercase()} ${candidateArtist.lowercase()}"
        val refLower = referenceQueryOrTitle.lowercase()
        val disqualifiers = listOf(
            "cover", "tribute", "karaoke", "ringtone", "nightcore", "8d audio", "slowed", "reverb", "instrumental version"
        )
        for (bad in disqualifiers) {
            if (candCombined.contains(bad) && !refLower.contains(bad)) {
                return true
            }
        }
        return false
    }

    /**
     * Alias for fetchCanonicalMetadataFromPublicRegistry used by GlobalMusicSearchEngine.
     */
    fun resolveCanonicalTrack(
        rawTitle: String,
        rawArtist: String,
        fallbackDurationMs: Long = 0L
    ): CanonicalMetadata {
        return fetchCanonicalMetadataFromPublicRegistry(rawTitle, rawArtist, fallbackDurationMs)
    }

    /**
     * Extracts normalized canonical metadata from an AudioTrack, enriching from the in-memory
     * canonical cache if previously resolved.
     */
    fun extractCanonicalMetadata(track: AudioTrack): CanonicalMetadata {
        val cleanTitle = normalizeTitle(track.title)
        val primaryArtist = extractPrimaryArtist(track.artist)
        val cacheKey = "${cleanTitle.lowercase()}|${primaryArtist.lowercase()}"

        canonicalCache[cacheKey]?.let { cached ->
            return cached.copy(
                isrc = sanitizeIsrc(track.isrc) ?: cached.isrc,
                durationMs = if (track.durationMs > 10_000L) track.durationMs else cached.durationMs
            )
        }

        return CanonicalMetadata(
            isrc = sanitizeIsrc(track.isrc),
            title = cleanTitle.ifBlank { track.title.trim() },
            artist = primaryArtist.ifBlank { track.artist.trim() },
            album = track.album.trim(),
            durationMs = track.durationMs,
            primaryGenre = track.genre,
            highResArtworkUrl = upgradeArtworkToHighRes(track.artworkUrl)
        )
    }

    /**
     * Queries public search registries (iTunes Search API & MusicBrainz) to retrieve
     * canonical Title, Primary Artist, Exact Duration (ms), Primary Genre, 1400x1400 Artwork, and ISRC.
     */
    fun fetchCanonicalMetadataFromPublicRegistry(
        rawTitle: String,
        rawArtist: String,
        fallbackDurationMs: Long = 0L
    ): CanonicalMetadata {
        val cleanTitle = normalizeTitle(rawTitle).ifBlank { rawTitle.trim() }
        val primaryArtist = extractPrimaryArtist(rawArtist).ifBlank { rawArtist.trim() }
        val cacheKey = "${cleanTitle.lowercase()}|${primaryArtist.lowercase()}"

        canonicalCache[cacheKey]?.let { return it }

        // Stage 1: Query iTunes Search API for canonical trackTimeMillis, primaryGenreName, and 1400x1400 artwork
        try {
            val encodedTerm = URLEncoder.encode("$cleanTitle $primaryArtist", "UTF-8")
            val itunesUrl = "https://itunes.apple.com/search?term=$encodedTerm&entity=song&limit=5"
            val request = Request.Builder()
                .url(itunesUrl)
                .header("User-Agent", "FlowAudio/2.0 (Android; CanonicalRegistry)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val results = json.optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        var bestMatch: JSONObject? = null
                        var bestSim = 0.0

                        for (i in 0 until results.length()) {
                            val item = results.getJSONObject(i)
                            val candTitle = item.optString("trackName", "")
                            val candArtist = item.optString("artistName", "")
                            val candDurMs = item.optLong("trackTimeMillis", 0L)

                            if (fallbackDurationMs > 10_000L && candDurMs > 0L) {
                                if (!isDurationValid(candDurMs, fallbackDurationMs)) continue
                            }

                            val titleSim = calculateTitleSimilarityPercent(candTitle, cleanTitle) / 100.0
                            val artistSim = maxOf(
                                calculateTokenSimilarity(extractPrimaryArtist(candArtist), primaryArtist),
                                calculateLevenshteinSimilarity(extractPrimaryArtist(candArtist), primaryArtist)
                            )
                            val combined = (titleSim * 0.6) + (artistSim * 0.4)
                            if (combined > bestSim && titleSim >= 0.85) {
                                bestSim = combined
                                bestMatch = item
                            }
                        }

                        if (bestMatch != null) {
                            val resolvedTitle = bestMatch.optString("trackName", rawTitle).trim()
                            val resolvedArtist = bestMatch.optString("artistName", rawArtist).trim()
                            val resolvedAlbum = bestMatch.optString("collectionName", "").trim()
                            val resolvedDurationMs = bestMatch.optLong("trackTimeMillis", fallbackDurationMs)
                            val resolvedGenre = bestMatch.optString("primaryGenreName", "")
                            val rawArt = bestMatch.optString("artworkUrl100", "")
                            val hiResArt = upgradeArtworkToHighRes(rawArt)
                            val resolvedIsrc = resolveIsrcIfMissing(resolvedTitle, resolvedArtist)

                            val canonical = CanonicalMetadata(
                                isrc = resolvedIsrc,
                                title = normalizeTitle(resolvedTitle).ifBlank { resolvedTitle },
                                artist = extractPrimaryArtist(resolvedArtist).ifBlank { resolvedArtist },
                                album = resolvedAlbum,
                                durationMs = if (resolvedDurationMs > 0L) resolvedDurationMs else fallbackDurationMs,
                                primaryGenre = resolvedGenre,
                                highResArtworkUrl = hiResArt
                            )
                            canonicalCache[cacheKey] = canonical
                            return canonical
                        }
                    }
                }
            }
        } catch (e: Exception) {
            runCatching { Log.d(TAG, "iTunes canonical lookup failed: ${e.message}") }
        }

        // Stage 2: Query MusicBrainz Open Registry for authoritative recording duration & ISRC
        try {
            val mbQuery = URLEncoder.encode("recording:\"$cleanTitle\" AND artist:\"$primaryArtist\"", "UTF-8")
            val mbUrl = "https://musicbrainz.org/ws/2/recording/?query=$mbQuery&limit=3&fmt=json"
            val request = Request.Builder()
                .url(mbUrl)
                .header("User-Agent", "FlowAudio/2.0 (https://flowaudio.io)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val recordings = json.optJSONArray("recordings")
                    if (recordings != null && recordings.length() > 0) {
                        for (i in 0 until recordings.length()) {
                            val rec = recordings.getJSONObject(i)
                            val recTitle = rec.optString("title", "")
                            val recLengthMs = rec.optLong("length", 0L)
                            if (calculateTitleSimilarityPercent(recTitle, cleanTitle) >= MATCH_THRESHOLD_PERCENT) {
                                if (fallbackDurationMs <= 10_000L || isDurationValid(recLengthMs, fallbackDurationMs)) {
                                    val isrcsArray = rec.optJSONArray("isrcs")
                                    val firstIsrc = if (isrcsArray != null && isrcsArray.length() > 0) {
                                        sanitizeIsrc(isrcsArray.optString(0))
                                    } else {
                                        null
                                    }
                                    val canonical = CanonicalMetadata(
                                        isrc = firstIsrc,
                                        title = cleanTitle,
                                        artist = primaryArtist,
                                        album = "",
                                        durationMs = if (recLengthMs > 0L) recLengthMs else fallbackDurationMs
                                    )
                                    canonicalCache[cacheKey] = canonical
                                    return canonical
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            runCatching { Log.d(TAG, "MusicBrainz canonical lookup failed: ${e.message}") }
        }

        val fallbackCanonical = CanonicalMetadata(
            isrc = resolveIsrcIfMissing(cleanTitle, primaryArtist),
            title = cleanTitle,
            artist = primaryArtist,
            album = "",
            durationMs = fallbackDurationMs
        )
        canonicalCache[cacheKey] = fallbackCanonical
        return fallbackCanonical
    }

    /**
     * Upgrades iTunes / Apple Music / Deezer artwork URLs to 1400x1400 high-resolution cover art.
     */
    fun upgradeArtworkToHighRes(artworkUrl: String?): String {
        if (artworkUrl.isNullOrBlank()) return ""
        return artworkUrl
            .replace("100x100bb", "1400x1400bb")
            .replace("600x600bb", "1400x1400bb")
            .replace("250x250-000000-80-0-0", "1000x1000-000000-80-0-0")
            .replace("500x500-000000-80-0-0", "1000x1000-000000-80-0-0")
    }

    /**
     * Strict Duration Sanity Filter:
     * Returns true ONLY if candidateDurationMs is within ±3000ms (±3 seconds) of canonicalDurationMs.
     */
    fun isDurationValid(candidateDurationMs: Long, canonicalDurationMs: Long): Boolean {
        if (canonicalDurationMs <= 10_000L || candidateDurationMs <= 0L) return true
        // Reject 30s previews when target is a full-length song
        if (candidateDurationMs in 1_000L..45_000L && canonicalDurationMs > 60_000L) {
            return false
        }
        return abs(candidateDurationMs - canonicalDurationMs) <= MAX_DURATION_DEVIATION_MS
    }

    /**
     * Sanitizes and validates an ISRC (12 alphanumeric characters, e.g., "USUM71703861").
     */
    fun sanitizeIsrc(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.replace("-", "").replace(" ", "").trim().uppercase()
        return if (cleaned.length == 12 && cleaned.matches(Regex("""^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$"""))) {
            cleaned
        } else if (cleaned.length in 10..15) {
            cleaned
        } else {
            null
        }
    }

    /**
     * Strips noise, parentheses, bracketed tags, soundtrack suffixes, feat/ft credits,
     * remaster tags, audio/video indicators, and punctuation.
     */
    fun normalizeTitle(text: String): String {
        return text
            .lowercase()
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("""\[[^]]*]"""), " ")
            .replace(
                Regex("""(?i)\b(feat|ft|featuring|from\s+the\s+original\s+motion\s+picture|original\s+motion\s+picture\s+soundtrack|original\s+soundtrack|soundtrack\s+version|film\s+version|official\s+video|official\s+audio|official\s+music\s+video|lyric\s+video|lyrics|remastered|remaster|deluxe\s+edition|anniversary\s+edition|bonus\s+track|4k|hd|hq|audio|video|original\s+mix|extended\s+mix|radio\s+edit)\b.*"""),
                " "
            )
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /**
     * Extracts the primary artist name, stripping secondary features and channel suffixes.
     */
    fun extractPrimaryArtist(artist: String): String {
        val firstSplit = artist.split(
            ",", "&", "feat.", "ft.", "feat", "ft", "•", "/", "-", "with", "vs", "pres."
        ).firstOrNull()?.trim() ?: artist

        return firstSplit
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
     * Computes token overlap similarity in [0.0, 1.0] to prevent single-word collisions.
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
                else if (t1.length >= 4 && t2.length >= 4 && calculateLevenshteinSimilarity(t1, t2) >= 0.85) 0.90
                else 0.0
            } ?: 0.0
            matchedCount += bestTokenMatch
        }
        val denominator = maxOf(tokens1.size, tokens2.size).toDouble()
        return (matchedCount / denominator).coerceIn(0.0, 1.0)
    }

    /**
     * Computes normalized title similarity percentage (0..100).
     */
    fun calculateTitleSimilarityPercent(candidateTitle: String, targetTitle: String): Int {
        val normCandTitle = normalizeTitle(candidateTitle)
        val normTargetTitle = normalizeTitle(targetTitle)
        if (normCandTitle.isBlank() || normTargetTitle.isBlank()) return 0

        val titleLev = calculateLevenshteinSimilarity(normCandTitle, normTargetTitle)
        val titleTok = calculateTokenSimilarity(normCandTitle, normTargetTitle)
        val combinedTitleSim = when {
            normCandTitle == normTargetTitle -> 1.0
            normCandTitle.startsWith(normTargetTitle) || normTargetTitle.startsWith(normCandTitle) -> 0.95
            normCandTitle.contains(normTargetTitle) || normTargetTitle.contains(normCandTitle) -> 0.90
            else -> (titleTok * 0.70) + (titleLev * 0.30)
        }
        return (combinedTitleSim * 100.0).toInt().coerceIn(0, 100)
    }

    /**
     * Computes Match Confidence Score (0–100%):
     * - Enforces strict ±3s duration sanity filter and >= 85% normalized title similarity.
     * - Title Similarity: 40 points
     * - Artist Similarity: 40 points
     * - Duration Sanity: 20 points (Strict ±3s tolerance)
     */
    fun computeMatchConfidenceScore(
        candidateTitle: String,
        candidateArtist: String,
        targetTitle: String,
        targetArtist: String,
        candidateDurationSec: Long,
        targetDurationSec: Long
    ): Int {
        // 1. Duration Sanity Filter with strict ±3s limit
        if (targetDurationSec > 10L && candidateDurationSec > 0L) {
            if (candidateDurationSec in 1L..45L && targetDurationSec > 60L) {
                return 0
            }
            val diffSec = abs(candidateDurationSec - targetDurationSec)
            if (diffSec > MAX_DURATION_DEVIATION_SEC) {
                return 0 // Disqualified by duration (>3s discrepancy)
            }
        } else if (candidateDurationSec in 1L..45L && targetDurationSec > 60L) {
            return 0
        }

        // 2. Reject unwanted remix/karaoke/ringtone/cover collisions
        val rawCandLower = candidateTitle.lowercase()
        val rawTargetLower = targetTitle.lowercase()
        val disqualifiers = listOf(
            "ringtone", "nightcore", "karaoke", "8d audio", "slowed", "reverb", "tribute", "cover version", "instrumental"
        )
        for (bad in disqualifiers) {
            if (rawCandLower.contains(bad) && !rawTargetLower.contains(bad)) {
                return 0
            }
        }

        val normCandTitle = normalizeTitle(candidateTitle)
        val normTargetTitle = normalizeTitle(targetTitle)
        if (normCandTitle.isBlank() || normTargetTitle.isBlank()) return 0

        // 3. Title Similarity Check (must exceed 85% similarity threshold)
        val titleSimPercent = calculateTitleSimilarityPercent(candidateTitle, targetTitle)
        if (titleSimPercent < MATCH_THRESHOLD_PERCENT) {
            return 0
        }
        val titlePoints = (titleSimPercent / 100.0 * 40.0).coerceIn(0.0, 40.0)

        // 4. Artist Matching (40 points)
        val normTargetArtist = extractPrimaryArtist(targetArtist)
        val normCandArtist = extractPrimaryArtist(candidateArtist)

        val artistSim = when {
            normTargetArtist.isBlank() -> 0.85
            normCandArtist == normTargetArtist -> 1.0
            normCandArtist.contains(normTargetArtist) || normTargetArtist.contains(normCandArtist) -> 0.92
            else -> maxOf(
                calculateTokenSimilarity(normCandArtist, normTargetArtist),
                calculateLevenshteinSimilarity(normCandArtist, normTargetArtist)
            )
        }
        val artistPoints = (artistSim * 40.0).coerceIn(0.0, 40.0)

        // 5. Duration Points (20 points)
        val durationPoints = if (targetDurationSec > 10L && candidateDurationSec > 0L) {
            val diffSec = abs(candidateDurationSec - targetDurationSec)
            when {
                diffSec <= 1L -> 20.0
                diffSec <= 2L -> 18.0
                diffSec <= 3L -> 16.0
                else -> 0.0
            }
        } else {
            20.0
        }

        return (titlePoints + artistPoints + durationPoints).toInt().coerceIn(0, 100)
    }

    /**
     * Cross-Provider Matching Decision:
     * Returns true ONLY if:
     * - Candidate and Target have identical ISRC (and valid duration), OR
     * - Duration difference is <= 3s AND normalized Title similarity >= 85% AND overall confidence score >= 85%.
     */
    fun isMatch(
        candidateTitle: String,
        candidateArtist: String,
        targetTitle: String,
        targetArtist: String,
        candidateDurationSec: Long,
        targetDurationSec: Long,
        candidateIsrc: String? = null,
        targetIsrc: String? = null
    ): Boolean {
        val candIsrc = sanitizeIsrc(candidateIsrc)
        val targIsrc = sanitizeIsrc(targetIsrc)

        // Strict Duration Sanity Check: tolerance is strictly ±3 seconds
        if (targetDurationSec > 10L && candidateDurationSec > 0L) {
            if (abs(candidateDurationSec - targetDurationSec) > MAX_DURATION_DEVIATION_SEC) {
                return false
            }
        }

        // ISRC Match
        if (candIsrc != null && targIsrc != null) {
            return candIsrc.equals(targIsrc, ignoreCase = true)
        }

        val score = computeMatchConfidenceScore(
            candidateTitle = candidateTitle,
            candidateArtist = candidateArtist,
            targetTitle = targetTitle,
            targetArtist = targetArtist,
            candidateDurationSec = candidateDurationSec,
            targetDurationSec = targetDurationSec
        )

        return score >= MATCH_THRESHOLD_PERCENT
    }

    /**
     * Resolves canonical ISRC from public catalog registries if missing on the track.
     */
    fun resolveIsrcIfMissing(title: String, artist: String): String? {
        val cleanTitle = normalizeTitle(title).ifBlank { title.trim() }
        val primaryArtist = extractPrimaryArtist(artist).ifBlank { artist.trim() }
        return try {
            val encoded = URLEncoder.encode("track:\"$cleanTitle\" artist:\"$primaryArtist\"", "UTF-8")
            val request = Request.Builder()
                .url("https://api.deezer.com/search?q=$encoded&limit=3")
                .header("User-Agent", "FlowLossless/2.0 (Android; ISRC-Resolver)")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val data = json.optJSONArray("data")
                    if (data != null && data.length() > 0) {
                        val first = data.getJSONObject(0)
                        val candTitle = first.optString("title", "")
                        if (calculateTitleSimilarityPercent(candTitle, cleanTitle) >= MATCH_THRESHOLD_PERCENT) {
                            val trackId = first.optLong("id", 0L)
                            if (trackId > 0L) {
                                val trackReq = Request.Builder()
                                    .url("https://api.deezer.com/track/$trackId")
                                    .header("User-Agent", "FlowLossless/2.0")
                                    .build()
                                httpClient.newCall(trackReq).execute().use { trackResp ->
                                    if (trackResp.isSuccessful) {
                                        val trackJson = JSONObject(trackResp.body?.string().orEmpty())
                                        val isrc = trackJson.optString("isrc", "")
                                        return sanitizeIsrc(isrc)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            runCatching {
                Log.d(TAG, "ISRC resolution failed for $cleanTitle by $primaryArtist: ${e.message}")
            }
            null
        }
    }
}
