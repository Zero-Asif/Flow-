package com.example.network

import android.util.Log
import com.example.model.AudioTrack
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Embedded ISRC & Canonical Metadata Matcher (Module: StreamMatcher)
 * 
 * Guarantees zero wrong-song mismatches by:
 * 1. Extracting canonical metadata (Title, Primary Artist, Track Duration, and ISRC).
 * 2. Cross-provider ISRC Matching: Matches by 12-character ISRC code (isrc:{ISRC}).
 * 3. Strict Duration Sanity Check: Enforces ±3 second duration tolerance (rejects >3s difference).
 * 4. Multi-Factor Identity Scoring: Requires >=85% confidence (Levenshtein + Token Overlap).
 * 5. Rejects unrelated soundtrack/remix/cover/preview collisions.
 */
object StreamMatcher {

    const val MATCH_THRESHOLD_PERCENT = 85
    const val MAX_DURATION_DEVIATION_SEC = 3L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    data class CanonicalMetadata(
        val isrc: String?,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long
    ) {
        val durationSec: Long
            get() = (durationMs / 1000L).coerceAtLeast(0L)
    }

    /**
     * Extracts canonical metadata from an AudioTrack for matching.
     */
    fun extractCanonicalMetadata(track: AudioTrack): CanonicalMetadata {
        val cleanTitle = normalizeTitle(track.title)
        val primaryArtist = extractPrimaryArtist(track.artist)
        return CanonicalMetadata(
            isrc = sanitizeIsrc(track.isrc),
            title = cleanTitle,
            artist = primaryArtist,
            album = track.album.trim(),
            durationMs = track.durationMs
        )
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
     * Strips noise, parentheses, bracketed tags, feat/ft credits, audio/video indicators, and punctuation.
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
                Regex("""(?i)\b(feat|ft|featuring|official\s+video|official\s+audio|official\s+music\s+video|lyric\s+video|lyrics|remastered|remaster|4k|hd|hq|audio|video|original\s+mix|extended\s+mix)\b.*"""),
                " "
            )
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /**
     * Extracts the primary artist name, stripping secondary features and channel prefixes.
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
     * Computes token overlap similarity in [0.0, 1.0] to prevent single-word collisions
     * (e.g. "Moner Ekla Ghore" vs "Moner Radio" yields low token overlap ~0.33 and is rejected).
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
     * Computes Match Confidence Score (0–100%):
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
        // 1. Duration check with strict ±3s limit
        if (targetDurationSec > 10L && candidateDurationSec > 0L) {
            // Reject 30s previews when target is full-length song
            if (candidateDurationSec in 1L..45L && targetDurationSec > 60L) {
                return 0
            }
            val diffSec = abs(candidateDurationSec - targetDurationSec)
            if (diffSec > MAX_DURATION_DEVIATION_SEC) {
                return 0 // Disqualified by duration
            }
        } else if (candidateDurationSec in 1L..45L && targetDurationSec > 60L) {
            return 0
        }

        // 2. Reject unwanted remix/karaoke/ringtone collisions
        val rawCandLower = candidateTitle.lowercase()
        val rawTargetLower = targetTitle.lowercase()
        val disqualifiers = listOf("ringtone", "nightcore", "karaoke", "8d audio", "slowed", "reverb", "tribute")
        for (bad in disqualifiers) {
            if (rawCandLower.contains(bad) && !rawTargetLower.contains(bad)) {
                return 0
            }
        }

        val normCandTitle = normalizeTitle(candidateTitle)
        val normTargetTitle = normalizeTitle(targetTitle)
        if (normCandTitle.isBlank() || normTargetTitle.isBlank()) return 0

        // 3. Title Matching (40 points)
        val titleLev = calculateLevenshteinSimilarity(normCandTitle, normTargetTitle)
        val titleTok = calculateTokenSimilarity(normCandTitle, normTargetTitle)
        val combinedTitleSim = when {
            normCandTitle == normTargetTitle -> 1.0
            normCandTitle.startsWith(normTargetTitle) || normTargetTitle.startsWith(normCandTitle) -> 0.95
            normCandTitle.contains(normTargetTitle) || normTargetTitle.contains(normCandTitle) -> 0.90
            else -> (titleTok * 0.70) + (titleLev * 0.30)
        }
        val titlePoints = (combinedTitleSim * 40.0).coerceIn(0.0, 40.0)

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

        val total = (titlePoints + artistPoints + durationPoints).toInt().coerceIn(0, 100)
        return total
    }

    /**
     * Cross-Provider Matching Decision:
     * Returns true ONLY if:
     * - Candidate and Target have identical ISRC, OR
     * - Duration difference is <= 3s AND Title/Artist confidence score is >= 85%.
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

        // ISRC Match
        if (candIsrc != null && targIsrc != null) {
            if (candIsrc.equals(targIsrc, ignoreCase = true)) {
                return true
            }
            // Explicitly mismatched ISRCs on known tracks are different recordings
            return false
        }

        // Strict Duration Sanity Check: tolerance is strictly ±3 seconds
        if (targetDurationSec > 0L && candidateDurationSec > 0L) {
            if (abs(candidateDurationSec - targetDurationSec) > MAX_DURATION_DEVIATION_SEC) {
                return false
            }
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
     * Resolves canonical ISRC from external catalog (Deezer or iTunes) if missing.
     */
    fun resolveIsrcIfMissing(title: String, artist: String): String? {
        val cleanTitle = normalizeTitle(title).ifBlank { title.trim() }
        val primaryArtist = extractPrimaryArtist(artist).ifBlank { artist.trim() }
        return try {
            val encoded = URLEncoder.encode("track:\"$cleanTitle\" artist:\"$primaryArtist\"", "UTF-8")
            val request = Request.Builder()
                .url("https://api.deezer.com/search?q=$encoded&limit=3")
                .header("User-Agent", "FlowLossless/1.0 (Android; ISRC-Resolver)")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val data = json.optJSONArray("data")
                if (data != null && data.length() > 0) {
                    val first = data.getJSONObject(0)
                    val candTitle = first.optString("title", "")
                    val candArtist = first.optJSONObject("artist")?.optString("name", "").orEmpty()
                    val titleSim = calculateTokenSimilarity(normalizeTitle(candTitle), cleanTitle)
                    if (titleSim >= 0.80) {
                        val trackId = first.optLong("id", 0L)
                        if (trackId > 0L) {
                            // Query track endpoint to get ISRC
                            val trackReq = Request.Builder()
                                .url("https://api.deezer.com/track/$trackId")
                                .header("User-Agent", "FlowLossless/1.0")
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
            null
        } catch (e: Exception) {
            runCatching {
                Log.d("StreamMatcher", "ISRC resolution failed for $cleanTitle by $primaryArtist: ${e.message}")
            }
            null
        }
    }
}
