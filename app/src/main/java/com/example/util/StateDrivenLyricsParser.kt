package com.example.util

import com.example.model.KaraokeLyricLine
import com.example.model.LyricSyllable

enum class LyricsPlaybackStatus {
    IDLE,
    INTRO,
    VOCAL_ACTIVE,
    INSTRUMENTAL_BREAK,
    OUTRO
}

data class LyricsSyncState(
    val status: LyricsPlaybackStatus = LyricsPlaybackStatus.IDLE,
    val activeLineIndex: Int = -1,
    val activeLine: KaraokeLyricLine? = null,
    val activeSyllableIndex: Int = -1,
    val activeSyllable: LyricSyllable? = null,
    val lineProgress: Float = 0f,
    val timeToNextLineMs: Long = 0L,
    val currentPositionMs: Long = 0L
)

object StateDrivenLyricsParser {

    /**
     * State-driven parser and synchronization engine that transitions between playback
     * states (INTRO, VOCAL_ACTIVE, INSTRUMENTAL_BREAK, OUTRO) and computes real-time
     * syllable progress against ExoPlayer currentPosition.
     */
    fun sync(lyrics: List<KaraokeLyricLine>, currentPositionMs: Long): LyricsSyncState {
        if (lyrics.isEmpty()) {
            return LyricsSyncState(status = LyricsPlaybackStatus.IDLE, currentPositionMs = currentPositionMs)
        }

        val firstLine = lyrics.first()

        // 1. INTRO state before first vocal line
        if (currentPositionMs < firstLine.timeMs) {
            return LyricsSyncState(
                status = LyricsPlaybackStatus.INTRO,
                activeLineIndex = -1,
                activeLine = null,
                timeToNextLineMs = firstLine.timeMs - currentPositionMs,
                currentPositionMs = currentPositionMs
            )
        }

        // 2. Active line search
        val activeIndex = lyrics.indexOfLast { it.timeMs <= currentPositionMs }
        if (activeIndex == -1) {
            return LyricsSyncState(status = LyricsPlaybackStatus.IDLE, currentPositionMs = currentPositionMs)
        }

        val activeLine = lyrics[activeIndex]
        val nextLine = if (activeIndex + 1 < lyrics.size) lyrics[activeIndex + 1] else null

        // Determine line duration for progress calculation
        val lineDuration = if (nextLine != null) {
            (nextLine.timeMs - activeLine.timeMs).coerceAtLeast(1000L)
        } else {
            5000L
        }

        val timeIntoLine = (currentPositionMs - activeLine.timeMs).coerceAtLeast(0L)
        val progress = (timeIntoLine.toFloat() / lineDuration.toFloat()).coerceIn(0f, 1f)

        // Check for instrumental break (gap between lines > 8s and passed vocal sustain)
        val isInstrumentalBreak = nextLine != null && (nextLine.timeMs - activeLine.timeMs > 8000L) && (timeIntoLine > 4500L)

        val status = when {
            isInstrumentalBreak -> LyricsPlaybackStatus.INSTRUMENTAL_BREAK
            activeIndex == lyrics.size - 1 && timeIntoLine > 6000L -> LyricsPlaybackStatus.OUTRO
            else -> LyricsPlaybackStatus.VOCAL_ACTIVE
        }

        // 3. Find active syllable / word
        var activeSyllableIdx = -1
        var activeSyllable: LyricSyllable? = null
        if (activeLine.syllables.isNotEmpty()) {
            activeSyllableIdx = activeLine.syllables.indexOfLast { it.timeMs <= currentPositionMs }
            if (activeSyllableIdx != -1) {
                activeSyllable = activeLine.syllables[activeSyllableIdx]
            }
        }

        val timeToNext = if (nextLine != null) (nextLine.timeMs - currentPositionMs).coerceAtLeast(0L) else 0L

        return LyricsSyncState(
            status = status,
            activeLineIndex = activeIndex,
            activeLine = activeLine,
            activeSyllableIndex = activeSyllableIdx,
            activeSyllable = activeSyllable,
            lineProgress = progress,
            timeToNextLineMs = timeToNext,
            currentPositionMs = currentPositionMs
        )
    }
}
