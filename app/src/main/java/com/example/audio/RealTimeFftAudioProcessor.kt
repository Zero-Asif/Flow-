package com.example.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time PCM AudioProcessor for Media3 ExoPlayer that taps into decoded audio frames
 * and performs an in-place Radix-2 Cooley-Tukey Fast Fourier Transform (FFT, N=256)
 * to extract 24 frequency spectrum bands:
 * - Bands 0..7: Sub-bass & Kick drum (20 Hz - 250 Hz)
 * - Bands 8..15: Midrange & Vocals (250 Hz - 2,500 Hz)
 * - Bands 16..23: Presence & Treble / Hi-hats (2,500 Hz - 16,000 Hz)
 *
 * Passes all audio bytes through completely unmodified for bit-perfect playback.
 */
@OptIn(UnstableApi::class)
class RealTimeFftAudioProcessor : BaseAudioProcessor() {

    companion object {
        private const val FFT_SIZE = 256
        private const val NUM_BANDS = 24
        private const val IDLE_LEVEL = 0.06f
    }

    private val pcmRingBuffer = FloatArray(FFT_SIZE)
    private var ringWriteIndex = 0
    private var samplesSinceLastFft = 0

    // Precomputed Hann window to prevent spectral leakage
    private val hannWindow = FloatArray(FFT_SIZE) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1)))).toFloat()
    }

    private val fftReal = FloatArray(FFT_SIZE)
    private val fftImag = FloatArray(FFT_SIZE)

    @Volatile
    private var latestBands: FloatArray = FloatArray(NUM_BANDS) { IDLE_LEVEL }

    @Volatile
    private var lastUpdateTimestampMs: Long = 0L

    @Volatile
    private var isCurrentlyPlaying: Boolean = false

    fun setPlaying(playing: Boolean) {
        isCurrentlyPlaying = playing
        if (!playing) {
            lastUpdateTimestampMs = 0L
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_24BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_32BIT
        ) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        // Duplicate slice for FFT analysis so original buffer position is untouched for passthrough
        val analysisSlice = inputBuffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        extractSamplesAndRunFft(analysisSlice, inputAudioFormat)

        // Pass audio through unmodified to hardware sink
        val outputBuffer = replaceOutputBuffer(remaining)
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
    }

    private fun extractSamplesAndRunFft(
        buffer: ByteBuffer,
        format: AudioProcessor.AudioFormat
    ) {
        val channels = format.channelCount.coerceAtLeast(1)
        val encoding = format.encoding

        try {
            when (encoding) {
                C.ENCODING_PCM_16BIT -> {
                    val frameSize = 2 * channels
                    while (buffer.remaining() >= frameSize) {
                        var monoSum = 0f
                        for (ch in 0 until channels) {
                            monoSum += buffer.short / 32768f
                        }
                        pushSample(monoSum / channels)
                    }
                }
                C.ENCODING_PCM_FLOAT -> {
                    val frameSize = 4 * channels
                    while (buffer.remaining() >= frameSize) {
                        var monoSum = 0f
                        for (ch in 0 until channels) {
                            val s = buffer.float
                            if (!s.isNaN() && !s.isInfinite()) {
                                monoSum += s.coerceIn(-1f, 1f)
                            }
                        }
                        pushSample(monoSum / channels)
                    }
                }
                C.ENCODING_PCM_24BIT -> {
                    val frameSize = 3 * channels
                    while (buffer.remaining() >= frameSize) {
                        var monoSum = 0f
                        for (ch in 0 until channels) {
                            val b0 = buffer.get().toInt() and 0xFF
                            val b1 = buffer.get().toInt() and 0xFF
                            val b2 = buffer.get().toInt()
                            val sample24 = (b2 shl 16) or (b1 shl 8) or b0
                            monoSum += sample24 / 8388608f
                        }
                        pushSample(monoSum / channels)
                    }
                }
                C.ENCODING_PCM_32BIT -> {
                    val frameSize = 4 * channels
                    while (buffer.remaining() >= frameSize) {
                        var monoSum = 0f
                        for (ch in 0 until channels) {
                            monoSum += buffer.int / 2147483648f
                        }
                        pushSample(monoSum / channels)
                    }
                }
                else -> Unit
            }
        } catch (_: Exception) {
            // Ignore buffer boundary edge cases
        }
    }

    private fun pushSample(sample: Float) {
        pcmRingBuffer[ringWriteIndex] = sample
        ringWriteIndex = (ringWriteIndex + 1) and (FFT_SIZE - 1)
        samplesSinceLastFft++

        // Compute FFT every 128 samples (~2.9ms at 44.1kHz)
        if (samplesSinceLastFft >= FFT_SIZE / 2) {
            samplesSinceLastFft = 0
            computeSpectrumBands()
        }
    }

    private fun computeSpectrumBands() {
        for (i in 0 until FFT_SIZE) {
            val idx = (ringWriteIndex + i) and (FFT_SIZE - 1)
            fftReal[i] = pcmRingBuffer[idx] * hannWindow[i]
            fftImag[i] = 0f
        }

        // In-place Cooley-Tukey Radix-2 FFT
        var j = 0
        for (i in 0 until FFT_SIZE) {
            if (i < j) {
                val tempR = fftReal[i]
                fftReal[i] = fftReal[j]
                fftReal[j] = tempR
            }
            var m = FFT_SIZE shr 1
            while (m >= 1 && j >= m) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        var step = 1
        while (step < FFT_SIZE) {
            val jump = step shl 1
            val deltaAngle = -PI / step
            val sineHalf = sin(0.5 * deltaAngle)
            val multiplierRe = -2.0 * sineHalf * sineHalf
            val multiplierIm = sin(deltaAngle)
            var wr = 1.0
            var wi = 0.0

            for (group in 0 until step) {
                var pair = group
                while (pair < FFT_SIZE) {
                    val match = pair + step
                    val prodRe = (wr * fftReal[match] - wi * fftImag[match]).toFloat()
                    val prodIm = (wr * fftImag[match] + wi * fftReal[match]).toFloat()
                    fftReal[match] = fftReal[pair] - prodRe
                    fftImag[match] = fftImag[pair] - prodIm
                    fftReal[pair] += prodRe
                    fftImag[pair] += prodIm
                    pair += jump
                }
                val nextWr = wr + wr * multiplierRe - wi * multiplierIm
                wi += wi * multiplierRe + wr * multiplierIm
                wr = nextWr
            }
            step = jump
        }

        // Map N/2 (128) FFT bins into 24 logarithmic audiophile frequency bands
        val halfSize = FFT_SIZE / 2
        val newBands = FloatArray(NUM_BANDS)
        val prevBands = latestBands

        for (band in 0 until NUM_BANDS) {
            // Logarithmic bin boundaries from bin 1 (sub-bass) to bin 116 (brilliance/treble)
            val startRatio = (band.toDouble() / NUM_BANDS).pow(1.65)
            val endRatio = ((band + 1).toDouble() / NUM_BANDS).pow(1.65)
            val startBin = (1 + (startRatio * (halfSize - 2)).toInt()).coerceIn(1, halfSize - 1)
            val endBin = max(startBin, (1 + (endRatio * (halfSize - 2)).toInt()).coerceIn(1, halfSize - 1))

            var energySum = 0f
            var count = 0
            for (bin in startBin..endBin) {
                val mag = hypot(fftReal[bin], fftImag[bin]) / (FFT_SIZE / 4f)
                energySum += mag
                count++
            }
            val avgMag = if (count > 0) energySum / count else 0f

            // Frequency-dependent perceptual weighting (A-weighting / Fletcher-Munson compensation
            // so mid and high frequencies visually balance with strong sub-bass energy)
            val freqBoost = when {
                band < 6 -> 2.4f   // Bass / Kick drum punch
                band < 14 -> 3.6f  // Vocal & snare presence
                else -> 5.2f       // Hi-hat & treble detail
            }

            // Convert magnitude to dB-like perceptual scale [0.06f .. 1.0f]
            val scaled = sqrt((avgMag * freqBoost).coerceAtLeast(0f))
            val target = (IDLE_LEVEL + scaled * 0.94f).coerceIn(IDLE_LEVEL, 1.0f)

            // Fast attack (0.55) for responsive beat transients, smooth release (0.25)
            val prev = prevBands[band]
            newBands[band] = if (target > prev) {
                prev + (target - prev) * 0.55f
            } else {
                prev + (target - prev) * 0.25f
            }
        }

        latestBands = newBands
        lastUpdateTimestampMs = System.currentTimeMillis()
    }

    /**
     * Returns the current 24-band real-time FFT spectrum, or smoothly decays toward IDLE_LEVEL
     * if playback is paused or audio frames have stopped arriving.
     */
    fun getCurrentSpectrumBands(isPlaying: Boolean, bassBoost: Float = 0f): List<Float> {
        val current = latestBands
        val ageMs = System.currentTimeMillis() - lastUpdateTimestampMs

        if (!isPlaying || ageMs > 250L) {
            // Smoothly decay existing bands toward idle minimum height
            val decayed = FloatArray(NUM_BANDS) { i ->
                val v = current[i]
                val next = v + (IDLE_LEVEL - v) * 0.28f
                if (next - IDLE_LEVEL < 0.005f) IDLE_LEVEL else next
            }
            latestBands = decayed
            return decayed.toList()
        }

        val bassGain = 1.0f + (bassBoost.coerceIn(0f, 1f) * 0.28f)
        return List(NUM_BANDS) { i ->
            val boost = if (i < 8) bassGain else 1.0f
            (current[i] * boost).coerceIn(IDLE_LEVEL, 1.0f)
        }
    }

    override fun onFlush() {
        ringWriteIndex = 0
        samplesSinceLastFft = 0
    }

    override fun onReset() {
        ringWriteIndex = 0
        samplesSinceLastFft = 0
        latestBands = FloatArray(NUM_BANDS) { IDLE_LEVEL }
    }
}
