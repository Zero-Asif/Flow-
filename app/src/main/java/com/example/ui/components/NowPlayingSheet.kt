package com.example.ui.components

import androidx.activity.compose.BackHandler
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode as AnimRepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AudioFormat
import com.example.model.AudioQualityInfo
import com.example.model.AudioTrack
import com.example.model.BadgeDisplayMode
import com.example.model.KaraokeLyricLine
import com.example.model.LyricsDisplayMode
import com.example.model.RepeatMode
import com.example.model.StreamBackend
import com.example.util.LyricsPlaybackStatus
import com.example.util.LyricsSyncState
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.DeezerPinkAccent
import com.example.ui.theme.DeezerPurplePrimary
import com.example.ui.theme.HiResGold
import com.example.ui.theme.SurfaceDarkCard
import com.example.ui.theme.SurfaceDarkElevated
import com.example.ui.theme.SurfaceDarkVoid
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryDark

@Composable
fun NowPlayingSheet(
    track: AudioTrack?,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    visualizerAmplitudes: List<Float>,
    isShuffle: Boolean,
    repeatMode: RepeatMode,
    gaplessEnabled: Boolean,
    sleepTimerSecondsRemaining: Int?,
    queue: List<AudioTrack>,
    queueIndex: Int,
    showLyrics: Boolean,
    showQueue: Boolean,
    audioQualityInfo: AudioQualityInfo = AudioQualityInfo(),
    badgeDisplayMode: BadgeDisplayMode = BadgeDisplayMode.AUDIOPHILE_FULL,
    streamBackend: StreamBackend = StreamBackend.AUTO_HIGH_FIDELITY,
    activeStreamCandidateName: String? = null,
    lyrics: List<KaraokeLyricLine> = emptyList(),
    lyricsSyncState: LyricsSyncState = LyricsSyncState(),
    lyricsDisplayMode: LyricsDisplayMode = LyricsDisplayMode.LINE_GLOW,
    onCycleBadgeMode: () -> Unit = {},
    onCycleStreamBackend: () -> Unit = {},
    onCycleStreamCandidate: () -> Unit = onCycleStreamBackend,
    onCycleLyricsMode: () -> Unit = {},
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleGapless: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    isDownloaded: Boolean = false,
    downloadProgress: Float? = null,
    onDownloadTrack: (AudioTrack) -> Unit = {},
    onToggleLyrics: () -> Unit,
    onToggleQueue: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onOpenShare: () -> Unit,
    onSelectQueueTrack: (AudioTrack) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler {
        if (showLyrics) {
            onToggleLyrics()
        } else if (showQueue) {
            onToggleQueue()
        } else {
            onDismiss()
        }
    }

    if (track == null) return

    val infiniteTransition = rememberInfiniteTransition(label = "pulse_transition")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isPlaying) 1.035f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = AnimRepeatMode.Reverse
        ),
        label = "album_pulse"
    )

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("now_playing_sheet"),
        color = SurfaceDarkVoid
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Navigation Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("now_playing_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Collapse Now Playing",
                        tint = TextPrimaryDark
                    )
                }

                // Header Audiophile status
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "PLAYING FROM LIBRARY",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        color = TextSecondaryDark
                    )
                    Text(
                        text = track.album,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimaryDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onOpenShare,
                    modifier = Modifier.testTag("now_playing_share_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share Track",
                        tint = TextPrimaryDark
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Dynamic Hardware Audio Quality & Bitrate Badge (Click to cycle styles)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(SurfaceDarkCard)
                    .border(1.dp, HiResGold.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                    .clickable { onCycleBadgeMode() }
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .testTag("audio_quality_badge")
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (isPlaying) CyberCyan else TextSecondaryDark)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = audioQualityInfo.getBadgeText(badgeDisplayMode),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = HiResGold,
                    letterSpacing = 0.5.sp
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Full Song Stream Provider Badge (Click to cycle real stream candidates: [Deezer HiFi FLAC], [Amazon Ultra HD], [Tidal Master], [JioSaavn 320k])
            val displayedStream = activeStreamCandidateName?.takeIf { it.isNotBlank() }
                ?: track.sourceProvider.takeIf { it.isNotBlank() }
                ?: streamBackend.label
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(DeezerPurplePrimary.copy(alpha = 0.25f))
                    .border(0.8.dp, DeezerPinkAccent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .clickable { onCycleStreamCandidate() }
                    .padding(horizontal = 10.dp, vertical = 3.dp)
                    .testTag("stream_backend_badge")
            ) {
                Text(
                    text = "Stream: [$displayedStream]",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimaryDark
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Main Content Area: Album Art OR Synchronized Lyrics OR Queue
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (showLyrics) {
                    // Synchronized Karaoke Lyrics View
                    LyricsView(
                        lyrics = lyrics,
                        currentPositionMs = currentPositionMs,
                        syncState = lyricsSyncState,
                        displayMode = lyricsDisplayMode,
                        onCycleDisplayMode = onCycleLyricsMode,
                        onSeekTo = onSeekTo,
                        onClose = onToggleLyrics
                    )
                } else if (showQueue) {
                    // Up Next Queue View
                    QueueView(
                        queue = queue,
                        currentIndex = queueIndex,
                        onSelectTrack = onSelectQueueTrack,
                        onClose = onToggleQueue
                    )
                } else {
                    // Album Art Display
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        val gradColors = track.gradientColors.map { Color(it) }
                        Box(
                            modifier = Modifier
                                .size(260.dp)
                                .scale(pulseScale)
                                .clip(RoundedCornerShape(28.dp))
                                .background(
                                    Brush.linearGradient(
                                        if (gradColors.size >= 2) gradColors else listOf(DeezerPurplePrimary, DeezerPinkAccent)
                                    )
                                )
                                .border(1.5.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(28.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!track.artworkUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = track.artworkUrl,
                                    contentDescription = track.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(28.dp))
                                )
                                // Gradient vignette overlay
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(
                                                    Color.Transparent,
                                                    Color.Black.copy(alpha = 0.35f)
                                                )
                                            )
                                        )
                                )
                            } else {
                                // Stylized vinyl rings & center emblem
                                Box(
                                    modifier = Modifier
                                        .size(110.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.45f))
                                        .border(2.dp, Color.White.copy(alpha = 0.2f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = Icons.Default.GraphicEq,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(36.dp)
                                        )
                                        Text(
                                            text = "HI-RES",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Black,
                                            color = HiResGold
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Real-time audio spectrum visualizer
                        AudioVisualizer(
                            amplitudes = visualizerAmplitudes,
                            isPlaying = isPlaying,
                            height = 36.dp,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Track Title & Favorite Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryDark
                        ),
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
                    Text(
                        text = "${track.artist} — ${track.genre} (${track.releaseYear})",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = TextSecondaryDark
                        ),
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
                }

                IconButton(
                    onClick = { onToggleFavorite(track.id) },
                    modifier = Modifier.testTag("now_playing_favorite_button")
                ) {
                    Icon(
                        imageVector = if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Toggle Favorite",
                        tint = if (track.isFavorite) DeezerPinkAccent else TextSecondaryDark,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Scrubber Progress Bar
            val progress = if (durationMs > 0) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
            Slider(
                value = progress,
                onValueChange = { newProgress ->
                    val newPos = (newProgress * durationMs).toLong()
                    onSeekTo(newPos)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("audio_scrubber_slider"),
                colors = SliderDefaults.colors(
                    thumbColor = DeezerPinkAccent,
                    activeTrackColor = DeezerPinkAccent,
                    inactiveTrackColor = SurfaceDarkCard
                )
            )

            // Timestamps: Elapsed & Remaining
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatMs(currentPositionMs),
                    fontSize = 12.sp,
                    color = TextSecondaryDark
                )
                Text(
                    text = "-${formatMs(durationMs - currentPositionMs)}",
                    fontSize = 12.sp,
                    color = TextSecondaryDark
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Main Playback Controls Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Shuffle
                IconButton(
                    onClick = onToggleShuffle,
                    modifier = Modifier.testTag("shuffle_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (isShuffle) DeezerPinkAccent else TextSecondaryDark,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Previous
                IconButton(
                    onClick = onPrevious,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("previous_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous Track",
                        tint = TextPrimaryDark,
                        modifier = Modifier.size(34.dp)
                    )
                }

                // Large Play/Pause FAB
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(DeezerPurplePrimary, DeezerPinkAccent))
                        )
                        .clickable { onTogglePlay() }
                        .testTag("now_playing_play_pause_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(38.dp)
                    )
                }

                // Next
                IconButton(
                    onClick = onNext,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("next_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next Track",
                        tint = TextPrimaryDark,
                        modifier = Modifier.size(34.dp)
                    )
                }

                // Repeat Mode
                IconButton(
                    onClick = onCycleRepeat,
                    modifier = Modifier.testTag("repeat_button")
                ) {
                    Icon(
                        imageVector = if (repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        contentDescription = "Repeat Mode",
                        tint = if (repeatMode != RepeatMode.OFF) DeezerPinkAccent else TextSecondaryDark,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Bottom Action Utilities Row: Lyrics, EQ, Sleep Timer, Queue
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceDarkCard.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Lyrics toggle
                IconButton(
                    onClick = onToggleLyrics,
                    modifier = Modifier.testTag("lyrics_toggle_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Subtitles,
                        contentDescription = "Synchronized Lyrics",
                        tint = if (showLyrics) CyberCyan else TextSecondaryDark
                    )
                }

                // Equalizer button
                IconButton(
                    onClick = onOpenEqualizer,
                    modifier = Modifier.testTag("equalizer_quick_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Equalizer,
                        contentDescription = "Equalizer",
                        tint = TextSecondaryDark
                    )
                }

                // Sleep Timer
                IconButton(
                    onClick = onOpenSleepTimer,
                    modifier = Modifier.testTag("sleep_timer_button")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.NightsStay,
                            contentDescription = "Sleep Timer",
                            tint = if (sleepTimerSecondsRemaining != null) HiResGold else TextSecondaryDark
                        )
                        if (sleepTimerSecondsRemaining != null) {
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "${sleepTimerSecondsRemaining / 60}m",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = HiResGold
                            )
                        }
                    }
                }

                // Offline Download button
                if (isDownloaded) {
                    IconButton(
                        onClick = {},
                        modifier = Modifier.testTag("now_playing_downloaded_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Downloaded Offline",
                            tint = CyberCyan
                        )
                    }
                } else if (downloadProgress != null) {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.size(22.dp),
                            color = CyberCyan,
                            strokeWidth = 2.5.dp
                        )
                    }
                } else {
                    IconButton(
                        onClick = { onDownloadTrack(track) },
                        modifier = Modifier.testTag("now_playing_download_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download full song",
                            tint = TextSecondaryDark
                        )
                    }
                }

                // Queue sheet toggle
                IconButton(
                    onClick = onToggleQueue,
                    modifier = Modifier.testTag("queue_toggle_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "Up Next Queue",
                        tint = if (showQueue) DeezerPinkAccent else TextSecondaryDark
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricsView(
    lyrics: List<KaraokeLyricLine>,
    currentPositionMs: Long,
    syncState: LyricsSyncState,
    displayMode: LyricsDisplayMode,
    onCycleDisplayMode: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onClose: () -> Unit
) {
    val listState = rememberLazyListState()

    val activeIndex = syncState.activeLineIndex

    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            val viewportHeight = listState.layoutInfo.viewportSize.height
            val centerOffset = if (viewportHeight > 0) -(viewportHeight / 3) else -280
            listState.animateScrollToItem(index = activeIndex, scrollOffset = centerOffset)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceDarkCard.copy(alpha = 0.94f))
            .padding(16.dp)
    ) {
        // Top Bar: Synchronized Lyrics tag, interactive mode switcher, and Close
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "SYNCED LYRICS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyberCyan,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                // Interactive Mode Switcher Pill (Click to toggle Line Glow vs Word-by-Word Karaoke)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(DeezerPurplePrimary.copy(alpha = 0.35f))
                        .border(1.dp, DeezerPinkAccent.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .clickable { onCycleDisplayMode() }
                        .padding(horizontal = 8.dp, vertical = 2.5.dp)
                        .testTag("lyrics_mode_toggle")
                ) {
                    Text(
                        text = displayMode.label,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = DeezerPinkAccent
                    )
                }
            }

            Text(
                text = "Close",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = TextSecondaryDark,
                modifier = Modifier
                    .clickable { onClose() }
                    .padding(4.dp)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (syncState.status == LyricsPlaybackStatus.INTRO && syncState.timeToNextLineMs > 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(DeezerPurplePrimary.copy(alpha = 0.25f))
                    .border(0.8.dp, CyberCyan.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "♪ Intro • Vocals begin in ${(syncState.timeToNextLineMs / 1000) + 1}s ♪",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyberCyan
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
        } else if (syncState.status == LyricsPlaybackStatus.INSTRUMENTAL_BREAK) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(DeezerPinkAccent.copy(alpha = 0.2f))
                    .border(0.8.dp, DeezerPinkAccent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "♪ Instrumental Solo ♪",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = DeezerPinkAccent
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        if (lyrics.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Subtitles,
                        contentDescription = null,
                        tint = TextSecondaryDark.copy(alpha = 0.4f),
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Fetching live synchronized lyrics from LRCLIB...",
                        color = TextSecondaryDark,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                itemsIndexed(lyrics) { index, line ->
                    val isActive = index == activeIndex
                    val isPast = index < activeIndex

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isActive) DeezerPurplePrimary.copy(alpha = 0.22f) else Color.Transparent
                            )
                            .clickable { onSeekTo(line.timeMs) }
                            .padding(vertical = 6.dp, horizontal = 8.dp)
                            .testTag("lyric_line_$index")
                    ) {
                        when (displayMode) {
                            LyricsDisplayMode.LINE_GLOW -> {
                                // Line Glow mode: active line in solid bright white, past/upcoming dimmed
                                Text(
                                    text = line.text,
                                    fontSize = if (isActive) 22.sp else 16.sp,
                                    fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Normal,
                                    color = when {
                                        isActive -> Color.White
                                        isPast -> TextSecondaryDark.copy(alpha = 0.50f)
                                        else -> TextSecondaryDark.copy(alpha = 0.25f)
                                    },
                                    textAlign = TextAlign.Start,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            LyricsDisplayMode.SYLLABLE_KARAOKE -> {
                                // Karaoke Word-by-Word progressive fill
                                val annotated = buildAnnotatedString {
                                    if (line.syllables.isNotEmpty()) {
                                        for (syllable in line.syllables) {
                                            val isWordPast = currentPositionMs >= (syllable.timeMs + syllable.durationMs)
                                            val isWordActive = currentPositionMs in syllable.timeMs..(syllable.timeMs + syllable.durationMs)
                                            val color = when {
                                                isWordActive -> CyberCyan
                                                isWordPast -> Color.White
                                                isActive -> Color.White.copy(alpha = 0.45f)
                                                isPast -> TextSecondaryDark.copy(alpha = 0.50f)
                                                else -> TextSecondaryDark.copy(alpha = 0.25f)
                                            }
                                            withStyle(
                                                SpanStyle(
                                                    color = color,
                                                    fontWeight = if (isWordActive) FontWeight.Black else if (isWordPast || isActive) FontWeight.Bold else FontWeight.Normal
                                                )
                                            ) {
                                                append(syllable.text)
                                            }
                                        }
                                    } else {
                                        val color = when {
                                            isActive -> Color.White
                                            isPast -> TextSecondaryDark.copy(alpha = 0.50f)
                                            else -> TextSecondaryDark.copy(alpha = 0.25f)
                                        }
                                        withStyle(SpanStyle(color = color, fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Normal)) {
                                            append(line.text)
                                        }
                                    }
                                }

                                Text(
                                    text = annotated,
                                    fontSize = if (isActive) 22.sp else 16.sp,
                                    textAlign = TextAlign.Start,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            LyricsDisplayMode.DUAL_SUBTITLES -> {
                                Text(
                                    text = line.text,
                                    fontSize = if (isActive) 19.sp else 14.sp,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isActive) Color.White else TextSecondaryDark.copy(alpha = 0.30f),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueView(
    queue: List<AudioTrack>,
    currentIndex: Int,
    onSelectTrack: (AudioTrack) -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceDarkCard.copy(alpha = 0.9f))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "UP NEXT (${queue.size} TRACKS)",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = DeezerPinkAccent,
                letterSpacing = 1.sp
            )
            Text(
                text = "Close",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = TextSecondaryDark,
                modifier = Modifier.clickable { onClose() }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(queue) { index, item ->
                val isCurrent = index == currentIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isCurrent) DeezerPurplePrimary.copy(alpha = 0.25f) else Color.Transparent)
                        .clickable { onSelectTrack(item) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${index + 1}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isCurrent) DeezerPinkAccent else TextSecondaryDark,
                        modifier = Modifier.width(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            fontSize = 14.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                            color = if (isCurrent) TextPrimaryDark else TextSecondaryDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${item.artist} • ${item.format.displayName}",
                            fontSize = 12.sp,
                            color = TextSecondaryDark,
                            maxLines = 1
                        )
                    }
                    Text(
                        text = item.durationFormatted,
                        fontSize = 12.sp,
                        color = TextSecondaryDark
                    )
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
