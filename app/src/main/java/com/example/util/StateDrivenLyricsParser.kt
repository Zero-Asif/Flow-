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
    val currentPositionMs: Long = 0L,
    val scrollResetTrigger: Long = 0L
)

object StateDrivenLyricsParser {

    /** +200ms vocal onset lead-in compensation window. */
    const val VOCAL_LEAD_IN_MS = 200L

    /**
     * State-driven parser and synchronization engine that transitions between playback
     * states (INTRO, VOCAL_ACTIVE, INSTRUMENTAL_BREAK, OUTRO) and computes real-time
     * line and syllable progress with +200ms vocal alignment lead compensation.
     */
    fun sync(lyricsList: List<KaraokeLyricLine>, currentPositionMs: Long): LyricsSyncState {
        if (lyricsList.isEmpty()) {
            return LyricsSyncState(status = LyricsPlaybackStatus.IDLE, currentPositionMs = currentPositionMs)
        }

        val targetPosition = currentPositionMs + VOCAL_LEAD_IN_MS
        val firstLine = lyricsList.first()

        // 1. INTRO state before first vocal line (with +200ms lead window)
        if (targetPosition < firstLine.startTimeMs) {
            return LyricsSyncState(
                status = LyricsPlaybackStatus.INTRO,
                activeLineIndex = 0,
                activeLine = firstLine,
                timeToNextLineMs = (firstLine.startTimeMs - currentPositionMs).coerceAtLeast(0L),
                currentPositionMs = currentPositionMs
            )
        }

        // 2. Active line search with +200ms lead compensation
        val activeLineIndex = lyricsList.indexOfLast { it.startTimeMs <= targetPosition }.coerceAtLeast(0)
        val activeLine = lyricsList[activeLineIndex]
        val nextLine = if (activeLineIndex + 1 < lyricsList.size) lyricsList[activeLineIndex + 1] else null

        // Determine line duration for progress calculation
        val lineDuration = if (nextLine != null) {
            (nextLine.startTimeMs - activeLine.startTimeMs).coerceAtLeast(1000L)
        } else {
            5000L
        }

        val timeIntoLine = (targetPosition - activeLine.startTimeMs).coerceAtLeast(0L)
        val progress = (timeIntoLine.toFloat() / lineDuration.toFloat()).coerceIn(0f, 1f)

        // Check for instrumental break (gap between lines > 8s and passed vocal sustain)
        val isInstrumentalBreak = nextLine != null &&
            (nextLine.startTimeMs - activeLine.startTimeMs > 8000L) &&
            (timeIntoLine > 4500L)

        val status = when {
            isInstrumentalBreak -> LyricsPlaybackStatus.INSTRUMENTAL_BREAK
            activeLineIndex == lyricsList.size - 1 && timeIntoLine > 6000L -> LyricsPlaybackStatus.OUTRO
            else -> LyricsPlaybackStatus.VOCAL_ACTIVE
        }

        // 3. Find active syllable / word with +200ms lead compensation
        var activeSyllableIdx = -1
        var activeSyllable: LyricSyllable? = null
        if (activeLine.syllables.isNotEmpty()) {
            activeSyllableIdx = activeLine.syllables.indexOfLast { it.startTimeMs <= targetPosition }
            if (activeSyllableIdx != -1) {
                activeSyllable = activeLine.syllables[activeSyllableIdx]
            }
        }

        val timeToNext = if (nextLine != null) (nextLine.startTimeMs - currentPositionMs).coerceAtLeast(0L) else 0L

        return LyricsSyncState(
            status = status,
            activeLineIndex = activeLineIndex,
            activeLine = activeLine,
            activeSyllableIndex = activeSyllableIdx,
            activeSyllable = activeSyllable,
            lineProgress = progress,
            timeToNextLineMs = timeToNext,
            currentPositionMs = currentPositionMs
        )
    }
}
