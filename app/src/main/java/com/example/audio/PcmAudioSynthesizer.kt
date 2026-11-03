package com.example.audio

import kotlin.math.PI
import kotlin.math.sin

/**
 * High-fidelity procedural audio generator for offline audiophile playback.
 * Generates rich musical chords, melodic pads, arpeggios, and warm sub-bass
 * using multi-oscillator additive synthesis with stereo panning.
 */
object PcmAudioSynthesizer {
    const val SAMPLE_RATE = 44100
    const val CHANNELS = 2 // Stereo

    // Musical note frequencies (Hz)
    private val NOTES = mapOf(
        "C2" to 65.41f, "D2" to 73.42f, "E2" to 82.41f, "F2" to 87.31f, "G2" to 98.00f, "A2" to 110.00f, "B2" to 123.47f,
        "C3" to 130.81f, "D3" to 146.83f, "E3" to 164.81f, "F3" to 174.61f, "G3" to 196.00f, "A3" to 220.00f, "B3" to 246.94f,
        "C4" to 261.63f, "D4" to 293.66f, "E4" to 329.63f, "F4" to 349.23f, "G4" to 392.00f, "A4" to 440.00f, "B4" to 493.88f,
        "C5" to 523.25f, "D5" to 587.33f, "E5" to 659.25f, "F5" to 698.46f, "G5" to 783.99f, "A5" to 880.00f, "B5" to 987.77f
    )

    private fun note(name: String): Float = NOTES[name] ?: 220.0f

    /**
     * Synthesizes 1 second of stereo 16-bit PCM audio samples for a given track preset
     * and current playback second.
     */
    fun generateChunk(
        preset: String,
        secondInTrack: Int,
        sampleCount: Int = SAMPLE_RATE / 4, // 250ms buffer chunk
        bassMultiplier: Float = 1.0f,
        trebleMultiplier: Float = 1.0f,
        virtualizerWidth: Float = 0.0f
    ): ShortArray {
        val buffer = ShortArray(sampleCount * CHANNELS)
        val chordProgression = when (preset) {
            "synthwave_pulse" -> listOf(
                listOf(note("A2"), note("C4"), note("E4"), note("A4")),
                listOf(note("F2"), note("A3"), note("C4"), note("F4")),
                listOf(note("C2"), note("E3"), note("G3"), note("C4")),
                listOf(note("G2"), note("B3"), note("D4"), note("G4"))
            )
            "ambient_dream" -> listOf(
                listOf(note("C3"), note("G3"), note("E4"), note("G4")),
                listOf(note("A2"), note("E3"), note("C4"), note("E4")),
                listOf(note("F2"), note("C3"), note("A3"), note("C4")),
                listOf(note("G2"), note("D3"), note("B3"), note("D4"))
            )
            "lofi_chill" -> listOf(
                listOf(note("D3"), note("F3"), note("A3"), note("C4")),
                listOf(note("G2"), note("B3"), note("D4"), note("F4")),
                listOf(note("C3"), note("E3"), note("G3"), note("B3")),
                listOf(note("A2"), note("C4"), note("E4"), note("G4"))
            )
            else -> listOf(
                listOf(note("C3"), note("E3"), note("G3"), note("B3")),
                listOf(note("A2"), note("C4"), note("E4"), note("G4")),
                listOf(note("F2"), note("A3"), note("C4"), note("E4")),
                listOf(note("G2"), note("B3"), note("D4"), note("G4"))
            )
        }

        val chordIndex = (secondInTrack / 4) % chordProgression.size
        val currentNotes = chordProgression[chordIndex]
        val rootFreq = currentNotes.firstOrNull() ?: 110f

        val startSample = (secondInTrack * SAMPLE_RATE) % (4 * SAMPLE_RATE)

        for (i in 0 until sampleCount) {
            val globalSample = startSample + i
            val time = globalSample.toFloat() / SAMPLE_RATE

            // 1. Warm Sub Bass
            val bassCycle = 2.0 * PI * rootFreq * time
            val bassSample = (sin(bassCycle) * 0.45 * bassMultiplier).toFloat()

            // 2. Chord Harmony Pad
            var padSample = 0f
            for ((noteIdx, noteFreq) in currentNotes.withIndex()) {
                val detune = 1.0f + (noteIdx * 0.002f)
                val padCycle = 2.0 * PI * (noteFreq * detune) * time
                val harmonic = sin(padCycle * 2.0) * 0.15
                padSample += ((sin(padCycle) + harmonic) * 0.12).toFloat()
            }

            // 3. Arpeggiator sparkle (every 1/4 second)
            val arpStep = ((time * 4).toInt()) % currentNotes.size
            val arpFreq = currentNotes[arpStep] * 2.0f
            val arpCycle = 2.0 * PI * arpFreq * time
            val arpEnvelope = (1.0f - ((time * 4) % 1.0f)).coerceIn(0f, 1f)
            val arpSample = (sin(arpCycle) * 0.18 * arpEnvelope * trebleMultiplier).toFloat()

            // Combine audio signals
            var mixedMono = (bassSample + padSample + arpSample).coerceIn(-1.0f, 1.0f)

            // Stereo panning & 3D Spatializer width
            val panL = (0.5 - 0.2 * sin(2.0 * PI * 0.25 * time) - (virtualizerWidth.toDouble() * 0.3)).coerceIn(0.1, 0.9).toFloat()
            val panR = (1.0f - panL)

            val leftValue = (mixedMono * panL * 32767.0f * 0.7f).toInt().coerceIn(-32768, 32767)
            val rightValue = (mixedMono * panR * 32767.0f * 0.7f).toInt().coerceIn(-32768, 32767)

            buffer[i * 2] = leftValue.toShort()
            buffer[i * 2 + 1] = rightValue.toShort()
        }

        return buffer
    }
}
