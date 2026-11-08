package com.example.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.ExoPlayerAudioEngine
import com.example.data.MusicRepository
import com.example.download.SongDownloadManager
import com.example.model.AudioFormat
import com.example.model.AudioQualityInfo
import com.example.model.AudioTrack
import com.example.model.BadgeDisplayMode
import com.example.model.EqualizerPreset
import com.example.model.FlowMood
import com.example.model.KaraokeLyricLine
import com.example.model.LyricsDisplayMode
import com.example.model.PlaybackState
import com.example.model.RepeatMode
import com.example.model.StreamBackend
import com.example.network.DownloadQualityOption
import com.example.network.GlobalMusicSearchEngine
import com.example.network.LyricsRepository
import com.example.network.ResolvedStreamCandidate
import com.example.network.TopResultItem
import com.example.network.TrackRadioRepository
import com.example.service.FlowPlaybackService
import com.example.ui.viewmodel.SearchUiState
import com.example.util.LyricsSyncState
import com.example.util.StateDrivenLyricsParser
import kotlinx.coroutines.CancellationException
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

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    val repository = MusicRepository(application.applicationContext)
    val audioEngine = ExoPlayerAudioEngine.getInstance(application.applicationContext)
    val downloadManager = SongDownloadManager.getInstance(application.applicationContext, repository)
    private val searchEngine = GlobalMusicSearchEngine()

    // Download states
    val downloadProgress: StateFlow<Map<String, Float>> = downloadManager.downloadProgress
    val downloadedTracks: StateFlow<List<AudioTrack>> = repository.downloadedTracks

    // Track pending quality selection before downloading
    private val _pendingDownloadTrack = MutableStateFlow<AudioTrack?>(null)
    val pendingDownloadTrack: StateFlow<AudioTrack?> = _pendingDownloadTrack.asStateFlow()

    // Playback state
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    // Dedicated 50ms high-frequency playback position StateFlow for lock-step lyrics synchronization
    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    // Scroll reset counter incremented every time currentTrack changes so Lyrics UI resets scroll offset to 0
    private val _lyricsScrollResetTrigger = MutableStateFlow(0L)
    val lyricsScrollResetTrigger: StateFlow<Long> = _lyricsScrollResetTrigger.asStateFlow()

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

    // Step 3.2: State-driven synced lyrics engine with +200ms vocal lead compensation and scroll reset trigger
    val lyricsSyncState: StateFlow<LyricsSyncState> = combine(
        _activeLyrics,
        _currentPositionMs,
        _lyricsScrollResetTrigger
    ) { lyricsList, posMs, resetTrigger ->
        val synced = StateDrivenLyricsParser.sync(lyricsList, posMs)
        val targetPosition = posMs + 200L
        val activeLineIndex = if (lyricsList.isEmpty()) {
            -1
        } else {
            lyricsList.indexOfLast { it.startTimeMs <= targetPosition }.coerceAtLeast(0)
        }
        synced.copy(
            activeLineIndex = activeLineIndex,
            activeLine = if (activeLineIndex in lyricsList.indices) lyricsList[activeLineIndex] else null,
            scrollResetTrigger = resetTrigger
        )
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

    // Real candidate stream switching
    val activeStreamCandidate: StateFlow<ResolvedStreamCandidate?> = audioEngine.activeStreamCandidate
    val availableStreamCandidates: StateFlow<List<ResolvedStreamCandidate>> = audioEngine.availableStreamCandidates

    private var lyricsJob: Job? = null
    private var lyricsPositionPollerJob: Job? = null
    private var lastObservedTrackId: String? = null

    // Search filters
    val searchQuery = MutableStateFlow("")
    val filterGenre = MutableStateFlow<String?>(null)
    val filterFormat = MutableStateFlow<AudioFormat?>(null)
    val filterYear = MutableStateFlow<Int?>(null)

    // Structured Search UI state
    private val _searchUiState = MutableStateFlow(SearchUiState())
    val searchUiState: StateFlow<SearchUiState> = _searchUiState.asStateFlow()

    // Global Music Search Engine states
    private val _globalSearchResults = MutableStateFlow<List<AudioTrack>>(emptyList())
    val globalSearchResults: StateFlow<List<AudioTrack>> = _globalSearchResults.asStateFlow()

    private val _isGlobalSearching = MutableStateFlow(false)
    val isGlobalSearching: StateFlow<Boolean> = _isGlobalSearching.asStateFlow()

    private val _globalTrendingTracks = MutableStateFlow<List<AudioTrack>>(emptyList())
    val globalTrendingTracks: StateFlow<List<AudioTrack>> = _globalTrendingTracks.asStateFlow()

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
                    track.album.contains(trimmedQuery, ignoreCase = true)
            }
            val deduplicatedGlobal = globalTracks.filter { gTrack ->
                matchingLocal.none { it.id == gTrack.id }
            }
            deduplicatedGlobal + matchingLocal
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

    private var sleepTimerJob: Job? = null

    init {
        // 1. Subscribe to SSOT Track Transition state
        viewModelScope.launch {
            audioEngine.currentTrackState.collectLatest { track ->
                if (track != null) {
                    val trackChanged = lastObservedTrackId != track.id
                    if (trackChanged) {
                        lastObservedTrackId = track.id
                        // Step 3.3: Whenever currentTrack changes, immediately clear active lyrics and reset scroll offset to 0
                        resetLyricsStateForNewTrack()
                    }

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
                        currentPositionMs = if (trackChanged) 0L else _playbackState.value.currentPositionMs,
                        durationMs = if (track.durationMs > 0) track.durationMs else _playbackState.value.durationMs
                    )
                    repository.saveLastPlayedTrack(track)
                    fetchLyricsForTrack(track)
                    repository.recordTrackPlayed(track, 10L)
                }
            }
        }

        // 2. Subscribe to SSOT Play/Pause state and start/stop 50ms high-precision lyrics position poller
        viewModelScope.launch {
            audioEngine.isPlayingState.collectLatest { isPlaying ->
                _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
                if (isPlaying) {
                    startHighFrequencyLyricsPoller()
                } else {
                    lyricsPositionPollerJob?.cancel()
                }
            }
        }

        // 3. Subscribe to SSOT Playback Position and Duration
        viewModelScope.launch {
            audioEngine.currentPositionState.collectLatest { pos ->
                _currentPositionMs.value = pos
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

        // Hydrate MiniPlayer from persisted lastPlayedTrack (or active engine track / history)
        val initialTracks = repository.tracks.value
        val restoredTrack = audioEngine.currentPlayingTrack
            ?: repository.loadLastPlayedTrack()
            ?: repository.history.value.firstOrNull()

        if (restoredTrack != null) {
            lastObservedTrackId = restoredTrack.id
            val restoredQueue = if (initialTracks.any { it.id == restoredTrack.id }) {
                initialTracks.map { if (it.id == restoredTrack.id) restoredTrack else it }
            } else {
                listOf(restoredTrack) + initialTracks
            }
            val restoredIndex = restoredQueue.indexOfFirst { it.id == restoredTrack.id }.coerceAtLeast(0)
            val isCurrentlyPlaying = audioEngine.exoPlayer.isPlaying
            val initialPos = if (isCurrentlyPlaying) audioEngine.exoPlayer.currentPosition.coerceAtLeast(0L) else 0L

            _currentPositionMs.value = initialPos
            _playbackState.value = _playbackState.value.copy(
                queue = restoredQueue,
                queueIndex = restoredIndex,
                currentTrack = restoredTrack,
                isPlaying = isCurrentlyPlaying,
                currentPositionMs = initialPos,
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
            if (isCurrentlyPlaying) {
                startHighFrequencyLyricsPoller()
            }
        } else {
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
                searchEngine.searchStructured("Global Top Hits").onSuccess { shelves ->
                    val topSong = (shelves.topResult as? TopResultItem.SongItem)?.track
                    val combined = if (topSong != null) listOf(topSong) + shelves.songs else shelves.songs
                    _globalTrendingTracks.value = combined.distinctBy { it.id }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Step 3.1: High-Frequency Polling (50ms) while playing so that lyrics highlighting keeps lock-step with vocals.
     */
    private fun startHighFrequencyLyricsPoller() {
        lyricsPositionPollerJob?.cancel()
        lyricsPositionPollerJob = viewModelScope.launch {
            while (isActive && _playbackState.value.isPlaying) {
                val pos = audioEngine.exoPlayer.currentPosition.coerceAtLeast(0L)
                _currentPositionMs.value = pos
                _playbackState.value = _playbackState.value.copy(currentPositionMs = pos)
                delay(50L)
            }
        }
    }

    /**
     * Step 3.3: State Reset — Immediately clears active lyrics, resets position to 0, and triggers scroll offset reset to 0.
     */
    private fun resetLyricsStateForNewTrack() {
        lyricsJob?.cancel()
        LyricsRepository.cancelActiveLyricsRequests()
        currentLyricsTrackId = null
        _activeLyrics.value = emptyList()
        _currentPositionMs.value = 0L
        _lyricsScrollResetTrigger.value = _lyricsScrollResetTrigger.value + 1L
    }

    /**
     * Debounced Search State Manager:
     * - Cancels any in-flight OkHttp API calls via suspendCancellableCoroutine when searchJob is cancelled.
     * - Updates structured SearchUiState with Result<SearchResultShelves>.
     */
    fun onSearchQueryChanged(newQuery: String) {
        searchQuery.value = newQuery
        searchJob?.cancel()

        val trimmed = newQuery.trim()
        if (trimmed.isEmpty()) {
            _globalSearchResults.value = emptyList()
            _isGlobalSearching.value = false
            _searchUiState.value = SearchUiState()
            return
        }

        _isGlobalSearching.value = true
        _searchUiState.value = _searchUiState.value.copy(isLoading = true, errorMessage = null)
        executeSearch(trimmed)
    }

    fun retrySearch() {
        val trimmed = searchQuery.value.trim()
        if (trimmed.isNotEmpty()) {
            executeSearch(trimmed)
        }
    }

    private fun executeSearch(query: String) {
        searchJob?.cancel()

        searchJob = viewModelScope.launch {
            _isGlobalSearching.value = true
            _searchUiState.value = _searchUiState.value.copy(isLoading = true, errorMessage = null)
            delay(280L)
            if (!isActive) return@launch
            try {
                val result = searchEngine.searchStructured(query)
                result.fold(
                    onSuccess = { shelves ->
                        val topSong = (shelves.topResult as? TopResultItem.SongItem)?.track
                        val allSongs = if (topSong != null) listOf(topSong) + shelves.songs else shelves.songs
                        _globalSearchResults.value = allSongs.distinctBy { it.id }
                        _searchUiState.value = SearchUiState(
                            isLoading = false,
                            topResult = shelves.topResult,
                            songs = shelves.songs,
                            artists = shelves.artists,
                            albums = shelves.albums,
                            playlists = shelves.playlists,
                            errorMessage = null
                        )
                    },
                    onFailure = { exception ->
                        if (exception is CancellationException) throw exception
                        _searchUiState.value = _searchUiState.value.copy(
                            isLoading = false,
                            errorMessage = exception.localizedMessage ?: "Failed to load results"
                        )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _searchUiState.value = _searchUiState.value.copy(
                    isLoading = false,
                    errorMessage = e.localizedMessage ?: "Failed to load results"
                )
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

        // Step 3.3: Immediately clear active lyrics and reset scroll offset to 0 on track change
        lastObservedTrackId = track.id
        resetLyricsStateForNewTrack()

        // Immediately update currentTrack in MusicViewModel (<50ms) so the mini-player and NowPlaying screen
        // reflect the newly tapped song right away while ExoPlayer buffers the verified stream.
        val targetIdx = newQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        _playbackState.value = _playbackState.value.copy(
            currentTrack = track,
            isPlaying = false,
            currentPositionMs = 0L,
            durationMs = track.durationMs,
            queue = newQueue,
            queueIndex = targetIdx
        )
        repository.saveLastPlayedTrack(track)
        fetchLyricsForTrack(track)

        // Delegate atomic stop -> reset -> resolve highest bitrate via MultiProviderStreamResolver -> play
        audioEngine.playTrack(track, newQueue, _selectedStreamBackend.value)

        // Smart Dynamic Track Radio Generation
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
            playTrack(track, current.queue)
        } else {
            ensureServiceStarted()
            val nextPlayState = !current.isPlaying
            _playbackState.value = _playbackState.value.copy(isPlaying = nextPlayState)
            audioEngine.togglePlayPause()
        }
    }

    private var currentLyricsTrackId: String? = null

    fun fetchLyricsForTrack(track: AudioTrack) {
        val targetId = track.id
        if (currentLyricsTrackId == targetId && (lyricsJob?.isActive == true || _activeLyrics.value.isNotEmpty())) {
            return
        }
        currentLyricsTrackId = targetId
        lyricsJob?.cancel()
        LyricsRepository.cancelActiveLyricsRequests()
        _activeLyrics.value = emptyList()
        _lyricsScrollResetTrigger.value = _lyricsScrollResetTrigger.value + 1L

        lyricsJob = viewModelScope.launch {
            try {
                val lyrics = LyricsRepository.getSyncedLyrics(
                    trackTitle = track.title,
                    artistName = track.artist,
                    albumName = track.album,
                    durationSeconds = (track.durationMs / 1000L).coerceAtLeast(0L)
                )
                // Only apply if this track is still the active bound track
                if (isActive && currentLyricsTrackId == targetId) {
                    _activeLyrics.value = lyrics
                    _lyricsScrollResetTrigger.value = _lyricsScrollResetTrigger.value + 1L
                }
            } catch (e: CancellationException) {
                throw e
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
        _currentPositionMs.value = positionMs
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

                if (remaining <= 30) {
                    val fadeVol = (remaining / 30f).coerceIn(0f, 1f)
                    audioEngine.setMasterVolume(fadeVol)
                }
            }

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
        lyricsPositionPollerJob?.cancel()
        if (!audioEngine.exoPlayer.isPlaying) {
            audioEngine.release()
        }
        sleepTimerJob?.cancel()
    }
}
