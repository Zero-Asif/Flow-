package com.example.model

data class LyricSyllable(
    val timeMs: Long,
    val durationMs: Long,
    val text: String
) {
    val startTimeMs: Long
        get() = timeMs
}

data class KaraokeLyricLine(
    val timeMs: Long,
    val text: String,
    val syllables: List<LyricSyllable> = emptyList()
) {
    val startTimeMs: Long
        get() = timeMs
}

enum class LyricsDisplayMode(val label: String) {
    LINE_GLOW("Line Glow"),
    SYLLABLE_KARAOKE("Karaoke Word-by-Word"),
    DUAL_SUBTITLES("Subtitles")
}
