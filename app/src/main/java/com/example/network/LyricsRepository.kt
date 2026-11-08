package com.example.network

import android.util.Log
import com.example.model.AudioTrack
import com.example.model.KaraokeLyricLine
import com.example.model.LyricSyllable
import com.example.util.LrcParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Multi-tier synchronized & regional lyrics resolver:
 * 1. LRCLIB Primary Synced LRC Resolver (`/api/get` & `/api/search`) with duration verification.
 * 2. YouTube Music InnerTube Lyrics Scraper (`music.youtube.com/youtubei/v1/search` -> `next` -> `browse`)
 *    for Bengali, Hindi, and regional songs where LRCLIB has no entry.
 * 3. Genius / Lyrics.ovh fallback plain-lyrics scraper for international & regional tracks.
 *
 * Strictly zero fake/placeholder lyrics: if all real sources return empty, returns `emptyList()`.
 */
object LyricsRepository {
    private const val TAG = "LyricsRepository"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3000, TimeUnit.MILLISECONDS)
        .readTimeout(3000, TimeUnit.MILLISECONDS)
        .build()

    private val lyricsCache = ConcurrentHashMap<String, List<KaraokeLyricLine>>()

    fun cancelActiveLyricsRequests() {
        runCatching {
            httpClient.dispatcher.cancelAll()
        }
    }

    suspend fun fetchLyricsForTrack(track: AudioTrack): List<KaraokeLyricLine> {
        return getSyncedLyrics(
            trackTitle = track.title,
            artistName = track.artist,
            albumName = track.album,
            durationSeconds = (track.durationMs / 1000L).coerceAtLeast(0L)
        )
    }

    suspend fun getSyncedLyrics(
        trackTitle: String,
        artistName: String,
        albumName: String? = null,
        durationSeconds: Long = 0L
    ): List<KaraokeLyricLine> = withContext(Dispatchers.IO) {
        val cleanTitle = cleanTrackTitle(trackTitle)
        val cleanArtist = cleanArtistName(artistName)
        val durationMs = durationSeconds * 1000L
        val cacheKey = "${cleanTitle.lowercase()}|${cleanArtist.lowercase()}|$durationSeconds"

        lyricsCache[cacheKey]?.let { cached ->
            if (cached.isNotEmpty()) return@withContext cached
        }

        try {
            // Tier 1: LRCLIB Exact & Search endpoints
            val lrcLibLines = fetchFromLrcLibExact(cleanTitle, cleanArtist, albumName.orEmpty(), durationSeconds)
                ?: fetchFromLrcLibSearch(cleanTitle, cleanArtist, durationSeconds)
            if (!lrcLibLines.isNullOrEmpty()) {
                lyricsCache[cacheKey] = lrcLibLines
                return@withContext lrcLibLines
            }

            // Tier 2: Parallel fallback to YouTube Music InnerTube Lyrics Tab + Lyrics.ovh / LRCLIB title-only
            val fallbackLines = coroutineScope {
                val ytmDeferred = async {
                    fetchFromYouTubeMusicLyrics(cleanTitle, cleanArtist, durationMs)
                }
                val ovhDeferred = async {
                    fetchFromLyricsOvhOrTitleFallback(cleanTitle, cleanArtist, durationMs)
                }
                val ytmResult = ytmDeferred.await()
                if (!ytmResult.isNullOrEmpty()) {
                    ytmResult
                } else {
                    ovhDeferred.await()
                }
            }

            if (!fallbackLines.isNullOrEmpty()) {
                lyricsCache[cacheKey] = fallbackLines
                return@withContext fallbackLines
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lyrics lookup failed for $trackTitle: ${e.message}")
        }

        emptyList()
    }

    private fun fetchFromLrcLibExact(
        title: String,
        artist: String,
        album: String,
        durationSec: Long
    ): List<KaraokeLyricLine>? {
        if (title.isBlank() || artist.isBlank()) return null
        return try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val encodedArtist = URLEncoder.encode(artist, "UTF-8")
            val encodedAlbum = URLEncoder.encode(album, "UTF-8")
            val effDur = durationSec.coerceAtLeast(1L)

            val urlStr = "https://lrclib.net/api/get?track_name=$encodedTitle&artist_name=$encodedArtist&album_name=$encodedAlbum&duration=$effDur"
            val body = executeGet(urlStr, timeoutMs = 2600) ?: return null
            val obj = JSONObject(body)

            if (obj.optBoolean("instrumental", false)) return null

            val candidateDurationSec = obj.optDouble("duration", 0.0)
            if (durationSec > 30L && candidateDurationSec > 30.0) {
                if (abs(durationSec - candidateDurationSec.toLong()) > 6L) {
                    return null
                }
            }

            val syncedLyrics = obj.optString("syncedLyrics", "")
            if (syncedLyrics.isNotBlank() && syncedLyrics != "null") {
                val parsed = LrcParser.parse(syncedLyrics)
                if (parsed.isNotEmpty()) return parsed
            }

            val plainLyrics = obj.optString("plainLyrics", "")
            if (plainLyrics.isNotBlank() && plainLyrics != "null") {
                val parsed = parsePlainLyricsToKaraokeLines(plainLyrics, durationSec * 1000L)
                if (parsed.isNotEmpty()) return parsed
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchFromLrcLibSearch(
        title: String,
        artist: String,
        durationSec: Long
    ): List<KaraokeLyricLine>? {
        return try {
            val query = URLEncoder.encode("$title $artist".trim(), "UTF-8")
            val urlStr = "https://lrclib.net/api/search?q=$query"
            val body = executeGet(urlStr, timeoutMs = 2800) ?: return null
            val array = JSONArray(body)
            if (array.length() == 0) return null

            var bestCandidate: JSONObject? = null
            var bestScore = -1.0

            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                if (obj.optBoolean("instrumental", false)) continue

                val candTitle = obj.optString("trackName", "")
                val candArtist = obj.optString("artistName", "")
                val candDuration = obj.optDouble("duration", 0.0)
                val synced = obj.optString("syncedLyrics", "")
                val plain = obj.optString("plainLyrics", "")
                val hasSynced = synced.isNotBlank() && synced != "null"
                val hasPlain = plain.isNotBlank() && plain != "null"
                if (!hasSynced && !hasPlain) continue

                if (durationSec > 30L && candDuration > 30.0) {
                    if (abs(durationSec - candDuration.toLong()) > 8L) continue
                }

                val titleSim = StreamMatcher.normalizedLevenshteinSimilarity(title, cleanTrackTitle(candTitle))
                val artistSim = if (artist.isBlank()) 0.85 else StreamMatcher.normalizedLevenshteinSimilarity(artist, cleanArtistName(candArtist))
                if (titleSim < 0.55 || artistSim < 0.45) continue

                val durationBonus = if (durationSec > 30 && candDuration > 30) {
                    val diff = abs(durationSec.toDouble() - candDuration)
                    when {
                        diff <= 2.0 -> 30.0
                        diff <= 4.0 -> 20.0
                        diff <= 8.0 -> 10.0
                        else -> 0.0
                    }
                } else 10.0

                val score = (titleSim * 50.0) + (artistSim * 35.0) + durationBonus + (if (hasSynced) 25.0 else 5.0)
                if (score > bestScore) {
                    bestScore = score
                    bestCandidate = obj
                }
            }

            val selected = bestCandidate ?: return null
            val synced = selected.optString("syncedLyrics", "")
            if (synced.isNotBlank() && synced != "null") {
                val parsed = LrcParser.parse(synced)
                if (parsed.isNotEmpty()) return parsed
            }

            val plain = selected.optString("plainLyrics", "")
            if (plain.isNotBlank() && plain != "null") {
                val parsed = parsePlainLyricsToKaraokeLines(plain, durationSec * 1000L)
                if (parsed.isNotEmpty()) return parsed
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Queries YouTube Music's InnerTube `search` -> `next` -> `browse` pipeline to extract
     * official regional/Bengali/Hindi song lyrics directly from YouTube Music's Lyrics tab.
     */
    private fun fetchFromYouTubeMusicLyrics(
        title: String,
        artist: String,
        durationMs: Long
    ): List<KaraokeLyricLine>? {
        return try {
            val searchQuery = "$title $artist".trim()
            val searchPayload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240918.01.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("query", searchQuery)
                put("params", "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D")
            }

            val searchResp = executePostJson(
                "https://music.youtube.com/youtubei/v1/search?prettyPrint=false",
                searchPayload.toString(),
                timeoutMs = 2600
            ) ?: return null

            val searchRoot = JSONObject(searchResp)
            val videoId = findFirstVideoId(searchRoot) ?: return null

            val nextPayload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240918.01.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("videoId", videoId)
            }

            val nextResp = executePostJson(
                "https://music.youtube.com/youtubei/v1/next?prettyPrint=false",
                nextPayload.toString(),
                timeoutMs = 2600
            ) ?: return null

            val nextRoot = JSONObject(nextResp)
            val lyricsBrowseId = findLyricsBrowseId(nextRoot) ?: return null

            val browsePayload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20240918.01.00")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("browseId", lyricsBrowseId)
            }

            val browseResp = executePostJson(
                "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false",
                browsePayload.toString(),
                timeoutMs = 2600
            ) ?: return null

            val browseRoot = JSONObject(browseResp)
            val lyricsText = extractLyricsTextFromBrowse(browseRoot)
            if (!lyricsText.isNullOrBlank()) {
                val parsed = parsePlainLyricsToKaraokeLines(lyricsText, durationMs)
                if (parsed.isNotEmpty()) return parsed
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "YouTube Music lyrics scrape failed: ${e.message}")
            null
        }
    }

    private fun fetchFromLyricsOvhOrTitleFallback(
        title: String,
        artist: String,
        durationMs: Long
    ): List<KaraokeLyricLine>? {
        if (artist.isNotBlank() && title.isNotBlank()) {
            try {
                val encodedArtist = URLEncoder.encode(artist, "UTF-8")
                val encodedTitle = URLEncoder.encode(title, "UTF-8")
                val ovhBody = executeGet("https://api.lyrics.ovh/v1/$encodedArtist/$encodedTitle", timeoutMs = 2400)
                if (!ovhBody.isNullOrBlank()) {
                    val obj = JSONObject(ovhBody)
                    val rawLyrics = obj.optString("lyrics", "")
                    if (rawLyrics.isNotBlank() && rawLyrics != "null") {
                        val cleaned = rawLyrics.replace(Regex("^Paroles de la chanson.*\\n?", RegexOption.IGNORE_CASE), "")
                        val parsed = parsePlainLyricsToKaraokeLines(cleaned, durationMs)
                        if (parsed.isNotEmpty()) return parsed
                    }
                }
            } catch (_: Exception) {
            }
        }

        try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val body = executeGet("https://lrclib.net/api/search?q=$encodedTitle", timeoutMs = 2400)
            if (!body.isNullOrBlank()) {
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val candTitle = obj.optString("trackName", "")
                    if (StreamMatcher.normalizedLevenshteinSimilarity(title, cleanTrackTitle(candTitle)) < 0.78) continue
                    val synced = obj.optString("syncedLyrics", "")
                    if (synced.isNotBlank() && synced != "null") {
                        val parsed = LrcParser.parse(synced)
                        if (parsed.isNotEmpty()) return parsed
                    }
                    val plain = obj.optString("plainLyrics", "")
                    if (plain.isNotBlank() && plain != "null") {
                        val parsed = parsePlainLyricsToKaraokeLines(plain, durationMs)
                        if (parsed.isNotEmpty()) return parsed
                    }
                }
            }
        } catch (_: Exception) {
        }

        return null
    }

    private fun findFirstVideoId(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                val vid = node.optString("videoId", "")
                if (vid.length >= 8) return vid
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    findFirstVideoId(node.opt(key))?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findFirstVideoId(node.opt(i))?.let { return it }
                }
            }
        }
        return null
    }

    private fun findLyricsBrowseId(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                val browseId = node.optString("browseId", "")
                if (browseId.startsWith("MPLYt")) return browseId
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    findLyricsBrowseId(node.opt(key))?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findLyricsBrowseId(node.opt(i))?.let { return it }
                }
            }
        }
        return null
    }

    private fun extractLyricsTextFromBrowse(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                val descShelf = node.optJSONObject("musicDescriptionShelfRenderer")
                if (descShelf != null) {
                    val runs = descShelf.optJSONObject("description")?.optJSONArray("runs")
                    if (runs != null && runs.length() > 0) {
                        val sb = StringBuilder()
                        for (i in 0 until runs.length()) {
                            sb.append(runs.optJSONObject(i)?.optString("text", "").orEmpty())
                        }
                        if (sb.isNotBlank()) return sb.toString()
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    extractLyricsTextFromBrowse(node.opt(key))?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    extractLyricsTextFromBrowse(node.opt(i))?.let { return it }
                }
            }
        }
        return null
    }

    private fun parsePlainLyricsToKaraokeLines(
        plainText: String,
        totalDurationMs: Long
    ): List<KaraokeLyricLine> {
        val cleanLines = plainText.lines()
            .map { it.trim() }
            .filter {
                it.isNotEmpty() &&
                    !it.startsWith("[") &&
                    !it.startsWith("Source:", ignoreCase = true) &&
                    !it.startsWith("Lyrics licensed", ignoreCase = true)
            }

        if (cleanLines.isEmpty()) return emptyList()
        val effectiveDuration = totalDurationMs.coerceAtLeast(cleanLines.size * 3_500L)
        val stepMs = (effectiveDuration / cleanLines.size).coerceAtLeast(2_200L)

        return cleanLines.mapIndexed { index, lineText ->
            val lineStart = index * stepMs
            val words = lineText.split(" ").filter { it.isNotBlank() }
            val syllables = if (words.isNotEmpty()) {
                val wordStep = (stepMs / words.size).coerceAtLeast(180L)
                words.mapIndexed { wIdx, w ->
                    LyricSyllable(
                        timeMs = lineStart + (wIdx * wordStep),
                        durationMs = wordStep,
                        text = "$w "
                    )
                }
            } else emptyList()

            KaraokeLyricLine(
                timeMs = lineStart,
                text = lineText,
                syllables = syllables
            )
        }
    }

    private fun cleanTrackTitle(title: String): String {
        return title
            .replace(Regex("\\s*\\([^)]*\\)"), "")
            .replace(Regex("\\s*\\[[^]]*]"), "")
            .replace(Regex("\\s*-\\s*(From|Official|Lyric|Audio|Video|Remastered).*$", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { title.trim() }
    }

    private fun cleanArtistName(artist: String): String {
        return artist
            .replace("- Topic", "", ignoreCase = true)
            .split(",", "&", "feat.", "ft.", "Feat.", "Ft.")
            .firstOrNull()
            ?.trim()
            .orEmpty()
            .ifBlank { artist.trim() }
    }

    private fun executeGet(urlStr: String, timeoutMs: Int = 2600): String? {
        return try {
            val client = httpClient.newBuilder()
                .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .build()
            val req = Request.Builder()
                .url(urlStr)
                .header("Accept", "application/json")
                .header("User-Agent", "FlowHiResMusicPlayer/4.0 (Android; Media3)")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun executePostJson(urlStr: String, jsonPayload: String, timeoutMs: Int = 2600): String? {
        return try {
            val client = httpClient.newBuilder()
                .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .build()
            val req = Request.Builder()
                .url(urlStr)
                .post(jsonPayload.toRequestBody("application/json; charset=UTF-8".toMediaType()))
                .header("Accept", "application/json")
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                )
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
