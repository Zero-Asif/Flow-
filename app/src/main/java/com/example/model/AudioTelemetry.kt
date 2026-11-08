package com.example.model

data class AudioQualityInfo(
    val formatName: String = "PROBING",
    val sampleRateHz: Int = 44100,
    val bitDepth: Int = 16,
    val bitrateKbps: Int = 0,
    val channelCount: Int = 2,
    val isLossless: Boolean = false,
    val isHiRes: Boolean = false
) {
    val qualityTierLabel: String
        get() = when {
            isLossless -> "BIT-PERFECT"
            bitrateKbps >= 256 || isHiRes -> "HIGH QUALITY"
            bitrateKbps > 0 -> "STANDARD"
            else -> "STREAMING"
        }

    fun getBadgeText(mode: BadgeDisplayMode): String = when (mode) {
        BadgeDisplayMode.AUDIOPHILE_FULL -> {
            val codecStr = formatName.ifBlank { "AUDIO" }.uppercase()
            val bitDepthStr = "${bitDepth.coerceAtLeast(16)}-BIT / "
            val rateStr = formatSampleRateKHz(sampleRateHz)
            val bitRateStr = if (bitrateKbps > 0) " • $bitrateKbps kbps" else ""
            val tierTag = " • $qualityTierLabel"
            "$codecStr $bitDepthStr$rateStr$bitRateStr$tierTag"
        }
        BadgeDisplayMode.CLEAN_STREAMING -> {
            val tier = when {
                isLossless && isHiRes -> "Hi-Res Master"
                isLossless -> "Lossless Audio"
                isHiRes -> "Hi-Res Master"
                bitrateKbps >= 256 -> "High Quality"
                else -> "Standard Stream"
            }
            val rate = if (bitrateKbps > 0) "$bitrateKbps kbps" else formatSampleRateKHz(sampleRateHz)
            "$tier • $rate"
        }
        BadgeDisplayMode.MINIMAL_BADGE -> {
            val prefix = when {
                isLossless && isHiRes -> "[Hi-Res] "
                isLossless -> "[Lossless] "
                isHiRes -> "[Hi-Res] "
                bitrateKbps >= 256 -> "[HQ] "
                else -> ""
            }
            "$prefix$formatName"
        }
    }

    companion object {
        fun formatSampleRateKHz(sampleRateHz: Int): String {
            if (sampleRateHz <= 0) return "44.1kHz"
            return when (sampleRateHz) {
                44100 -> "44.1kHz"
                88200 -> "88.2kHz"
                176400 -> "176.4kHz"
                else -> {
                    if (sampleRateHz % 1000 == 0) {
                        "${sampleRateHz / 1000}kHz"
                    } else {
                        String.format(java.util.Locale.US, "%.1fkHz", sampleRateHz / 1000.0)
                    }
                }
            }
        }
    }
}

enum class BadgeDisplayMode(val label: String) {
    AUDIOPHILE_FULL("Full Audiophile (Bit-Depth & Rate)"),
    CLEAN_STREAMING("Clean Stream (Tier & Bitrate)"),
    MINIMAL_BADGE("Minimalist (Hi-Res Tag)")
}

enum class StreamBackend(val label: String, val description: String) {
    AUTO_HIGH_FIDELITY("Auto Hi-Fi (Recommended)", "Best full-length high bitrate stream"),
    JIOSAAVN_320K("JioSaavn 320k Stream", "Full 320kbps MP3 / AAC Master direct audio"),
    PIPED_OPUS("Piped Opus Stream", "160kbps WebM Opus full audio container"),
    INVIDIOUS_AAC("Invidious AAC Stream", "256kbps high-fidelity AAC stream"),
    LOCAL_LOSSLESS("Local Lossless Master", "24-bit 96kHz procedural PCM audio")
}


