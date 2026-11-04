package com.example.model

enum class AudioFormat(val displayName: String, val isLossless: Boolean) {
    FLAC("FLAC", true),
    ALAC("ALAC", true),
    WAV("WAV", true),
    AIFF("AIFF", true),
    MP3("MP3", false),
    AAC("AAC", false)
}

data class LyricLine(
    val timeMs: Long,
    val text: String
)

data class AudioTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val format: AudioFormat = AudioFormat.FLAC,
    val sampleRate: Int = 96000, // 96 kHz Hi-Res
    val bitDepth: Int = 24,       // 24-bit studio master
    val bitrateKbps: Int = 2822,
    val genre: String = "Synthwave",
    val releaseYear: Int = 2024,
    val gradientColors: List<Long> = listOf(0xFF8B5CF6, 0xFFEC4899),
    val lyrics: List<LyricLine> = emptyList(),
    val uriString: String? = null,
    val artworkUrl: String? = null,
    val isGlobalOnline: Boolean = false,
    val synthPreset: String = "cosmic_synth",
    val isFavorite: Boolean = false,
    val playCount: Int = 0,
    val lastPlayedTimestamp: Long = 0L,
    val isLocalImport: Boolean = false,
    val fileSizeFormatted: String = "32.4 MB",
    val isDownloaded: Boolean = false,
    val localFilePath: String? = null,
    val sourceProvider: String = "Flow Lossless",
    val isrc: String? = null
) {
    val isOfflineAvailable: Boolean
        get() = isDownloaded || isLocalImport || !localFilePath.isNullOrBlank()

    val fidelityBadge: String
        get() = "${format.displayName} ${bitDepth}B/${sampleRate / 1000}kHz"

    val durationFormatted: String
        get() {
            val totalSeconds = durationMs / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }
}
