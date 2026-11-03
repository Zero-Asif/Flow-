package com.example.util

import com.example.model.KaraokeLyricLine
import com.example.model.LyricSyllable

object LrcParser {

    private val LINE_TIME_REGEX = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?]""")
    private val SYLLABLE_TIME_REGEX = Regex("""<(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?>([^<]+)""")

    fun parse(lrcContent: String): List<KaraokeLyricLine> {
        if (lrcContent.isBlank()) return emptyList()

        val rawLines = lrcContent.lines()
        val parsedLines = mutableListOf<KaraokeLyricLine>()

        for (raw in rawLines) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("[ti:") || trimmed.startsWith("[ar:") ||
                trimmed.startsWith("[al:") || trimmed.startsWith("[by:") || trimmed.startsWith("[offset:")
            ) {
                continue
            }

            val match = LINE_TIME_REGEX.find(trimmed) ?: continue
            val timeMs = parseTimestamp(match.groupValues[1], match.groupValues[2], match.groupValues.getOrNull(3))
            val textAfterTime = trimmed.substring(match.range.last + 1).trim()

            // Check if syllables exist: <mm:ss.xx>word
            val syllables = mutableListOf<LyricSyllable>()
            val syllableMatches = SYLLABLE_TIME_REGEX.findAll(textAfterTime).toList()

            var cleanText = textAfterTime
            if (syllableMatches.isNotEmpty()) {
                cleanText = syllableMatches.joinToString("") { it.groupValues[4] }.trim()
                for (i in syllableMatches.indices) {
                    val sMatch = syllableMatches[i]
                    val sTimeMs = parseTimestamp(sMatch.groupValues[1], sMatch.groupValues[2], sMatch.groupValues.getOrNull(3))
                    val sText = sMatch.groupValues[4]
                    val nextTimeMs = if (i + 1 < syllableMatches.size) {
                        val nextMatch = syllableMatches[i + 1]
                        parseTimestamp(nextMatch.groupValues[1], nextMatch.groupValues[2], nextMatch.groupValues.getOrNull(3))
                    } else {
                        sTimeMs + 800L
                    }
                    val sDuration = (nextTimeMs - sTimeMs).coerceAtLeast(150L)
                    syllables.add(LyricSyllable(sTimeMs, sDuration, sText))
                }
            }

            parsedLines.add(KaraokeLyricLine(timeMs = timeMs, text = cleanText, syllables = syllables))
        }

        // Sort by timestamp
        val sorted = parsedLines.sortedBy { it.timeMs }

        // Post-process: if line has no syllables, generate proportional word timings so word-by-word karaoke animates smoothly
        val finalLines = mutableListOf<KaraokeLyricLine>()
        for (i in sorted.indices) {
            val current = sorted[i]
            if (current.syllables.isNotEmpty()) {
                finalLines.add(current)
            } else {
                val nextTime = if (i + 1 < sorted.size) sorted[i + 1].timeMs else (current.timeMs + 4000L)
                val lineDuration = (nextTime - current.timeMs).coerceIn(1000L, 8000L)
                val words = current.text.split(" ").filter { it.isNotBlank() }

                if (words.isEmpty()) {
                    finalLines.add(current)
                } else {
                    val totalChars = words.sumOf { it.length }.coerceAtLeast(1)
                    val generatedSyllables = mutableListOf<LyricSyllable>()
                    var elapsedMs = 0L

                    for (word in words) {
                        val wordDuration = (lineDuration * word.length / totalChars).coerceAtLeast(200L)
                        val wordStart = current.timeMs + elapsedMs
                        generatedSyllables.add(LyricSyllable(wordStart, wordDuration, "$word "))
                        elapsedMs += wordDuration
                    }
                    finalLines.add(current.copy(syllables = generatedSyllables))
                }
            }
        }

        return finalLines
    }

    private fun parseTimestamp(minStr: String, secStr: String, fracStr: String?): Long {
        val minutes = minStr.toLongOrNull() ?: 0L
        val seconds = secStr.toLongOrNull() ?: 0L
        val fractionMs = when {
            fracStr == null || fracStr.isEmpty() -> 0L
            fracStr.length == 1 -> fracStr.toLong() * 100L
            fracStr.length == 2 -> fracStr.toLong() * 10L
            else -> fracStr.take(3).toLong()
        }
        return (minutes * 60L + seconds) * 1000L + fractionMs
    }
}
