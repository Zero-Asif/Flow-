package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.model.AudioTrack
import com.example.network.AlbumResult
import com.example.network.ArtistResult
import com.example.network.PlaylistResult
import com.example.network.TopResultItem
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.DeezerPinkAccent
import com.example.ui.theme.DeezerPurplePrimary
import com.example.ui.theme.HiResGold
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.SurfaceDarkCard
import com.example.ui.theme.SurfaceDarkElevated
import com.example.ui.theme.SurfaceDarkVoid
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryDark
import com.example.ui.viewmodel.SearchUiState
import com.example.ui.viewmodel.SearchViewModel

@Composable
fun SearchScreen(
    searchViewModel: SearchViewModel = viewModel(),
    onTrackClick: (AudioTrack) -> Unit = { searchViewModel.playTrack(it) },
    downloadProgress: Map<String, Float> = emptyMap(),
    downloadedTrackIds: Set<String> = emptySet(),
    onDownloadTrack: (AudioTrack) -> Unit = {},
    onToggleFavorite: (String) -> Unit = {},
    globalTrendingTracks: List<AudioTrack> = emptyList(),
    modifier: Modifier = Modifier
) {
    val query by searchViewModel.searchQuery.collectAsStateWithLifecycle()
    val uiState by searchViewModel.uiState.collectAsStateWithLifecycle()

    SearchScreen(
        query = query,
        onQueryChange = { searchViewModel.onQueryChanged(it) },
        uiState = uiState,
        onTrackClick = onTrackClick,
        onRetry = { searchViewModel.retrySearch() },
        downloadProgress = downloadProgress,
        downloadedTrackIds = downloadedTrackIds,
        onDownloadTrack = onDownloadTrack,
        onToggleFavorite = onToggleFavorite,
        globalTrendingTracks = globalTrendingTracks,
        modifier = modifier
    )
}

@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    uiState: SearchUiState,
    onTrackClick: (AudioTrack) -> Unit,
    onRetry: () -> Unit = {},
    downloadProgress: Map<String, Float> = emptyMap(),
    downloadedTrackIds: Set<String> = emptySet(),
    onDownloadTrack: (AudioTrack) -> Unit = {},
    onToggleFavorite: (String) -> Unit = {},
    globalTrendingTracks: List<AudioTrack> = emptyList(),
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceDarkVoid)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // Search Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Global Music Search",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.ExtraBold,
                        color = TextPrimaryDark
                    )
                )
                Text(
                    text = "YouTube Music • Structured Shelves • Hi-Res Audio",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondaryDark)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Search Input Bar
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = {
                Text(
                    text = "Search songs, artists, albums, playlists...",
                    color = TextSecondaryDark.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = CyberCyan
                )
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .padding(end = 4.dp),
                            strokeWidth = 2.dp,
                            color = CyberCyan
                        )
                    }
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = { onQueryChange("") },
                            modifier = Modifier.testTag("clear_search_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear search",
                                tint = TextSecondaryDark
                            )
                        }
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    focusManager.clearFocus()
                }
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CyberCyan,
                unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
                focusedContainerColor = SurfaceDarkElevated,
                unfocusedContainerColor = SurfaceDarkElevated,
                focusedTextColor = TextPrimaryDark,
                unfocusedTextColor = TextPrimaryDark,
                cursorColor = CyberCyan
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("search_input_field")
        )

        // Non-blocking loading indicator bar
        if (uiState.isLoading) {
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                color = CyberCyan,
                trackColor = SurfaceDarkElevated,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .testTag("search_loading_indicator")
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Main Structured Search Content
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("search_results_list"),
            contentPadding = PaddingValues(bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Error State Banner (with Retry prompt)
            if (uiState.errorMessage != null && query.isNotBlank()) {
                item(key = "error_banner") {
                    ErrorPromptCard(
                        errorMessage = uiState.errorMessage,
                        onRetry = {
                            focusManager.clearFocus()
                            onRetry()
                        }
                    )
                }
            }

            // 2. Pinned Top Result Shelf
            val topResult = uiState.topResult
            if (topResult != null && query.isNotBlank()) {
                item(key = "pinned_top_result") {
                    Column {
                        SectionHeaderTitle(
                            title = "TOP RESULT",
                            accentColor = HiResGold
                        )
                        when (topResult) {
                            is TopResultItem.SongItem -> {
                                TopSongHeroCard(
                                    track = topResult.track,
                                    isDownloaded = downloadedTrackIds.contains(topResult.track.id),
                                    downloadProgress = downloadProgress[topResult.track.id],
                                    onClick = {
                                        focusManager.clearFocus()
                                        onTrackClick(topResult.track)
                                    },
                                    onToggleFavorite = { onToggleFavorite(topResult.track.id) },
                                    onDownload = { onDownloadTrack(topResult.track) }
                                )
                            }
                            is TopResultItem.ArtistItem -> {
                                TopArtistBannerCard(
                                    artist = topResult.artist,
                                    onClick = {
                                        focusManager.clearFocus()
                                        onQueryChange(topResult.artist.name)
                                    }
                                )
                            }
                            is TopResultItem.AlbumItem -> {
                                TopAlbumBannerCard(
                                    album = topResult.album,
                                    onClick = {
                                        focusManager.clearFocus()
                                        onQueryChange("${topResult.album.title} ${topResult.album.artist}".trim())
                                    }
                                )
                            }
                            is TopResultItem.PlaylistItem -> {
                                TopPlaylistBannerCard(
                                    playlist = topResult.playlist,
                                    onClick = {
                                        focusManager.clearFocus()
                                        onQueryChange(topResult.playlist.title)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 3. Songs Shelf (Static maxLines = 1, TextOverflow.Ellipsis, no horizontal basicMarquee on unselected items)
            if (uiState.songs.isNotEmpty() && query.isNotBlank()) {
                item(key = "songs_shelf_header") {
                    SectionHeaderTitle(
                        title = "SONGS (${uiState.songs.size})",
                        accentColor = CyberCyan
                    )
                }

                items(
                    items = uiState.songs,
                    key = { track -> "song_${track.id}" }
                ) { track ->
                    SearchTrackRow(
                        track = track,
                        isDownloaded = downloadedTrackIds.contains(track.id),
                        downloadProgress = downloadProgress[track.id],
                        onClick = {
                            focusManager.clearFocus()
                            onTrackClick(track)
                        },
                        onToggleFavorite = { onToggleFavorite(track.id) },
                        onDownload = { onDownloadTrack(track) }
                    )
                }
            }

            // 4. Horizontal Artists Shelf (Circular Cards)
            if (uiState.artists.isNotEmpty() && query.isNotBlank()) {
                item(key = "artists_shelf") {
                    Column {
                        SectionHeaderTitle(
                            title = "ARTISTS",
                            accentColor = DeezerPinkAccent
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("artists_horizontal_shelf")
                        ) {
                            items(
                                items = uiState.artists,
                                key = { artist -> "artist_${artist.browseId}_${artist.name}" }
                            ) { artist ->
                                ArtistCircularCard(
                                    artist = artist,
                                    onClick = {
                                        focusManager.clearFocus()
                                        onQueryChange(artist.name)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 5. Horizontal Albums Shelf (Square Cards)
            if (uiState.albums.isNotEmpty() && query.isNotBlank()) {
                item(key = "albums_shelf") {
                    Column {
                        SectionHeaderTitle(
                            title = "ALBUMS & SINGLES",
                            accentColor = HiResGold
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("albums_horizontal_shelf")
                        ) {
                            items(
                                items = uiState.albums,
                                key = { album -> "album_${album.browseId}_${album.title}" }
                            ) { album ->
                                AlbumSquareCard(
                                    album = album,
                                    onClick = {
                                        focusManager.clearFocus()
                                        onQueryChange("${album.title} ${album.artist}".trim())
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 6. Horizontal Playlists Shelf
            if (uiState.playlists.isNotEmpty() && query.isNotBlank()) {
                item(key = "playlists_shelf") {
                    Column {
                        SectionHeaderTitle(
                            title = "FEATURED PLAYLISTS",
                            accentColor = SuccessGreen
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("playlists_horizontal_shelf")
                        ) {
                            items(
                                items = uiState.playlists,
                                key = { playlist -> "playlist_${playlist.browseId}_${playlist.title}" }
                            ) { playlist ->
                                PlaylistShelfCard(
                                    playlist = playlist,
                                    onClick = {
                                        focusManager.clearFocus()
                                        onQueryChange(playlist.title)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 7. Default Trending / Discovery Tracks when query is blank
            if (query.isBlank() && globalTrendingTracks.isNotEmpty()) {
                item(key = "trending_header") {
                    SectionHeaderTitle(
                        title = "GLOBAL TRENDING HITS",
                        accentColor = CyberCyan
                    )
                }

                items(
                    items = globalTrendingTracks,
                    key = { track -> "trend_${track.id}" }
                ) { track ->
                    SearchTrackRow(
                        track = track,
                        isDownloaded = downloadedTrackIds.contains(track.id),
                        downloadProgress = downloadProgress[track.id],
                        onClick = {
                            focusManager.clearFocus()
                            onTrackClick(track)
                        },
                        onToggleFavorite = { onToggleFavorite(track.id) },
                        onDownload = { onDownloadTrack(track) }
                    )
                }
            }

            // 8. Empty State when search completed with no items
            val hasAnyResults = uiState.topResult != null ||
                uiState.songs.isNotEmpty() ||
                uiState.artists.isNotEmpty() ||
                uiState.albums.isNotEmpty() ||
                uiState.playlists.isNotEmpty()

            if (query.isNotBlank() && !uiState.isLoading && uiState.errorMessage == null && !hasAnyResults) {
                item(key = "empty_search_state") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = TextSecondaryDark.copy(alpha = 0.5f),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No results found for \"$query\"",
                                style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondaryDark)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeaderTitle(
    title: String,
    accentColor: Color
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall.copy(
            color = accentColor,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.2.sp
        ),
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
    )
}

@Composable
private fun ErrorPromptCard(
    errorMessage: String,
    onRetry: () -> Unit
) {
    Surface(
        color = SurfaceDarkElevated,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DeezerPinkAccent.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .testTag("search_error_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = "Error",
                    tint = DeezerPinkAccent,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Search Error",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = TextPrimaryDark,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = TextSecondaryDark
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberCyan.copy(alpha = 0.2f),
                    contentColor = CyberCyan
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.testTag("search_retry_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Retry",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Retry",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

@Composable
private fun TopSongHeroCard(
    track: AudioTrack,
    isDownloaded: Boolean,
    downloadProgress: Float?,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownload: () -> Unit
) {
    val isDownloading = downloadProgress != null && downloadProgress < 1f
    val artistSubtitle = buildString {
        append(track.artist.ifBlank { "Unknown Artist" })
        if (track.album.isNotBlank()) {
            append(" • ")
            append(track.album)
        }
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        DeezerPurplePrimary.copy(alpha = 0.28f),
                        DeezerPinkAccent.copy(alpha = 0.16f),
                        SurfaceDarkCard
                    )
                )
            )
            .border(
                width = 1.dp,
                color = HiResGold.copy(alpha = 0.45f),
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.material3.ripple()
            ) {
                onClick()
            }
            .testTag("best_match_card_${track.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.Center) {
                TrackThumbnailBox(
                    track = track,
                    sizeDp = 72,
                    cornerRadiusDp = 14
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play Top Match",
                        tint = CyberCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = TextPrimaryDark,
                        fontWeight = FontWeight.ExtraBold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(3.dp))

                Text(
                    text = artistSubtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = TextSecondaryDark,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = CyberCyan.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "SONG",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = CyberCyan,
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    if (track.durationMs > 0L) {
                        Text(
                            text = track.durationFormatted,
                            style = MaterialTheme.typography.labelSmall.copy(color = TextSecondaryDark)
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (track.isFavorite) DeezerPinkAccent else TextSecondaryDark
                    )
                }

                IconButton(
                    onClick = onDownload,
                    modifier = Modifier.size(36.dp)
                ) {
                    when {
                        isDownloading -> {
                            CircularProgressIndicator(
                                progress = { (downloadProgress ?: 0.1f).coerceIn(0.05f, 1f) },
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = CyberCyan
                            )
                        }
                        isDownloaded || track.isDownloaded -> {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Downloaded",
                                tint = SuccessGreen
                            )
                        }
                        else -> {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Download",
                                tint = TextSecondaryDark
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopArtistBannerCard(
    artist: ArtistResult,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = SurfaceDarkElevated,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DeezerPinkAccent.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .testTag("top_artist_banner_${artist.browseId}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArtworkThumbBox(
                url = artist.thumbnail,
                sizeDp = 72,
                isCircular = true,
                fallbackIcon = Icons.Default.Person
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = TextPrimaryDark,
                        fontWeight = FontWeight.ExtraBold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = artist.subscribers.ifBlank { "Verified Artist" },
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondaryDark),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = DeezerPinkAccent.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "ARTIST",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = DeezerPinkAccent,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TopAlbumBannerCard(
    album: AlbumResult,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = SurfaceDarkElevated,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, HiResGold.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .testTag("top_album_banner_${album.browseId}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArtworkThumbBox(
                url = album.thumbnail,
                sizeDp = 72,
                isCircular = false,
                fallbackIcon = Icons.Default.Album
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = album.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = TextPrimaryDark,
                        fontWeight = FontWeight.ExtraBold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                val subtitle = listOf(album.artist, album.year).filter { it.isNotBlank() }.joinToString(" • ")
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondaryDark),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Surface(
                    color = HiResGold.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = album.type.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = HiResGold,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TopPlaylistBannerCard(
    playlist: PlaylistResult,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = SurfaceDarkElevated,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, SuccessGreen.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .testTag("top_playlist_banner_${playlist.browseId}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArtworkThumbBox(
                url = playlist.thumbnail,
                sizeDp = 72,
                isCircular = false,
                fallbackIcon = Icons.Default.QueueMusic
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = TextPrimaryDark,
                        fontWeight = FontWeight.ExtraBold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                val subtitle = listOf(playlist.author, playlist.trackCount).filter { it.isNotBlank() }.joinToString(" • ")
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondaryDark),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Surface(
                    color = SuccessGreen.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "PLAYLIST",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = SuccessGreen,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

/**
 * Static, zero-jitter search track row with `maxLines = 1` and `TextOverflow.Ellipsis`
 * (no horizontal `basicMarquee` on unselected list items).
 */
@Composable
private fun SearchTrackRow(
    track: AudioTrack,
    isDownloaded: Boolean,
    downloadProgress: Float?,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownload: () -> Unit
) {
    val isDownloading = downloadProgress != null && downloadProgress < 1f
    val subtitleLine = buildString {
        append(track.artist.ifBlank { "Unknown Artist" })
        if (track.album.isNotBlank()) {
            append(" • ")
            append(track.album)
        }
        if (track.durationMs > 0L) {
            append(" • ")
            append(track.durationFormatted)
        }
    }

    Surface(
        color = SurfaceDarkElevated,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.06f),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = androidx.compose.material3.ripple()
            ) {
                onClick()
            }
            .testTag("search_track_row_${track.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TrackThumbnailBox(
                track = track,
                sizeDp = 52,
                cornerRadiusDp = 10
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = TextPrimaryDark,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = subtitleLine,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = TextSecondaryDark
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Favorite",
                    tint = if (track.isFavorite) DeezerPinkAccent else TextSecondaryDark,
                    modifier = Modifier.size(19.dp)
                )
            }

            IconButton(
                onClick = onDownload,
                modifier = Modifier.size(34.dp)
            ) {
                when {
                    isDownloading -> {
                        CircularProgressIndicator(
                            progress = { (downloadProgress ?: 0.1f).coerceIn(0.05f, 1f) },
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = CyberCyan
                        )
                    }
                    isDownloaded || track.isDownloaded -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Downloaded",
                            tint = SuccessGreen,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download",
                            tint = TextSecondaryDark,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistCircularCard(
    artist: ArtistResult,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(104.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(6.dp)
            .testTag("artist_card_${artist.browseId}")
    ) {
        ArtworkThumbBox(
            url = artist.thumbnail,
            sizeDp = 84,
            isCircular = true,
            fallbackIcon = Icons.Default.Person
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.bodySmall.copy(
                color = TextPrimaryDark,
                fontWeight = FontWeight.Bold
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        if (artist.subscribers.isNotBlank()) {
            Text(
                text = artist.subscribers,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = TextSecondaryDark,
                    fontSize = 10.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AlbumSquareCard(
    album: AlbumResult,
    onClick: () -> Unit
) {
    Surface(
        color = SurfaceDarkElevated,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .width(136.dp)
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .testTag("album_card_${album.browseId}")
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            ArtworkThumbBox(
                url = album.thumbnail,
                sizeDp = 116,
                isCircular = false,
                fallbackIcon = Icons.Default.Album
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = album.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = TextPrimaryDark,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = listOf(album.artist, album.year).filter { it.isNotBlank() }.joinToString(" • ")
            Text(
                text = subtitle.ifBlank { album.type },
                style = MaterialTheme.typography.labelSmall.copy(color = TextSecondaryDark),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PlaylistShelfCard(
    playlist: PlaylistResult,
    onClick: () -> Unit
) {
    Surface(
        color = SurfaceDarkElevated,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .width(148.dp)
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .testTag("playlist_card_${playlist.browseId}")
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            ArtworkThumbBox(
                url = playlist.thumbnail,
                sizeDp = 128,
                isCircular = false,
                fallbackIcon = Icons.Default.QueueMusic
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = playlist.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = TextPrimaryDark,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val sub = listOf(playlist.author, playlist.trackCount).filter { it.isNotBlank() }.joinToString(" • ")
            Text(
                text = sub.ifBlank { "Playlist" },
                style = MaterialTheme.typography.labelSmall.copy(color = TextSecondaryDark),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ArtworkThumbBox(
    url: String,
    sizeDp: Int,
    isCircular: Boolean,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector
) {
    val shape = if (isCircular) CircleShape else RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(DeezerPurplePrimary.copy(alpha = 0.55f), DeezerPinkAccent.copy(alpha = 0.45f))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (url.isNotBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size((sizeDp * 0.42f).dp)
            )
        }
    }
}

@Composable
private fun TrackThumbnailBox(
    track: AudioTrack,
    sizeDp: Int,
    cornerRadiusDp: Int
) {
    val c1 = Color(track.gradientColors.getOrElse(0) { 0xFF8B5CF6 })
    val c2 = Color(track.gradientColors.getOrElse(1) { 0xFFEC4899 })

    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(cornerRadiusDp.dp))
            .background(Brush.linearGradient(listOf(c1, c2))),
        contentAlignment = Alignment.Center
    ) {
        if (!track.artworkUrl.isNullOrBlank()) {
            AsyncImage(
                model = track.artworkUrl,
                contentDescription = track.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size((sizeDp * 0.45f).dp)
            )
        }
    }
}
