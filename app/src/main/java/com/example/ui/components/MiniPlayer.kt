package com.example.ui.components

import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.layout.ContentScale
import com.example.util.AlbumArtHelper

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AudioTrack
import com.example.ui.theme.DeezerPinkAccent
import com.example.ui.theme.DeezerPurplePrimary
import com.example.ui.theme.HiResGold
import com.example.ui.theme.SurfaceDarkCard
import com.example.ui.theme.SurfaceDarkElevated
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryDark

@Composable
fun MiniPlayer(
    currentTrack: AudioTrack?,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onExpand: () -> Unit,
    isBuffering: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (currentTrack == null) return

    val progress = if (durationMs > 0) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onExpand() }
            .testTag("mini_player_dock"),
        color = SurfaceDarkElevated.copy(alpha = 0.96f),
        tonalElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column {
            // Thin progress / buffering bar along top edge
            if (isBuffering && !isPlaying) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp),
                    color = DeezerPinkAccent,
                    trackColor = SurfaceDarkCard
                )
            } else {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp),
                    color = DeezerPinkAccent,
                    trackColor = SurfaceDarkCard
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Album Art Thumbnail
                val gradColors = currentTrack.gradientColors.map { Color(it) }
                if (!currentTrack.artworkUrl.isNullOrBlank()) {
                    val context = LocalContext.current
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(AlbumArtHelper.enhanceArtworkUrl(currentTrack.artworkUrl))
                            .memoryCacheKey(currentTrack.id)
                            .diskCacheKey(currentTrack.id)
                            .crossfade(true)
                            .build(),
                        contentDescription = currentTrack.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                Brush.linearGradient(
                                    if (gradColors.size >= 2) gradColors else listOf(DeezerPurplePrimary, DeezerPinkAccent)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = currentTrack.format.displayName,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Title, Artist, Hi-Res Badge
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = currentTrack.title,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimaryDark
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        // Dynamic Codec / Hi-Res badge
                        val isHiResTrack = currentTrack.format.isLossless || currentTrack.bitDepth >= 24 || currentTrack.sampleRate >= 88200
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(HiResGold.copy(alpha = 0.18f))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = if (isHiResTrack) "Hi-Res" else "${currentTrack.format.displayName} ${currentTrack.bitDepth}B",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = HiResGold
                            )
                        }
                    }

                    Text(
                        text = if (isBuffering && !isPlaying) {
                            "${currentTrack.artist} • Buffering stream..."
                        } else {
                            "${currentTrack.artist} • ${currentTrack.format.displayName} • ${currentTrack.bitrateKbps} kbps"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = TextSecondaryDark,
                            fontSize = 12.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Favorite toggle
                IconButton(
                    onClick = { onToggleFavorite(currentTrack.id) },
                    modifier = Modifier.testTag("mini_player_favorite_button")
                ) {
                    Icon(
                        imageVector = if (currentTrack.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (currentTrack.isFavorite) DeezerPinkAccent else TextSecondaryDark,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Play / Pause / Buffering button
                IconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(DeezerPurplePrimary)
                        .testTag("mini_player_play_pause")
                ) {
                    if (isBuffering && !isPlaying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.2.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Skip Next button
                IconButton(
                    onClick = onNext,
                    modifier = Modifier.testTag("mini_player_next_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next Track",
                        tint = TextPrimaryDark,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
