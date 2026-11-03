package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.model.AudioQualityInfo
import com.example.model.AudioTrack
import com.example.network.DownloadQualityOption
import com.example.network.UniversalStreamResolver
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.DeezerPinkAccent
import com.example.ui.theme.DeezerPurplePrimary
import com.example.ui.theme.HiResGold
import com.example.ui.theme.SurfaceDarkCard
import com.example.ui.theme.SurfaceDarkElevated
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryDark

@Composable
fun DownloadQualityDialog(
    track: AudioTrack,
    activeQualityInfo: AudioQualityInfo? = null,
    onConfirmDownload: (DownloadQualityOption) -> Unit,
    onDismiss: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val maxModalHeight = (configuration.screenHeightDp * 0.86f).dp.coerceAtLeast(360.dp)

    val dynamicOptions = remember(track, activeQualityInfo) {
        UniversalStreamResolver.getDynamicDownloadOptions(track, activeQualityInfo)
    }

    val defaultOptionItem = remember(dynamicOptions) {
        dynamicOptions.firstOrNull { it.isRecommended } ?: dynamicOptions.first()
    }

    var selectedOption by remember(defaultOptionItem) {
        mutableStateOf(defaultOptionItem.option)
    }

    val selectedItem = dynamicOptions.find { it.option == selectedOption } ?: defaultOptionItem

    val sourceHeaderLabel = remember(defaultOptionItem, activeQualityInfo) {
        val activeFormat = activeQualityInfo?.formatName ?: defaultOptionItem.formatName
        val activeBitDepth = activeQualityInfo?.bitDepth?.takeIf { it > 0 } ?: defaultOptionItem.bitDepth
        val activeSampleRate = activeQualityInfo?.sampleRateHz?.takeIf { it > 0 } ?: defaultOptionItem.sampleRateHz
        val activeBitrate = activeQualityInfo?.bitrateKbps?.takeIf { it > 0 } ?: defaultOptionItem.bitrateKbps
        val sampleRateFormatted = AudioQualityInfo.formatSampleRateKHz(activeSampleRate)
        if (defaultOptionItem.isLossless) {
            "Active Source: $activeFormat ${activeBitDepth}-BIT / $sampleRateFormatted • $activeBitrate kbps • Lossless / Hi-Res Bit-Perfect"
        } else {
            "Active Source: $activeFormat ${activeBitDepth}-BIT / $sampleRateFormatted • $activeBitrate kbps • High Quality Lossy"
        }
    }

    val scrollState = rememberScrollState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = SurfaceDarkElevated,
            tonalElevation = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .heightIn(max = maxModalHeight)
                .testTag("download_quality_dialog")
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Fixed Header Section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(DeezerPurplePrimary.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.HighQuality,
                                contentDescription = "Select Download Quality",
                                tint = HiResGold,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Select Download Quality",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimaryDark
                            )
                            Text(
                                text = "${track.title} • ${track.artist} (${track.durationFormatted})",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondaryDark,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Dynamic Source Stream Telemetry Banner
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceDarkCard)
                            .border(
                                width = 1.dp,
                                color = if (defaultOptionItem.isLossless) HiResGold.copy(alpha = 0.45f) else CyberCyan.copy(alpha = 0.35f),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("download_source_telemetry_banner")
                    ) {
                        Text(
                            text = sourceHeaderLabel,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (defaultOptionItem.isLossless) HiResGold else CyberCyan
                        )
                    }
                }

                // Scrollable Quality Cards Container (flex weight so it scrolls smoothly above pinned footer)
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .testTag("download_quality_scroll_list")
                ) {
                    dynamicOptions.forEach { item ->
                        val isSelected = selectedOption == item.option
                        val borderColor = when {
                            isSelected && item.isLossless -> HiResGold
                            isSelected && item.isRecommended -> CyberCyan
                            isSelected -> DeezerPinkAccent
                            else -> Color.White.copy(alpha = 0.08f)
                        }
                        val containerBg = if (isSelected) {
                            DeezerPurplePrimary.copy(alpha = 0.22f)
                        } else {
                            SurfaceDarkCard
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(containerBg)
                                .border(1.5.dp, borderColor, RoundedCornerShape(14.dp))
                                .clickable { selectedOption = item.option }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                                .testTag("quality_option_${item.option.name.lowercase()}")
                        ) {
                            Icon(
                                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                contentDescription = item.title,
                                tint = if (isSelected) {
                                    if (item.isLossless) HiResGold else DeezerPinkAccent
                                } else {
                                    TextSecondaryDark
                                },
                                modifier = Modifier.size(22.dp)
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (item.isLossless) HiResGold.copy(alpha = 0.18f)
                                            else DeezerPurplePrimary.copy(alpha = 0.25f)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = item.badgeText,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (item.isLossless) HiResGold else CyberCyan
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = if (item.isRecommended) "(Recommended) ${item.title}" else item.title,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (item.isLossless) HiResGold else TextPrimaryDark
                                )

                                Spacer(modifier = Modifier.height(2.dp))

                                Text(
                                    text = item.subtitle,
                                    fontSize = 11.sp,
                                    color = TextSecondaryDark
                                )
                            }
                        }
                    }
                }

                // Sticky Bottom Action Container pinned with SafeArea / NavigationBar inset padding
                HorizontalDivider(
                    color = Color.White.copy(alpha = 0.08f),
                    thickness = 1.dp
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceDarkElevated)
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("cancel_download_quality_button")
                    ) {
                        Text(
                            text = "Cancel",
                            color = TextSecondaryDark,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(
                        onClick = { onConfirmDownload(selectedOption) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedItem.isLossless) HiResGold else DeezerPinkAccent,
                            contentColor = if (selectedItem.isLossless) Color.Black else Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("confirm_download_quality_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val buttonText = if (selectedItem.isLossless) {
                            "Download ${selectedItem.formatName} (${selectedItem.estimatedSizeMb})"
                        } else {
                            "Download ${selectedItem.bitrateKbps} kbps (${selectedItem.estimatedSizeMb})"
                        }
                        Text(
                            text = buttonText,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

