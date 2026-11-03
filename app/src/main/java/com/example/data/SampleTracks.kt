package com.example.data

import com.example.model.AudioFormat
import com.example.model.AudioTrack
import com.example.model.LyricLine

object SampleTracks {
    val ALL_TRACKS: List<AudioTrack> = listOf(
        AudioTrack(
            id = "track_1",
            title = "Midnight Horizon",
            artist = "Neon Odyssey",
            album = "Retrowave Dreams",
            durationMs = 214000L,
            format = AudioFormat.FLAC,
            sampleRate = 96000,
            bitDepth = 24,
            bitrateKbps = 2950,
            genre = "Synthwave",
            releaseYear = 2024,
            gradientColors = listOf(0xFF8B5CF6, 0xFFEC4899),
            synthPreset = "synthwave_pulse",
            fileSizeFormatted = "44.2 MB",
            lyrics = listOf(
                LyricLine(4000L, "City skyline glowing through the digital mist"),
                LyricLine(12000L, "Analog memories we could never resist"),
                LyricLine(24000L, "Cruising the neon freeway into the night"),
                LyricLine(36000L, "Synthesizer echoes burning ultra violet bright"),
                LyricLine(48000L, "Feel the sub-bass pulse beneath the asphalt sea"),
                LyricLine(62000L, "Lossless frequencies of pure electric harmony"),
                LyricLine(80000L, "Where the tape rewinds and colors intertwine"),
                LyricLine(98000L, "Lost in the pulse of another timeline")
            )
        ),
        AudioTrack(
            id = "track_2",
            title = "Ethereal Drift",
            artist = "Aura Bloom",
            album = "Sublime Atmospheres",
            durationMs = 195000L,
            format = AudioFormat.ALAC,
            sampleRate = 192000,
            bitDepth = 24,
            bitrateKbps = 4608,
            genre = "Ambient",
            releaseYear = 2024,
            gradientColors = listOf(0xFF06B6D4, 0xFF3B82F6),
            synthPreset = "ambient_dream",
            fileSizeFormatted = "67.8 MB",
            lyrics = listOf(
                LyricLine(5000L, "Floating softly above cloud silhouettes"),
                LyricLine(18000L, "Drifting in spaces the waking world forgets"),
                LyricLine(32000L, "Crystalline harmonics hanging in the quiet air"),
                LyricLine(48000L, "Gentle resonance without a solitary care"),
                LyricLine(66000L, "Close your eyes and breathe the silence deep"),
                LyricLine(84000L, "Where soundwaves shimmer as the stars begin to leap")
            )
        ),
        AudioTrack(
            id = "track_3",
            title = "Rainy Day Tape",
            artist = "Komorebi Beats",
            album = "Coffee & Rain",
            durationMs = 168000L,
            format = AudioFormat.FLAC,
            sampleRate = 96000,
            bitDepth = 24,
            bitrateKbps = 2740,
            genre = "Lo-Fi",
            releaseYear = 2023,
            gradientColors = listOf(0xFFF59E0B, 0xFFEF4444),
            synthPreset = "lofi_chill",
            fileSizeFormatted = "34.6 MB",
            lyrics = listOf(
                LyricLine(6000L, "Warm cup in hand, drops drumming on the glass"),
                LyricLine(16000L, "Watching the slow grey afternoon hours pass"),
                LyricLine(30000L, "Vinyl warmth with seven-th chords in sway"),
                LyricLine(45000L, "Melting the rush of the bustling world away"),
                LyricLine(62000L, "Dusty Rhodes keys, soft jazz snare in time"),
                LyricLine(78000L, "Every gentle beat a timeless rhyme")
            )
        ),
        AudioTrack(
            id = "track_4",
            title = "Celestial Symphony No. 5",
            artist = "Orchestra Hyperion",
            album = "Infinite Cosmos",
            durationMs = 280000L,
            format = AudioFormat.FLAC,
            sampleRate = 96000,
            bitDepth = 24,
            bitrateKbps = 3200,
            genre = "Orchestral",
            releaseYear = 2024,
            gradientColors = listOf(0xFF6366F1, 0xFF8B5CF6),
            synthPreset = "orchestral_dawn",
            fileSizeFormatted = "58.1 MB",
            lyrics = listOf(
                LyricLine(8000L, "Horns herald the crest of the morning star"),
                LyricLine(24000L, "Strings ascending towards galaxies afar"),
                LyricLine(42000L, "Timpani thundering across the velvet space"),
                LyricLine(64000L, "A grand crescendo in time and grace"),
                LyricLine(90000L, "Unbroken dynamics, bit-perfect clarity"),
                LyricLine(120000L, "The universe alive in sonic verity")
            )
        ),
        AudioTrack(
            id = "track_5",
            title = "Tokyo Midnight Drive",
            artist = "Cyber Ronin",
            album = "Neo Shibuya 2099",
            durationMs = 230000L,
            format = AudioFormat.FLAC,
            sampleRate = 96000,
            bitDepth = 24,
            bitrateKbps = 3100,
            genre = "Cyberpunk",
            releaseYear = 2024,
            gradientColors = listOf(0xFFFF007A, 0xFF00F0FF),
            synthPreset = "cyber_drive",
            fileSizeFormatted = "49.0 MB",
            lyrics = listOf(
                LyricLine(5000L, "Holographic rain reflecting on the glass"),
                LyricLine(18000L, "Accelerating through the speed-trap pass"),
                LyricLine(32000L, "Overclocked engines and distorted basslines"),
                LyricLine(50000L, "Flickering ads and radioactive signs"),
                LyricLine(70000L, "No speed limits in the digital dark"),
                LyricLine(88000L, "Leaving behind an electric spark")
            )
        ),
        AudioTrack(
            id = "track_6",
            title = "Blue Note Reverie",
            artist = "Miles Henderson Quartet",
            album = "After Hours in Harlem",
            durationMs = 245000L,
            format = AudioFormat.WAV,
            sampleRate = 96000,
            bitDepth = 24,
            bitrateKbps = 4608,
            genre = "Jazz",
            releaseYear = 2023,
            gradientColors = listOf(0xFFD97706, 0xFF451A03),
            synthPreset = "jazz_groove",
            fileSizeFormatted = "80.6 MB",
            lyrics = listOf(
                LyricLine(7000L, "Muted trumpet crying in a smoky bar"),
                LyricLine(22000L, "Walking upright bass that travels far"),
                LyricLine(40000L, "Brushed cymbals whispering on the beat"),
                LyricLine(60000L, "Cobblestone footsteps on the empty street"),
                LyricLine(82000L, "Pure organic warmth, untouched by gain"),
                LyricLine(105000L, "Jazz melodies that wash the rain")
            )
        ),
        AudioTrack(
            id = "track_7",
            title = "Golden Hour Acoustic",
            artist = "Canyon & Pine",
            album = "Folk Across the Ridge",
            durationMs = 208000L,
            format = AudioFormat.FLAC,
            sampleRate = 96000,
            bitDepth = 24,
            bitrateKbps = 2650,
            genre = "Acoustic",
            releaseYear = 2023,
            gradientColors = listOf(0xFF10B981, 0xFF047857),
            synthPreset = "acoustic_warm",
            fileSizeFormatted = "41.2 MB",
            lyrics = listOf(
                LyricLine(5000L, "Pine needles crunching on the mountain trail"),
                LyricLine(17000L, "Amber sun shining through the cedar veil"),
                LyricLine(34000L, "Six steel strings resonating clear and wide"),
                LyricLine(52000L, "Singing alongside the river's tide"),
                LyricLine(72000L, "Home is wherever the song begins"),
                LyricLine(92000L, "Carried gently on the autumn winds")
            )
        ),
        AudioTrack(
            id = "track_8",
            title = "Supernova Pulse",
            artist = "Cosmo Dynamic",
            album = "Galactic Rave",
            durationMs = 222000L,
            format = AudioFormat.ALAC,
            sampleRate = 96000,
            bitDepth = 24,
            bitrateKbps = 2900,
            genre = "Electronic",
            releaseYear = 2024,
            gradientColors = listOf(0xFF9333EA, 0xFFC026D3),
            synthPreset = "synthwave_pulse",
            fileSizeFormatted = "46.3 MB",
            lyrics = listOf(
                LyricLine(4000L, "Building up the pressure in the core"),
                LyricLine(15000L, "Gravitational collapse forevermore"),
                LyricLine(28000L, "Release the shockwave through the floor"),
                LyricLine(44000L, "Can you hear the stellar sirens roar?"),
                LyricLine(60000L, "Drop the frequency into light speed"),
                LyricLine(80000L, "Energy is all our senses need")
            )
        )
    )
}
