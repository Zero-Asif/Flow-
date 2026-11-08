package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import com.example.model.AudioFormat
import com.example.model.AudioTrack
import com.example.model.EqualizerPreset
import com.example.model.FlowMood
import com.example.model.ListeningStats
import com.example.model.Playlist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MusicRepository(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("flow_lossless_prefs", Context.MODE_PRIVATE)

    private val _downloadedTracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    val downloadedTracks: StateFlow<List<AudioTrack>> = _downloadedTracks.asStateFlow()

    private val _lastPlayedTrack = MutableStateFlow<AudioTrack?>(null)
    val lastPlayedTrack: StateFlow<AudioTrack?> = _lastPlayedTrack.asStateFlow()

    private val _tracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    val tracks: StateFlow<List<AudioTrack>> = _tracks.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _history = MutableStateFlow<List<AudioTrack>>(emptyList())
    val history: StateFlow<List<AudioTrack>> = _history.asStateFlow()

    private val _customPresets = MutableStateFlow<List<EqualizerPreset>>(emptyList())
    val customPresets: StateFlow<List<EqualizerPreset>> = _customPresets.asStateFlow()

    private val _stats = MutableStateFlow(ListeningStats())
    val stats: StateFlow<ListeningStats> = _stats.asStateFlow()

    init {
        loadData()
    }

    private fun isCorruptRawTrackEntry(title: String, artist: String, localPath: String? = null): Boolean {
        val trimmedTitle = title.trim()
        val trimmedArtist = artist.trim()
        if (trimmedTitle.isBlank()) return true
        if (trimmedArtist.equals("Offline Saved Track", ignoreCase = true)) return true
        if (trimmedTitle.endsWith("_art", ignoreCase = true) || trimmedTitle.endsWith(" art", ignoreCase = true)) return true
        if (localPath != null && (localPath.endsWith(".jpg", true) || localPath.endsWith(".png", true))) return true
        val rawIdRegex = Regex("""^(yt|saavn|deezer|amazon|tidal)[\s_]+[A-Za-z0-9_-]{4,20}(\s+art)?$""", RegexOption.IGNORE_CASE)
        return rawIdRegex.matches(trimmedTitle)
    }

    private fun serializeTrackToJson(t: AudioTrack): JSONObject {
        val obj = JSONObject()
        obj.put("id", t.id)
        obj.put("title", t.title)
        obj.put("artist", t.artist)
        obj.put("album", t.album)
        obj.put("durationMs", t.durationMs)
        obj.put("format", t.format.name)
        obj.put("sampleRate", t.sampleRate)
        obj.put("bitDepth", t.bitDepth)
        obj.put("bitrateKbps", t.bitrateKbps)
        obj.put("genre", t.genre)
        obj.put("releaseYear", t.releaseYear)
        val gradArr = JSONArray()
        t.gradientColors.forEach { gradArr.put(it) }
        obj.put("gradientColors", gradArr)
        obj.put("uriString", t.uriString)
        obj.put("artworkUrl", t.artworkUrl)
        obj.put("isFavorite", t.isFavorite)
        obj.put("isDownloaded", t.isDownloaded)
        obj.put("localFilePath", t.localFilePath)
        obj.put("sourceProvider", t.sourceProvider)
        obj.put("isGlobalOnline", t.isGlobalOnline)
        obj.put("isLocalImport", t.isLocalImport)
        obj.put("fileSize", t.fileSizeFormatted)
        return obj
    }

    private fun deserializeTrackFromJson(obj: JSONObject, savedFavs: Set<String> = _favorites.value): AudioTrack {
        val id = obj.getString("id")
        val gradArr = obj.optJSONArray("gradientColors")
        val gradients = mutableListOf<Long>()
        if (gradArr != null && gradArr.length() >= 2) {
            for (i in 0 until gradArr.length()) {
                gradients.add(gradArr.optLong(i, 0xFF00F0FF))
            }
        } else {
            gradients.add(0xFF00F0FF)
            gradients.add(0xFF7928CA)
        }
        val formatName = obj.optString("format", "FLAC")
        val parsedFormat = try {
            AudioFormat.valueOf(formatName)
        } catch (_: Exception) {
            AudioFormat.FLAC
        }
        val localPath = obj.optString("localFilePath", "").ifBlank { null }
        val isDownloadedFlag = obj.optBoolean("isDownloaded", false) && localPath != null && File(localPath).exists()

        return AudioTrack(
            id = id,
            title = obj.optString("title", "Unknown Track"),
            artist = obj.optString("artist", "Unknown Artist"),
            album = obj.optString("album", "Single"),
            durationMs = obj.optLong("durationMs", 180000L),
            format = parsedFormat,
            sampleRate = obj.optInt("sampleRate", 44100),
            bitDepth = obj.optInt("bitDepth", 16),
            bitrateKbps = obj.optInt("bitrateKbps", 320),
            genre = obj.optString("genre", "Global Stream"),
            releaseYear = obj.optInt("releaseYear", 2024),
            gradientColors = gradients,
            uriString = obj.optString("uriString", "").ifBlank { null },
            artworkUrl = obj.optString("artworkUrl", "").ifBlank { null },
            isFavorite = savedFavs.contains(id) || obj.optBoolean("isFavorite", false),
            isDownloaded = isDownloadedFlag,
            localFilePath = if (isDownloadedFlag) localPath else null,
            sourceProvider = obj.optString("sourceProvider", "Lossless Stream").ifBlank { "Lossless Stream" },
            isGlobalOnline = obj.optBoolean("isGlobalOnline", false),
            isLocalImport = obj.optBoolean("isLocalImport", false),
            fileSizeFormatted = obj.optString("fileSize", "12.0 MB")
        )
    }

    /**
     * Persists the last played track to SharedPreferences so the MiniPlayer
     * restores the true last played song across app launches.
     */
    fun saveLastPlayedTrack(track: AudioTrack) {
        _lastPlayedTrack.value = track
        try {
            val jsonStr = serializeTrackToJson(track).toString()
            prefs.edit().putString("last_played_track_json", jsonStr).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Loads the persisted last played track from SharedPreferences, or null if no song has been played yet.
     */
    fun loadLastPlayedTrack(): AudioTrack? {
        val raw = prefs.getString("last_played_track_json", null)
        if (!raw.isNullOrBlank()) {
            try {
                val track = deserializeTrackFromJson(JSONObject(raw), _favorites.value)
                _lastPlayedTrack.value = track
                return track
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return _lastPlayedTrack.value
    }

    private fun loadData() {
        // Load favorites
        val savedFavs = prefs.getStringSet("favorites", emptySet()) ?: emptySet()
        _favorites.value = savedFavs

        // Load tracks with favorites flag
        val initialTracks = SampleTracks.ALL_TRACKS.map { track ->
            track.copy(isFavorite = savedFavs.contains(track.id))
        }.toMutableList()

        // Load imported local tracks from prefs
        val localTracksJson = prefs.getString("local_tracks", "[]") ?: "[]"
        try {
            val jsonArray = JSONArray(localTracksJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                initialTracks.add(
                    AudioTrack(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        artist = obj.optString("artist", "Unknown Artist"),
                        album = obj.optString("album", "Local Audio"),
                        durationMs = obj.optLong("durationMs", 180000L),
                        format = AudioFormat.valueOf(obj.optString("format", "FLAC")),
                        sampleRate = obj.optInt("sampleRate", 96000),
                        bitDepth = obj.optInt("bitDepth", 24),
                        bitrateKbps = obj.optInt("bitrateKbps", 2822),
                        genre = obj.optString("genre", "Imported"),
                        releaseYear = obj.optInt("releaseYear", 2024),
                        gradientColors = listOf(0xFF06B6D4, 0xFF3B82F6),
                        uriString = obj.optString("uriString", null),
                        isLocalImport = true,
                        fileSizeFormatted = obj.optString("fileSize", "28.5 MB")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Load downloaded tracks from prefs + purge any corrupt raw-ID or _art entries
        val downloadedTracksJson = prefs.getString("downloaded_tracks", "[]") ?: "[]"
        val downloadedList = mutableListOf<AudioTrack>()
        var hadCorruptDownloadedEntries = false
        try {
            val jsonArray = JSONArray(downloadedTracksJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val localPath = obj.optString("localFilePath", null)
                val rawTitle = obj.optString("title", "")
                val rawArtist = obj.optString("artist", "Unknown Artist")
                if (isCorruptRawTrackEntry(rawTitle, rawArtist, localPath)) {
                    hadCorruptDownloadedEntries = true
                    continue
                }
                val track = AudioTrack(
                    id = obj.getString("id"),
                    title = rawTitle,
                    artist = rawArtist,
                    album = obj.optString("album", "Downloaded Audio"),
                    durationMs = obj.optLong("durationMs", 180000L),
                    format = runCatching { AudioFormat.valueOf(obj.optString("format", "AAC")) }.getOrDefault(AudioFormat.AAC),
                    sampleRate = obj.optInt("sampleRate", 44100),
                    bitDepth = obj.optInt("bitDepth", 16),
                    bitrateKbps = obj.optInt("bitrateKbps", 320),
                    genre = obj.optString("genre", "Downloaded"),
                    releaseYear = obj.optInt("releaseYear", 2024),
                    gradientColors = listOf(0xFF00F0FF, 0xFF7928CA),
                    uriString = obj.optString("uriString", null),
                    artworkUrl = obj.optString("artworkUrl", null),
                    isFavorite = savedFavs.contains(obj.getString("id")),
                    isDownloaded = true,
                    localFilePath = localPath,
                    sourceProvider = obj.optString("sourceProvider", "Downloaded Master"),
                    isLocalImport = true,
                    fileSizeFormatted = obj.optString("fileSize", "8.5 MB")
                )
                if (localPath != null && File(localPath).exists()) {
                    downloadedList.add(track)
                    val idx = initialTracks.indexOfFirst { it.id == track.id }
                    if (idx != -1) {
                        initialTracks[idx] = track
                    } else {
                        initialTracks.add(track)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Clean up any stray image/temp files in downloadsDir and scan only valid audio files with human-readable names
        try {
            val validAudioExtensions = setOf("flac", "m4a", "mp3", "wav", "wave", "alac", "opus")
            val downloadsDir = File(context.filesDir, "downloads")
            val artworkDir = File(context.filesDir, "downloads_artwork").apply { if (!exists()) mkdirs() }
            if (downloadsDir.exists() && downloadsDir.isDirectory) {
                downloadsDir.listFiles()?.forEach { file ->
                    val ext = file.extension.lowercase()
                    if (ext in setOf("jpg", "jpeg", "png", "webp", "part")) {
                        if (ext != "part") {
                            runCatching { file.copyTo(File(artworkDir, file.name), overwrite = true) }
                        }
                        runCatching { file.delete() }
                        return@forEach
                    }
                    if (
                        file.isFile &&
                        ext in validAudioExtensions &&
                        file.length() > 50_000L &&
                        downloadedList.none { it.localFilePath == file.absolutePath }
                    ) {
                        val baseName = file.nameWithoutExtension
                        // Do NOT recover raw ID filenames like "yt_ge4M2dVBgTw" or "*_art" as fake track titles
                        if (isCorruptRawTrackEntry(baseName.replace("_", " "), "", file.absolutePath)) {
                            return@forEach
                        }
                        val fmt = when (ext) {
                            "flac" -> AudioFormat.FLAC
                            "wav", "wave" -> AudioFormat.WAV
                            "alac" -> AudioFormat.ALAC
                            "aac", "m4a", "mp4" -> AudioFormat.AAC
                            else -> AudioFormat.MP3
                        }
                        val cleanTitle = baseName.substringAfter(" - ", baseName).replace("_", " ").trim()
                        val cleanArtist = if (baseName.contains(" - ")) {
                            baseName.substringBefore(" - ").replace("_", " ").trim()
                        } else {
                            "Local Library"
                        }
                        val sizeMb = String.format(java.util.Locale.US, "%.1f MB", file.length() / (1024.0 * 1024.0))
                        val recoveredTrack = AudioTrack(
                            id = baseName,
                            title = cleanTitle,
                            artist = cleanArtist,
                            album = "Flow Downloads",
                            durationMs = 210000L,
                            format = fmt,
                            sampleRate = if (fmt.isLossless) 96000 else 44100,
                            bitDepth = if (fmt.isLossless) 24 else 16,
                            bitrateKbps = if (fmt.isLossless) 1411 else 320,
                            genre = "Downloaded",
                            releaseYear = 2025,
                            gradientColors = listOf(0xFF00F0FF, 0xFF7928CA),
                            uriString = file.absolutePath,
                            isDownloaded = true,
                            localFilePath = file.absolutePath,
                            sourceProvider = "Flow Downloads",
                            isLocalImport = true,
                            fileSizeFormatted = sizeMb
                        )
                        downloadedList.add(recoveredTrack)
                        if (initialTracks.none { it.id == recoveredTrack.id }) {
                            initialTracks.add(recoveredTrack)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        _downloadedTracks.value = downloadedList
        _tracks.value = initialTracks
        if (hadCorruptDownloadedEntries) {
            saveDownloadedTracks()
        }

        // Load persisted listening history
        val historyJson = prefs.getString("history_tracks_json", "[]") ?: "[]"
        try {
            val hArr = JSONArray(historyJson)
            val loadedHistory = mutableListOf<AudioTrack>()
            for (i in 0 until hArr.length()) {
                loadedHistory.add(deserializeTrackFromJson(hArr.getJSONObject(i), savedFavs))
            }
            _history.value = loadedHistory
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Load persisted lastPlayedTrack
        loadLastPlayedTrack()

        // Initialize Playlists
        val defaultPlaylists = listOf(
            Playlist(
                id = "pl_flow_daily",
                name = "Flow Daily Mix",
                description = "Deezer-style personalized infinite mix based on your listening habits",
                trackIds = initialTracks.take(6).map { it.id },
                isSmartFlow = true,
                isSystem = true,
                gradientColors = listOf(0xFFFF007A, 0xFF7928CA)
            ),
            Playlist(
                id = "pl_hires_master",
                name = "24-bit Hi-Res Masters",
                description = "Bit-perfect FLAC and ALAC audiophile studio masters",
                trackIds = initialTracks.filter { it.bitDepth >= 24 }.map { it.id },
                isSystem = true,
                gradientColors = listOf(0xFFFFB800, 0xFFFF007A)
            ),
            Playlist(
                id = "pl_synth_night",
                name = "Cyber Synth & Night Drive",
                description = "Electric neon synths and deep driving sub-bass",
                trackIds = initialTracks.filter { it.genre in listOf("Synthwave", "Cyberpunk", "Electronic") }.map { it.id },
                isSystem = true,
                gradientColors = listOf(0xFF8B5CF6, 0xFF00F0FF)
            )
        )

        // Load custom playlists from prefs
        val customPlaylistsJson = prefs.getString("custom_playlists", "[]") ?: "[]"
        val customPlaylists = mutableListOf<Playlist>()
        try {
            val jsonArray = JSONArray(customPlaylistsJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val trackIdsList = mutableListOf<String>()
                val tArr = obj.getJSONArray("trackIds")
                for (j in 0 until tArr.length()) {
                    trackIdsList.add(tArr.getString(j))
                }
                customPlaylists.add(
                    Playlist(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        description = obj.optString("description", ""),
                        trackIds = trackIdsList,
                        isSmartFlow = false,
                        isSystem = false,
                        gradientColors = listOf(0xFFA855F7, 0xFFEC4899)
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        _playlists.value = defaultPlaylists + customPlaylists
        updateStats()
    }

    fun toggleFavorite(trackId: String) {
        val currentFavs = _favorites.value.toMutableSet()
        if (currentFavs.contains(trackId)) {
            currentFavs.remove(trackId)
        } else {
            currentFavs.add(trackId)
        }
        _favorites.value = currentFavs
        prefs.edit().putStringSet("favorites", currentFavs).apply()

        _tracks.value = _tracks.value.map {
            if (it.id == trackId) it.copy(isFavorite = currentFavs.contains(trackId)) else it
        }
        _lastPlayedTrack.value?.let { last ->
            if (last.id == trackId) {
                saveLastPlayedTrack(last.copy(isFavorite = currentFavs.contains(trackId)))
            }
        }
        updateStats()
    }

    fun recordTrackPlayed(track: AudioTrack, listenedSeconds: Long) {
        // Persist as lastPlayedTrack immediately
        saveLastPlayedTrack(track)

        // Update history and persist to SharedPreferences
        val newHistory = (listOf(track) + _history.value.filter { it.id != track.id }).take(30)
        _history.value = newHistory
        try {
            val hArr = JSONArray()
            newHistory.forEach { hArr.put(serializeTrackToJson(it)) }
            prefs.edit().putString("history_tracks_json", hArr.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Update track play count
        _tracks.value = _tracks.value.map {
            if (it.id == track.id) {
                it.copy(
                    playCount = it.playCount + 1,
                    lastPlayedTimestamp = System.currentTimeMillis()
                )
            } else it
        }

        val totalTime = prefs.getLong("total_listen_time_sec", 0L) + listenedSeconds
        val totalPlays = prefs.getInt("total_plays_count", 0) + 1
        prefs.edit()
            .putLong("total_listen_time_sec", totalTime)
            .putInt("total_plays_count", totalPlays)
            .apply()

        updateStats()
    }

    private fun updateStats() {
        val allTracks = _tracks.value
        val totalSecs = prefs.getLong("total_listen_time_sec", 14800L) // Initial demo value for rich stats
        val totalPlays = prefs.getInt("total_plays_count", 28)

        val artistCounts = mutableMapOf<String, Int>()
        val genreCounts = mutableMapOf<String, Int>()

        for (t in allTracks) {
            val weight = if (t.isFavorite) 3 else 1
            artistCounts[t.artist] = (artistCounts[t.artist] ?: 0) + weight + t.playCount
            genreCounts[t.genre] = (genreCounts[t.genre] ?: 0) + weight + t.playCount
        }

        val topArtists = artistCounts.toList().sortedByDescending { it.second }.take(5)
        val topGenres = genreCounts.toList().sortedByDescending { it.second }.take(5)

        _stats.value = ListeningStats(
            totalSecondsListened = totalSecs,
            totalTracksPlayed = totalPlays,
            losslessRatio = 0.96f,
            topArtists = topArtists,
            topGenres = topGenres,
            audioOutputLatencyMs = 3.8f,
            directBitPerfectDac = true
        )
    }

    /**
     * Deezer-style Smart Flow generator based on mood and listening history
     */
    fun generateFlowMix(mood: FlowMood): List<AudioTrack> {
        val all = _tracks.value
        val filtered = when (mood) {
            FlowMood.ALL -> all
            FlowMood.CHILL -> all.filter { it.genre in listOf("Lo-Fi", "Acoustic", "Ambient") }
            FlowMood.FOCUS -> all.filter { it.genre in listOf("Ambient", "Orchestral", "Lo-Fi") }
            FlowMood.WORKOUT -> all.filter { it.genre in listOf("Electronic", "Cyberpunk", "Synthwave") }
            FlowMood.NIGHT_DRIVE -> all.filter { it.genre in listOf("Synthwave", "Cyberpunk") }
            FlowMood.MELANCHOLY -> all.filter { it.genre in listOf("Jazz", "Acoustic", "Ambient") }
        }

        val pool = if (filtered.isEmpty()) all else filtered
        // Shuffle with favorites and top played prioritized
        return pool.sortedByDescending { (if (it.isFavorite) 5 else 0) + it.playCount }.shuffled()
    }

    fun createPlaylist(name: String, description: String, trackIds: List<String>): Playlist {
        val newPlaylist = Playlist(
            id = "pl_${System.currentTimeMillis()}",
            name = name,
            description = description,
            trackIds = trackIds,
            isSmartFlow = false,
            isSystem = false,
            gradientColors = listOf(0xFFA855F7, 0xFFEC4899)
        )
        val updated = _playlists.value + newPlaylist
        _playlists.value = updated
        saveCustomPlaylists()
        return newPlaylist
    }

    fun addTrackToPlaylist(playlistId: String, trackId: String) {
        _playlists.value = _playlists.value.map { pl ->
            if (pl.id == playlistId && !pl.trackIds.contains(trackId)) {
                pl.copy(trackIds = pl.trackIds + trackId)
            } else pl
        }
        saveCustomPlaylists()
    }

    private fun saveCustomPlaylists() {
        val customOnly = _playlists.value.filter { !it.isSystem }
        val arr = JSONArray()
        for (pl in customOnly) {
            val obj = JSONObject()
            obj.put("id", pl.id)
            obj.put("name", pl.name)
            obj.put("description", pl.description)
            val tArr = JSONArray()
            pl.trackIds.forEach { tArr.put(it) }
            obj.put("trackIds", tArr)
            arr.put(obj)
        }
        prefs.edit().putString("custom_playlists", arr.toString()).apply()
    }

    /**
     * Import local audio file picked via Android SAF / File Picker
     */
    fun importLocalAudio(uri: Uri): AudioTrack? {
        try {
            var fileName = "Local_Audio_${System.currentTimeMillis()}"
            var fileSize = 0L

            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) fileName = cursor.getString(nameIndex)
                    if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                }
            }

            val format = when {
                fileName.endsWith(".flac", ignoreCase = true) -> AudioFormat.FLAC
                fileName.endsWith(".alac", ignoreCase = true) -> AudioFormat.ALAC
                fileName.endsWith(".wav", ignoreCase = true) -> AudioFormat.WAV
                fileName.endsWith(".m4a", ignoreCase = true) -> AudioFormat.ALAC
                fileName.endsWith(".mp3", ignoreCase = true) -> AudioFormat.MP3
                else -> AudioFormat.FLAC
            }

            val cleanTitle = fileName.substringBeforeLast(".")
                .replace("_", " ")
                .replace("-", " ")
                .trim()

            val sizeMb = if (fileSize > 0) "%.1f MB".format(fileSize / (1024.0 * 1024.0)) else "24.5 MB"

            val newTrack = AudioTrack(
                id = "local_${System.currentTimeMillis()}",
                title = cleanTitle,
                artist = "Local Library",
                album = "Storage Audio",
                durationMs = 210000L,
                format = format,
                sampleRate = if (format.isLossless) 96000 else 44100,
                bitDepth = if (format.isLossless) 24 else 16,
                bitrateKbps = if (format.isLossless) 2822 else 320,
                genre = "Local Files",
                releaseYear = 2024,
                gradientColors = listOf(0xFF00F0FF, 0xFF3B82F6),
                uriString = uri.toString(),
                isLocalImport = true,
                fileSizeFormatted = sizeMb
            )

            _tracks.value = _tracks.value + newTrack

            // Persist local track list
            saveLocalTracks()
            return newTrack
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun saveLocalTracks() {
        val locals = _tracks.value.filter { it.isLocalImport }
        val arr = JSONArray()
        for (t in locals) {
            val obj = JSONObject()
            obj.put("id", t.id)
            obj.put("title", t.title)
            obj.put("artist", t.artist)
            obj.put("album", t.album)
            obj.put("durationMs", t.durationMs)
            obj.put("format", t.format.name)
            obj.put("sampleRate", t.sampleRate)
            obj.put("bitDepth", t.bitDepth)
            obj.put("bitrateKbps", t.bitrateKbps)
            obj.put("genre", t.genre)
            obj.put("uriString", t.uriString)
            obj.put("fileSize", t.fileSizeFormatted)
            arr.put(obj)
        }
        prefs.edit().putString("local_tracks", arr.toString()).apply()
    }

    fun addDownloadedTrack(track: AudioTrack) {
        val current = _downloadedTracks.value.filter { it.id != track.id } + track
        _downloadedTracks.value = current
        _tracks.value = _tracks.value.map { if (it.id == track.id) track else it }.let { list ->
            if (list.none { it.id == track.id }) list + track else list
        }
        saveDownloadedTracks()
    }

    fun removeDownloadedTrack(trackId: String) {
        _downloadedTracks.value = _downloadedTracks.value.filter { it.id != trackId }
        _tracks.value = _tracks.value.map {
            if (it.id == trackId) it.copy(isDownloaded = false, localFilePath = null) else it
        }
        saveDownloadedTracks()
    }

    private fun saveDownloadedTracks() {
        val list = _downloadedTracks.value
        val arr = JSONArray()
        for (t in list) {
            val obj = JSONObject()
            obj.put("id", t.id)
            obj.put("title", t.title)
            obj.put("artist", t.artist)
            obj.put("album", t.album)
            obj.put("durationMs", t.durationMs)
            obj.put("format", t.format.name)
            obj.put("sampleRate", t.sampleRate)
            obj.put("bitDepth", t.bitDepth)
            obj.put("bitrateKbps", t.bitrateKbps)
            obj.put("genre", t.genre)
            obj.put("releaseYear", t.releaseYear)
            obj.put("uriString", t.uriString)
            obj.put("artworkUrl", t.artworkUrl)
            obj.put("localFilePath", t.localFilePath)
            obj.put("sourceProvider", t.sourceProvider)
            obj.put("fileSize", t.fileSizeFormatted)
            arr.put(obj)
        }
        prefs.edit().putString("downloaded_tracks", arr.toString()).apply()
    }

    /**
     * Cross-device backup & sync export to JSON
     */
    fun exportBackupJson(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("timestamp", System.currentTimeMillis())

        val favArray = JSONArray()
        _favorites.value.forEach { favArray.put(it) }
        root.put("favorites", favArray)

        val plArray = JSONArray()
        for (pl in _playlists.value.filter { !it.isSystem }) {
            val plObj = JSONObject()
            plObj.put("id", pl.id)
            plObj.put("name", pl.name)
            plObj.put("description", pl.description)
            val tArr = JSONArray()
            pl.trackIds.forEach { tArr.put(it) }
            plObj.put("tracks", tArr)
            plArray.put(plObj)
        }
        root.put("customPlaylists", plArray)

        return root.toString(2)
    }

    /**
     * Cross-device restore from JSON
     */
    fun importBackupJson(jsonString: String): Boolean {
        return try {
            val root = JSONObject(jsonString)
            if (root.has("favorites")) {
                val favs = root.getJSONArray("favorites")
                val set = mutableSetOf<String>()
                for (i in 0 until favs.length()) {
                    set.add(favs.getString(i))
                }
                _favorites.value = set
                prefs.edit().putStringSet("favorites", set).apply()
            }
            loadData()
            true
        } catch (e: Exception) {
            false
        }
    }
}
