package com.example.model

data class ListeningStats(
    val totalSecondsListened: Long = 0L,
    val totalTracksPlayed: Int = 0,
    val losslessRatio: Float = 1.0f, // 100% lossless FLAC/ALAC
    val topArtists: List<Pair<String, Int>> = emptyList(),
    val topGenres: List<Pair<String, Int>> = emptyList(),
    val favoriteFormats: List<Pair<AudioFormat, Int>> = emptyList(),
    val audioOutputLatencyMs: Float = 4.2f, // Audiophile low latency indicator
    val directBitPerfectDac: Boolean = true
) {
    val totalHoursFormatted: String
        get() {
            val hours = totalSecondsListened / 3600f
            return if (hours >= 1.0f) "%.1f hrs".format(hours) else "${totalSecondsListened / 60} mins"
        }
}
