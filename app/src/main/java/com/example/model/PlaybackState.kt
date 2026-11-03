package com.example.model

enum class RepeatMode {
    OFF,
    ALL,
    ONE
}

enum class FlowMood(val displayName: String, val iconDescription: String) {
    ALL("All Flow", "Personalized mix"),
    CHILL("Chill", "Relaxing acoustic and lo-fi"),
    FOCUS("Focus", "Ambient and deep synth"),
    WORKOUT("Workout", "High energy electronic"),
    NIGHT_DRIVE("Night Drive", "Moody synthwave and retrowave"),
    MELANCHOLY("Melancholy", "Emotive piano and slow ballads")
}

data class PlaybackState(
    val currentTrack: AudioTrack? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queue: List<AudioTrack> = emptyList(),
    val queueIndex: Int = 0,
    val isShuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.ALL,
    val gaplessEnabled: Boolean = true,
    val crossfadeMs: Int = 0, // 0 for strict bit-perfect gapless
    val sleepTimerSecondsRemaining: Int? = null,
    val visualizerAmplitudes: List<Float> = List(24) { 0.2f }
)
