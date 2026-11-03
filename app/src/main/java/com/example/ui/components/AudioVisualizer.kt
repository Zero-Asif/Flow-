package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.DeezerPinkAccent
import com.example.ui.theme.DeezerPurplePrimary
import kotlin.math.abs

/**
 * Real-Time 24-Band Audio Spectrum Visualizer:
 * - Driven by live FFT frequency bins extracted from ExoPlayer's decoded PCM audio stream
 *   via RealTimeFftAudioProcessor (Bass/Kick: bars 0..7, Vocals/Mids: bars 8..15, Highs/Air: bars 16..23).
 * - Uses a 60fps frame-synced linear interpolation (lerp) loop with fast attack and smooth release
 *   so bars bounce naturally to the beat and decay smoothly to idle height when paused or silent.
 */
@Composable
fun AudioVisualizer(
    amplitudes: List<Float>,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
    barCount: Int = 24
) {
    val currentAmplitudes by rememberUpdatedState(amplitudes)
    val currentIsPlaying by rememberUpdatedState(isPlaying)

    val smoothedBars = remember(barCount) {
        mutableStateListOf<Float>().apply {
            repeat(barCount) { add(0.08f) }
        }
    }
    var frameTick by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(barCount) {
        while (true) {
            withFrameNanos { frameTimeNanos ->
                val targetList = currentAmplitudes
                val playing = currentIsPlaying
                var anyChanged = false

                for (i in 0 until barCount) {
                    val rawTarget = if (playing) {
                        targetList.getOrElse(i) { 0.08f }.coerceIn(0.08f, 1.0f)
                    } else {
                        0.08f
                    }

                    val current = smoothedBars[i]
                    val diff = rawTarget - current
                    if (abs(diff) > 0.002f) {
                        // Fast attack (0.42f) for bass/kick transients; smooth release decay (0.16f) when falling or paused
                        val lerpFactor = when {
                            !playing -> 0.14f
                            diff > 0f && i < 8 -> 0.45f // Sub-bass & kick drum punch
                            diff > 0f -> 0.36f          // Mid & treble attack
                            else -> 0.18f               // Natural gravity release
                        }
                        smoothedBars[i] = (current + diff * lerpFactor).coerceIn(0.08f, 1.0f)
                        anyChanged = true
                    } else if (current != rawTarget) {
                        smoothedBars[i] = rawTarget
                        anyChanged = true
                    }
                }

                if (anyChanged || playing) {
                    frameTick = (frameTimeNanos % 1_000_000L).toFloat()
                }
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        // Read frameTick to subscribe Canvas draw pass to 60fps smoothed bar updates
        val tick = frameTick
        if (tick < -1f) return@Canvas

        val totalWidth = size.width
        val canvasHeight = size.height
        val barSpacing = 4.dp.toPx()
        val totalSpacing = barSpacing * (barCount - 1)
        val barWidth = ((totalWidth - totalSpacing) / barCount).coerceAtLeast(2f)

        val brush = Brush.verticalGradient(
            colors = listOf(
                DeezerPinkAccent,
                DeezerPurplePrimary,
                CyberCyan
            )
        )

        for (i in 0 until barCount) {
            val amp = smoothedBars.getOrElse(i) { 0.08f }.coerceIn(0.08f, 1.0f)
            val barHeight = (canvasHeight * amp).coerceAtLeast(barWidth)
            val x = i * (barWidth + barSpacing)
            val y = (canvasHeight - barHeight) / 2f // Vertically centered waveform symmetry

            drawRoundRect(
                brush = brush,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

