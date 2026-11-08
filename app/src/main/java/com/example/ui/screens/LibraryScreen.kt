package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.model.AudioTrack
import com.example.model.Playlist
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.DeezerPinkAccent
import com.example.ui.theme.DeezerPurplePrimary
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.SurfaceDarkElevated
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryDark

@Composable
fun LibraryScreen(
    tracks: List<AudioTrack>,
    downloadedTracks: List<AudioTrack> = emptyList(),
    playlists: List<Playlist>,
    favorites: Set<String>,
    onPlayTrack: (AudioTrack, List<AudioTrack>) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onDeleteDownloadedTrack: (String) -> Unit = {},
    onImportAudio: (Uri) -> Unit,
    onCreatePlaylist: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Playlists", "Downloaded", "Offline Storage", "Favorites")

    // File picker launcher for local FLAC/ALAC/WAV files
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { onImportAudio(it) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("library_screen")
            .padding(top = 16.dp)
    ) {
        // Library Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Your Library",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Black,
                        color = TextPrimaryDark
                    )
                )
                Text(
                    text = "Local Storage & Hi-Res Masters",
                    fontSize = 12.sp,
                    color = TextSecondaryDark
                )
            }

            // New Playlist Button
            Button(
                onClick = onCreatePlaylist,
                colors = ButtonDefaults.buttonColors(containerColor = DeezerPurplePrimary),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.testTag("new_playlist_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Playlist", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Privacy & Guest Mode Notice
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceDarkElevated)
                .border(1.dp, SuccessGreen.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = SuccessGreen,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Guest Access • Zero Account Needed • Complete Privacy",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = SuccessGreen
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Scrollable Tab Navigation — prevents mid-word wrapping like "Downloa\nded"
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            edgePadding = 16.dp,
            containerColor = Color.Transparent,
            contentColor = DeezerPinkAccent,
            divider = {},
            indicator = { tabPositions ->
                if (selectedTab in tabPositions.indices) {
                    TabRowDefaults.Indicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = DeezerPinkAccent,
                        height = 2.5.dp
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            fontSize = 13.sp,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Medium,
                            color = if (selectedTab == index) TextPrimaryDark else TextSecondaryDark,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Visible
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Tab Contents
        when (selectedTab) {
            0 -> PlaylistsTab(
                playlists = playlists,
                tracks = tracks,
                onPlayTrack = onPlayTrack
            )
            1 -> DownloadedTab(
                downloadedTracks = downloadedTracks,
                onPlayTrack = onPlayTrack,
                onToggleFavorite = onToggleFavorite,
                onDeleteTrack = onDeleteDownloadedTrack
            )
            2 -> LocalStorageTab(
                tracks = tracks,
                onImportClick = {
                    audioPickerLauncher.launch(arrayOf("audio/*", "application/ogg"))
                },
                onPlayTrack = onPlayTrack,
                onToggleFavorite = onToggleFavorite
            )
            3 -> FavoritesTab(
                tracks = tracks.filter { favorites.contains(it.id) },
                onPlayTrack = onPlayTrack,
                onToggleFavorite = onToggleFavorite
            )
        }
    }
}

@Composable
private fun PlaylistsTab(
    playlists: List<Playlist>,
    tracks: List<AudioTrack>,
    onPlayTrack: (AudioTrack, List<AudioTrack>) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp)
    ) {
        items(playlists) { playlist ->
            val pTracks = tracks.filter { playlist.trackIds.contains(it.id) }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (pTracks.isNotEmpty()) onPlayTrack(pTracks.first(), pTracks)
                    },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDarkElevated)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val gradColors = playlist.gradientColors.map { Color(it) }
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Brush.linearGradient(gradColors)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlaylistPlay,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playlist.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${playlist.trackIds.size} songs • ${playlist.description}",
                            fontSize = 12.sp,
                            color = TextSecondaryDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        onClick = {
                            if (pTracks.isNotEmpty()) onPlayTrack(pTracks.first(), pTracks)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            tint = DeezerPinkAccent
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalStorageTab(
    tracks: List<AudioTrack>,
    onImportClick: () -> Unit,
    onPlayTrack: (AudioTrack, List<AudioTrack>) -> Unit,
    onToggleFavorite: (String) -> Unit
) {
    val localImported = tracks.filter { it.isLocalImport }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 80.dp)
    ) {
        // Storage File Picker Action Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onImportClick() }
                    .testTag("import_audio_button"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDarkElevated)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(CyberCyan.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = CyberCyan,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Import Audio from Device",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryDark
                        )
                        Text(
                            text = "Select FLAC, ALAC, WAV or MP3 files from storage",
                            fontSize = 12.sp,
                            color = TextSecondaryDark
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = CyberCyan
                    )
                }
            }
        }

        item {
            Text(
                text = "IMPORTED LOCAL TRACKS (${localImported.size})",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TextSecondaryDark,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        if (localImported.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = null,
                            tint = TextSecondaryDark,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No imported audio files yet",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimaryDark
                        )
                        Text(
                            text = "Tap the import button above to browse your device",
                            fontSize = 12.sp,
                            color = TextSecondaryDark
                        )
                    }
                }
            }
        } else {
            items(localImported) { track ->
                TrackListItem(
                    track = track,
                    onClick = { onPlayTrack(track, localImported) },
                    onToggleFavorite = { onToggleFavorite(track.id) }
                )
            }
        }
    }
}

@Composable
private fun FavoritesTab(
    tracks: List<AudioTrack>,
    onPlayTrack: (AudioTrack, List<AudioTrack>) -> Unit,
    onToggleFavorite: (String) -> Unit
) {
    if (tracks.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    tint = DeezerPinkAccent.copy(alpha = 0.5f),
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "No favorites yet",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimaryDark
                )
                Text(
                    text = "Tap the heart icon on any song to add it here",
                    fontSize = 13.sp,
                    color = TextSecondaryDark
                )
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 80.dp)
        ) {
            items(tracks) { track ->
                TrackListItem(
                    track = track,
                    onClick = { onPlayTrack(track, tracks) },
                    onToggleFavorite = { onToggleFavorite(track.id) }
                )
            }
        }
    }
}

@Composable
private fun DownloadedTab(
    downloadedTracks: List<AudioTrack>,
    onPlayTrack: (AudioTrack, List<AudioTrack>) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onDeleteTrack: (String) -> Unit
) {
    if (downloadedTracks.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = CyberCyan.copy(alpha = 0.6f),
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "No Downloaded Songs",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark
                    )
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Search worldwide songs from YouTube Music, JioSaavn, Deezer & Apple Music and tap download to save them for 100% offline playback.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = TextSecondaryDark,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                )
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 80.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${downloadedTracks.size} OFFLINE SONGS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyberCyan,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Zero Data Used",
                        fontSize = 11.sp,
                        color = SuccessGreen,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            items(downloadedTracks) { track ->
                DownloadedTrackItem(
                    track = track,
                    onPlay = { onPlayTrack(track, downloadedTracks) },
                    onToggleFavorite = { onToggleFavorite(track.id) },
                    onDelete = { onDeleteTrack(track.id) }
                )
            }
        }
    }
}

@Composable
private fun DownloadedTrackItem(
    track: AudioTrack,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDarkElevated)
            .clickable { onPlay() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!track.artworkUrl.isNullOrBlank()) {
            AsyncImage(
                model = track.artworkUrl,
                contentDescription = track.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
        } else {
            val gradColors = track.gradientColors.map { Color(it) }
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Brush.linearGradient(gradColors)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = track.artist,
                    fontSize = 12.sp,
                    color = TextSecondaryDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(CyberCyan.copy(alpha = 0.16f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "OFFLINE",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = CyberCyan
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Text(
                    text = "${track.format.displayName} • ${track.bitrateKbps} kbps • ${track.fileSizeFormatted}",
                    fontSize = 11.sp,
                    color = TextSecondaryDark.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "Favorite",
                tint = if (track.isFavorite) DeezerPinkAccent else TextSecondaryDark,
                modifier = Modifier.size(22.dp)
            )
        }

        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete downloaded song",
                tint = TextSecondaryDark.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
