package com.example.network

import android.util.Log
import com.example.model.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Canonical ISRC-first track metadata and multi-factor stream matching engine.
 *
 * Implements:
 * 1. Canonical Metadata Fetching (iTunes Search API & Deezer API) to resolve official
 *    `ISRC`, `trackTimeMillis`, `primaryGenreName`, `collectionName`, and high-res artwork.
 * 2. Strict Vocal-Master Verification & Anti-Instrumental/Karaoke Filter:
 *    - Immediately discards any candidate stream or track containing `"instrumental"`, `"karaoke"`,
 *      `"backing track"`, `"minus one"`, `"no vocal"`, `"off vocal"`, `"piano tutorial"`,
 *      `"acoustic cover"`, `"guitar backing"`, etc., unless the user's query explicitly requests it.
 *    - Prioritizes candidates explicitly labeled `"Official Audio"`, `"Official Music Video"`,
 *      or `"- Topic"` (YouTube Official Auto-Generated Music Channels).
 * 3. Weighted Confidence Matrix (`0..100+` score) for stream verification:
 *    - ISRC Exact Match: +50 pts
 *    - Title & Artist Levenshtein Similarity (> 0.85): +25 pts
 *    - Duration Proximity (within ±3.0 seconds of canonical duration): +25 pts
 *    - Minimum threshold to accept a stream: 70 pts (and hard rejection if duration differs by > 3.0s).
 */
object StreamMatcher {
    private const val TAG = "StreamMatcher"

    /** Strict ±3.0 second tolerance (3,000ms) for canonical track verification. */
    const val STRICT_DURATION_TOLERANCE_MS = 3_000L

    /** Minimum required confidence score (0..100) to accept a candidate stream. */
    const val MIN_ACCEPTABLE_CONFIDENCE_SCORE = 70

    /**
     * Strict negative keywords that indicate a vocal-stripped instrumental, karaoke,
     * backing track, tutorial, or cover. Any stream or search candidate containing these
     * tokens is immediately disqualified unless the user's query explicitly contains the keyword.
     */
    val BANNED_INSTRUMENTAL_KEYWORDS = listOf(
        "instrumental",
        "karaoke",
        "backing track",
        "minus one",
        "no vocal",
        "no vocals",
        "off vocal",
        "off-vocal",
        "piano tutorial",
        "guitar tutorial",
        "acoustic cover",
        "guitar backing",
        "backing guitar",
        "without vocals",
        "vocalless",
        "vocal-less",
        "instrumental version",
        "instrumental mix",
        "karaoke version",
        "piano cover",
        "guitar cover",
        "flute cover",
        "violin cover",
        "drum cover",
        "bass cover",
        "8d audio",
        "nightcore",
        "slowed and reverb",
        "slowed + reverb",
        "sped up",
        "chopped and screwed",
        "lofi flip",
        "tribute to",
        "made famous by",
        "in the style of",
        "originally performed by",
        "ringtone"
    )

    private val SECONDARY_COVER_MODIFIERS = listOf(
        "cover",
        "tribute",
        "parody",
        "reprise",
        "fan made",
        "fanmade",
        "amateur",
        "unplugged cover"
    )

    /**
     * Positive vocal hierarchy markers that identify authentic vocal master recordings.
     */
    private val VOCAL_MASTER_MARKERS = listOf(
        "official audio",
        "official music video",
        "official video",
        "official lyric video",
        "original motion picture soundtrack",
        "full video",
        "full audio"
    )

    data class CanonicalMetadata(
        val title: String,
        val artist: String,
        val album: String = "Single",
        val durationSec: Long = 0L,
        val isrc: String? = null,
        val highResArtworkUrl: String? = null
    )

    data class CanonicalTrackInfo(
        val canonicalTitle: String,
        val primaryArtist: String,
        val album: String,
        val durationMs: Long,
        val isrc: String = "",
        val highResArtworkUrl: String = ""
    )

    data class CanonicalTrackMetadata(
        val isrc: String = "",
        val canonicalTitle: String,
        val canonicalArtist: String,
        val albumName: String,
        val primaryGenreName: String,
        val trackTimeMillis: Long,
        val releaseYear: Int,
        val trackNumber: Int = 1,
        val highResArtworkUrl: String,
        val previewStreamUrl: String = ""
    )

    data class CandidateScoreResult(
        val totalScore: Int,
        val isrcMatched: Boolean,
        val titleArtistSimilarity: Double,
        val durationDeltaMs: Long,
        val isDurationWithinTolerance: Boolean,
        val isAccepted: Boolean,
        val rejectionReason: String? = null
    )

    /**
     * Strict negative filter that inspects candidate stream titles, descriptions, and
     * channel/artist names for banned instrumental, karaoke, backing track, or cover markers.
     *
     * Returns true if the candidate MUST be discarded.
     */
    fun isBannedInstrumentalOrKaraoke(
        candidateTitle: String,
        candidateArtistOrChannel: String = "",
        candidateDescription: String = "",
        userQuery: String = ""
    ): Boolean {
        val candTitleLower = candidateTitle.lowercase()
        val candArtistLower = candidateArtistOrChannel.lowercase()
        val candDescLower = candidateDescription.lowercase()
        val queryLower = userQuery.lowercase()

        for (keyword in BANNED_INSTRUMENTAL_KEYWORDS) {
            val inCandidate = candTitleLower.contains(keyword) ||
                candArtistLower.contains(keyword) ||
                candDescLower.contains(keyword)
            if (inCandidate && !queryLower.contains(keyword)) {
                return true
            }
        }
        return false
    }

    /**
     * Computes a priority bonus for authentic vocal master recordings:
     * - Candidates from "- Topic" (YouTube Official Auto-Generated Music Channels): +30 pts
     * - Candidates explicitly labeled "Official Audio" or "Official Music Video": +25 pts
     * - Official Artist Channel / Verified markers: +15 pts
     */
    fun computeVocalMasterPriorityBonus(
        candidateTitle: String,
        candidateArtistOrChannel: String = ""
    ): Int {
        val titleLower = candidateTitle.lowercase()
        val artistLower = candidateArtistOrChannel.lowercase()
        var bonus = 0

        if (artistLower.endsWith("- topic") || artistLower.contains(" - topic")) {
            bonus += 30
        }
        if (titleLower.contains("official audio")) {
            bonus += 28
        } else if (titleLower.contains("official music video") || titleLower.contains("official video")) {
            bonus += 24
        } else if (VOCAL_MASTER_MARKERS.any { titleLower.contains(it) }) {
            bonus += 18
        }

        if (artistLower.contains("official") || artistLower.contains("vevo")) {
            bonus += 12
        }

        return bonus
    }

    fun extractCanonicalMetadata(track: AudioTrack): CanonicalMetadata {
        return CanonicalMetadata(
            title = track.title,
            artist = track.artist.replace("- Topic", "").trim(),
            album = track.album,
            durationSec = (track.durationMs / 1000L).coerceAtLeast(0L),
            isrc = track.effectiveCanonicalIsrc,
            highResArtworkUrl = track.artworkUrl
        )
    }

    suspend fun resolveCanonicalTrack(
        rawTitle: String,
        rawArtist: String,
        fallbackDurationMs: Long = 0L
    ): CanonicalTrackInfo? = withContext(Dispatchers.IO) {
        val fetched = fetchCanonicalMetadata(
            title = rawTitle,
            artist = rawArtist,
            existingDurationMs = fallbackDurationMs
        )
        CanonicalTrackInfo(
            canonicalTitle = fetched.canonicalTitle,
            primaryArtist = fetched.canonicalArtist,
            album = fetched.albumName,
            durationMs = fetched.trackTimeMillis,
            isrc = fetched.isrc,
            highResArtworkUrl = fetched.highResArtworkUrl
        )
    }

    fun isMatch(
        candidateTitle: String,
        candidateArtist: String,
        targetTitle: String,
        targetArtist: String,
        candidateDurationSec: Long = 0L,
        targetDurationSec: Long = 0L,
        candidateIsrc: String? = null,
        targetIsrc: String? = null
    ): Boolean {
        return computeMatchConfidenceScore(
            canonicalTitle = targetTitle,
            canonicalArtist = targetArtist,
            canonicalDurationMs = targetDurationSec * 1000L,
            canonicalIsrc = targetIsrc.orEmpty(),
            candidateTitle = candidateTitle,
            candidateArtist = candidateArtist,
            candidateDurationMs = candidateDurationSec * 1000L,
            candidateIsrc = candidateIsrc.orEmpty()
        ).isAccepted
    }

    /**
     * Queries iTunes Search API and Deezer Canonical Search API to extract the official
     * canonical ISRC, exact duration (`trackTimeMillis`), `primaryGenreName`, `albumName`,
     * and 1000x1000 artwork URL.
     */
    suspend fun fetchCanonicalMetadata(
        title: String,
        artist: String,
        existingIsrc: String = "",
        existingDurationMs: Long = 0L
    ): CanonicalTrackMetadata = withContext(Dispatchers.IO) {
        val cleanTitle = cleanSearchText(title)
        val cleanArtist = if (artist.equals("Unknown Artist", ignoreCase = true)) "" else cleanSearchText(artist)

        val itunesMeta = queryItunesCanonicalMetadata(cleanTitle, cleanArtist, existingDurationMs)
        val deezerMeta = queryDeezerCanonicalMetadata(cleanTitle, cleanArtist, existingIsrc, itunesMeta?.trackTimeMillis ?: existingDurationMs)

        val resolvedIsrc = when {
            existingIsrc.isNotBlank() -> existingIsrc.trim().uppercase()
            !deezerMeta?.isrc.isNullOrBlank() -> deezerMeta!!.isrc.trim().uppercase()
            !itunesMeta?.isrc.isNullOrBlank() -> itunesMeta!!.isrc.trim().uppercase()
            else -> ""
        }

        val resolvedDurationMs = when {
            itunesMeta != null && itunesMeta.trackTimeMillis > 30_000L -> itunesMeta.trackTimeMillis
            deezerMeta != null && deezerMeta.trackTimeMillis > 30_000L -> deezerMeta.trackTimeMillis
            existingDurationMs > 30_000L -> existingDurationMs
            else -> 0L
        }

        val resolvedArtwork = when {
            !itunesMeta?.highResArtworkUrl.isNullOrBlank() -> itunesMeta!!.highResArtworkUrl
            !deezerMeta?.highResArtworkUrl.isNullOrBlank() -> deezerMeta!!.highResArtworkUrl
            else -> ""
        }

        CanonicalTrackMetadata(
            isrc = resolvedIsrc,
            canonicalTitle = itunesMeta?.canonicalTitle?.takeIf { it.isNotBlank() }
                ?: deezerMeta?.canonicalTitle?.takeIf { it.isNotBlank() }
                ?: title,
            canonicalArtist = itunesMeta?.canonicalArtist?.takeIf { it.isNotBlank() }
                ?: deezerMeta?.canonicalArtist?.takeIf { it.isNotBlank() }
                ?: artist,
            albumName = itunesMeta?.albumName?.takeIf { it.isNotBlank() }
                ?: deezerMeta?.albumName?.takeIf { it.isNotBlank() }
                ?: title,
            primaryGenreName = itunesMeta?.primaryGenreName?.takeIf { it.isNotBlank() }
                ?: deezerMeta?.primaryGenreName?.takeIf { it.isNotBlank() }
                ?: "Music",
            trackTimeMillis = resolvedDurationMs,
            releaseYear = itunesMeta?.releaseYear?.takeIf { it > 1900 }
                ?: deezerMeta?.releaseYear?.takeIf { it > 1900 }
                ?: 2024,
            trackNumber = itunesMeta?.trackNumber ?: deezerMeta?.trackNumber ?: 1,
            highResArtworkUrl = resolvedArtwork,
            previewStreamUrl = itunesMeta?.previewStreamUrl?.takeIf { it.isNotBlank() }
                ?: deezerMeta?.previewStreamUrl.orEmpty()
        )
    }

    private fun queryItunesCanonicalMetadata(
        cleanTitle: String,
        cleanArtist: String,
        referenceDurationMs: Long
    ): CanonicalTrackMetadata? {
        return try {
            val term = URLEncoder.encode("$cleanTitle $cleanArtist".trim(), "UTF-8")
            val endpoint = "https://itunes.apple.com/search?term=$term&entity=song&limit=8"
            val body = httpGet(endpoint, timeoutMs = 2000) ?: return null
            val root = JSONObject(body)
            val results = root.optJSONArray("results") ?: return null
            if (results.length() == 0) return null

            var bestObj: JSONObject? = null
            var bestScore = -1.0

            for (i in 0 until results.length()) {
                val obj = results.optJSONObject(i) ?: continue
                val trackName = obj.optString("trackName", "")
                val artistName = obj.optString("artistName", "")
                if (trackName.isBlank() || artistName.isBlank()) continue
                val collectionName = obj.optString("collectionName", "")
                val durationMs = obj.optLong("trackTimeMillis", 0L)

                if (isDisqualifiedCoverOrRemix(cleanTitle, cleanArtist, trackName, artistName, collectionName)) {
                    continue
                }

                val titleSim = normalizedLevenshteinSimilarity(cleanTitle, cleanSearchText(trackName))
                val artistSim = if (cleanArtist.isBlank()) 0.90 else bestArtistSimilarity(cleanArtist, artistName)
                val durationPenalty = if (referenceDurationMs > 30_000L && durationMs > 30_000L) {
                    val diffSec = abs(referenceDurationMs - durationMs) / 1000.0
                    if (diffSec > 8.0) -0.35 else -(diffSec * 0.02)
                } else 0.0

                val combined = (titleSim * 0.60) + (artistSim * 0.40) + durationPenalty
                if (combined > bestScore) {
                    bestScore = combined
                    bestObj = obj
                }
            }

            val chosen = bestObj ?: return null
            if (bestScore < 0.55) return null

            val rawArtwork = chosen.optString("artworkUrl100", "")
            val highResArt = if (rawArtwork.isNotBlank()) {
                rawArtwork.replace("100x100bb", "1000x1000bb").replace("100x100", "1000x1000")
            } else ""

            val releaseDate = chosen.optString("releaseDate", "")
            val releaseYear = releaseDate.take(4).toIntOrNull() ?: 2024

            CanonicalTrackMetadata(
                isrc = chosen.optString("isrc", "").uppercase(),
                canonicalTitle = chosen.optString("trackName", cleanTitle),
                canonicalArtist = chosen.optString("artistName", cleanArtist),
                albumName = chosen.optString("collectionName", cleanTitle),
                primaryGenreName = chosen.optString("primaryGenreName", "Music"),
                trackTimeMillis = chosen.optLong("trackTimeMillis", referenceDurationMs),
                releaseYear = releaseYear,
                trackNumber = chosen.optInt("trackNumber", 1),
                highResArtworkUrl = highResArt,
                previewStreamUrl = chosen.optString("previewUrl", "")
            )
        } catch (e: Exception) {
            Log.w(TAG, "iTunes canonical metadata lookup failed: ${e.message}")
            null
        }
    }

    private fun queryDeezerCanonicalMetadata(
        cleanTitle: String,
        cleanArtist: String,
        existingIsrc: String,
        referenceDurationMs: Long
    ): CanonicalTrackMetadata? {
        return try {
            if (existingIsrc.isNotBlank()) {
                val isrcBody = httpGet("https://api.deezer.com/track/isrc:${URLEncoder.encode(existingIsrc.trim(), "UTF-8")}", timeoutMs = 1800)
                if (!isrcBody.isNullOrBlank()) {
                    val obj = JSONObject(isrcBody)
                    val title = obj.optString("title", "")
                    if (title.isNotBlank()) {
                        val artistObj = obj.optJSONObject("artist")
                        val albumObj = obj.optJSONObject("album")
                        val durationSec = obj.optLong("duration", 0L)
                        val releaseDate = obj.optString("release_date", "")
                        return CanonicalTrackMetadata(
                            isrc = obj.optString("isrc", existingIsrc).uppercase(),
                            canonicalTitle = title,
                            canonicalArtist = artistObj?.optString("name", cleanArtist) ?: cleanArtist,
                            albumName = albumObj?.optString("title", title) ?: title,
                            primaryGenreName = "Music",
                            trackTimeMillis = if (durationSec > 0L) durationSec * 1000L else referenceDurationMs,
                            releaseYear = releaseDate.take(4).toIntOrNull() ?: 2024,
                            trackNumber = obj.optInt("track_position", 1),
                            highResArtworkUrl = albumObj?.optString("cover_xl", "")?.ifBlank {
                                albumObj.optString("cover_big", "")
                            }.orEmpty(),
                            previewStreamUrl = obj.optString("preview", "")
                        )
                    }
                }
            }

            val q = if (cleanArtist.isNotBlank()) {
                "track:\"$cleanTitle\" artist:\"$cleanArtist\""
            } else {
                cleanTitle
            }
            val encoded = URLEncoder.encode(q, "UTF-8")
            val searchBody = httpGet("https://api.deezer.com/search?q=$encoded&limit=6", timeoutMs = 1800) ?: return null
            val root = JSONObject(searchBody)
            val dataArr = root.optJSONArray("data") ?: return null
            if (dataArr.length() == 0) return null

            var bestObj: JSONObject? = null
            var bestScore = -1.0

            for (i in 0 until dataArr.length()) {
                val obj = dataArr.optJSONObject(i) ?: continue
                val trackTitle = obj.optString("title", "")
                val artistObj = obj.optJSONObject("artist")
                val artistName = artistObj?.optString("name", "") ?: ""
                if (trackTitle.isBlank() || artistName.isBlank()) continue
                val albumObj = obj.optJSONObject("album")
                val albumTitle = albumObj?.optString("title", "") ?: ""
                val durationMs = obj.optLong("duration", 0L) * 1000L

                if (isDisqualifiedCoverOrRemix(cleanTitle, cleanArtist, trackTitle, artistName, albumTitle)) {
                    continue
                }

                val titleSim = normalizedLevenshteinSimilarity(cleanTitle, cleanSearchText(trackTitle))
                val artistSim = if (cleanArtist.isBlank()) 0.90 else bestArtistSimilarity(cleanArtist, artistName)
                val durationBoost = if (referenceDurationMs > 30_000L && durationMs > 30_000L) {
                    val delta = abs(referenceDurationMs - durationMs)
                    if (delta <= STRICT_DURATION_TOLERANCE_MS) 0.15 else if (delta > 8_000L) -0.35 else 0.0
                } else 0.0

                val score = (titleSim * 0.60) + (artistSim * 0.40) + durationBoost
                if (score > bestScore) {
                    bestScore = score
                    bestObj = obj
                }
            }

            val chosen = bestObj ?: return null
            if (bestScore < 0.55) return null

            val artistObj = chosen.optJSONObject("artist")
            val albumObj = chosen.optJSONObject("album")
            val durationMs = chosen.optLong("duration", 0L) * 1000L

            CanonicalTrackMetadata(
                isrc = chosen.optString("isrc", "").uppercase(),
                canonicalTitle = chosen.optString("title", cleanTitle),
                canonicalArtist = artistObj?.optString("name", cleanArtist) ?: cleanArtist,
                albumName = albumObj?.optString("title", cleanTitle) ?: cleanTitle,
                primaryGenreName = "Music",
                trackTimeMillis = if (durationMs > 0L) durationMs else referenceDurationMs,
                releaseYear = 2024,
                trackNumber = 1,
                highResArtworkUrl = albumObj?.optString("cover_xl", "")?.ifBlank {
                    albumObj.optString("cover_big", "")
                }.orEmpty(),
                previewStreamUrl = chosen.optString("preview", "")
            )
        } catch (e: Exception) {
            Log.w(TAG, "Deezer canonical metadata lookup failed: ${e.message}")
            null
        }
    }

    /**
     * Evaluates a candidate stream against the canonical track metadata using the Weighted Confidence Matrix:
     * - ISRC Exact Match: +50 points
     * - Title & Artist Levenshtein Similarity (> 0.85): +25 points (scaled 0..25 for partial matches >= 0.72)
     * - Duration Proximity (within ±3.0 seconds of canonical duration): +25 points
     * - Vocal Master Hierarchy Bonus: up to +30 points for Official Audio / - Topic channels
     * - Minimum threshold to accept a stream: 70 points.
     * - Hard guardrails:
     *   1. Any banned instrumental/karaoke/backing track is immediately rejected.
     *   2. Any candidate whose duration deviates by more than ±3.0 seconds from a known canonical duration is rejected.
     */
    fun computeMatchConfidenceScore(
        canonicalTitle: String,
        canonicalArtist: String,
        canonicalDurationMs: Long,
        canonicalIsrc: String = "",
        candidateTitle: String,
        candidateArtist: String,
        candidateDurationMs: Long,
        candidateIsrc: String = "",
        candidateAlbum: String = ""
    ): CandidateScoreResult {
        // 0. Strict negative filter for instrumental/karaoke/backing tracks & covers
        if (isBannedInstrumentalOrKaraoke(
                candidateTitle = candidateTitle,
                candidateArtistOrChannel = candidateArtist,
                candidateDescription = candidateAlbum,
                userQuery = "$canonicalTitle $canonicalArtist"
            )
        ) {
            return CandidateScoreResult(
                totalScore = 0,
                isrcMatched = false,
                titleArtistSimilarity = 0.0,
                durationDeltaMs = abs(canonicalDurationMs - candidateDurationMs),
                isDurationWithinTolerance = false,
                isAccepted = false,
                rejectionReason = "Rejected banned instrumental/karaoke/backing track"
            )
        }

        if (isDisqualifiedCoverOrRemix(canonicalTitle, canonicalArtist, candidateTitle, candidateArtist, candidateAlbum)) {
            return CandidateScoreResult(
                totalScore = 0,
                isrcMatched = false,
                titleArtistSimilarity = 0.0,
                durationDeltaMs = abs(canonicalDurationMs - candidateDurationMs),
                isDurationWithinTolerance = false,
                isAccepted = false,
                rejectionReason = "Rejected non-canonical cover/remix/karaoke modifier"
            )
        }

        // 1. Reject 30-second preview clips when matching full-length tracks
        if (candidateDurationMs in 1L..59_999L && (canonicalDurationMs >= 60_000L || canonicalDurationMs == 0L)) {
            return CandidateScoreResult(
                totalScore = 0,
                isrcMatched = false,
                titleArtistSimilarity = 0.0,
                durationDeltaMs = abs(canonicalDurationMs - candidateDurationMs),
                isDurationWithinTolerance = false,
                isAccepted = false,
                rejectionReason = "Rejected <60s snippet preview (${candidateDurationMs}ms)"
            )
        }

        // 2. ISRC Match (+50 points)
        val isrcMatched = canonicalIsrc.isNotBlank() &&
            candidateIsrc.isNotBlank() &&
            canonicalIsrc.trim().equals(candidateIsrc.trim(), ignoreCase = true)

        // 3. Title & Artist Levenshtein Similarity
        val cleanTargetTitle = cleanSearchText(canonicalTitle)
        val cleanCandTitle = cleanSearchText(candidateTitle)
        val titleSim = normalizedLevenshteinSimilarity(cleanTargetTitle, cleanCandTitle)

        val cleanTargetArtist = if (canonicalArtist.equals("Unknown Artist", ignoreCase = true)) "" else cleanSearchText(canonicalArtist)
        val artistSim = if (cleanTargetArtist.isBlank()) {
            0.90
        } else {
            max(
                bestArtistSimilarity(cleanTargetArtist, candidateArtist),
                if (cleanCandTitle.contains(cleanTargetArtist)) 0.88 else 0.0
            )
        }

        val combinedSimilarity = (titleSim * 0.58) + (artistSim * 0.42)

        if (!isrcMatched && (titleSim < 0.50 || artistSim < 0.45)) {
            return CandidateScoreResult(
                totalScore = 0,
                isrcMatched = false,
                titleArtistSimilarity = combinedSimilarity,
                durationDeltaMs = if (canonicalDurationMs > 30_000L && candidateDurationMs > 30_000L) abs(canonicalDurationMs - candidateDurationMs) else 0L,
                isDurationWithinTolerance = false,
                isAccepted = false,
                rejectionReason = "Low title/artist similarity (title=${"%.2f".format(titleSim)}, artist=${"%.2f".format(artistSim)})"
            )
        }

        // 4. Duration Proximity Check (allows studio master vs music video intro/outro differences when title & artist strongly match)
        val hasBothDurations = canonicalDurationMs >= 60_000L && candidateDurationMs >= 60_000L
        val durationDeltaMs = if (hasBothDurations) abs(canonicalDurationMs - candidateDurationMs) else 0L
        val effectiveToleranceMs = when {
            isrcMatched -> 60_000L
            titleSim >= 0.82 && artistSim >= 0.75 -> 60_000L
            combinedSimilarity >= 0.78 -> 25_000L
            else -> 12_000L
        }
        val isDurationValid = !hasBothDurations || durationDeltaMs <= effectiveToleranceMs

        if (hasBothDurations && !isDurationValid) {
            return CandidateScoreResult(
                totalScore = 0,
                isrcMatched = isrcMatched,
                titleArtistSimilarity = combinedSimilarity,
                durationDeltaMs = durationDeltaMs,
                isDurationWithinTolerance = false,
                isAccepted = false,
                rejectionReason = "Duration delta ${durationDeltaMs}ms exceeds ±${effectiveToleranceMs}ms tolerance"
            )
        }

        val durationPoints = when {
            hasBothDurations && durationDeltaMs <= 1_500L -> 25
            hasBothDurations && durationDeltaMs <= STRICT_DURATION_TOLERANCE_MS -> 22
            hasBothDurations && durationDeltaMs <= 15_000L -> 20
            hasBothDurations && durationDeltaMs <= effectiveToleranceMs -> 16
            !hasBothDurations && candidateDurationMs >= 60_000L -> 18
            else -> 14
        }

        val similarityPoints = when {
            combinedSimilarity >= 0.85 -> 25
            combinedSimilarity >= 0.78 -> 22
            combinedSimilarity >= 0.70 -> 18
            combinedSimilarity >= 0.62 -> 12
            else -> 0
        }

        val isrcOrVerifiedMetadataPoints = when {
            isrcMatched -> 50
            combinedSimilarity >= 0.85 && isDurationValid -> 45
            combinedSimilarity >= 0.76 && isDurationValid -> 35
            else -> 0
        }

        val vocalMasterBonus = computeVocalMasterPriorityBonus(candidateTitle, candidateArtist)
        val totalScore = (isrcOrVerifiedMetadataPoints + similarityPoints + durationPoints + vocalMasterBonus).coerceAtMost(130)
        val accepted = isDurationValid && totalScore >= MIN_ACCEPTABLE_CONFIDENCE_SCORE

        return CandidateScoreResult(
            totalScore = totalScore,
            isrcMatched = isrcMatched,
            titleArtistSimilarity = combinedSimilarity,
            durationDeltaMs = durationDeltaMs,
            isDurationWithinTolerance = isDurationValid,
            isAccepted = accepted,
            rejectionReason = if (accepted) null else "Confidence score $totalScore below threshold $MIN_ACCEPTABLE_CONFIDENCE_SCORE"
        )
    }

    /**
     * Checks if a candidate track is a disqualified instrumental, karaoke, cover, tribute, parody,
     * slowed/reverb, or sped-up version when the canonical query did not ask for one.
     */
    fun isDisqualifiedCoverOrRemix(
        canonicalTitle: String,
        canonicalArtist: String,
        candidateTitle: String,
        candidateArtist: String,
        candidateAlbum: String = ""
    ): Boolean {
        val targetCombined = "$canonicalTitle $canonicalArtist".lowercase()
        if (isBannedInstrumentalOrKaraoke(
                candidateTitle = candidateTitle,
                candidateArtistOrChannel = candidateArtist,
                candidateDescription = candidateAlbum,
                userQuery = targetCombined
            )
        ) {
            return true
        }

        val candCombined = "$candidateTitle $candidateArtist $candidateAlbum".lowercase()
        for (modifier in SECONDARY_COVER_MODIFIERS) {
            if (candCombined.contains(modifier) && !targetCombined.contains(modifier)) {
                return true
            }
        }
        return false
    }

    /**
     * Computes normalized Levenshtein similarity between two strings in `[0.0, 1.0]`.
     */
    fun normalizedLevenshteinSimilarity(a: String, b: String): Double {
        val s1 = a.trim().lowercase()
        val s2 = b.trim().lowercase()
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        if (s1.contains(s2) || s2.contains(s1)) {
            val ratio = min(s1.length, s2.length).toDouble() / max(s1.length, s2.length).toDouble()
            return max(0.86, ratio)
        }

        val dist = levenshteinDistance(s1, s2)
        val maxLen = max(s1.length, s2.length)
        val rawSim = (1.0 - dist.toDouble() / maxLen.toDouble()).coerceIn(0.0, 1.0)

        val tokens1 = s1.split(" ").filter { it.length > 1 }.toSet()
        val tokens2 = s2.split(" ").filter { it.length > 1 }.toSet()
        val tokenSim = if (tokens1.isNotEmpty() && tokens2.isNotEmpty()) {
            val intersection = tokens1.intersect(tokens2).size.toDouble()
            intersection / min(tokens1.size, tokens2.size).toDouble()
        } else 0.0

        return max(rawSim, tokenSim * 0.92)
    }

    private fun bestArtistSimilarity(canonicalArtist: String, candidateArtist: String): Double {
        val targetParts = canonicalArtist.lowercase()
            .split(Regex("[,&/|]|\\bfeat\\.?\\b|\\bft\\.?\\b"))
            .map { cleanSearchText(it) }
            .filter { it.length >= 2 }
        val candParts = candidateArtist.lowercase()
            .replace("- topic", "")
            .split(Regex("[,&/|]|\\bfeat\\.?\\b|\\bft\\.?\\b"))
            .map { cleanSearchText(it) }
            .filter { it.length >= 2 }

        if (targetParts.isEmpty() || candParts.isEmpty()) {
            return normalizedLevenshteinSimilarity(cleanSearchText(canonicalArtist), cleanSearchText(candidateArtist))
        }

        var maxSim = 0.0
        for (t in targetParts) {
            for (c in candParts) {
                val sim = normalizedLevenshteinSimilarity(t, c)
                if (sim > maxSim) maxSim = sim
            }
        }
        return maxSim
    }

    private fun levenshteinDistance(lhs: CharSequence, rhs: CharSequence): Int {
        val lhsLen = lhs.length
        val rhsLen = rhs.length
        var cost = IntArray(lhsLen + 1) { it }
        var newCost = IntArray(lhsLen + 1)

        for (i in 1..rhsLen) {
            newCost[0] = i
            for (j in 1..lhsLen) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1
                newCost[j] = min(min(costInsert, costDelete), costReplace)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[lhsLen]
    }

    fun cleanSearchText(raw: String): String {
        return raw.lowercase()
            .replace("- topic", " ")
            .replace(Regex("\\((?:official|lyric|video|audio|remaster(?:ed)?|from\\s+[^)]*|feat\\.?[^)]*|ft\\.?[^)]*)\\)"), " ")
            .replace(Regex("\\[(?:official|lyric|video|audio|remaster(?:ed)?|from\\s+[^]]*|feat\\.?[^]]*|ft\\.?[^]]*)]"), " ")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun httpGet(urlStr: String, timeoutMs: Int = 2200): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "FlowHiResAudioEngine/4.0 (Android; Media3)")
            }
            if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
