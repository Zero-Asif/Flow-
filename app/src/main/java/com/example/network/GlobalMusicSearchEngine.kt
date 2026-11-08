package com.example.network

import com.example.model.AudioTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ln

// ==========================================
// 1. DATA MODELS & EXCEPTIONS
// ==========================================

class UnsupportedResponseException(message: String) : IOException(message)

sealed class TopResultItem {
    data class SongItem(val track: AudioTrack) : TopResultItem()
    data class ArtistItem(val artist: ArtistResult) : TopResultItem()
    data class AlbumItem(val album: AlbumResult) : TopResultItem()
    data class PlaylistItem(val playlist: PlaylistResult) : TopResultItem()
}

data class SearchResultShelves(
    val topResult: TopResultItem?,
    val songs: List<AudioTrack>,
    val artists: List<ArtistResult>,
    val albums: List<AlbumResult>,
    val playlists: List<PlaylistResult>
)

data class ArtistResult(
    val browseId: String,
    val name: String,
    val subscribers: String = "",
    val thumbnail: String = ""
)

data class AlbumResult(
    val browseId: String,
    val title: String,
    val artist: String = "",
    val year: String = "",
    val thumbnail: String = "",
    val type: String = "Album"
)

data class PlaylistResult(
    val browseId: String,
    val title: String,
    val author: String = "",
    val trackCount: String = "",
    val thumbnail: String = ""
)

private data class ScoredTrackCandidate(
    val track: AudioTrack,
    val playCount: Long,
    val originalIndex: Int,
    val isOfficial: Boolean
)

// ==========================================
// 2. PRODUCTION SEARCH ENGINE IMPLEMENTATION
// ==========================================

class GlobalMusicSearchEngine(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private enum class EntityKind {
        ARTIST,
        ALBUM,
        PLAYLIST,
        UNKNOWN
    }

    suspend fun searchStructured(query: String): Result<SearchResultShelves> = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            return@withContext Result.success(emptyShelves())
        }

        val payload = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20261021.01.00")
                    put("hl", "en")
                    put("gl", "US")
                })
            })
            put("query", trimmedQuery)
        }

        val request = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/search")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0")
            .header("Referer", "https://music.youtube.com/")
            .header("Origin", "https://music.youtube.com")
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        try {
            coroutineContext.ensureActive()

            val rawJson = executeAsyncCancellable(client, request)

            coroutineContext.ensureActive()
            val shelves = parseYtmSearchResponse(rawJson, trimmedQuery)
            Result.success(shelves)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun executeAsyncCancellable(
        client: OkHttpClient,
        request: Request
    ): String {
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)

            continuation.invokeOnCancellation {
                call.cancel()
            }

            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bodyString = response.use { res ->
                            if (!res.isSuccessful) {
                                throw IOException("YouTube Music HTTP error: ${res.code}")
                            }
                            res.body?.string() ?: throw IOException("Empty response body from YouTube Music")
                        }
                        if (continuation.isActive) {
                            continuation.resume(bodyString)
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(e)
                        }
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }
            })
        }
    }

    private suspend fun parseYtmSearchResponse(rawJson: String, originalQuery: String): SearchResultShelves {
        val root = JSONObject(rawJson)

        val songsCandidates = mutableListOf<ScoredTrackCandidate>()
        val artistsList = mutableListOf<ArtistResult>()
        val albumsList = mutableListOf<AlbumResult>()
        val playlistsList = mutableListOf<PlaylistResult>()
        var topResultCandidate: TopResultItem? = null
        var globalSongIndex = 0

        val tabs = root.optJSONObject("contents")
            ?.optJSONObject("tabbedSearchResultsRenderer")
            ?.optJSONArray("tabs")
            ?: throw UnsupportedResponseException("Missing tabs in response")

        val tabRenderer = (0 until tabs.length())
            .mapNotNull { tabs.optJSONObject(it)?.optJSONObject("tabRenderer") }
            .firstOrNull { it.optBoolean("selected", false) }
            ?: tabs.optJSONObject(0)?.optJSONObject("tabRenderer")
            ?: throw UnsupportedResponseException("No valid search tab found")

        val tabContent = tabRenderer.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")
            ?: throw UnsupportedResponseException("Missing sectionListRenderer contents")

        for (i in 0 until tabContent.length()) {
            coroutineContext.ensureActive()
            val sectionObj = tabContent.optJSONObject(i) ?: continue

            // -------------------------------------------------------------
            // 1. Handle Dedicated Top Card (musicCardShelfRenderer)
            // -------------------------------------------------------------
            if (sectionObj.has("musicCardShelfRenderer")) {
                val card = sectionObj.getJSONObject("musicCardShelfRenderer")
                val title = extractRunsText(card.optJSONObject("title")?.optJSONArray("runs"))
                    .takeIf { it.isNotEmpty() }

                val subtitleRuns = card.optJSONObject("subtitle")?.optJSONArray("runs") ?: JSONArray()
                val subtitleInfo = parseAllColumns(subtitleRuns)
                val thumbUrl = extractThumbnail(card.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer"))

                val cardNav = extractCardNavigation(card)
                val watchVideoId = cardNav?.optJSONObject("watchEndpoint")?.optString("videoId") ?: ""
                val browseEndpoint = cardNav?.optJSONObject("browseEndpoint")
                val browseId = browseEndpoint?.optString("browseId") ?: ""
                val pageType = browseEndpoint?.optJSONObject("browseEndpointContextSupportedConfigs")
                    ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType") ?: ""

                if (title != null) {
                    when (classifyEntity(pageType, browseId, subtitleInfo.entityType, "")) {
                        EntityKind.ARTIST -> {
                            topResultCandidate = TopResultItem.ArtistItem(
                                ArtistResult(browseId, title, subtitleInfo.subscribersText, thumbUrl)
                            )
                        }
                        EntityKind.ALBUM -> {
                            topResultCandidate = TopResultItem.AlbumItem(
                                AlbumResult(browseId, title, subtitleInfo.artist, subtitleInfo.year, thumbUrl, subtitleInfo.entityType.ifEmpty { "Album" })
                            )
                        }
                        EntityKind.PLAYLIST -> {
                            topResultCandidate = TopResultItem.PlaylistItem(
                                PlaylistResult(browseId, title, subtitleInfo.author, subtitleInfo.trackCountText, thumbUrl)
                            )
                        }
                        EntityKind.UNKNOWN -> {
                            if (watchVideoId.isNotEmpty() && !isInstrumentalOrKaraoke(title, subtitleInfo.artist)) {
                                topResultCandidate = TopResultItem.SongItem(
                                    AudioTrack(
                                        id = watchVideoId,
                                        title = title,
                                        artist = subtitleInfo.artist,
                                        album = subtitleInfo.album,
                                        durationMs = subtitleInfo.durationMs,
                                        artworkUrl = thumbUrl,
                                        streamUrl = ""
                                    )
                                )
                            }
                        }
                    }
                }

                // Also parse sub-items inside card contents!
                val cardContents = card.optJSONArray("contents")
                if (cardContents != null) {
                    for (c in 0 until cardContents.length()) {
                        val itemRenderer = cardContents.optJSONObject(c)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                        parseResponsiveListItem(
                            itemRenderer = itemRenderer,
                            shelfTitle = "More from YouTube",
                            songsCandidates = songsCandidates,
                            artistsList = artistsList,
                            albumsList = albumsList,
                            playlistsList = playlistsList,
                            indexProvider = { globalSongIndex++ }
                        )
                    }
                }
                continue
            }

            // -------------------------------------------------------------
            // 2. Handle Structured Shelves & itemSectionRenderer
            // -------------------------------------------------------------
            val shelfTitle: String
            val items: JSONArray?

            if (sectionObj.has("musicShelfRenderer")) {
                val shelf = sectionObj.getJSONObject("musicShelfRenderer")
                shelfTitle = extractRunsText(shelf.optJSONObject("title")?.optJSONArray("runs"))
                items = shelf.optJSONArray("contents")
            } else if (sectionObj.has("itemSectionRenderer")) {
                val itemSection = sectionObj.getJSONObject("itemSectionRenderer")
                shelfTitle = ""
                items = itemSection.optJSONArray("contents")
            } else {
                continue
            }

            if (items == null) continue

            for (j in 0 until items.length()) {
                coroutineContext.ensureActive()
                val itemRenderer = items.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue

                parseResponsiveListItem(
                    itemRenderer = itemRenderer,
                    shelfTitle = shelfTitle,
                    songsCandidates = songsCandidates,
                    artistsList = artistsList,
                    albumsList = albumsList,
                    playlistsList = playlistsList,
                    indexProvider = { globalSongIndex++ }
                )
            }
        }

        // -------------------------------------------------------------
        // 3. Balanced Scoring & Deduplication by Unique Video ID
        // -------------------------------------------------------------
        val rankedSongs = rankCandidatesBalanced(songsCandidates, originalQuery)
            .distinctBy { it.id }

        val finalTop = topResultCandidate ?: rankedSongs.firstOrNull()?.let { TopResultItem.SongItem(it) }

        val finalSongsList = if (finalTop is TopResultItem.SongItem) {
            rankedSongs.filter { it.id != finalTop.track.id }
        } else {
            rankedSongs
        }

        return SearchResultShelves(
            topResult = finalTop,
            songs = finalSongsList,
            artists = artistsList.distinctBy { it.browseId.ifEmpty { it.name } },
            albums = albumsList.distinctBy { it.browseId.ifEmpty { it.title } },
            playlists = playlistsList.distinctBy { it.browseId.ifEmpty { it.title } }
        )
    }

    private fun parseResponsiveListItem(
        itemRenderer: JSONObject,
        shelfTitle: String,
        songsCandidates: MutableList<ScoredTrackCandidate>,
        artistsList: MutableList<ArtistResult>,
        albumsList: MutableList<AlbumResult>,
        playlistsList: MutableList<PlaylistResult>,
        indexProvider: () -> Int
    ) {
        val flexColumns = itemRenderer.optJSONArray("flexColumns") ?: return
        if (flexColumns.length() < 2) return

        val col1Runs = flexColumns.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs")

        val titleRun = col1Runs?.optJSONObject(0) ?: return
        val title = titleRun.optString("text").trim().takeIf { it.isNotEmpty() } ?: return

        val primaryNav = titleRun.optJSONObject("navigationEndpoint")
            ?: itemRenderer.optJSONObject("navigationEndpoint")

        val videoId = primaryNav?.optJSONObject("watchEndpoint")?.optString("videoId")
            ?: itemRenderer.optJSONObject("playlistItemData")?.optString("videoId") ?: ""

        val browseEndpoint = primaryNav?.optJSONObject("browseEndpoint")
        val browseId = browseEndpoint?.optString("browseId") ?: ""
        val pageType = browseEndpoint?.optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType") ?: ""

        // Aggregate ALL remaining flex columns (Column 1, Column 2, etc.) for complete metadata
        val allRemainingRuns = JSONArray()
        for (colIdx in 1 until flexColumns.length()) {
            val runs = flexColumns.optJSONObject(colIdx)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs") ?: continue
            for (r in 0 until runs.length()) {
                runs.optJSONObject(r)?.let { allRemainingRuns.put(it) }
            }
        }

        val metaInfo = parseAllColumns(allRemainingRuns)
        val thumbUrl = extractThumbnail(itemRenderer.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer"))

        when (classifyEntity(pageType, browseId, metaInfo.entityType, shelfTitle)) {
            EntityKind.ARTIST -> {
                val validBrowseId = browseId.ifEmpty { metaInfo.artistBrowseId }
                artistsList.add(ArtistResult(validBrowseId, title, metaInfo.subscribersText.ifEmpty { metaInfo.playCountText }, thumbUrl))
            }
            EntityKind.ALBUM -> {
                val validBrowseId = browseId.ifEmpty { metaInfo.albumBrowseId }
                albumsList.add(AlbumResult(validBrowseId, title, metaInfo.artist, metaInfo.year, thumbUrl, metaInfo.entityType.ifEmpty { "Album" }))
            }
            EntityKind.PLAYLIST -> {
                val validBrowseId = browseId.ifEmpty { metaInfo.playlistBrowseId }
                playlistsList.add(PlaylistResult(validBrowseId, title, metaInfo.author, metaInfo.trackCountText, thumbUrl))
            }
            EntityKind.UNKNOWN -> {
                if (videoId.isNotEmpty() && !isInstrumentalOrKaraoke(title, metaInfo.artist)) {
                    val track = AudioTrack(
                        id = videoId,
                        title = title,
                        artist = metaInfo.artist,
                        album = metaInfo.album,
                        durationMs = metaInfo.durationMs,
                        artworkUrl = thumbUrl,
                        streamUrl = ""
                    )
                    val isOfficial = metaInfo.artist.contains("- topic", ignoreCase = true) ||
                            title.contains("official audio", ignoreCase = true) ||
                            title.contains("official music video", ignoreCase = true)

                    songsCandidates.add(ScoredTrackCandidate(track, metaInfo.playCountNumeric, indexProvider(), isOfficial))
                }
            }
        }
    }

    private fun classifyEntity(
        pageType: String,
        browseId: String,
        entityType: String,
        shelfTitle: String
    ): EntityKind {
        when (pageType) {
            "MUSIC_PAGE_TYPE_ARTIST" -> return EntityKind.ARTIST
            "MUSIC_PAGE_TYPE_ALBUM" -> return EntityKind.ALBUM
            "MUSIC_PAGE_TYPE_PLAYLIST" -> return EntityKind.PLAYLIST
        }

        when {
            browseId.startsWith("UC") -> return EntityKind.ARTIST
            browseId.startsWith("MPREb") -> return EntityKind.ALBUM
            browseId.startsWith("VL") || browseId.startsWith("PL") -> return EntityKind.PLAYLIST
        }

        when (entityType.lowercase(Locale.ROOT)) {
            "artist" -> return EntityKind.ARTIST
            "album", "single", "ep" -> return EntityKind.ALBUM
            "playlist" -> return EntityKind.PLAYLIST
        }

        val normalizedShelf = shelfTitle.lowercase(Locale.ROOT)
        return when {
            normalizedShelf.contains("artist") -> EntityKind.ARTIST
            normalizedShelf.contains("album") -> EntityKind.ALBUM
            normalizedShelf.contains("playlist") -> EntityKind.PLAYLIST
            else -> EntityKind.UNKNOWN
        }
    }

    private data class SubtitleMeta(
        val artist: String = "",
        val artistBrowseId: String = "",
        val album: String = "",
        val albumBrowseId: String = "",
        val playlistBrowseId: String = "",
        val author: String = "",
        val year: String = "",
        val durationMs: Long = 0L,
        val playCountText: String = "",
        val playCountNumeric: Long = 0L,
        val subscribersText: String = "",
        val trackCountText: String = "",
        val entityType: String = ""
    )

    private fun extractCardNavigation(card: JSONObject): JSONObject? {
        val onTap = card.optJSONObject("onTap")
        if (onTap != null) {
            if (onTap.has("watchEndpoint") || onTap.has("browseEndpoint")) return onTap
            onTap.optJSONObject("innertubeCommand")?.let { return it }
            val commands = onTap.optJSONObject("commandExecutorCommand")?.optJSONArray("commands")
            if (commands != null) {
                for (i in 0 until commands.length()) {
                    val cmd = commands.optJSONObject(i) ?: continue
                    if (cmd.has("watchEndpoint") || cmd.has("browseEndpoint")) return cmd
                }
            }
        }
        return card.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint")
    }

    /**
     * Iterates over all runs across column 1, 2, and 3 to extract artist, views, duration, and labels
     */
    private fun parseAllColumns(runs: JSONArray): SubtitleMeta {
        var artist = ""
        var artistBrowseId = ""
        var album = ""
        var albumBrowseId = ""
        var playlistBrowseId = ""
        var author = ""
        var year = ""
        var durationMs = 0L
        var playCountText = ""
        var playCountNumeric = 0L
        var subscribersText = ""
        var trackCountText = ""
        var entityType = ""

        val trackCountRegex = Regex("""^(\d+[\d,.]*)\s+(?:tracks?|songs?|videos?)$""", RegexOption.IGNORE_CASE)

        for (i in 0 until runs.length()) {
            val run = runs.optJSONObject(i) ?: continue
            val text = run.optString("text")?.trim() ?: continue
            if (text == "•" || text == "&" || text.isEmpty()) continue

            val nav = run.optJSONObject("navigationEndpoint")
            val browseEndpoint = nav?.optJSONObject("browseEndpoint")
            val bId = browseEndpoint?.optString("browseId") ?: ""
            val pageType = browseEndpoint?.optJSONObject("browseEndpointContextSupportedConfigs")
                ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType") ?: ""

            when {
                trackCountRegex.matches(text) -> {
                    trackCountText = text
                }
                text.equals("Song", true) || text.equals("Artist", true) ||
                text.equals("Album", true) || text.equals("Single", true) ||
                text.equals("EP", true) || text.equals("Playlist", true) || text.equals("Video", true) -> {
                    if (entityType.isEmpty()) entityType = text
                }
                text.matches(Regex("""^\d+:\d+(?::\d+)?$""")) -> {
                    durationMs = parseDurationToMs(text)
                }
                text.contains("subscriber", true) || text.contains("audience", true) -> {
                    subscribersText = text
                }
                text.contains("play", true) || text.contains("view", true) -> {
                    playCountText = text
                    playCountNumeric = parseCountToNumeric(text)
                }
                text.matches(Regex("""^(19|20)\d{2}$""")) -> {
                    year = text
                }
                pageType == "MUSIC_PAGE_TYPE_USER_CHANNEL" -> {
                    if (author.isEmpty()) author = text
                }
                pageType == "MUSIC_PAGE_TYPE_ARTIST" || bId.startsWith("UC") -> {
                    if (artist.isEmpty()) {
                        artist = text
                        artistBrowseId = bId
                    }
                }
                pageType == "MUSIC_PAGE_TYPE_ALBUM" || bId.startsWith("MPREb") -> {
                    if (album.isEmpty()) {
                        album = text
                        albumBrowseId = bId
                    }
                }
                pageType == "MUSIC_PAGE_TYPE_PLAYLIST" || bId.startsWith("VL") || bId.startsWith("PL") -> {
                    playlistBrowseId = bId
                }
            }
        }

        return SubtitleMeta(
            artist = artist,
            artistBrowseId = artistBrowseId,
            album = album,
            albumBrowseId = albumBrowseId,
            playlistBrowseId = playlistBrowseId,
            author = author,
            year = year,
            durationMs = durationMs,
            playCountText = playCountText,
            playCountNumeric = playCountNumeric,
            subscribersText = subscribersText,
            trackCountText = trackCountText,
            entityType = entityType
        )
    }

    private fun tokenize(text: String): Set<String> {
        return text.lowercase(Locale.ROOT)
            .split(Regex("""[^\p{L}\p{M}\p{N}]+"""))
            .filter { it.isNotBlank() && it.length > 1 }
            .toSet()
    }

    private fun rankCandidatesBalanced(candidates: List<ScoredTrackCandidate>, query: String): List<AudioTrack> {
        val cleanQuery = query.trim().lowercase(Locale.ROOT)
        val queryTokens = tokenize(cleanQuery)

        return candidates.sortedByDescending { item ->
            var score = 0.0
            val trackTitle = item.track.title.lowercase(Locale.ROOT)
            val trackArtist = item.track.artist.lowercase(Locale.ROOT)

            score += (40_000.0 / (item.originalIndex + 1.0))

            if (trackTitle == cleanQuery) {
                score += 50_000.0
            } else if (trackTitle.startsWith(cleanQuery)) {
                score += 25_000.0
            }

            val titleWords = tokenize(trackTitle)
            val artistWords = tokenize(trackArtist)

            var matchedTitleTokens = 0
            for (token in queryTokens) {
                if (titleWords.contains(token)) {
                    matchedTitleTokens++
                }
            }
            score += (matchedTitleTokens * 4_000.0)

            var matchedArtistTokens = 0
            for (token in queryTokens) {
                if (artistWords.contains(token)) {
                    matchedArtistTokens++
                }
            }
            score += (matchedArtistTokens * 2_500.0)

            if (trackArtist.isNotEmpty() && trackArtist == cleanQuery) {
                score += 10_000.0
            }

            if (item.playCount > 0L) {
                val logPopularity = ln(item.playCount.toDouble() + 1.0) / ln(10.0)
                score += (logPopularity * 1_200.0)
            }

            if (item.isOfficial) {
                score += 2_000.0
            }

            val containsRemixQuery = queryTokens.contains("remix")
            val containsHardstyleQuery = queryTokens.contains("hardstyle")
            val containsCoverQuery = queryTokens.contains("cover")
            val containsLiveQuery = queryTokens.contains("live")

            if (titleWords.contains("remix") && !containsRemixQuery) score -= 6_000.0
            if (titleWords.contains("hardstyle") && !containsHardstyleQuery) score -= 10_000.0
            if (titleWords.contains("cover") && !containsCoverQuery) score -= 8_000.0
            if (titleWords.contains("live") && !containsLiveQuery) score -= 4_000.0

            score
        }.map { it.track }
    }

    private fun isInstrumentalOrKaraoke(title: String, artist: String): Boolean {
        val text = "$title $artist".lowercase(Locale.ROOT)
        val bannedTags = listOf(
            "instrumental", "karaoke", "backing track", "minus one",
            "no vocal", "off vocal", "piano tutorial", "guitar backing"
        )
        return bannedTags.any { text.contains(it) }
    }

    private fun extractThumbnail(renderer: JSONObject?): String {
        val thumbnails = renderer?.optJSONObject("thumbnail")?.optJSONArray("thumbnails") ?: return ""
        if (thumbnails.length() == 0) return ""

        var bestUrl = ""
        var maxArea = -1

        for (i in 0 until thumbnails.length()) {
            val thumb = thumbnails.optJSONObject(i) ?: continue
            val url = thumb.optString("url")
            val width = thumb.optInt("width", 0)
            val height = thumb.optInt("height", 0)
            val area = width * height

            if (area > maxArea && url.isNotEmpty()) {
                maxArea = area
                bestUrl = url
            }
        }

        return if (bestUrl.isNotEmpty()) bestUrl else thumbnails.optJSONObject(thumbnails.length() - 1)?.optString("url") ?: ""
    }

    private fun extractRunsText(runs: JSONArray?): String {
        if (runs == null || runs.length() == 0) return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text") ?: "")
        }
        return sb.toString().trim()
    }

    private fun parseCountToNumeric(text: String): Long {
        val clean = text
            .replace(",", "")
            .replace(Regex("""[^0-9.KMBkmb]"""), "")
            .uppercase(Locale.ROOT)

        return try {
            when {
                clean.endsWith("B") -> (clean.dropLast(1).toDouble() * 1_000_000_000).toLong()
                clean.endsWith("M") -> (clean.dropLast(1).toDouble() * 1_000_000).toLong()
                clean.endsWith("K") -> (clean.dropLast(1).toDouble() * 1_000).toLong()
                else -> clean.toLongOrNull() ?: 0L
            }
        } catch (e: NumberFormatException) {
            0L
        }
    }

    private fun parseDurationToMs(timeStr: String): Long {
        val parts = timeStr.split(":").map { it.toIntOrNull() ?: 0 }
        return when (parts.size) {
            2 -> (parts[0] * 60L + parts[1]) * 1000L
            3 -> (parts[0] * 3600L + parts[1] * 60L + parts[2]) * 1000L
            else -> 0L
        }
    }

    private fun emptyShelves() = SearchResultShelves(null, emptyList(), emptyList(), emptyList(), emptyList())
}
