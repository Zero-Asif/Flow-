package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.model.AudioFormat
import com.example.model.AudioTrack
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.DeezerPinkAccent
import com.example.ui.theme.DeezerPurplePrimary
import com.example.ui.theme.HiResGold
import com.example.ui.theme.SurfaceDarkCard
import com.example.ui.theme.SurfaceDarkElevated
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryDark

import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import com.example.network.CategorizedSearchResults
import com.example.network.MusicSourceFilter
import com.example.network.SearchSuggestionsResult
import com.example.util.AlbumArtHelper

@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    selectedGenre: String?,
    onSelectGenre: (String?) -> Unit,
    selectedFormat: AudioFormat?,
    onSelectFormat: (AudioFormat?) -> Unit,
    selectedYear: Int?,
    onSelectYear: (Int?) -> Unit,
    selectedSourceFilter: MusicSourceFilter = MusicSourceFilter.ALL,
    onSelectSourceFilter: (MusicSourceFilter) -> Unit = {},
    downloadProgress: Map<String, Float> = emptyMap(),
    downloadedTrackIds: Set<String> = emptySet(),
    onDownloadTrack: (AudioTrack) -> Unit = {},
    searchResults: List<AudioTrack>,
    searchSuggestions: SearchSuggestionsResult = SearchSuggestionsResult(),
    categorizedResults: CategorizedSearchResults = CategorizedSearchResults(),
    isGlobalSearching: Boolean = false,
    globalTrendingTracks: List<AudioTrack> = emptyList(),
    onPlayTrack: (AudioTrack, List<AudioTrack>) -> Unit,
    onToggleFavorite: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val quickExploreArtists = listOf(
        "Daft Punk", "The Weeknd", "Taylor Swift", "Billie Eilish",
        "Coldplay", "Hans Zimmer", "Dua Lipa", "Queen", "Adele", "Eminem"
    )
    val genres = listOf("Pop", "Synthwave", "Rock", "Hip-Hop", "Electronic", "Ambient", "Classical", "Jazz", "R&B")
    val formats = listOf(AudioFormat.FLAC, AudioFormat.ALAC, AudioFormat.WAV)

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("search_screen")
            .padding(top = 16.dp)
    ) {
        // Search Header Title & Worldwide indicator
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                Text(
                    text = "Global Music Search",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Black,
                        color = TextPrimaryDark
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Search all songs, artists & albums in the world",
                    fontSize = 12.sp,
                    color = TextSecondaryDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(DeezerPurplePrimary.copy(alpha = 0.2f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Public,
                        contentDescription = null,
                        tint = CyberCyan,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "WORLDWIDE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = CyberCyan,
                        maxLines = 1,
                        softWrap = false,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Search Input Field
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search any song, artist, album in the world...", color = TextSecondaryDark) },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, tint = CyberCyan)
            },
            trailingIcon = {
                if (isGlobalSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = DeezerPinkAccent
                    )
                } else if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextSecondaryDark)
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .testTag("search_text_input"),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = SurfaceDarkElevated,
                unfocusedContainerColor = SurfaceDarkElevated,
                focusedBorderColor = DeezerPurplePrimary,
                unfocusedBorderColor = SurfaceDarkCard,
                focusedTextColor = TextPrimaryDark,
                unfocusedTextColor = TextPrimaryDark
            )
        )

        // Linear Progress bar during search
        AnimatedVisibility(
            visible = isGlobalSearching,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp),
                    color = DeezerPinkAccent,
                    trackColor = SurfaceDarkCard
                )
                Text(
                    text = "Searching worldwide catalog...",
                    fontSize = 11.sp,
                    color = CyberCyan,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                )
            }
        }

        // Real-Time Typeahead Autocomplete Suggestions (Query Completions & Top Artist Suggestions)
        if (query.isNotBlank() && (searchSuggestions.queryCompletions.isNotEmpty() || searchSuggestions.topArtists.isNotEmpty())) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val combinedSuggestions = (searchSuggestions.topArtists + searchSuggestions.queryCompletions).distinct().take(8)
                items(combinedSuggestions) { suggestion ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(DeezerPurplePrimary.copy(alpha = 0.22f))
                            .clickable { onQueryChange(suggestion) }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = CyberCyan,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = suggestion,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimaryDark,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Main Scrollable Container (flex: 1 / weight(1f)) holding filters and results so the list never collapses to 0 height
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("search_results_list")
        ) {
            // Filter & Provider Chips Header Item
            item(key = "search_filters_section") {
                Column {
                    // Quick Explore Artists (One-tap search suggestions)
                    Text(
                        text = "WORLDWIDE ARTISTS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondaryDark,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(quickExploreArtists) { artist ->
                            val isCurrent = query.equals(artist, ignoreCase = true)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (isCurrent) DeezerPinkAccent else SurfaceDarkCard)
                                    .clickable { onQueryChange(artist) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = artist,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isCurrent) Color.White else TextPrimaryDark
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // SpotiFLAC Multi-Source Provider Filter Chips
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "STREAMING PROVIDERS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyberCyan,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(CyberCyan.copy(alpha = 0.15f))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "SpotiFLAC Engine",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = CyberCyan
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(MusicSourceFilter.values().filter { it != MusicSourceFilter.APPLE_AMAZON }) { filter ->
                            val isSelected = selectedSourceFilter == filter
                            FilterChip(
                                selected = isSelected,
                                onClick = { onSelectSourceFilter(filter) },
                                label = { Text(filter.displayName) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CyberCyan,
                                    selectedLabelColor = Color.Black,
                                    containerColor = SurfaceDarkCard,
                                    labelColor = TextSecondaryDark
                                ),
                                border = null
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Genre Filter Chips
                    Text(
                        text = "GENRE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondaryDark,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = selectedGenre == null,
                                onClick = { onSelectGenre(null) },
                                label = { Text("All") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = DeezerPurplePrimary,
                                    selectedLabelColor = Color.White,
                                    containerColor = SurfaceDarkCard,
                                    labelColor = TextSecondaryDark
                                ),
                                border = null
                            )
                        }
                        items(genres) { genre ->
                            val isSelected = selectedGenre == genre
                            FilterChip(
                                selected = isSelected,
                                onClick = { onSelectGenre(if (isSelected) null else genre) },
                                label = { Text(genre) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = DeezerPurplePrimary,
                                    selectedLabelColor = Color.White,
                                    containerColor = SurfaceDarkCard,
                                    labelColor = TextSecondaryDark
                                ),
                                border = null
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Audio Quality Format Filter Chips
                    Text(
                        text = "AUDIO QUALITY FORMAT",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondaryDark,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = selectedFormat == null,
                                onClick = { onSelectFormat(null) },
                                label = { Text("All Qualities") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = HiResGold,
                                    selectedLabelColor = Color.Black,
                                    containerColor = SurfaceDarkCard,
                                    labelColor = TextSecondaryDark
                                ),
                                border = null
                            )
                        }
                        items(formats) { fmt ->
                            val isSelected = selectedFormat == fmt
                            FilterChip(
                                selected = isSelected,
                                onClick = { onSelectFormat(if (isSelected) null else fmt) },
                                label = { Text(fmt.displayName) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = HiResGold,
                                    selectedLabelColor = Color.Black,
                                    containerColor = SurfaceDarkCard,
                                    labelColor = TextSecondaryDark
                                ),
                                border = null
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Results Count Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val countText = if (query.isNotBlank()) {
                            "${searchResults.size} Songs Found Worldwide"
                        } else {
                            "${searchResults.size} Songs Ready"
                        }

                        Text(
                            text = countText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = CyberCyan
                        )
                        if (selectedGenre != null || selectedFormat != null || query.isNotEmpty()) {
                            Text(
                                text = "Reset Filters",
                                fontSize = 12.sp,
                                color = DeezerPinkAccent,
                                modifier = Modifier.clickable {
                                    onQueryChange("")
                                    onSelectGenre(null)
                                    onSelectFormat(null)
                                    onSelectYear(null)
                                }
                            )
                        }
                    }
                }
            }

            if (query.isBlank() && globalTrendingTracks.isNotEmpty()) {
                item(key = "trending_header") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.TrendingUp,
                            contentDescription = null,
                            tint = DeezerPinkAccent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "TRENDING WORLDWIDE",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = DeezerPinkAccent,
                            letterSpacing = 1.sp
                        )
                    }
                }

                items(
                    items = globalTrendingTracks,
                    key = { trendingTrack -> "trending_${trendingTrack.id}" }
                ) { trendingTrack ->
                    val isDownloaded = trendingTrack.isDownloaded || downloadedTrackIds.contains(trendingTrack.id)
                    val progress = downloadProgress[trendingTrack.id]
                    SearchResultItem(
                        track = trendingTrack,
                        isDownloaded = isDownloaded,
                        downloadProgress = progress,
                        onDownloadTrack = { onDownloadTrack(trendingTrack) },
                        onClick = { onPlayTrack(trendingTrack, listOf(trendingTrack)) },
                        onToggleFavorite = { onToggleFavorite(trendingTrack.id) }
                    )
                }

                item(key = "library_songs_header") {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "LIBRARY SONGS & LOCAL LOSSLESS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = TextSecondaryDark,
                        letterSpacing = 1.sp
                    )
                }
            }

            if (searchResults.isEmpty() && query.isNotBlank() && isGlobalSearching) {
                item(key = "searching_loading_placeholder") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                color = CyberCyan,
                                strokeWidth = 3.dp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Probing Lossless & Hi-Res Streams...",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimaryDark
                            )
                            Text(
                                text = "Scanning FLAC, ALAC, WAV & 320 kbps catalogs for '$query'",
                                fontSize = 12.sp,
                                color = TextSecondaryDark
                            )
                        }
                    }
                }
            } else if (searchResults.isEmpty() && query.isNotBlank() && !isGlobalSearching) {
                item(key = "empty_search_state") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.FilterList,
                                contentDescription = null,
                                tint = TextSecondaryDark,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No matching songs found",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimaryDark
                            )
                            Text(
                                text = "Try searching for artist names like 'Daft Punk' or 'The Weeknd'",
                                fontSize = 13.sp,
                                color = TextSecondaryDark
                            )
                        }
                    }
                }
            } else {
                // Segregated Search Sections (Amazon / YouTube Music Standard: Best Match, Artists, Albums, Songs)
                val bestMatch = categorizedResults.bestMatch
                if (query.isNotBlank() && bestMatch != null) {
                    item(key = "best_match_header") {
                        Text(
                            text = "BEST MATCH (TOP RESULT)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = HiResGold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    item(key = "best_match_${bestMatch.id}") {
                        val isDownloaded = bestMatch.isDownloaded || downloadedTrackIds.contains(bestMatch.id)
                        val progress = downloadProgress[bestMatch.id]
                        SearchResultItem(
                            track = bestMatch,
                            isDownloaded = isDownloaded,
                            downloadProgress = progress,
                            onDownloadTrack = { onDownloadTrack(bestMatch) },
                            onClick = { onPlayTrack(bestMatch, listOf(bestMatch)) },
                            onToggleFavorite = { onToggleFavorite(bestMatch.id) }
                        )
                    }
                }

                if (query.isNotBlank() && (categorizedResults.artists.isNotEmpty() || categorizedResults.albums.isNotEmpty())) {
                    item(key = "artists_albums_segregated_row") {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            if (categorizedResults.artists.isNotEmpty()) {
                                Text(
                                    text = "ARTISTS",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = CyberCyan,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(categorizedResults.artists, key = { "artist_${it.name}" }) { artistSummary ->
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(SurfaceDarkElevated)
                                                .clickable { onQueryChange(artistSummary.name) }
                                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (!artistSummary.artworkUrl.isNullOrBlank()) {
                                                    AsyncImage(
                                                        model = AlbumArtHelper.enhanceArtworkUrl(artistSummary.artworkUrl),
                                                        contentDescription = artistSummary.name,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier
                                                            .size(28.dp)
                                                            .clip(CircleShape)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                }
                                                Column {
                                                    Text(
                                                        text = artistSummary.name,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = TextPrimaryDark,
                                                        maxLines = 1
                                                    )
                                                    Text(
                                                        text = artistSummary.topGenre,
                                                        fontSize = 10.sp,
                                                        color = TextSecondaryDark
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            if (categorizedResults.albums.isNotEmpty()) {
                                Text(
                                    text = "ALBUMS",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = DeezerPinkAccent,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(categorizedResults.albums, key = { "album_${it.title}_${it.artist}" }) { albumSummary ->
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(SurfaceDarkElevated)
                                                .clickable { onPlayTrack(albumSummary.representativeTrack, listOf(albumSummary.representativeTrack)) }
                                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (!albumSummary.artworkUrl.isNullOrBlank()) {
                                                    AsyncImage(
                                                        model = AlbumArtHelper.enhanceArtworkUrl(albumSummary.artworkUrl),
                                                        contentDescription = albumSummary.title,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier
                                                            .size(32.dp)
                                                            .clip(RoundedCornerShape(6.dp))
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                }
                                                Column {
                                                    Text(
                                                        text = albumSummary.title,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = TextPrimaryDark,
                                                        maxLines = 1
                                                    )
                                                    Text(
                                                        text = albumSummary.artist,
                                                        fontSize = 10.sp,
                                                        color = TextSecondaryDark,
                                                        maxLines = 1
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item(key = "songs_section_header") {
                        Text(
                            text = "SONGS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = TextSecondaryDark,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                items(
                    items = searchResults,
                    key = { track -> track.id }
                ) { track ->
                    val isDownloaded = track.isDownloaded || downloadedTrackIds.contains(track.id)
                    val progress = downloadProgress[track.id]
                    SearchResultItem(
                        track = track,
                        isDownloaded = isDownloaded,
                        downloadProgress = progress,
                        onDownloadTrack = { onDownloadTrack(track) },
                        onClick = { onPlayTrack(track, listOf(track)) },
                        onToggleFavorite = { onToggleFavorite(track.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun SearchResultItem(
    track: AudioTrack,
    isDownloaded: Boolean = false,
    downloadProgress: Float? = null,
    onDownloadTrack: () -> Unit = {},
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDarkElevated)
            .clickable { onClick() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail with AsyncImage or Lossless Gradient
        if (!track.artworkUrl.isNullOrBlank()) {
            AsyncImage(
                model = AlbumArtHelper.enhanceArtworkUrl(track.artworkUrl),
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
                Text(
                    text = track.format.displayName,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White
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
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(
                        iterations = Int.MAX_VALUE,
                        initialDelayMillis = 1500,
                        repeatDelayMillis = 1500,
                        velocity = 30.dp
                    )
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = track.artist,
                    fontSize = 12.sp,
                    color = TextSecondaryDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .basicMarquee(
                            iterations = Int.MAX_VALUE,
                            initialDelayMillis = 1500,
                            repeatDelayMillis = 1500,
                            velocity = 30.dp
                        )
                )
                Spacer(modifier = Modifier.width(6.dp))
                // Dynamic Quality & Bitrate badge
                val isLosslessBadge = track.format.isLossless || track.bitrateKbps >= 1411
                val badgeColor = if (isLosslessBadge) HiResGold else CyberCyan
                val sampleRateKhz = if (track.sampleRate % 1000 == 0) {
                    "${track.sampleRate / 1000}kHz"
                } else {
                    String.format(java.util.Locale.US, "%.1fkHz", track.sampleRate / 1000.0)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(badgeColor.copy(alpha = 0.16f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "${track.format.displayName} ${track.bitDepth}B/$sampleRateKhz • ${track.bitrateKbps} kbps",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                val providerLabel = track.sourceProvider ?: if (track.isGlobalOnline) "Worldwide Stream" else "Lossless Offline"
                val providerColor = when {
                    providerLabel.contains("Amazon", ignoreCase = true) -> HiResGold
                    providerLabel.contains("Deezer", ignoreCase = true) -> DeezerPinkAccent
                    providerLabel.contains("Tidal", ignoreCase = true) -> CyberCyan
                    providerLabel.contains("YouTube", ignoreCase = true) -> Color(0xFFFF4E45)
                    providerLabel.contains("Saavn", ignoreCase = true) -> CyberCyan
                    providerLabel.contains("Apple", ignoreCase = true) -> Color(0xFFA855F7)
                    else -> CyberCyan.copy(alpha = 0.85f)
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(providerColor.copy(alpha = 0.15f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = providerLabel,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = providerColor
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = track.album,
                    fontSize = 11.sp,
                    color = TextSecondaryDark.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 1-Tap Offline Download Button
        if (isDownloaded) {
            IconButton(
                onClick = {},
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Downloaded offline",
                    tint = CyberCyan,
                    modifier = Modifier.size(22.dp)
                )
            }
        } else if (downloadProgress != null) {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.size(24.dp),
                    color = CyberCyan,
                    strokeWidth = 2.5.dp
                )
            }
        } else {
            IconButton(
                onClick = onDownloadTrack,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "Download full song",
                    tint = TextSecondaryDark,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Favorite Toggle Button
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
    }
}
