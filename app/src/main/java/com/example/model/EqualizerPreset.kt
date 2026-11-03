package com.example.model

data class EqualizerPreset(
    val name: String,
    // 10 bands: 31Hz, 63Hz, 125Hz, 250Hz, 500Hz, 1kHz, 2kHz, 4kHz, 8kHz, 16kHz
    // Gain in dB: -12.0f to +12.0f
    val bandGains: List<Float>,
    val bassBoost: Float = 0f, // 0.0f to 1.0f
    val virtualizer: Float = 0f, // 0.0f to 1.0f
    val isCustom: Boolean = false
) {
    companion object {
        val BAND_FREQUENCIES = listOf(
            "31Hz", "63Hz", "125Hz", "250Hz", "500Hz",
            "1kHz", "2kHz", "4kHz", "8kHz", "16kHz"
        )

        val FLAT = EqualizerPreset(
            name = "Bit-Perfect (Flat)",
            bandGains = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            bassBoost = 0f,
            virtualizer = 0f
        )

        val AUDIOPHILE_REFERENCE = EqualizerPreset(
            name = "Audiophile Reference",
            bandGains = listOf(1.5f, 1.0f, 0.5f, 0.0f, -0.5f, 0.0f, 1.0f, 1.5f, 2.0f, 2.5f),
            bassBoost = 0.2f,
            virtualizer = 0.15f
        )

        val BASS_BOOST = EqualizerPreset(
            name = "Deezer Bass Heavy",
            bandGains = listOf(6.0f, 5.0f, 4.0f, 2.0f, 0.5f, -0.5f, 0.0f, 1.5f, 2.0f, 1.0f),
            bassBoost = 0.75f,
            virtualizer = 0.25f
        )

        val ELECTRONIC = EqualizerPreset(
            name = "Electronic / Synth",
            bandGains = listOf(4.5f, 4.0f, 2.0f, 0.0f, -1.0f, 1.5f, 2.5f, 3.5f, 4.0f, 4.5f),
            bassBoost = 0.5f,
            virtualizer = 0.4f
        )

        val VOCAL_CLARITY = EqualizerPreset(
            name = "Vocal Clarity",
            bandGains = listOf(-2.0f, -1.0f, 0.0f, 1.0f, 2.5f, 4.0f, 3.5f, 2.0f, 1.0f, 0.5f),
            bassBoost = 0.1f,
            virtualizer = 0.3f
        )

        val JAZZ_WARM = EqualizerPreset(
            name = "Acoustic & Jazz Warmth",
            bandGains = listOf(2.5f, 2.0f, 1.5f, 1.0f, 0.5f, 0.5f, 1.0f, 1.5f, 2.0f, 1.5f),
            bassBoost = 0.3f,
            virtualizer = 0.35f
        )

        val ALL_PRESETS = listOf(
            FLAT,
            AUDIOPHILE_REFERENCE,
            BASS_BOOST,
            ELECTRONIC,
            VOCAL_CLARITY,
            JAZZ_WARM
        )
    }
}
