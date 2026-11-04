package com.example.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.ExoPlayerAudioEngine
import com.example.data.MusicRepository
import com.example.model.AudioFormat
import com.example.model.AudioQualityInfo
import com.example.model.AudioTrack
import com.example.model.BadgeDisplayMode
import com.example.model.EqualizerPreset
import com.example.model.FlowMood
import com.example.model.KaraokeLyricLine
import com.example.model.ListeningStats
import com.example.model.LyricsDisplayMode
import com.example.model.PlaybackState
import com.example.model.Playlist
import com.example.model.RepeatMode
import com.example.model.StreamBackend
import com.example.service.FlowPlaybackService
import com.example.util.LyricsSyncState
import com.example.util.StateDrivenLyricsParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

import com.example.download.SongDownloadManager
import com.example.network.CategorizedSearchResults
import com.example.network.ResolvedStreamCandidate
import com.example.network.DownloadQualityOption
import com.example.network.GlobalMusicSearchEngine
import com.example.network.MusicSourceFilter
import com.example.network.SearchSuggestionsResult
import com.example.network.TrackRadioRepository

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    val repository = MusicRepository(application.applicationContext)
    val audioEngine = ExoPlayerAudioEngine.getInstance(application.applicationContext)
    val downloadManager = SongDownloadManager.getInstance(application.applicationContext, repository)

    // Download states
    val downloadProgress: StateFlow<Map<String, Float>> = downloadManager.downloadProgress
    val downloadedTracks: StateFlow<List<AudioTrack>> = repository.downloadedTracks

    // Track pending quality selection before downloading
    private val _pendingDownloadTrack = MutableStateFlow<AudioTrack?>(null)
    val pendingDownloadTrack: StateFlow<AudioTrack?> = _pendingDownloadTrack.asStateFlow()

    // SpotiFLAC multi-source search filter
    val selectedMusicSourceFilter = MutableStateFlow(MusicSourceFilter.ALL)

    // Playback state
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    // Atomic track loading & stream resolution error states
    val isLoadingTrack: StateFlow<Boolean> = audioEngine.isLoadingTrack
    val playbackError: StateFlow<String?> = audioEngine.playbackError

    fun dismissPlaybackError() {
        audioEngine.clearPlaybackError()
    }

    // Active Equalizer preset
    private val _activePreset = MutableStateFlow(EqualizerPreset.AUDIOPHILE_REFERENCE)
    val activePreset: StateFlow<EqualizerPreset> = _activePreset.asStateFlow()

    // UI Navigation & Dialog states
    private val _isNowPlayingExpanded = MutableStateFlow(false)
    val isNowPlayingExpanded: StateFlow<Boolean> = _isNowPlayingExpanded.asStateFlow()

    private val _showLyrics = MutableStateFlow(false)
    val showLyrics: StateFlow<Boolean> = _showLyrics.asStateFlow()

    private val _showQueue = MutableStateFlow(false)
    val showQueue: StateFlow<Boolean> = _showQueue.asStateFlow()

    private val _showSleepTimerDialog = MutableStateFlow(false)
    val showSleepTimerDialog: StateFlow<Boolean> = _showSleepTimerDialog.asStateFlow()

    private val _showSyncDialog = MutableStateFlow(false)
    val showSyncDialog: StateFlow<Boolean> = _showSyncDialog.asStateFlow()

    private val _showShareDialog = MutableStateFlow(false)
    val showShareDialog: StateFlow<Boolean> = _showShareDialog.asStateFlow()

    private val _showCreatePlaylistDialog = MutableStateFlow(false)
    val showCreatePlaylistDialog: StateFlow<Boolean> = _showCreatePlaylistDialog.asStateFlow()

    private val _selectedFlowMood = MutableStateFlow(FlowMood.ALL)
    val selectedFlowMood: StateFlow<FlowMood> = _selectedFlowMood.asStateFlow()

    // Real-Time Synchronized Lyrics state
    private val _activeLyrics = MutableStateFlow<List<KaraokeLyricLine>>(emptyList())
    val activeLyrics: StateFlow<List<KaraokeLyricLine>> = _activeLyrics.asStateFlow()

    // State-driven synced lyrics engine combining raw LRC and ExoPlayer position
    val lyricsSyncState: StateFlow<LyricsSyncState> = combine(
        _activeLyrics,
        _playbackState
    ) { lyrics, playback ->
        StateDrivenLyricsParser.sync(lyrics, playback.currentPositionMs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LyricsSyncState())

    private val _lyricsDisplayMode = MutableStateFlow(LyricsDisplayMode.LINE_GLOW)
    val lyricsDisplayMode: StateFlow<LyricsDisplayMode> = _lyricsDisplayMode.asStateFlow()

    // Dynamic Audio Quality Telemetry state
    private val _audioQualityInfo = MutableStateFlow(AudioQualityInfo())
    val audioQualityInfo: StateFlow<AudioQualityInfo> = _audioQualityInfo.asStateFlow()

    private val _badgeDisplayMode = MutableStateFlow(BadgeDisplayMode.AUDIOPHILE_FULL)
    val badgeDisplayMode: StateFlow<BadgeDisplayMode> = _badgeDisplayMode.asStateFlow()

    // Audio Streaming Backend selector
    private val _selectedStreamBackend = MutableStateFlow(StreamBackend.AUTO_HIGH_FIDELITY)
    val selectedStreamBackend: StateFlow<StreamBackend> = _selectedStreamBackend.asStateFlow()

    // Real candidate stream switching (Deezer HiFi FLAC, Amazon Ultra HD, Tidal Master, JioSaavn 320k)
    val activeStreamCandidate: StateFlow<ResolvedStreamCandidate?> = audioEngine.activeStreamCandidate
    val availableStreamCandidates: StateFlow<List<ResolvedStreamCandidate>> = audioEngine.availableStreamCandidates

    private var lyricsJob: Job? = null

    // Search filters
    val searchQuery = MutableStateFlow("")
    val filterGenre = MutableStateFlow<String?>(null)
    val filterFormat = MutableStateFlow<AudioFormat?>(null)
    val filterYear = MutableStateFlow<Int?>(null)

    // Global Music Search Engine states
    private val _globalSearchResults = MutableStateFlow<List<AudioTrack>>(emptyList())
    val globalSearchResults: StateFlow<List<AudioTrack>> = _globalSearchResults.asStateFlow()

    private val _isGlobalSearching = MutableStateFlow(false)
    val isGlobalSearching: StateFlow<Boolean> = _isGlobalSearching.asStateFlow()

    private val _globalTrendingTracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    val globalTrendingTracks: StateFlow<List<AudioTrack>> = _globalTrendingTracks.asStateFlow()

    private val _searchSuggestions = MutableStateFlow(SearchSuggestionsResult())
    val searchSuggestions: StateFlow<SearchSuggestionsResult> = _searchSuggestions.asStateFlow()

    private var searchJob: Job? = null
    private var isRadioMode: Boolean = true
    private var isFetchingRadioBatch: Boolean = false
    private var radioJob: Job? = null

    // Combined filtered search results (Local tracks + Global songs across the world)
    private val rawSearchResults: StateFlow<List<AudioTrack>> = combine(
        repository.tracks,
        _globalSearchResults,
        searchQuery
    ) { localTracks, globalTracks, query ->
        val trimmedQuery = query.trim()
        if (trimmedQuery.isNotEmpty()) {
            val matchingLocal = localTracks.filter { track ->
                track.title.contains(trimmedQuery, ignoreCase = true) ||
                    track.artist.contains(trimmedQuery, ignoreCase = true) ||
                    track.album.contains(trimmedQuery, ignoreCase = true) ||
                    GlobalMusicSearchEngine.computeSearchRelevanceScore(track, trimmedQuery) >= 62
            }
            val deduplicatedGlobal = globalTracks.filter { gTrack ->
                matchingLocal.none { it.id == gTrack.id }
            }
            // Rank combined results by Fuzzy Relevance Score + Lossless Quality Cascade
            GlobalMusicSearchEngine.rankTracksByQueryAndQuality(matchingLocal + deduplicatedGlobal, trimmedQuery)
        } else {
            localTracks
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val searchResults: StateFlow<List<AudioTrack>> = combine(
        rawSearchResults,
        filterGenre,
        filterFormat,
        filterYear
    ) { tracks, genre, format, year ->
        tracks.filter { track ->
            val matchesGenre = genre == null || track.genre.contains(genre, ignoreCase = true)
            val matchesFormat = format == null || track.format == format
            val matchesYear = year == null || track.releaseYear == year
            matchesGenre && matchesFormat && matchesYear
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val categorizedSearchResults: StateFlow<CategorizedSearchResults> = combine(
        searchResults,
        searchQuery
    ) { results, query ->
        if (query.isNotBlank()) {
            GlobalMusicSearchEngine.segregateSearchResults(results, query)
        } else {
            CategorizedSearchResults()
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CategorizedSearchResults())

    private var sleepTimerJob: Job? = null

    init {
        // 1. Subscribe to SSOT Track Transition state (emitted only after ExoPlayer queues & prepares resolved stream)
        viewModelScope.launch {
            audioEngine.currentTrackState.collectLatest { track ->
                if (track != null) {
                    val currentQueue = audioEngine.activeQueue.ifEmpty { _playbackState.value.queue }
                    val updatedQueue = if (currentQueue.any { it.id == track.id }) {
                        currentQueue.map { if (it.id == track.id) track else it }
                    } else {
                        listOf(track) + currentQueue
                    }
                    val newIdx = updatedQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                    _playbackState.value = _playbackState.value.copy(
                        currentTrack = track,
                        queue = updatedQueue,
                        queueIndex = newIdx,
                        currentPositionMs = 0L,
                        durationMs = if (track.durationMs > 0) track.durationMs else _playbackState.value.durationMs
                    )
                    repository.saveLastPlayedTrack(track)
                    fetchLyricsForTrack(track)
                    repository.recordTrackPlayed(track, 10L)
                }
            }
        }

        // 2. Subscribe to SSOT Play/Pause state
        viewModelScope.launch {
            audioEngine.isPlayingState.collectLatest { isPlaying ->
                _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
            }
        }

        // 3. Subscribe to SSOT Playback Position and Duration
        viewModelScope.launch {
            audioEngine.currentPositionState.collectLatest { pos ->
                _playbackState.value = _playbackState.value.copy(currentPositionMs = pos)
            }
        }

        viewModelScope.launch {
            audioEngine.durationState.collectLatest { dur ->
                if (dur > 0) {
                    _playbackState.value = _playbackState.value.copy(durationMs = dur)
                }
            }
        }

        // 4. Subscribe to SSOT Dynamic Audio Quality Telemetry
        viewModelScope.launch {
            audioEngine.audioQualityState.collectLatest { quality ->
                _audioQualityInfo.value = quality
            }
        }

        // 5. Subscribe to SSOT Spectrum Visualizer
        viewModelScope.launch {
            audioEngine.visualizerAmplitudes.collectLatest { amplitudes ->
                _playbackState.value = _playbackState.value.copy(visualizerAmplitudes = amplitudes)
            }
        }

        audioEngine.onFavoriteToggleRequested = { track ->
            toggleFavorite(track.id)
        }
        audioEngine.onQueueNearEnd = {
            fetchNextRadioBatch()
        }

        audioEngine.setEqualizerPreset(_activePreset.value)
        audioEngine.setGaplessEnabled(true)

        // Hydrate MiniPlayer from persisted lastPlayedTrack (or active engine track / history), NEVER hardcoding "Midnight Horizon"
        val initialTracks = repository.tracks.value
        val restoredTrack = audioEngine.currentPlayingTrack
            ?: repository.loadLastPlayedTrack()
            ?: repository.history.value.firstOrNull()

        if (restoredTrack != null) {
            val restoredQueue = if (initialTracks.any { it.id == restoredTrack.id }) {
                initialTracks.map { if (it.id == restoredTrack.id) restoredTrack else it }
            } else {
                listOf(restoredTrack) + initialTracks
            }
            val restoredIndex = restoredQueue.indexOfFirst { it.id == restoredTrack.id }.coerceAtLeast(0)
            val isCurrentlyPlaying = audioEngine.exoPlayer.isPlaying

            _playbackState.value = _playbackState.value.copy(
                queue = restoredQueue,
                queueIndex = restoredIndex,
                currentTrack = restoredTrack,
                isPlaying = isCurrentlyPlaying,
                currentPositionMs = if (isCurrentlyPlaying) audioEngine.exoPlayer.currentPosition.coerceAtLeast(0L) else 0L,
                durationMs = restoredTrack.durationMs
            )
            _audioQualityInfo.value = AudioQualityInfo(
                formatName = restoredTrack.format.displayName,
                sampleRateHz = restoredTrack.sampleRate,
                bitDepth = restoredTrack.bitDepth,
                bitrateKbps = restoredTrack.bitrateKbps,
                channelCount = 2,
                isLossless = restoredTrack.format.isLossless,
                isHiRes = restoredTrack.format.isLossless || restoredTrack.sampleRate >= 96000 || restoredTrack.bitDepth >= 24
            )
            fetchLyricsForTrack(restoredTrack)
        } else {
            // No play history exists yet: keep currentTrack = null so MiniPlayer gracefully hides until a song is played
            _playbackState.value = _playbackState.value.copy(
                queue = initialTracks,
                queueIndex = 0,
                currentTrack = null,
                isPlaying = false,
                currentPositionMs = 0L,
                durationMs = 0L
            )
        }

        // Fetch initial trending global hits
        viewModelScope.launch {
            try {
                val trending = GlobalMusicSearchEngine.getGlobalTrendingTracks()
                _globalTrendingTracks.value = trending
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setMusicSourceFilter(filter: MusicSourceFilter) {
        selectedMusicSourceFilter.value = filter
        val currentQuery = searchQuery.value.trim()
        if (currentQuery.isNotEmpty()) {
            executeSearch(currentQuery, filter)
        }
    }

    /**
     * Debounced Search State Manager (250ms debounce per Module 2.1):
     * - Cancels any in-flight OkHttp API calls and active search coroutine immediately.
     * - Clears stale global search list state so old results never linger or desync.
     */
    fun onSearchQueryChanged(newQuery: String) {
        searchQuery.value = newQuery
        searchJob?.cancel()
        GlobalMusicSearchEngine.cancelActiveSearchCalls()

        val trimmed = newQuery.trim()
        if (trimmed.isEmpty()) {
            _globalSearchResults.value = emptyList()
            _searchSuggestions.value = SearchSuggestionsResult()
            _isGlobalSearching.value = false
            return
        }

        // Immediately clear previous search results & mark loading before debounce fires
        _globalSearchResults.value = emptyList()
        _isGlobalSearching.value = true
        executeSearch(trimmed, selectedMusicSourceFilter.value)
    }

    private fun executeSearch(query: String, filter: MusicSourceFilter) {
        searchJob?.cancel()
        GlobalMusicSearchEngine.cancelActiveSearchCalls()

        searchJob = viewModelScope.launch {
            _isGlobalSearching.value = true
            _globalSearchResults.value = emptyList()
            // 250ms debounce window per Amazon / YouTube Music typeahead specification
            delay(250L)
            if (!isActive) return@launch
            try {
                val results = GlobalMusicSearchEngine.searchAllSources(
                    query = query,
                    filter = filter
                )
                if (isActive && searchQuery.value.trim().equals(query.trim(), ignoreCase = true)) {
                    _globalSearchResults.value = results
                    val suggestions = GlobalMusicSearchEngine.fetchAutocompleteSuggestions(query, results)
                    if (isActive) {
                        _searchSuggestions.value = suggestions
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (isActive) {
                    _isGlobalSearching.value = false
                }
            }
        }
    }

    fun requestDownloadTrack(track: AudioTrack) {
        _pendingDownloadTrack.value = track
    }

    fun dismissDownloadDialog() {
        _pendingDownloadTrack.value = null
    }

    fun downloadTrack(
        track: AudioTrack,
        qualityOption: DownloadQualityOption = DownloadQualityOption.HI_RES_LOSSLESS
    ) {
        _pendingDownloadTrack.value = null
        downloadManager.downloadTrack(
            track = track,
            backend = _selectedStreamBackend.value,
            qualityOption = qualityOption,
            onSuccess = { downloaded ->
                if (_playbackState.value.currentTrack?.id == downloaded.id) {
                    _playbackState.value = _playbackState.value.copy(currentTrack = downloaded)
                }
            }
        )
    }

    fun deleteDownloadedTrack(trackId: String) {
        downloadManager.deleteDownloadedTrack(trackId)
    }

    fun isTrackDownloaded(trackId: String): Boolean {
        return downloadManager.isDownloaded(trackId)
    }

    private fun ensureServiceStarted() {
        try {
            val context = getApplication<Application>().applicationContext
            val intent = Intent(context, FlowPlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun playTrack(track: AudioTrack, queue: List<AudioTrack> = emptyList()) {
        ensureServiceStarted()

        val isSingleSelection = queue.isEmpty() || queue.size == 1
        isRadioMode = isSingleSelection

        val baseQueue = if (isSingleSelection) listOf(track) else queue
        val newQueue = if (baseQueue.any { it.id == track.id }) {
            baseQueue
        } else {
            listOf(track) + baseQueue
        }

        // Immediately reflect stopped/reset state while engine halts previous stream and prepares new URI.
        // Do NOT update currentTrack or mini-player state until audioEngine confirms STATE_READY for the new track URL.
        _playbackState.value = _playbackState.value.copy(
            isPlaying = false,
            currentPositionMs = 0L,
            queue = newQueue,
            queueIndex = 0
        )

        // Delegate atomic stop -> reset -> resolve highest bitrate -> STATE_READY -> emit UI state -> play
        audioEngine.playTrack(track, newQueue, _selectedStreamBackend.value)

        // Smart Dynamic Track Radio Generation:
        // When played as a standalone seed track from search, do NOT feed the remaining search results.
        // Instead, asynchronously populate "Up Next" with 15-20 contextually related songs
        // (similar artists, tempo, genre) — strictly excluding duplicate titles or remixes of the seed track!
        if (isSingleSelection) {
            radioJob?.cancel()
            radioJob = viewModelScope.launch {
                try {
                    val similarTracks = TrackRadioRepository.generateTrackRadio(
                        seedTrack = track,
                        limit = 20,
                        excludedTrackIds = setOf(track.id)
                    )
                    if (isActive && similarTracks.isNotEmpty()) {
                        val currentQ = _playbackState.value.queue
                        val existingIds = currentQ.map { it.id }.toSet()
                        val filteredNew = similarTracks.filter { it.id !in existingIds }
                        val expandedQueue = currentQ + filteredNew
                        _playbackState.value = _playbackState.value.copy(queue = expandedQueue)
                        audioEngine.updateQueue(expandedQueue)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    /**
     * Infinite Auto-Play Radio:
     * When queue has <= 3 tracks left in Radio mode, automatically fetch the next batch
     * of contextually related tracks to maintain infinite playback without repetitive loops.
     */
    fun fetchNextRadioBatch() {
        if (!isRadioMode || isFetchingRadioBatch) return
        val currentQueue = _playbackState.value.queue
        if (currentQueue.isEmpty()) return
        val seed = _playbackState.value.currentTrack ?: currentQueue.last()

        isFetchingRadioBatch = true
        viewModelScope.launch {
            try {
                val existingIds = currentQueue.map { it.id }.toSet()
                val nextBatch = TrackRadioRepository.generateTrackRadio(
                    seedTrack = seed,
                    limit = 15,
                    excludedTrackIds = existingIds
                )
                if (isActive && nextBatch.isNotEmpty()) {
                    val freshItems = nextBatch.filter { it.id !in existingIds }
                    if (freshItems.isNotEmpty()) {
                        val expanded = _playbackState.value.queue + freshItems
                        _playbackState.value = _playbackState.value.copy(queue = expanded)
                        audioEngine.updateQueue(expanded)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isFetchingRadioBatch = false
            }
        }
    }

    fun togglePlayPause() {
        val current = _playbackState.value
        val track = current.currentTrack
        if (track == null && current.queue.isNotEmpty()) {
            playTrack(current.queue.first(), current.queue)
        } else if (track != null && audioEngine.exoPlayer.mediaItemCount == 0) {
            // Hydrated lastPlayedTrack from storage: load and play it on first tap
            playTrack(track, current.queue)
        } else {
            ensureServiceStarted()
            // Immediate reactive UI update for zero tap lag
            val nextPlayState = !current.isPlaying
            _playbackState.value = _playbackState.value.copy(isPlaying = nextPlayState)
            audioEngine.togglePlayPause()
        }
    }

    private var currentLyricsTrackId: String? = null

    fun fetchLyricsForTrack(track: AudioTrack) {
        if (currentLyricsTrackId == track.id && lyricsJob?.isActive == true) {
            return
        }
        currentLyricsTrackId = track.id
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            try {
                val lyrics = com.example.network.LyricsRepository.getSyncedLyrics(
                    trackTitle = track.title,
                    artistName = track.artist,
                    albumName = track.album,
                    durationSeconds = track.durationMs / 1000L
                )
                _activeLyrics.value = lyrics
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun cycleLyricsDisplayMode() {
        val modes = LyricsDisplayMode.values()
        val nextIdx = (modes.indexOf(_lyricsDisplayMode.value) + 1) % modes.size
        _lyricsDisplayMode.value = modes[nextIdx]
    }

    fun setLyricsDisplayMode(mode: LyricsDisplayMode) {
        _lyricsDisplayMode.value = mode
    }

    fun cycleBadgeDisplayMode() {
        val modes = BadgeDisplayMode.values()
        val nextIdx = (modes.indexOf(_badgeDisplayMode.value) + 1) % modes.size
        _badgeDisplayMode.value = modes[nextIdx]
    }

    fun setBadgeDisplayMode(mode: BadgeDisplayMode) {
        _badgeDisplayMode.value = mode
    }

    fun setStreamBackend(backend: StreamBackend) {
        _selectedStreamBackend.value = backend
        val curr = _playbackState.value.currentTrack
        if (curr != null) {
            audioEngine.playTrack(curr, _playbackState.value.queue, backend)
        }
    }

    fun cycleStreamCandidate() {
        audioEngine.cycleStreamCandidate()
    }

    fun selectStreamCandidate(candidate: ResolvedStreamCandidate) {
        audioEngine.selectStreamCandidate(candidate)
    }

    fun playNext() {
        val current = _playbackState.value
        if (current.queue.isEmpty()) return

        audioEngine.playNext()
    }

    fun playPrevious() {
        val current = _playbackState.value
        if (current.queue.isEmpty()) return

        audioEngine.playPrevious()
    }

    fun seekTo(positionMs: Long) {
        audioEngine.seekTo(positionMs)
        _playbackState.value = _playbackState.value.copy(currentPositionMs = positionMs)
    }

    fun toggleShuffle() {
        val current = _playbackState.value
        val newShuffle = !current.isShuffle
        _playbackState.value = current.copy(isShuffle = newShuffle)
        audioEngine.setShuffleMode(newShuffle)
    }

    fun cycleRepeatMode() {
        val current = _playbackState.value
        val nextMode = when (current.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        _playbackState.value = current.copy(repeatMode = nextMode)
        audioEngine.setRepeatMode(nextMode)
    }

    fun toggleGapless() {
        val current = _playbackState.value
        val newGapless = !current.gaplessEnabled
        _playbackState.value = current.copy(gaplessEnabled = newGapless)
        audioEngine.setGaplessEnabled(newGapless)
    }

    /**
     * Deezer-style Flow: Instant infinite smart music stream
     */
    fun startFlow(mood: FlowMood = _selectedFlowMood.value) {
        _selectedFlowMood.value = mood
        val mix = repository.generateFlowMix(mood)
        if (mix.isNotEmpty()) {
            playTrack(mix.first(), mix)
            _isNowPlayingExpanded.value = true
        }
    }

    fun selectEqualizerPreset(preset: EqualizerPreset) {
        _activePreset.value = preset
        audioEngine.setEqualizerPreset(preset)
    }

    fun updateEqualizerBand(bandIndex: Int, newGain: Float) {
        val current = _activePreset.value
        val newGains = current.bandGains.toMutableList()
        if (bandIndex in newGains.indices) {
            newGains[bandIndex] = newGain
            val updated = current.copy(
                name = "Custom Tuning",
                bandGains = newGains,
                isCustom = true
            )
            _activePreset.value = updated
            audioEngine.setEqualizerPreset(updated)
        }
    }

    fun updateBassBoost(boost: Float) {
        val updated = _activePreset.value.copy(bassBoost = boost.coerceIn(0f, 1f))
        _activePreset.value = updated
        audioEngine.setEqualizerPreset(updated)
    }

    fun updateVirtualizer(virtualizer: Float) {
        val updated = _activePreset.value.copy(virtualizer = virtualizer.coerceIn(0f, 1f))
        _activePreset.value = updated
        audioEngine.setEqualizerPreset(updated)
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _playbackState.value = _playbackState.value.copy(sleepTimerSecondsRemaining = null)
            audioEngine.setMasterVolume(1.0f)
            return
        }

        val totalSeconds = minutes * 60
        _playbackState.value = _playbackState.value.copy(sleepTimerSecondsRemaining = totalSeconds)

        sleepTimerJob = viewModelScope.launch {
            var remaining = totalSeconds
            while (isActive && remaining > 0) {
                delay(1000L)
                remaining--
                _playbackState.value = _playbackState.value.copy(sleepTimerSecondsRemaining = remaining)

                // Fade out in last 30 seconds for gentle audiophile sleep
                if (remaining <= 30) {
                    val fadeVol = (remaining / 30f).coerceIn(0f, 1f)
                    audioEngine.setMasterVolume(fadeVol)
                }
            }

            // Time's up: pause playback
            audioEngine.togglePlayPause()
            audioEngine.setMasterVolume(1.0f)
            _playbackState.value = _playbackState.value.copy(
                isPlaying = false,
                sleepTimerSecondsRemaining = null
            )
        }
    }

    fun importLocalAudioUri(uri: Uri) {
        val track = repository.importLocalAudio(uri)
        if (track != null) {
            playTrack(track, repository.tracks.value)
        }
    }

    fun toggleFavorite(trackId: String) {
        repository.toggleFavorite(trackId)
        val currentTrack = _playbackState.value.currentTrack
        if (currentTrack?.id == trackId) {
            _playbackState.value = _playbackState.value.copy(
                currentTrack = currentTrack.copy(isFavorite = !currentTrack.isFavorite)
            )
        }
    }

    fun setNowPlayingExpanded(expanded: Boolean) {
        _isNowPlayingExpanded.value = expanded
    }

    fun toggleLyrics() {
        _showLyrics.value = !_showLyrics.value
    }

    fun toggleQueue() {
        _showQueue.value = !_showQueue.value
    }

    fun setShowSleepTimerDialog(show: Boolean) {
        _showSleepTimerDialog.value = show
    }

    fun setShowSyncDialog(show: Boolean) {
        _showSyncDialog.value = show
    }

    fun setShowShareDialog(show: Boolean) {
        _showShareDialog.value = show
    }

    fun setShowCreatePlaylistDialog(show: Boolean) {
        _showCreatePlaylistDialog.value = show
    }

    override fun onCleared() {
        super.onCleared()
        if (!audioEngine.exoPlayer.isPlaying) {
            audioEngine.release()
        }
        sleepTimerJob?.cancel()
    }
}
