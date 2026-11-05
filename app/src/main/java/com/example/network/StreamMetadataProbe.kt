package com.example.network

import com.example.audio.AudioMetadataProber
import com.example.model.AudioQualityInfo

/**
 * StreamMetadataProbe:
 * Consolidated into the unified AudioMetadataProber source of truth.
 * Forwards all calls to com.example.audio.AudioMetadataProber.
 */
object StreamMetadataProbe {

    fun getCachedQuality(url: String): AudioQualityInfo? {
        return AudioMetadataProber.getCachedQuality(url)
    }

    suspend fun probeStream(
        url: String,
        durationMs: Long = 0L,
        fallbackFormat: String = "AAC",
        fallbackBitrate: Int = 320,
        fallbackSampleRate: Int = 44100,
        fallbackBitDepth: Int = 16
    ): AudioQualityInfo {
        return AudioMetadataProber.probeStream(
            url = url,
            durationMs = durationMs,
            fallbackFormat = fallbackFormat,
            fallbackBitrate = fallbackBitrate,
            fallbackSampleRate = fallbackSampleRate,
            fallbackBitDepth = fallbackBitDepth
        )
    }
}
