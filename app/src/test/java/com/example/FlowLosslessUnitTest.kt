package com.example

import com.example.audio.PcmAudioSynthesizer
import com.example.data.SampleTracks
import com.example.model.EqualizerPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowLosslessUnitTest {

    @Test
    fun testSampleTracksAreHighFidelity() {
        val tracks = SampleTracks.ALL_TRACKS
        assertTrue("Track library should not be empty", tracks.isNotEmpty())

        for (track in tracks) {
            assertTrue("Track should have positive duration", track.durationMs > 0)
            assertTrue("Track bit depth should be at least 16-bit", track.bitDepth >= 16)
            assertTrue("Track sample rate should be at least 44100 Hz", track.sampleRate >= 44100)
            assertTrue("Lossless format should be marked correctly", track.format.isLossless)
            assertNotNull("Track gradient colors should exist", track.gradientColors)
        }
    }

    @Test
    fun testEqualizerPresets() {
        assertEquals("Band frequencies must be 10", 10, EqualizerPreset.BAND_FREQUENCIES.size)
        val flat = EqualizerPreset.FLAT
        assertEquals(10, flat.bandGains.size)
        assertTrue(flat.bandGains.all { it == 0f })

        for (preset in EqualizerPreset.ALL_PRESETS) {
            assertEquals("All presets must have 10 band gains", 10, preset.bandGains.size)
            assertTrue("Bass boost must be within 0..1", preset.bassBoost in 0f..1f)
            assertTrue("Virtualizer must be within 0..1", preset.virtualizer in 0f..1f)
        }
    }

    @Test
    fun testProceduralAudioSynthesizer() {
        val chunk = PcmAudioSynthesizer.generateChunk(
            preset = "synthwave_pulse",
            secondInTrack = 0,
            sampleCount = 1000,
            bassMultiplier = 1.0f,
            trebleMultiplier = 1.0f,
            virtualizerWidth = 0.2f
        )
        // Stereo has 2 channels
        assertEquals(2000, chunk.size)
        // Ensure not all zeros (audio is generated)
        val hasSound = chunk.any { it != 0.toShort() }
        assertTrue("Synthesized audio buffer should contain valid waveform data", hasSound)
    }

    @Test
    fun testFlacAndAlacFormatsSupported() {
        val flacTracks = SampleTracks.ALL_TRACKS.filter { it.format == com.example.model.AudioFormat.FLAC }
        val alacTracks = SampleTracks.ALL_TRACKS.filter { it.format == com.example.model.AudioFormat.ALAC }

        assertTrue("Should include FLAC tracks in library", flacTracks.isNotEmpty())
        assertTrue("Should include ALAC tracks in library", alacTracks.isNotEmpty())

        assertEquals("FLAC", com.example.model.AudioFormat.FLAC.displayName)
        assertEquals("ALAC", com.example.model.AudioFormat.ALAC.displayName)
        assertTrue(com.example.model.AudioFormat.FLAC.isLossless)
        assertTrue(com.example.model.AudioFormat.ALAC.isLossless)
    }

    @Test
    fun testGlobalMusicTrackProperties() {
        val globalTrack = com.example.model.AudioTrack(
            id = "itunes_12345",
            title = "Around the World",
            artist = "Daft Punk",
            album = "Homework",
            durationMs = 240000L,
            format = com.example.model.AudioFormat.ALAC,
            sampleRate = 96000,
            bitDepth = 24,
            bitrateKbps = 2822,
            genre = "Electronic",
            releaseYear = 1997,
            gradientColors = listOf(0xFFA855F7, 0xFF00F0FF),
            uriString = "https://audio-ssl.itunes.apple.com/preview.m4a",
            artworkUrl = "https://is1-ssl.mzstatic.com/image/thumb/600x600bb.jpg",
            isGlobalOnline = true,
            fileSizeFormatted = "32.0 MB"
        )

        assertTrue(globalTrack.isGlobalOnline)
        assertNotNull(globalTrack.artworkUrl)
        assertEquals("ALAC", globalTrack.format.displayName)
        assertTrue(globalTrack.format.isLossless)
        assertEquals(24, globalTrack.bitDepth)
    }
}
