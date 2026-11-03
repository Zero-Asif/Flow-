package com.example.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.SpatialAudio
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.EqualizerPreset
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.DeezerPinkAccent
import com.example.ui.theme.DeezerPurplePrimary
import com.example.ui.theme.HiResGold
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.SurfaceDarkCard
import com.example.ui.theme.SurfaceDarkElevated
import com.example.ui.theme.TextPrimaryDark
import com.example.ui.theme.TextSecondaryDark

@Composable
fun EqualizerScreen(
    activePreset: EqualizerPreset,
    gaplessEnabled: Boolean,
    onSelectPreset: (EqualizerPreset) -> Unit,
    onBandChange: (Int, Float) -> Unit,
    onBassBoostChange: (Float) -> Unit,
    onVirtualizerChange: (Float) -> Unit,
    onToggleGapless: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("equalizer_screen")
            .padding(top = 16.dp),
        contentPadding = PaddingValues(bottom = 90.dp)
    ) {
        // Equalizer Header
        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Audiophile Equalizer",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Black,
                                color = TextPrimaryDark
                            )
                        )
                        Text(
                            text = "10-Band Graphic DSP • 32-bit Floating Point",
                            fontSize = 12.sp,
                            color = CyberCyan
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceDarkCard)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = activePreset.name,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = DeezerPinkAccent
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(14.dp))
        }

        // Preset Selection Chips
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(EqualizerPreset.ALL_PRESETS) { preset ->
                    val isSelected = activePreset.name == preset.name
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectPreset(preset) },
                        label = {
                            Text(
                                text = preset.name,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = DeezerPurplePrimary,
                            selectedLabelColor = Color.White,
                            containerColor = SurfaceDarkElevated,
                            labelColor = TextSecondaryDark
                        ),
                        border = null,
                        shape = RoundedCornerShape(20.dp)
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Frequency Response Curve Canvas
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDarkElevated)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "FREQUENCY RESPONSE CURVE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondaryDark,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "±12 dB",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = HiResGold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    FrequencyCurveCanvas(
                        bandGains = activePreset.bandGains,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(20.dp))
        }

        // 10-Band Sliders
        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = "10 FREQUENCY BANDS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondaryDark,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                EqualizerPreset.BAND_FREQUENCIES.forEachIndexed { index, freqName ->
                    val currentGain = activePreset.bandGains.getOrElse(index) { 0f }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = freqName,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimaryDark,
                            modifier = Modifier.width(52.dp)
                        )

                        Slider(
                            value = currentGain,
                            onValueChange = { onBandChange(index, it) },
                            valueRange = -12f..12f,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("eq_band_slider_$index"),
                            colors = SliderDefaults.colors(
                                thumbColor = DeezerPinkAccent,
                                activeTrackColor = DeezerPurplePrimary,
                                inactiveTrackColor = SurfaceDarkCard
                            )
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        val sign = if (currentGain > 0) "+" else ""
                        Text(
                            text = "$sign%.1f dB".format(currentGain),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (currentGain != 0f) CyberCyan else TextSecondaryDark,
                            modifier = Modifier.width(52.dp)
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Sound Enhancement Modules: Bass Boost & 3D Virtualizer
        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                Text(
                    text = "ACOUSTIC ENHANCEMENTS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondaryDark,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Bass Boost Card
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDarkElevated)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = DeezerPinkAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Bass Boost",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimaryDark
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${(activePreset.bassBoost * 100).toInt()}%",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = DeezerPinkAccent
                            )
                            Slider(
                                value = activePreset.bassBoost,
                                onValueChange = onBassBoostChange,
                                valueRange = 0f..1f,
                                colors = SliderDefaults.colors(
                                    thumbColor = DeezerPinkAccent,
                                    activeTrackColor = DeezerPinkAccent,
                                    inactiveTrackColor = SurfaceDarkCard
                                )
                            )
                        }
                    }

                    // 3D Virtualizer Card
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDarkElevated)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.SurroundSound,
                                    contentDescription = null,
                                    tint = CyberCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "3D Spatializer",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimaryDark
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${(activePreset.virtualizer * 100).toInt()}%",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Black,
                                color = CyberCyan
                            )
                            Slider(
                                value = activePreset.virtualizer,
                                onValueChange = onVirtualizerChange,
                                valueRange = 0f..1f,
                                colors = SliderDefaults.colors(
                                    thumbColor = CyberCyan,
                                    activeTrackColor = CyberCyan,
                                    inactiveTrackColor = SurfaceDarkCard
                                )
                            )
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(18.dp))
        }

        // Gapless Playback Switch Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDarkElevated)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Gapless Playback",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimaryDark
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(SuccessGreen.copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "Bit-Perfect",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SuccessGreen
                                )
                            }
                        }
                        Text(
                            text = "Zero-second silent gap between tracks for seamless live albums and classical suites.",
                            fontSize = 12.sp,
                            color = TextSecondaryDark
                        )
                    }

                    Switch(
                        checked = gaplessEnabled,
                        onCheckedChange = { onToggleGapless() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = DeezerPurplePrimary,
                            uncheckedThumbColor = TextSecondaryDark,
                            uncheckedTrackColor = SurfaceDarkCard
                        ),
                        modifier = Modifier.testTag("gapless_playback_switch")
                    )
                }
            }
        }
    }
}

@Composable
fun FrequencyCurveCanvas(
    bandGains: List<Float>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val midY = h / 2f

        // Draw horizontal grid center line (0 dB)
        drawLine(
            color = Color.White.copy(alpha = 0.12f),
            start = Offset(0f, midY),
            end = Offset(w, midY),
            strokeWidth = 1.dp.toPx()
        )

        // Draw +6dB and -6dB guide lines
        val quarterH = h / 4f
        drawLine(
            color = Color.White.copy(alpha = 0.05f),
            start = Offset(0f, midY - quarterH),
            end = Offset(w, midY - quarterH),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
            color = Color.White.copy(alpha = 0.05f),
            start = Offset(0f, midY + quarterH),
            end = Offset(w, midY + quarterH),
            strokeWidth = 1.dp.toPx()
        )

        if (bandGains.isEmpty()) return@Canvas

        val path = Path()
        val fillPath = Path()

        val points = mutableListOf<Offset>()
        val step = w / (bandGains.size - 1).coerceAtLeast(1)

        for (i in bandGains.indices) {
            val x = i * step
            val gain = bandGains[i] // -12 to +12
            // Map gain to y: +12 -> 0, -12 -> h
            val y = midY - (gain / 12f) * (midY - 8.dp.toPx())
            points.add(Offset(x, y))
        }

        // Smooth cubic curve through points
        path.moveTo(points.first().x, points.first().y)
        fillPath.moveTo(points.first().x, points.first().y)

        for (i in 0 until points.size - 1) {
            val p0 = points[i]
            val p1 = points[i + 1]
            val cx1 = (p0.x + p1.x) / 2f
            val cy1 = p0.y
            val cx2 = (p0.x + p1.x) / 2f
            val cy2 = p1.y
            path.cubicTo(cx1, cy1, cx2, cy2, p1.x, p1.y)
            fillPath.cubicTo(cx1, cy1, cx2, cy2, p1.x, p1.y)
        }

        fillPath.lineTo(w, h)
        fillPath.lineTo(0f, h)
        fillPath.close()

        // Draw gradient fill below curve
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    DeezerPinkAccent.copy(alpha = 0.25f),
                    DeezerPurplePrimary.copy(alpha = 0.1f),
                    Color.Transparent
                )
            )
        )

        // Draw stroke line
        drawPath(
            path = path,
            brush = Brush.horizontalGradient(
                colors = listOf(DeezerPinkAccent, DeezerPurplePrimary, CyberCyan)
            ),
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )

        // Draw nodes at each band point
        points.forEach { pt ->
            drawCircle(
                color = Color.White,
                radius = 3.dp.toPx(),
                center = pt
            )
        }
    }
}
