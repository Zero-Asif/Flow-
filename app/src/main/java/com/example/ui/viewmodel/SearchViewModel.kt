package com.example.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioPlayerService
import com.example.audio.ExoPlayerAudioEngine
import com.example.model.AudioTrack
import com.example.network.AlbumResult
import com.example.network.ArtistResult
import com.example.network.GlobalMusicSearchEngine
import com.example.network.PlaylistResult
import com.example.network.TopResultItem
import com.example.service.FlowPlaybackService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val isLoading: Boolean = false,
    val topResult: TopResultItem? = null,
    val songs: List<AudioTrack> = emptyList(),
    val artists: List<ArtistResult> = emptyList(),
    val albums: List<AlbumResult> = emptyList(),
    val playlists: List<PlaylistResult> = emptyList(),
    val errorMessage: String? = null
)

class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val searchEngine = GlobalMusicSearchEngine()
    private val audioEngine = ExoPlayerAudioEngine.getInstance(application.applicationContext)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChanged(newQuery: String) {
        _searchQuery.value = newQuery
        searchJob?.cancel()

        val trimmed = newQuery.trim()
        if (trimmed.isEmpty()) {
            _uiState.value = SearchUiState()
            return
        }

        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                delay(280L)
                val result = searchEngine.searchStructured(trimmed)
                result.fold(
                    onSuccess = { shelves ->
                        _uiState.value = SearchUiState(
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
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = exception.localizedMessage ?: "Failed to load results"
                            )
                        }
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.localizedMessage ?: "Failed to load results"
                    )
                }
            }
        }
    }

    fun retrySearch() {
        val current = _searchQuery.value.trim()
        if (current.isNotEmpty()) {
            onQueryChanged(current)
        }
    }

    fun playTrack(track: AudioTrack) {
        try {
            val context = getApplication<Application>().applicationContext
            val intent = Intent(context, FlowPlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (_: Exception) {
        }
        audioEngine.playTrack(track, listOf(track))
    }
}
