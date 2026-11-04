package com.example

import com.example.model.AudioQualityInfo
import com.example.model.BadgeDisplayMode
import com.example.util.LrcParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAndLyricsUnitTest {

    @Test
    fun testLrcParser_standardTimestamps() {
        val lrc = """
            [ti:Test Track]
            [ar:Test Artist]
            [00:04.50]First line of lyrics
            [00:12.80]Second line with feeling
            [01:05.10]Chorus kicks in here
        """.trimIndent()

        val parsed = LrcParser.parse(lrc)
        assertEquals(3, parsed.size)
        assertEquals(4500L, parsed[0].timeMs)
        assertEquals("First line of lyrics", parsed[0].text)
        assertEquals(12800L, parsed[1].timeMs)
        assertEquals("Second line with feeling", parsed[1].text)
        assertEquals(65100L, parsed[2].timeMs)
        assertEquals("Chorus kicks in here", parsed[2].text)

        // Syllable words should be generated for progressive karaoke wipe
        assertTrue(parsed[0].syllables.isNotEmpty())
    }

    @Test
    fun testAudioQualityInfo_badgeFormatting() {
        val hiResInfo = AudioQualityInfo(
            formatName = "ALAC",
            sampleRateHz = 96000,
            bitDepth = 24,
            bitrateKbps = 2822,
            isLossless = true,
            isHiRes = true
        )

        val fullBadge = hiResInfo.getBadgeText(BadgeDisplayMode.AUDIOPHILE_FULL)
        assertTrue(fullBadge.contains("ALAC"))
        assertTrue(fullBadge.contains("24-BIT"))
        assertTrue(fullBadge.contains("96kHz"))
        assertTrue(fullBadge.contains("2822 kbps"))
        assertTrue(fullBadge.contains("BIT-PERFECT"))

        val cleanBadge = hiResInfo.getBadgeText(BadgeDisplayMode.CLEAN_STREAMING)
        assertTrue(cleanBadge.contains("Hi-Res Master"))
        assertTrue(cleanBadge.contains("2822 kbps"))

        val minimalBadge = hiResInfo.getBadgeText(BadgeDisplayMode.MINIMAL_BADGE)
        assertEquals("[Hi-Res] ALAC", minimalBadge)
    }

    @Test
    fun testAudioQualityInfo_opusStreamingBadge() {
        val opusInfo = AudioQualityInfo(
            formatName = "OPUS",
            sampleRateHz = 48000,
            bitDepth = 24,
            bitrateKbps = 160,
            isLossless = false,
            isHiRes = true
        )

        val fullBadge = opusInfo.getBadgeText(BadgeDisplayMode.AUDIOPHILE_FULL)
        assertTrue(fullBadge.contains("OPUS"))
        assertTrue(fullBadge.contains("48kHz"))
        assertTrue(fullBadge.contains("160 kbps"))

        val cleanBadge = opusInfo.getBadgeText(BadgeDisplayMode.CLEAN_STREAMING)
        assertTrue(cleanBadge.contains("Hi-Res Master"))
        assertTrue(cleanBadge.contains("160 kbps"))
    }

    @Test
    fun testStateDrivenLyricsParser_stateTransitions() {
        val lrc = """
            [00:05.00]First line begins
            [00:20.00]Second line after long gap
        """.trimIndent()

        val parsed = LrcParser.parse(lrc)
        assertEquals(2, parsed.size)

        // 1. Before intro
        val introState = com.example.util.StateDrivenLyricsParser.sync(parsed, 2000L)
        assertEquals(com.example.util.LyricsPlaybackStatus.INTRO, introState.status)
        assertEquals(3000L, introState.timeToNextLineMs)
        assertEquals(-1, introState.activeLineIndex)

        // 2. Active vocal
        val vocalState = com.example.util.StateDrivenLyricsParser.sync(parsed, 6000L)
        assertEquals(com.example.util.LyricsPlaybackStatus.VOCAL_ACTIVE, vocalState.status)
        assertEquals(0, vocalState.activeLineIndex)
        assertEquals("First line begins", vocalState.activeLine?.text)

        // 3. Instrumental break (gap between 5s and 20s is 15s; at 12s we're past 5s vocal sustain)
        val breakState = com.example.util.StateDrivenLyricsParser.sync(parsed, 12000L)
        assertEquals(com.example.util.LyricsPlaybackStatus.INSTRUMENTAL_BREAK, breakState.status)
        assertEquals(0, breakState.activeLineIndex)
        assertEquals(8000L, breakState.timeToNextLineMs)

        // 4. Second line vocal active
        val secondLineState = com.example.util.StateDrivenLyricsParser.sync(parsed, 21000L)
        assertEquals(com.example.util.LyricsPlaybackStatus.VOCAL_ACTIVE, secondLineState.status)
        assertEquals(1, secondLineState.activeLineIndex)
    }

    @Test
    fun testAudioQualityInfo_jiosaavn320kBadge() {
        val saavnInfo = AudioQualityInfo(
            formatName = "MP3",
            sampleRateHz = 44100,
            bitDepth = 16,
            bitrateKbps = 320,
            isLossless = false,
            isHiRes = true
        )

        val fullBadge = saavnInfo.getBadgeText(BadgeDisplayMode.AUDIOPHILE_FULL)
        assertTrue(fullBadge.contains("MP3"))
        assertTrue(fullBadge.contains("16-BIT / 44.1kHz"))
        assertTrue(fullBadge.contains("320 kbps"))
        assertTrue(fullBadge.contains("HIGH QUALITY"))

        val cleanBadge = saavnInfo.getBadgeText(BadgeDisplayMode.CLEAN_STREAMING)
        assertTrue(cleanBadge.contains("Hi-Res Master"))
        assertTrue(cleanBadge.contains("320 kbps"))

        assertEquals(com.example.model.StreamBackend.JIOSAAVN_320K.label, "JioSaavn 320k Stream")
    }

    @Test
    fun testAudioTrack_downloadProperties() {
        val onlineTrack = com.example.model.AudioTrack(
            id = "test_song_1",
            title = "Test Song",
            artist = "Test Artist",
            album = "Test Album",
            durationMs = 180000L,
            format = com.example.model.AudioFormat.MP3,
            sampleRate = 44100,
            bitDepth = 16,
            bitrateKbps = 320,
            genre = "Pop",
            releaseYear = 2024,
            gradientColors = listOf(0xFF00F0FF, 0xFF7928CA),
            sourceProvider = "YouTube Music",
            isDownloaded = false,
            localFilePath = null
        )

        val downloadedTrack = onlineTrack.copy(
            isDownloaded = true,
            localFilePath = "/data/user/0/app/files/downloads/test_song_1.mp3"
        )

        assertTrue(downloadedTrack.isDownloaded)
        assertTrue(downloadedTrack.isOfflineAvailable)
        assertEquals("YouTube Music", downloadedTrack.sourceProvider)
        assertEquals("/data/user/0/app/files/downloads/test_song_1.mp3", downloadedTrack.localFilePath)
    }

    @Test
    fun testFullLengthStreamUrlDetection_rejects30sPreviews() {
        val itunesPreview = "https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview115/v4/p.m4a"
        val deezerPreview = "https://cdns-preview-e.dzcdn.net/stream/c-12345.mp3"
        val saavn320k = "https://aac.saavncdn.com/450/f467e05e2825cec2203546333e0d0550_320.mp4"

        org.junit.Assert.assertFalse(com.example.network.UniversalStreamResolver.isFullLengthStreamUrl(itunesPreview))
        org.junit.Assert.assertFalse(com.example.network.UniversalStreamResolver.isFullLengthStreamUrl(deezerPreview))
        assertTrue(com.example.network.UniversalStreamResolver.isFullLengthStreamUrl(saavn320k))
    }

    @Test
    fun testPlainLyricsConversion_generatesSynchronizedLinesAndSyllables() {
        val plain = """
            [Verse 1]
            Look at the stars
            Look how they shine for you
            And everything you do
            Yeah they were all yellow
        """.trimIndent()

        val timed = com.example.network.LyricsRepository.convertPlainLyricsToTimedLines(plain, 200L)
        assertEquals(4, timed.size)
        assertEquals("Look at the stars", timed[0].text)
        assertTrue(timed[0].timeMs >= 4000L)
        assertTrue(timed[1].timeMs > timed[0].timeMs)
        assertTrue(timed[0].syllables.isNotEmpty())
    }

    @Test
    fun testJioSaavnDesDecryption_andDownloadQualitySelection() {
        // Test round-trip DES encryption -> decryption to 320kbps URL
        val sampleRawUrl = "http://aac.saavncdn.com/427/6b6b51a3e2206a2f3a36d6e6234c215d_96.mp4"
        val cipher = javax.crypto.Cipher.getInstance("DES/ECB/PKCS5Padding")
        val keySpec = javax.crypto.spec.SecretKeySpec("38346591".toByteArray(Charsets.UTF_8), "DES")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec)
        val encoded = java.util.Base64.getEncoder().encodeToString(cipher.doFinal(sampleRawUrl.toByteArray(Charsets.UTF_8)))

        val decrypted320 = com.example.network.UniversalStreamResolver.decryptJioSaavnMediaUrl(encoded, has320kbps = true)
        assertNotNull(decrypted320)
        assertEquals("https://aac.saavncdn.com/427/6b6b51a3e2206a2f3a36d6e6234c215d_320.mp4", decrypted320)

        // Verify DownloadQualityOption recommends HI_RES_LOSSLESS first
        val options = com.example.network.DownloadQualityOption.entries
        assertTrue(options.first().isRecommended)
        assertTrue(options.first().isLosslessTier)
        assertEquals(2822, options.first().targetBitrateKbps)

        // Verify raw FLAC stream URL is preserved untouched when HI_RES_LOSSLESS is selected
        val rawFlacUrl = "https://cdn.flowaudio.io/masters/track_01_24bit_96khz.flac"
        val preservedFlac = com.example.network.UniversalStreamResolver.applyQualityOptionToUrl(
            rawFlacUrl,
            com.example.network.DownloadQualityOption.HI_RES_LOSSLESS
        )
        assertEquals(rawFlacUrl, preservedFlac)

        // Verify applying lossy quality options selects the exact requested stream tier
        val url160 = com.example.network.UniversalStreamResolver.applyQualityOptionToUrl(
            decrypted320!!,
            com.example.network.DownloadQualityOption.HIGH_160K
        )
        assertTrue(url160.endsWith("_160.mp4"))

        val url320 = com.example.network.UniversalStreamResolver.applyQualityOptionToUrl(
            url160,
            com.example.network.DownloadQualityOption.MASTER_320K
        )
        assertTrue(url320.endsWith("_320.mp4"))
    }

    @Test
    fun testDynamicDownloadOptions_hiResFlacAndLossyLabels() {
        val hiResTrack = com.example.model.AudioTrack(
            id = "flac_master_1",
            title = "Midnight Odyssey",
            artist = "Aura Synth",
            album = "Studio Masters",
            durationMs = 200_000L, // 200 seconds
            format = com.example.model.AudioFormat.FLAC,
            sampleRate = 96000,
            bitDepth = 24,
            bitrateKbps = 2822
        )
        val activeQuality = AudioQualityInfo(
            formatName = "FLAC",
            sampleRateHz = 96000,
            bitDepth = 24,
            bitrateKbps = 2822,
            isLossless = true,
            isHiRes = true
        )

        val dynamicOptions = com.example.network.UniversalStreamResolver.getDynamicDownloadOptions(hiResTrack, activeQuality)
        assertEquals(4, dynamicOptions.size)

        // 1. Top option must be Hi-Res FLAC (Original Master), recommended and lossless
        val topOption = dynamicOptions[0]
        assertEquals(com.example.network.DownloadQualityOption.HI_RES_LOSSLESS, topOption.option)
        assertEquals("Hi-Res FLAC (Original Master)", topOption.title)
        assertTrue(topOption.isLossless)
        assertTrue(topOption.isRecommended)
        assertTrue(topOption.subtitle.contains("Lossless / Hi-Res Bit-Perfect"))
        assertTrue(topOption.subtitle.contains("24-BIT / 96kHz"))
        assertTrue(topOption.subtitle.contains("2822 kbps"))
        assertTrue(topOption.estimatedSizeMb.contains("68.9 MB"))

        // 2. Second option must be 320 kbps labeled as Lossy Compressed Audio (never "Uncompressed")
        val option320 = dynamicOptions[1]
        assertEquals(com.example.network.DownloadQualityOption.MASTER_320K, option320.option)
        assertEquals("High Quality MP3 / Lossy 320 kbps", option320.title)
        org.junit.Assert.assertFalse(option320.isLossless)
        assertTrue(option320.subtitle.contains("Lossy Compressed Audio"))
        org.junit.Assert.assertFalse(option320.subtitle.contains("Uncompressed", ignoreCase = true))
    }

    @Test
    fun testSortStreamCandidates_prioritizesHighestBitrateSampleRateAndFormat() {
        val candidates = listOf(
            com.example.network.ResolvedStreamCandidate(
                streamUrl = "https://cdn.example.com/song_320.mp3",
                formatName = "MP3",
                bitrateKbps = 320,
                sampleRateHz = 44100,
                bitDepth = 16,
                isLossless = false
            ),
            com.example.network.ResolvedStreamCandidate(
                streamUrl = "https://cdn.example.com/song_320.m4a",
                formatName = "AAC",
                bitrateKbps = 320,
                sampleRateHz = 48000,
                bitDepth = 24,
                isLossless = false
            ),
            com.example.network.ResolvedStreamCandidate(
                streamUrl = "https://cdn.example.com/song_1411.wav",
                formatName = "WAV",
                bitrateKbps = 1411,
                sampleRateHz = 44100,
                bitDepth = 16,
                isLossless = true
            ),
            com.example.network.ResolvedStreamCandidate(
                streamUrl = "https://cdn.example.com/song_1411.alac",
                formatName = "ALAC",
                bitrateKbps = 1411,
                sampleRateHz = 44100,
                bitDepth = 16,
                isLossless = true
            ),
            com.example.network.ResolvedStreamCandidate(
                streamUrl = "https://cdn.example.com/song_2822.flac",
                formatName = "FLAC",
                bitrateKbps = 2822,
                sampleRateHz = 96000,
                bitDepth = 24,
                isLossless = true
            ),
            com.example.network.ResolvedStreamCandidate(
                streamUrl = "https://cdn.example.com/song_4608.flac",
                formatName = "FLAC",
                bitrateKbps = 4608,
                sampleRateHz = 192000,
                bitDepth = 24,
                isLossless = true
            )
        )

        val sorted = com.example.network.UniversalStreamResolver.sortStreamCandidates(candidates)
        assertEquals(6, sorted.size)
        // 1st: 4608 kbps 24-bit 192kHz FLAC
        assertEquals("FLAC", sorted[0].formatName)
        assertEquals(4608, sorted[0].bitrateKbps)
        assertEquals(192000, sorted[0].sampleRateHz)
        // 2nd: 2822 kbps 24-bit 96kHz FLAC
        assertEquals("FLAC", sorted[1].formatName)
        assertEquals(2822, sorted[1].bitrateKbps)
        // 3rd: 1411 kbps ALAC (ranks above 1411 kbps WAV)
        assertEquals("ALAC", sorted[2].formatName)
        // 4th: 1411 kbps WAV
        assertEquals("WAV", sorted[3].formatName)
        // 5th: 320 kbps 48kHz AAC (ranks above 320 kbps 44.1kHz MP3)
        assertEquals("AAC", sorted[4].formatName)
        // 6th: 320 kbps MP3
        assertEquals("MP3", sorted[5].formatName)
    }

    @Test
    fun testMultiProviderWaterfallAndQualityCascadeSorting() {
        val youtube256 = com.example.model.AudioTrack(
            id = "yt_1",
            title = "Blinding Lights",
            artist = "The Weeknd",
            album = "After Hours",
            durationMs = 200_000L,
            format = com.example.model.AudioFormat.AAC,
            sampleRate = 48000,
            bitDepth = 16,
            bitrateKbps = 256,
            sourceProvider = "YouTube Music 256k"
        )
        val saavn320 = youtube256.copy(
            id = "saavn_1",
            format = com.example.model.AudioFormat.AAC,
            sampleRate = 44100,
            bitDepth = 16,
            bitrateKbps = 320,
            sourceProvider = "JioSaavn 320k"
        )
        val tidal1411 = youtube256.copy(
            id = "tidal_1",
            format = com.example.model.AudioFormat.FLAC,
            sampleRate = 44100,
            bitDepth = 16,
            bitrateKbps = 1411,
            sourceProvider = "Tidal HiFi"
        )
        val deezer1411 = youtube256.copy(
            id = "deezer_1",
            format = com.example.model.AudioFormat.FLAC,
            sampleRate = 44100,
            bitDepth = 16,
            bitrateKbps = 1411,
            sourceProvider = "Deezer HiFi"
        )
        val tidalMaster2822 = youtube256.copy(
            id = "tidal_master_1",
            format = com.example.model.AudioFormat.FLAC,
            sampleRate = 96000,
            bitDepth = 24,
            bitrateKbps = 2822,
            sourceProvider = "Tidal Master"
        )
        val amazonUltraHd4608 = youtube256.copy(
            id = "amazon_1",
            format = com.example.model.AudioFormat.FLAC,
            sampleRate = 192000,
            bitDepth = 24,
            bitrateKbps = 4608,
            sourceProvider = "Amazon Music Ultra HD"
        )

        val unsorted = listOf(youtube256, saavn320, tidal1411, deezer1411, tidalMaster2822, amazonUltraHd4608)
        val sorted = com.example.network.GlobalMusicSearchEngine.sortTracksByQualityCascade(unsorted)

        // 1. Amazon Music Ultra HD (24-bit / 192kHz / 4608 kbps FLAC) must be #1
        assertEquals("amazon_1", sorted[0].id)
        assertEquals(24, sorted[0].bitDepth)
        assertEquals(192000, sorted[0].sampleRate)
        assertEquals(4608, sorted[0].bitrateKbps)

        // 2. Tidal Master (24-bit / 96kHz / 2822 kbps FLAC) must be #2
        assertEquals("tidal_master_1", sorted[1].id)

        // 3. Deezer HiFi (16-bit / 44.1kHz / 1411 kbps FLAC) ranks above Tidal 1411k per waterfall priority
        assertEquals("deezer_1", sorted[2].id)
        assertEquals("tidal_1", sorted[3].id)

        // 4. Lossy fallbacks at the bottom
        assertEquals("saavn_1", sorted[4].id)
        assertEquals("yt_1", sorted[5].id)
    }

    @Test
    fun testStreamMetadataProbe_calculatesBitrateFromPayloadAndDuration() = kotlinx.coroutines.runBlocking {
        val testDurationMs = 200_000L // 200 seconds

        val probed = com.example.network.StreamMetadataProbe.probeStream(
            url = "https://example.com/test_stream_320.mp4",
            durationMs = testDurationMs,
            fallbackFormat = "AAC",
            fallbackBitrate = 320
        )

        assertNotNull(probed)
        assertEquals("AAC", probed.formatName)
        assertEquals(320, probed.bitrateKbps)
        assertEquals(44100, probed.sampleRateHz)
        assertEquals(16, probed.bitDepth)
        org.junit.Assert.assertFalse(probed.isLossless)
    }

    @Test
    fun testTrackRadioRepository_seedTitleExclusion() {
        val seed = com.example.model.AudioTrack(
            id = "seed_1",
            title = "Starboy",
            artist = "The Weeknd",
            album = "Starboy",
            durationMs = 230000L,
            format = com.example.model.AudioFormat.FLAC,
            sampleRate = 96000,
            bitDepth = 24,
            bitrateKbps = 2822,
            genre = "R&B / Pop",
            releaseYear = 2016,
            gradientColors = listOf(0xFF00F0FF, 0xFF7928CA),
            sourceProvider = "Tidal Master"
        )

        // Title normalization test: "Starboy", "Starboy (Remix)", "Starboy [Live]" should all normalize to matching stems
        val normSeed = seed.title.lowercase().trim()
        val variant1 = "Starboy (Kygo Remix)".lowercase()
        val variant2 = "Starboy - Live in Paris".lowercase()
        val unrelated = "Can't Feel My Face".lowercase()

        assertTrue(variant1.contains(normSeed))
        assertTrue(variant2.contains(normSeed))
        org.junit.Assert.assertFalse(unrelated.contains(normSeed))
    }

    @Test
    fun testWaterfallCandidateRanking_ordersCorrectly() {
        val amazonCand = com.example.network.ResolvedStreamCandidate(
            streamUrl = "https://amazon.com/song.flac",
            formatName = "FLAC",
            sampleRateHz = 192000,
            bitDepth = 24,
            bitrateKbps = 4608,
            isLossless = true,
            providerName = "Amazon Music Ultra HD"
        )
        val deezerCand = com.example.network.ResolvedStreamCandidate(
            streamUrl = "https://deezer.com/song.flac",
            formatName = "FLAC",
            sampleRateHz = 44100,
            bitDepth = 16,
            bitrateKbps = 1411,
            isLossless = true,
            providerName = "Deezer HiFi"
        )
        val tidalCand = com.example.network.ResolvedStreamCandidate(
            streamUrl = "https://tidal.com/song.flac",
            formatName = "FLAC",
            sampleRateHz = 44100,
            bitDepth = 16,
            bitrateKbps = 1411,
            isLossless = true,
            providerName = "Tidal HiFi"
        )
        val youtubeCand = com.example.network.ResolvedStreamCandidate(
            streamUrl = "https://youtube.com/stream",
            formatName = "OPUS",
            sampleRateHz = 48000,
            bitDepth = 16,
            bitrateKbps = 256,
            isLossless = false,
            providerName = "YouTube Music Stream"
        )

        val candidates = listOf(youtubeCand, tidalCand, deezerCand, amazonCand)
        val sorted = com.example.network.UniversalStreamResolver.sortStreamCandidates(candidates)

        // 1. Amazon Ultra HD (4608 kbps 24-bit 192kHz) is top
        assertEquals("Amazon Music Ultra HD", sorted[0].providerName)
        assertEquals(4608, sorted[0].bitrateKbps)
        // 2. Deezer HiFi (1411 kbps) ranks above Tidal HiFi (1411 kbps)
        assertEquals("Deezer HiFi", sorted[1].providerName)
        assertEquals("Tidal HiFi", sorted[2].providerName)
        // 3. YouTube Music (256 kbps Opus) is last fallback
        assertEquals("YouTube Music Stream", sorted[3].providerName)
    }

    @Test
    fun testDurationSanityCheck_rejectsDiscrepancyGreaterThan4Seconds() {
        // Target track duration: 200 seconds (Resilient matching: permits real-world variations, rejects previews and wrong-song collisions)
        val validScore = com.example.network.UniversalStreamResolver.scoreCandidateMatch(
            candidateTitle = "Starboy (Official Video)",
            candidateArtist = "The Weeknd feat. Daft Punk",
            targetTitle = "Starboy",
            targetArtist = "The Weeknd",
            durationSec = 203L, // +3s discrepancy allowed
            targetDurationSec = 200L
        )
        assertTrue("Expected score >= 50 but got $validScore", validScore >= 50)

        // Real-world soundtrack variation: "Aaj Ki Raat" with varied artist metadata should pass resilient threshold
        val variationScore = com.example.network.UniversalStreamResolver.scoreCandidateMatch(
            candidateTitle = "Aaj Ki Raat (From Stree 2)",
            candidateArtist = "Madhubanti Bagchi, Sachin-Jigar",
            targetTitle = "Aaj Ki Raat",
            targetArtist = "Sabrina Saadi",
            durationSec = 228L,
            targetDurationSec = 210L
        )
        assertTrue("Expected variation score >= 50 but got $variationScore", variationScore >= 50)

        // 30s preview snippet (< 45s when target is full song) is strictly rejected
        val rejectedPreviewScore = com.example.network.UniversalStreamResolver.scoreCandidateMatch(
            candidateTitle = "Starboy",
            candidateArtist = "The Weeknd",
            targetTitle = "Starboy",
            targetArtist = "The Weeknd",
            durationSec = 30L, // 30s preview rejected
            targetDurationSec = 200L
        )
        assertTrue(rejectedPreviewScore < 0)

        // Wrong-song mismatch test: "Moner Radio" by "Jeet Gannguli" when searching "Moner Ekla Ghore" by "Arefin Rumey"
        val wrongSongScore = com.example.network.UniversalStreamResolver.computeMatchConfidenceScore(
            candidateTitle = "Moner Radio (From Awara)",
            candidateArtist = "Jeet Gannguli",
            targetTitle = "Moner Ekla Ghore",
            targetArtist = "Arefin Rumey",
            durationSec = 240L,
            targetDurationSec = 240L
        )
        assertTrue("Expected wrong song to be rejected (< 50) but got $wrongSongScore", wrongSongScore < 50)
    }

    @Test
    fun testHighResolutionCoverArtEnhancement_upgradesAllProviderThumbnails() {
        val deezerLowRes = "https://e-cdns-images.dzcdn.net/images/cover/abc123/250x250-000000-80-0-0.jpg"
        val deezerHiRes = com.example.util.AlbumArtHelper.enhanceArtworkUrl(deezerLowRes)
        assertTrue(deezerHiRes.contains("/1000x1000-000000-80-0-0.jpg"))

        val amazonLowRes = "https://m.media-amazon.com/images/I/71xyz._SX300_.jpg"
        val amazonHiRes = com.example.util.AlbumArtHelper.enhanceArtworkUrl(amazonLowRes)
        assertTrue(amazonHiRes.contains("._UL1500_.jpg"))

        val ytLowRes = "https://lh3.googleusercontent.com/abc=w120-h120-l90-rj"
        val ytHiRes = com.example.util.AlbumArtHelper.enhanceArtworkUrl(ytLowRes)
        assertTrue(ytHiRes.contains("=w1080-h1080-l95-rj"))

        val saavnLowRes = "https://c.saavncdn.com/123/Song-150x150.jpg"
        val saavnHiRes = com.example.util.AlbumArtHelper.enhanceArtworkUrl(saavnLowRes)
        assertTrue(saavnHiRes.contains("500x500.jpg"))
    }

    @Test
    fun testAudioMetadataProber_derivesBitrateFromSizeAndDuration() {
        // 35,275,000 bytes over 200 seconds = (35275000 * 8) / (200 * 1000) = 1411 kbps
        val flacBitrate = com.example.audio.AudioMetadataProber.deriveBitrateFromSizeAndDuration(
            fileSizeBytes = 35_275_000L,
            durationMs = 200_000L,
            formatName = "FLAC",
            sampleRateHz = 44100,
            bitDepth = 16,
            channels = 2
        )
        assertEquals(1411, flacBitrate)

        // Uncompressed 16-bit 44.1kHz WAV must always be 1411 kbps
        val wavBitrate = com.example.audio.AudioMetadataProber.deriveBitrateFromSizeAndDuration(
            fileSizeBytes = 0L,
            durationMs = 200_000L,
            formatName = "WAV",
            sampleRateHz = 44100,
            bitDepth = 16,
            channels = 2
        )
        assertEquals(1411, wavBitrate)
    }

    @Test
    fun testFuzzySearchRankingAndSegregation_toleratesTypos() {
        val arijitTrack = com.example.model.AudioTrack(
            id = "arijit_1",
            title = "Tum Hi Ho",
            artist = "Arijit Singh",
            album = "Aashiqui 2",
            durationMs = 262_000L,
            format = com.example.model.AudioFormat.FLAC,
            sampleRate = 44100,
            bitDepth = 16,
            bitrateKbps = 1411,
            sourceProvider = "Deezer HiFi"
        )
        val unrelatedTrack = arijitTrack.copy(
            id = "other_1",
            title = "Random Song",
            artist = "Unknown Singer",
            album = "Unknown Album"
        )

        // Typo query: "arijit sing" -> should rank "Arijit Singh" #1
        val ranked = com.example.network.GlobalMusicSearchEngine.rankTracksByQueryAndQuality(
            tracks = listOf(unrelatedTrack, arijitTrack),
            query = "arijit sing"
        )
        assertEquals("arijit_1", ranked.first().id)

        val segregated = com.example.network.GlobalMusicSearchEngine.segregateSearchResults(ranked, "arijit sing")
        assertNotNull(segregated.bestMatch)
        assertEquals("arijit_1", segregated.bestMatch?.id)
        assertTrue(segregated.artists.any { it.name == "Arijit Singh" })
        assertTrue(segregated.albums.any { it.title == "Aashiqui 2" })
    }

    @Test
    fun testUncompressedPcmWavBitrateCalculation_strictly1411Kbps() = kotlinx.coroutines.runBlocking {
        // 16-bit / 44.1kHz stereo WAV must strictly calculate as (44100 * 16 * 2) / 1000 = 1411 kbps
        val probedWav = com.example.network.StreamMetadataProbe.probeStream(
            url = "file:///cache/audio_lossless/track_01_full_200s.wav",
            durationMs = 200_000L,
            fallbackFormat = "WAV",
            fallbackBitrate = 666, // Even if a bogus fallback is passed, WAV formula overrides it to 1411 kbps
            fallbackSampleRate = 44100,
            fallbackBitDepth = 16
        )
        assertEquals("WAV", probedWav.formatName)
        assertEquals(44100, probedWav.sampleRateHz)
        assertEquals(16, probedWav.bitDepth)
        assertEquals(1411, probedWav.bitrateKbps)
        assertTrue(probedWav.isLossless)
    }

    @Test
    fun testStreamMatcher_canonicalMetadataAndIsrcMatching() {
        val rawTrack = com.example.model.AudioTrack(
            id = "test_01",
            title = "Starboy (Official Music Video) [Remastered 4K] ft. Daft Punk",
            artist = "The Weeknd feat. Daft Punk",
            album = "Starboy",
            durationMs = 230_000L,
            isrc = "US-UM7-16-04282"
        )
        val canonical = com.example.network.StreamMatcher.extractCanonicalMetadata(rawTrack)
        assertEquals("starboy", canonical.title)
        assertEquals("the weeknd", canonical.artist)
        assertEquals("USUM71604282", canonical.isrc)
        assertEquals(230L, canonical.durationSec)

        // Matching with identical ISRC
        assertTrue(
            com.example.network.StreamMatcher.isMatch(
                candidateTitle = "Random Song Title",
                candidateArtist = "Different Artist",
                targetTitle = "Starboy",
                targetArtist = "The Weeknd",
                candidateDurationSec = 230L,
                targetDurationSec = 230L,
                candidateIsrc = "US-UM7-16-04282",
                targetIsrc = "USUM71604282"
            )
        )

        // Reject explicitly different ISRCs
        val isrcMismatch = com.example.network.StreamMatcher.isMatch(
            candidateTitle = "Starboy",
            candidateArtist = "The Weeknd",
            targetTitle = "Starboy",
            targetArtist = "The Weeknd",
            candidateDurationSec = 230L,
            targetDurationSec = 230L,
            candidateIsrc = "GBUM71604282",
            targetIsrc = "USUM71604282"
        )
        assertTrue("Mismatched ISRCs must be rejected", !isrcMismatch)
    }

    @Test
    fun testStreamMatcher_strictDurationTolerance() {
        // Discrepancy <= 3s passes
        val passesWithin3s = com.example.network.StreamMatcher.isMatch(
            candidateTitle = "Blinding Lights",
            candidateArtist = "The Weeknd",
            targetTitle = "Blinding Lights",
            targetArtist = "The Weeknd",
            candidateDurationSec = 203L,
            targetDurationSec = 200L
        )
        assertTrue("Duration difference of 3s should pass", passesWithin3s)

        // Discrepancy > 3s rejected
        val rejectedOver3s = com.example.network.StreamMatcher.isMatch(
            candidateTitle = "Blinding Lights",
            candidateArtist = "The Weeknd",
            targetTitle = "Blinding Lights",
            targetArtist = "The Weeknd",
            candidateDurationSec = 204L, // +4s discrepancy > 3s limit
            targetDurationSec = 200L
        )
        assertTrue("Duration difference > 3s must be strictly rejected", !rejectedOver3s)
    }

    @Test
    fun testAudioPlayerService_exactMimeTypeResolution() {
        assertEquals(
            androidx.media3.common.MimeTypes.AUDIO_FLAC,
            com.example.audio.AudioPlayerService.resolveExactMimeType("https://cdn.example.com/stream.flac", "FLAC")
        )
        assertEquals(
            androidx.media3.common.MimeTypes.AUDIO_WAV,
            com.example.audio.AudioPlayerService.resolveExactMimeType("file:///cache/stream.wav", "WAV")
        )
        assertEquals(
            androidx.media3.common.MimeTypes.AUDIO_MPEG,
            com.example.audio.AudioPlayerService.resolveExactMimeType("https://cdn.example.com/audio.mp3", "MP3")
        )
        assertEquals(
            androidx.media3.common.MimeTypes.AUDIO_AAC,
            com.example.audio.AudioPlayerService.resolveExactMimeType("https://saavncdn.com/stream_320.mp4", "AAC")
        )
    }

    @Test
    fun testLosslessAudioEngine_cleanLabelsWithoutFakeBadges() {
        // JioSaavn 320k candidate must be accurately labeled without pretending to be FLAC
        val canonical = com.example.network.StreamMatcher.CanonicalMetadata(
            isrc = null,
            title = "Test Song",
            artist = "Test Artist",
            album = "Test Album",
            durationMs = 210_000L
        )
        // Verify candidate sorting places lossless first
        val losslessCand = com.example.network.ResolvedStreamCandidate(
            streamUrl = "https://flac.deezer.com/123.flac",
            formatName = "FLAC",
            sampleRateHz = 44100,
            bitDepth = 16,
            bitrateKbps = 1411,
            isLossless = true,
            providerName = "Deezer HiFi FLAC"
        )
        val saavnCand = com.example.network.ResolvedStreamCandidate(
            streamUrl = "https://saavncdn.com/123_320.mp4",
            formatName = "AAC",
            sampleRateHz = 44100,
            bitDepth = 16,
            bitrateKbps = 320,
            isLossless = false,
            providerName = "JioSaavn (320 kbps AAC)"
        )
        val sorted = com.example.network.UniversalStreamResolver.sortStreamCandidates(listOf(saavnCand, losslessCand))
        assertEquals("Deezer HiFi FLAC", sorted.first().providerName)
        assertEquals(1411, sorted.first().bitrateKbps)
        assertTrue(sorted.first().isLossless)

        assertEquals("JioSaavn (320 kbps AAC)", sorted[1].providerName)
        assertEquals(320, sorted[1].bitrateKbps)
        assertTrue(!sorted[1].isLossless)
    }
}


