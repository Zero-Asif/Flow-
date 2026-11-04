package com.example

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.components.CreatePlaylistDialog
import com.example.ui.components.DownloadQualityDialog
import com.example.ui.components.MiniPlayer
import com.example.ui.components.NowPlayingSheet
import com.example.ui.components.ShareTrackDialog
import com.example.ui.components.SleepTimerDialog
import com.example.ui.components.SyncBackupDialog
import com.example.ui.screens.AnalyticsScreen
import com.example.ui.screens.EqualizerScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.theme.DeezerPinkAccent
import com.example.ui.theme.DeezerPurplePrimary
import com.example.ui.theme.FlowMusicTheme
import com.example.ui.theme.SurfaceDarkElevated
import com.example.ui.theme.SurfaceDarkVoid
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryDark
import com.example.viewmodel.MusicViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFormat(android.graphics.PixelFormat.RGBA_8888)
        enableEdgeToEdge()
        setContent {
            FlowMusicTheme {
                MainAppContent()
            }
        }
    }
}

@Composable
fun MainAppContent(viewModel: MusicViewModel = viewModel()) {
    val context = LocalContext.current
    var selectedNavIndex by remember { mutableIntStateOf(0) }

    // Request notification permission on Android 13+ (API 33+) for System Media Notification
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Permission granted or dismissed */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // If on subscreen, system back navigates to Home
    BackHandler(enabled = selectedNavIndex != 0) {
        selectedNavIndex = 0
    }

    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val activePreset by viewModel.activePreset.collectAsStateWithLifecycle()
    val isNowPlayingExpanded by viewModel.isNowPlayingExpanded.collectAsStateWithLifecycle()
    val showLyrics by viewModel.showLyrics.collectAsStateWithLifecycle()
    val showQueue by viewModel.showQueue.collectAsStateWithLifecycle()
    val showSleepTimerDialog by viewModel.showSleepTimerDialog.collectAsStateWithLifecycle()
    val showSyncDialog by viewModel.showSyncDialog.collectAsStateWithLifecycle()
    val showShareDialog by viewModel.showShareDialog.collectAsStateWithLifecycle()
    val showCreatePlaylistDialog by viewModel.showCreatePlaylistDialog.collectAsStateWithLifecycle()
    val selectedFlowMood by viewModel.selectedFlowMood.collectAsStateWithLifecycle()

    val tracks by viewModel.repository.tracks.collectAsStateWithLifecycle()
    val playlists by viewModel.repository.playlists.collectAsStateWithLifecycle()
    val favorites by viewModel.repository.favorites.collectAsStateWithLifecycle()
    val history by viewModel.repository.history.collectAsStateWithLifecycle()
    val stats by viewModel.repository.stats.collectAsStateWithLifecycle()

    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val filterGenre by viewModel.filterGenre.collectAsStateWithLifecycle()
    val filterFormat by viewModel.filterFormat.collectAsStateWithLifecycle()
    val filterYear by viewModel.filterYear.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val searchSuggestions by viewModel.searchSuggestions.collectAsStateWithLifecycle()
    val categorizedSearchResults by viewModel.categorizedSearchResults.collectAsStateWithLifecycle()
    val isGlobalSearching by viewModel.isGlobalSearching.collectAsStateWithLifecycle()
    val globalTrendingTracks by viewModel.globalTrendingTracks.collectAsStateWithLifecycle()

    val activeLyrics by viewModel.activeLyrics.collectAsStateWithLifecycle()
    val lyricsSyncState by viewModel.lyricsSyncState.collectAsStateWithLifecycle()
    val lyricsDisplayMode by viewModel.lyricsDisplayMode.collectAsStateWithLifecycle()
    val audioQualityInfo by viewModel.audioQualityInfo.collectAsStateWithLifecycle()
    val badgeDisplayMode by viewModel.badgeDisplayMode.collectAsStateWithLifecycle()
    val selectedStreamBackend by viewModel.selectedStreamBackend.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val downloadedTracks by viewModel.downloadedTracks.collectAsStateWithLifecycle()
    val pendingDownloadTrack by viewModel.pendingDownloadTrack.collectAsStateWithLifecycle()
    val selectedMusicSourceFilter by viewModel.selectedMusicSourceFilter.collectAsStateWithLifecycle()
    val playbackError by viewModel.playbackError.collectAsStateWithLifecycle()
    val activeStreamCandidate by viewModel.activeStreamCandidate.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().background(SurfaceDarkVoid)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = SurfaceDarkVoid,
            bottomBar = {
                Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                    // Persistent Mini Player above Bottom Bar
                    if (playbackState.currentTrack != null) {
                        MiniPlayer(
                            currentTrack = playbackState.currentTrack,
                            isPlaying = playbackState.isPlaying,
                            currentPositionMs = playbackState.currentPositionMs,
                            durationMs = playbackState.durationMs,
                            onTogglePlay = { viewModel.togglePlayPause() },
                            onNext = { viewModel.playNext() },
                            onToggleFavorite = { viewModel.toggleFavorite(it) },
                            onExpand = { viewModel.setNowPlayingExpanded(true) }
                        )
                    }

                    // Navigation Bar
                    NavigationBar(
                        containerColor = SurfaceDarkElevated,
                        tonalElevation = 8.dp
                    ) {
                        val navItems = listOf(
                            Triple("Home", Icons.Default.Home, "nav_home"),
                            Triple("Search", Icons.Default.Search, "nav_search"),
                            Triple("Library", Icons.Default.LibraryMusic, "nav_library"),
                            Triple("Equalizer", Icons.Default.Equalizer, "nav_equalizer"),
                            Triple("Stats", Icons.Default.Insights, "nav_analytics")
                        )

                        navItems.forEachIndexed { index, (label, icon, tag) ->
                            NavigationBarItem(
                                selected = selectedNavIndex == index,
                                onClick = { selectedNavIndex = index },
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label, fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = DeezerPinkAccent,
                                    selectedTextColor = DeezerPinkAccent,
                                    unselectedIconColor = TextSecondaryDark,
                                    unselectedTextColor = TextSecondaryDark,
                                    indicatorColor = DeezerPurplePrimary.copy(alpha = 0.25f)
                                ),
                                modifier = Modifier.testTag(tag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .statusBarsPadding()
            ) {
                when (selectedNavIndex) {
                    0 -> HomeScreen(
                        tracks = tracks,
                        playlists = playlists,
                        recentTracks = history,
                        downloadedTracks = downloadedTracks,
                        selectedMood = selectedFlowMood,
                        onSelectMood = { viewModel.startFlow(it) },
                        onStartFlow = { viewModel.startFlow() },
                        onPlayTrack = { track, q -> viewModel.playTrack(track, q) },
                        onToggleFavorite = { viewModel.toggleFavorite(it) },
                        onOpenSync = { viewModel.setShowSyncDialog(true) },
                        onOpenEqualizer = { selectedNavIndex = 3 }
                    )
                    1 -> SearchScreen(
                        query = searchQuery,
                        onQueryChange = { viewModel.onSearchQueryChanged(it) },
                        selectedGenre = filterGenre,
                        onSelectGenre = { viewModel.filterGenre.value = it },
                        selectedFormat = filterFormat,
                        onSelectFormat = { viewModel.filterFormat.value = it },
                        selectedYear = filterYear,
                        onSelectYear = { viewModel.filterYear.value = it },
                        selectedSourceFilter = selectedMusicSourceFilter,
                        onSelectSourceFilter = { viewModel.setMusicSourceFilter(it) },
                        downloadProgress = downloadProgress,
                        downloadedTrackIds = downloadedTracks.map { it.id }.toSet(),
                        onDownloadTrack = { viewModel.requestDownloadTrack(it) },
                        searchResults = searchResults,
                        searchSuggestions = searchSuggestions,
                        categorizedResults = categorizedSearchResults,
                        isGlobalSearching = isGlobalSearching,
                        globalTrendingTracks = globalTrendingTracks,
                        onPlayTrack = { track, q -> viewModel.playTrack(track, q) },
                        onToggleFavorite = { viewModel.toggleFavorite(it) }
                    )
                    2 -> LibraryScreen(
                        tracks = tracks,
                        downloadedTracks = downloadedTracks,
                        playlists = playlists,
                        favorites = favorites,
                        onPlayTrack = { track, q -> viewModel.playTrack(track, q) },
                        onToggleFavorite = { viewModel.toggleFavorite(it) },
                        onDeleteDownloadedTrack = { viewModel.deleteDownloadedTrack(it) },
                        onImportAudio = { viewModel.importLocalAudioUri(it) },
                        onCreatePlaylist = { viewModel.setShowCreatePlaylistDialog(true) }
                    )
                    3 -> EqualizerScreen(
                        activePreset = activePreset,
                        gaplessEnabled = playbackState.gaplessEnabled,
                        onSelectPreset = { viewModel.selectEqualizerPreset(it) },
                        onBandChange = { band, gain -> viewModel.updateEqualizerBand(band, gain) },
                        onBassBoostChange = { viewModel.updateBassBoost(it) },
                        onVirtualizerChange = { viewModel.updateVirtualizer(it) },
                        onToggleGapless = { viewModel.toggleGapless() }
                    )
                    4 -> AnalyticsScreen(
                        stats = stats,
                        onOpenSync = { viewModel.setShowSyncDialog(true) },
                        onOpenSleepTimer = { viewModel.setShowSleepTimerDialog(true) }
                    )
                }
            }
        }

        // Full Screen Now Playing Sheet with Slide Transition
        AnimatedVisibility(
            visible = isNowPlayingExpanded,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            val currentPlayingTrack = playbackState.currentTrack
            val isCurrentDownloaded = currentPlayingTrack?.let {
                viewModel.isTrackDownloaded(it.id) || downloadedTracks.any { d -> d.id == it.id }
            } ?: false
            val currentDownloadProgress = currentPlayingTrack?.let { downloadProgress[it.id] }

            NowPlayingSheet(
                track = playbackState.currentTrack,
                isPlaying = playbackState.isPlaying,
                currentPositionMs = playbackState.currentPositionMs,
                durationMs = playbackState.durationMs,
                visualizerAmplitudes = playbackState.visualizerAmplitudes,
                isShuffle = playbackState.isShuffle,
                repeatMode = playbackState.repeatMode,
                gaplessEnabled = playbackState.gaplessEnabled,
                sleepTimerSecondsRemaining = playbackState.sleepTimerSecondsRemaining,
                queue = playbackState.queue,
                queueIndex = playbackState.queueIndex,
                showLyrics = showLyrics,
                showQueue = showQueue,
                audioQualityInfo = audioQualityInfo,
                badgeDisplayMode = badgeDisplayMode,
                streamBackend = selectedStreamBackend,
                activeStreamCandidateName = activeStreamCandidate?.providerName,
                lyrics = activeLyrics,
                lyricsSyncState = lyricsSyncState,
                lyricsDisplayMode = lyricsDisplayMode,
                onCycleBadgeMode = { viewModel.cycleBadgeDisplayMode() },
                onCycleStreamBackend = {
                    val backends = com.example.model.StreamBackend.values()
                    val nextIdx = (backends.indexOf(selectedStreamBackend) + 1) % backends.size
                    viewModel.setStreamBackend(backends[nextIdx])
                },
                onCycleStreamCandidate = { viewModel.cycleStreamCandidate() },
                onCycleLyricsMode = { viewModel.cycleLyricsDisplayMode() },
                onTogglePlay = { viewModel.togglePlayPause() },
                onNext = { viewModel.playNext() },
                onPrevious = { viewModel.playPrevious() },
                onSeekTo = { viewModel.seekTo(it) },
                onToggleShuffle = { viewModel.toggleShuffle() },
                onCycleRepeat = { viewModel.cycleRepeatMode() },
                onToggleGapless = { viewModel.toggleGapless() },
                onToggleFavorite = { viewModel.toggleFavorite(it) },
                isDownloaded = isCurrentDownloaded,
                downloadProgress = currentDownloadProgress,
                onDownloadTrack = { viewModel.requestDownloadTrack(it) },
                onToggleLyrics = { viewModel.toggleLyrics() },
                onToggleQueue = { viewModel.toggleQueue() },
                onOpenEqualizer = {
                    viewModel.setNowPlayingExpanded(false)
                    selectedNavIndex = 3
                },
                onOpenSleepTimer = { viewModel.setShowSleepTimerDialog(true) },
                onOpenShare = { viewModel.setShowShareDialog(true) },
                onSelectQueueTrack = { viewModel.playTrack(it, playbackState.queue) },
                onDismiss = { viewModel.setNowPlayingExpanded(false) }
            )
        }

        // Download Quality Selection Dialog (Dynamically reads active stream metadata & recommends Hi-Res FLAC or 320k Lossy)
        pendingDownloadTrack?.let { trackToDownload ->
            val currentActiveQuality = if (playbackState.currentTrack?.id == trackToDownload.id) {
                audioQualityInfo
            } else {
                null
            }
            DownloadQualityDialog(
                track = trackToDownload,
                activeQualityInfo = currentActiveQuality,
                onConfirmDownload = { qualityOption ->
                    viewModel.downloadTrack(trackToDownload, qualityOption)
                },
                onDismiss = { viewModel.dismissDownloadDialog() }
            )
        }

        // Sleep Timer Dialog
        if (showSleepTimerDialog) {
            SleepTimerDialog(
                currentRemainingSec = playbackState.sleepTimerSecondsRemaining,
                onSetTimer = { minutes -> viewModel.setSleepTimer(minutes) },
                onDismiss = { viewModel.setShowSleepTimerDialog(false) }
            )
        }

        // Cross-Device Sync & Backup Dialog
        if (showSyncDialog) {
            SyncBackupDialog(
                onExportJson = { viewModel.repository.exportBackupJson() },
                onImportJson = { viewModel.repository.importBackupJson(it) },
                onDismiss = { viewModel.setShowSyncDialog(false) }
            )
        }

        // Share Track Dialog
        if (showShareDialog) {
            ShareTrackDialog(
                track = playbackState.currentTrack,
                onDismiss = { viewModel.setShowShareDialog(false) }
            )
        }

        // Create Playlist Dialog
        if (showCreatePlaylistDialog) {
            CreatePlaylistDialog(
                onCreate = { name, desc ->
                    viewModel.repository.createPlaylist(name, desc, emptyList())
                },
                onDismiss = { viewModel.setShowCreatePlaylistDialog(false) }
            )
        }
    }
}
