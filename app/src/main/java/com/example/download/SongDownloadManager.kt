package com.example.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.audio.AudioMetadataProber
import com.example.data.MusicRepository
import com.example.model.AudioFormat
import com.example.model.AudioTrack
import com.example.model.StreamBackend
import com.example.network.DownloadQualityOption
import com.example.network.StreamMatcher
import com.example.network.UniversalStreamResolver
import com.example.util.AlbumArtHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * SpotiFLAC-Style High-Speed Public MediaStore Audio Download Engine:
 * - Saves downloaded songs directly into the user-accessible public `/Music/Flow/` directory
 *   via Android `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` (Android 10+ / API 29+ with `RELATIVE_PATH = "Music/Flow"`
 *   and `IS_PENDING = 1 -> 0` atomic commit) with `TITLE`, `ARTIST`, `ALBUM_ARTIST`, and `ALBUM`.
 * - Verifies actual container format from magic bytes (`fLaC` -> `.flac`, `ftyp` -> `.m4a`, `ID3`/MPEG -> `.mp3`, `RIFF`/`WAVE` -> `.wav`)
 *   so file extensions and format labels strictly match the real container format.
 * - Embeds standard container metadata & full-resolution cover artwork directly into the audio container:
 *   1. `.m4a`: MP4 metadata atoms (`moov.udta.meta.ilst` with `covr` JPEG/PNG artwork, `©nam`, `©ART`, `aART`, `©alb`, `©day`, plus `stco`/`co64` chunk offset updates).
 *   2. `.flac`: Vorbis/FLAC metadata blocks (`VORBIS_COMMENT` + `METADATA_BLOCK_PICTURE` block type 6).
 *   3. `.mp3`: ID3v2.3 frames (`TIT2`, `TPE1`, `TPE2`, `TALB`, `TYER`, `TSRC`, and `APIC` Front Cover Art).
 * - Sanitizes track titles/artists via `StreamMatcher` and stores offline artwork in `downloads_artwork` so `_art.jpg` files are never indexed as audio tracks.
 */
class SongDownloadManager(
    private val context: Context,
    private val repository: MusicRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    // Map of trackId -> progress percentage (0.0f to 1.0f)
    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    private val downloadsDir = File(context.filesDir, "downloads").apply {
        if (!exists()) mkdirs()
    }

    private val artworkCacheDir = File(context.filesDir, "downloads_artwork").apply {
        if (!exists()) mkdirs()
    }

    init {
        // Clean up any legacy *_art.jpg files inside the audio downloads directory
        runCatching {
            downloadsDir.listFiles()?.forEach { file ->
                if (file.isFile && (file.name.endsWith("_art.jpg", ignoreCase = true) ||
                        file.name.endsWith(".jpg", ignoreCase = true) ||
                        file.name.endsWith(".png", ignoreCase = true))
                ) {
                    val dest = File(artworkCacheDir, file.name)
                    file.copyTo(dest, overwrite = true)
                    file.delete()
                }
            }
        }
    }

    fun isDownloaded(trackId: String): Boolean {
        val tracks = repository.downloadedTracks.value
        if (tracks.any { it.id == trackId }) return true
        val baseName = sanitizeFileName(trackId)
        return listOf("flac", "m4a", "wav", "mp3", "opus").any { ext ->
            val candidate = File(downloadsDir, "$baseName.$ext")
            candidate.exists() && candidate.length() > 50_000L
        }
    }

    /**
     * Sanitizes a track's title and artist before saving to storage, resolving canonical metadata
     * via StreamMatcher if the title or artist contains raw ID patterns (`yt [id]`, `Offline Saved Track`).
     */
    suspend fun ensureCanonicalTrackMetadata(track: AudioTrack): AudioTrack {
        val rawIdRegex = Regex("""^(yt[\s_]+[A-Za-z0-9_-]{6,15}(\s+art)?|saavn[\s_]+\S+|deezer[\s_]+\S+|amazon[\s_]+\S+|tidal[\s_]+\S+)$""", RegexOption.IGNORE_CASE)
        val hasCorruptTitle = track.title.isBlank() ||
            rawIdRegex.matches(track.title.trim()) ||
            track.title.endsWith(" art", ignoreCase = true) ||
            track.title.equals("Unknown Track", ignoreCase = true)
        val hasCorruptArtist = track.artist.isBlank() ||
            track.artist.equals("Offline Saved Track", ignoreCase = true) ||
            track.artist.equals("Unknown Artist", ignoreCase = true)

        if (!hasCorruptTitle && !hasCorruptArtist) {
            return track
        }

        val queryHint = track.title
            .replace(Regex("""^(yt|saavn|deezer|amazon|tidal)[\s_]+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+art$""", RegexOption.IGNORE_CASE), "")
            .trim()

        val canonical = StreamMatcher.resolveCanonicalTrack(
            rawTitle = queryHint.ifBlank { track.title },
            rawArtist = if (hasCorruptArtist) "" else track.artist,
            fallbackDurationMs = track.durationMs
        )

        val resolvedTitle = when {
            !hasCorruptTitle -> track.title
            canonical != null && canonical.canonicalTitle.isNotBlank() && !rawIdRegex.matches(canonical.canonicalTitle) -> canonical.canonicalTitle
            else -> "Flow Audio Track"
        }
        val resolvedArtist = when {
            !hasCorruptArtist -> track.artist
            canonical != null && canonical.primaryArtist.isNotBlank() && !canonical.primaryArtist.equals("Offline Saved Track", true) -> canonical.primaryArtist
            else -> "Flow Artist"
        }
        val resolvedAlbum = if (track.album.isBlank() || track.album.equals("Offline Downloads", true)) {
            canonical?.album?.ifBlank { "Single" } ?: "Single"
        } else {
            track.album
        }
        val resolvedArt = if (track.artworkUrl.isNullOrBlank()) {
            canonical?.highResArtworkUrl?.ifBlank { null }
        } else {
            track.artworkUrl
        }

        return track.copy(
            title = resolvedTitle,
            artist = resolvedArtist,
            album = resolvedAlbum,
            artworkUrl = resolvedArt
        )
    }

    fun downloadTrack(
        track: AudioTrack,
        backend: StreamBackend = StreamBackend.AUTO_HIGH_FIDELITY,
        qualityOption: DownloadQualityOption = if (track.format.isLossless && UniversalStreamResolver.isTrueLosslessUrl(track.effectiveStreamUrl)) {
            DownloadQualityOption.HI_RES_LOSSLESS
        } else {
            DownloadQualityOption.MASTER_320K
        },
        onSuccess: ((AudioTrack) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        scope.launch {
            try {
                updateProgress(track.id, 0.05f)

                val canonicalTrack = ensureCanonicalTrackMetadata(track)

                val effectiveBackend = if (qualityOption == DownloadQualityOption.HI_RES_LOSSLESS && !canonicalTrack.isGlobalOnline) {
                    StreamBackend.LOCAL_LOSSLESS
                } else {
                    backend
                }

                // 1. Resolve full verified stream URL with strict 4-Pillar canonical parity
                val cachedTopCandidate = UniversalStreamResolver.getTopResolvedCandidateForTrack(canonicalTrack.id)
                val (resolvedUrl, qualityInfo) = when {
                    cachedTopCandidate != null && cachedTopCandidate.streamUrl.isNotBlank() &&
                        (qualityOption != DownloadQualityOption.HI_RES_LOSSLESS || cachedTopCandidate.isLossless) -> {
                        Pair(cachedTopCandidate.streamUrl, cachedTopCandidate.toAudioQualityInfo())
                    }
                    qualityOption == DownloadQualityOption.HI_RES_LOSSLESS &&
                        !canonicalTrack.effectiveStreamUrl.isNullOrBlank() &&
                        UniversalStreamResolver.isTrueLosslessUrl(canonicalTrack.effectiveStreamUrl) -> {
                        val losslessUrl = canonicalTrack.effectiveStreamUrl!!
                        Pair(
                            losslessUrl,
                            com.example.model.AudioQualityInfo(
                                formatName = canonicalTrack.format.displayName,
                                sampleRateHz = canonicalTrack.sampleRate.coerceAtLeast(44100),
                                bitDepth = canonicalTrack.bitDepth.coerceAtLeast(16),
                                bitrateKbps = canonicalTrack.bitrateKbps.takeIf { it > 320 } ?: 1411,
                                isLossless = true,
                                isHiRes = true
                            )
                        )
                    }
                    else -> {
                        UniversalStreamResolver.resolveFullAudioStream(
                            context = context,
                            track = canonicalTrack,
                            backend = effectiveBackend
                        )
                    }
                }

                // Apply user's selected quality tier (preserves raw stream when HI_RES_LOSSLESS is selected)
                val streamUrl = UniversalStreamResolver.applyQualityOptionToUrl(resolvedUrl, qualityOption)

                updateProgress(canonicalTrack.id, 0.15f)

                val tempStagingFile = File(downloadsDir, "${sanitizeFileName(canonicalTrack.id)}.part")

                // 2. Download 100% raw audio stream bytes to staging file with byte-level progress
                if (streamUrl.startsWith("http://") || streamUrl.startsWith("https://")) {
                    val request = Request.Builder()
                        .url(streamUrl)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) FlowAudio/2.0")
                        .build()

                    var response = httpClient.newCall(request).execute()
                    if (!response.isSuccessful && streamUrl != resolvedUrl) {
                        response.close()
                        val fallbackReq = Request.Builder()
                            .url(resolvedUrl)
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) FlowAudio/2.0")
                            .build()
                        response = httpClient.newCall(fallbackReq).execute()
                    }
                    if (!response.isSuccessful) {
                        response.close()
                        throw IllegalStateException("Failed to download: HTTP ${response.code}")
                    }

                    val body = response.body ?: throw IllegalStateException("Empty response body")
                    val contentLength = body.contentLength()
                    var bytesCopied = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(tempStagingFile).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                bytesCopied += read
                                if (contentLength > 0) {
                                    val pct = 0.15f + (bytesCopied.toFloat() / contentLength) * 0.65f
                                    updateProgress(canonicalTrack.id, pct.coerceIn(0.15f, 0.80f))
                                }
                            }
                            output.flush()
                        }
                    }
                } else {
                    val srcFile = File(streamUrl.removePrefix("file://"))
                    if (srcFile.exists()) {
                        srcFile.copyTo(tempStagingFile, overwrite = true)
                    }
                }

                // Verify real container format from downloaded file magic bytes + stream URL (Honest Container Verification)
                val (verifiedFormat, verifiedExt, verifiedMime) = detectActualContainerFormat(tempStagingFile, streamUrl)
                val targetAudioFile = File(downloadsDir, "${sanitizeFileName(canonicalTrack.id)}.$verifiedExt")
                if (targetAudioFile.exists()) targetAudioFile.delete()
                tempStagingFile.renameTo(targetAudioFile)

                updateProgress(canonicalTrack.id, 0.84f)

                // 3. Download high-res cover artwork bytes for offline cache and embedded container tagging
                val targetArtworkFile = File(artworkCacheDir, "${sanitizeFileName(canonicalTrack.id)}_art.jpg")
                var localArtPath: String? = null
                var artworkBytes = ByteArray(0)
                val enhancedArtworkUrl = AlbumArtHelper.enhanceArtworkUrl(canonicalTrack.artworkUrl)
                if (enhancedArtworkUrl.isNotBlank() && enhancedArtworkUrl.startsWith("http")) {
                    try {
                        val artReq = Request.Builder().url(enhancedArtworkUrl).build()
                        httpClient.newCall(artReq).execute().use { artResp ->
                            if (artResp.isSuccessful) {
                                val bytes = artResp.body?.bytes()
                                if (bytes != null && bytes.isNotEmpty()) {
                                    artworkBytes = bytes
                                    FileOutputStream(targetArtworkFile).use { artOut ->
                                        artOut.write(bytes)
                                        artOut.flush()
                                    }
                                    localArtPath = targetArtworkFile.absolutePath
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("SongDownloadManager", "Artwork download optional failure: ${e.message}")
                    }
                }
                if (artworkBytes.isEmpty()) {
                    runCatching {
                        artworkBytes = AlbumArtHelper.getArtworkByteArray(context, canonicalTrack)
                        if (artworkBytes.isNotEmpty()) {
                            FileOutputStream(targetArtworkFile).use { artOut ->
                                artOut.write(artworkBytes)
                                artOut.flush()
                            }
                            localArtPath = targetArtworkFile.absolutePath
                        }
                    }
                }

                // 4. Embed container metadata tags & cover art directly into the downloaded file (.m4a, .flac, .mp3)
                embedContainerMetadataTags(
                    audioFile = targetAudioFile,
                    extension = verifiedExt,
                    track = canonicalTrack,
                    artworkBytes = artworkBytes
                )

                updateProgress(canonicalTrack.id, 0.92f)

                // 5. Publish to user-accessible public /Music/Flow/ directory via Android MediaStore
                val displayFileName = "${sanitizeDisplayFilename("${canonicalTrack.artist} - ${canonicalTrack.title}")}.$verifiedExt"
                val publicMediaRef = exportToPublicMusicFlowDirectory(
                    sourceFile = targetAudioFile,
                    displayFileName = displayFileName,
                    mimeType = verifiedMime,
                    track = canonicalTrack
                )

                val downloadedBytes = targetAudioFile.length()
                val sizeMb = String.format(java.util.Locale.US, "%.1f MB", downloadedBytes / (1024.0 * 1024.0))
                val actualSampleRate = if (verifiedFormat.isLossless) {
                    maxOf(canonicalTrack.sampleRate, qualityInfo.sampleRateHz).takeIf { it >= 44100 } ?: 44100
                } else {
                    qualityInfo.sampleRateHz.takeIf { it >= 44100 } ?: 44100
                }
                val actualBitDepth = if (verifiedFormat.isLossless) {
                    maxOf(canonicalTrack.bitDepth, qualityInfo.bitDepth).takeIf { it >= 16 } ?: 16
                } else {
                    16
                }
                val derivedContainerBitrate = AudioMetadataProber.deriveBitrateFromSizeAndDuration(
                    fileSizeBytes = downloadedBytes,
                    durationMs = canonicalTrack.durationMs,
                    formatName = verifiedFormat.displayName,
                    sampleRateHz = actualSampleRate,
                    bitDepth = actualBitDepth,
                    channels = 2
                )
                val actualBitrate = when {
                    derivedContainerBitrate > 0 -> if (!verifiedFormat.isLossless && derivedContainerBitrate in 305..335) 320 else derivedContainerBitrate
                    verifiedFormat.isLossless -> maxOf(canonicalTrack.bitrateKbps, qualityInfo.bitrateKbps).takeIf { it > 320 } ?: 1411
                    else -> qualityOption.targetBitrateKbps.coerceAtMost(320)
                }
                val qualityLabel = if (verifiedFormat.isLossless) {
                    "$sizeMb • $actualBitrate kbps Hi-Res ${verifiedFormat.displayName} (Saved to /Music/Flow)"
                } else {
                    "$sizeMb • $actualBitrate kbps ${verifiedFormat.displayName} (Saved to /Music/Flow)"
                }

                val effectiveUriString = publicMediaRef.contentUri?.toString()
                    ?: Uri.fromFile(targetAudioFile).toString()

                val downloadedTrack = canonicalTrack.copy(
                    uriString = effectiveUriString,
                    streamUrl = effectiveUriString,
                    localFilePath = targetAudioFile.absolutePath,
                    artworkUrl = localArtPath ?: canonicalTrack.artworkUrl,
                    isDownloaded = true,
                    isGlobalOnline = false,
                    isLocalImport = true,
                    fileSizeFormatted = qualityLabel,
                    bitrateKbps = actualBitrate,
                    sampleRate = actualSampleRate,
                    bitDepth = actualBitDepth,
                    format = verifiedFormat
                )

                // 6. Trigger MediaScannerConnection.scanFile() so the track immediately appears in system audio libraries
                runCatching {
                    val pathsToScan = listOfNotNull(
                        publicMediaRef.publicFilePath,
                        targetAudioFile.absolutePath
                    ).distinct().toTypedArray()
                    MediaScannerConnection.scanFile(
                        context,
                        pathsToScan,
                        Array(pathsToScan.size) { verifiedMime }
                    ) { path, uri ->
                        Log.i("SongDownloadManager", "MediaScanner indexed public download: $path -> $uri")
                    }
                }

                // 7. Save into persistent downloaded tracks in repository
                withContext(Dispatchers.Main) {
                    repository.addDownloadedTrack(downloadedTrack)
                    updateProgress(canonicalTrack.id, 1.0f)
                    removeProgress(canonicalTrack.id)
                    onSuccess?.invoke(downloadedTrack)
                }

            } catch (e: Exception) {
                Log.e("SongDownloadManager", "Download failed for ${track.title}", e)
                withContext(Dispatchers.Main) {
                    removeProgress(track.id)
                    onError?.invoke(e.message ?: "Download failed")
                }
            }
        }
    }

    /**
     * Inspects the actual magic bytes of the downloaded file (along with URL fallback)
     * so `.m4a` AAC streams are never given a `.flac` extension or fake FLAC label.
     */
    private fun detectActualContainerFormat(file: File, streamUrl: String): Triple<AudioFormat, String, String> {
        val header = ByteArray(64)
        var readBytes = 0
        if (file.exists() && file.length() >= 12) {
            runCatching {
                FileInputStream(file).use { fis ->
                    readBytes = fis.read(header)
                }
            }
        }

        if (readBytes >= 12) {
            // 1. FLAC magic: "fLaC"
            if (header[0] == 'f'.code.toByte() && header[1] == 'L'.code.toByte() &&
                header[2] == 'a'.code.toByte() && header[3] == 'C'.code.toByte()
            ) {
                return Triple(AudioFormat.FLAC, "flac", "audio/flac")
            }

            // 2. WAV magic: "RIFF" ... "WAVE"
            if (header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
                header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
                header[8] == 'W'.code.toByte() && header[9] == 'A'.code.toByte() &&
                header[10] == 'V'.code.toByte() && header[11] == 'E'.code.toByte()
            ) {
                return Triple(AudioFormat.WAV, "wav", "audio/wav")
            }

            // 3. MP4 / M4A magic: "ftyp" at offset 4
            if (header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()
            ) {
                return Triple(AudioFormat.AAC, "m4a", "audio/mp4")
            }

            // 4. MP3 magic: "ID3" or MPEG frame sync
            if ((header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) ||
                ((header[0].toInt() and 0xFF) == 0xFF && ((header[1].toInt() and 0xE0) == 0xE0))
            ) {
                return Triple(AudioFormat.MP3, "mp3", "audio/mpeg")
            }
        }

        val cleanUrl = streamUrl.lowercase().substringBefore("?")
        return when {
            cleanUrl.endsWith(".flac") -> Triple(AudioFormat.FLAC, "flac", "audio/flac")
            cleanUrl.endsWith(".wav") || cleanUrl.endsWith(".wave") -> Triple(AudioFormat.WAV, "wav", "audio/wav")
            cleanUrl.endsWith(".mp3") -> Triple(AudioFormat.MP3, "mp3", "audio/mpeg")
            else -> Triple(AudioFormat.AAC, "m4a", "audio/mp4")
        }
    }

    private data class PublicMediaExportResult(
        val contentUri: Uri?,
        val publicFilePath: String?
    )

    /**
     * Exports the tagged audio file into the phone's public `/Music/Flow/` folder using
     * `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` with `RELATIVE_PATH = "Music/Flow"`,
     * `TITLE`, `ARTIST`, `ALBUM_ARTIST`, `ALBUM`, and `IS_PENDING = 1 -> 0`.
     */
    private fun exportToPublicMusicFlowDirectory(
        sourceFile: File,
        displayFileName: String,
        mimeType: String,
        track: AudioTrack
    ): PublicMediaExportResult {
        var exportedUri: Uri? = null
        var exportedPublicPath: String? = null

        // 1. Android 10+ (API 29+): Scoped Storage MediaStore export to /Music/Flow
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayFileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Flow")
                    put(MediaStore.Audio.Media.TITLE, track.title)
                    put(MediaStore.Audio.Media.ARTIST, track.artist)
                    put(MediaStore.Audio.Media.ALBUM_ARTIST, track.artist)
                    put(MediaStore.Audio.Media.ALBUM, track.album.ifBlank { "Flow Downloads" })
                    put(MediaStore.Audio.Media.YEAR, track.releaseYear)
                    if (track.durationMs > 0L) {
                        put(MediaStore.Audio.Media.DURATION, track.durationMs)
                    }
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val collectionUri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val insertedUri = resolver.insert(collectionUri, contentValues)
                if (insertedUri != null) {
                    resolver.openOutputStream(insertedUri)?.use { outStream ->
                        FileInputStream(sourceFile).use { inStream ->
                            inStream.copyTo(outStream, bufferSize = 32 * 1024)
                            outStream.flush()
                        }
                    }

                    val finalizeValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    resolver.update(insertedUri, finalizeValues, null, null)
                    exportedUri = insertedUri
                }
            } catch (e: Exception) {
                Log.w("SongDownloadManager", "MediaStore Q+ export fallback triggered: ${e.message}")
            }
        }

        // 2. Also ensure direct public `/Music/Flow/` directory file path is populated when accessible
        try {
            val publicMusicRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val flowPublicDir = File(publicMusicRoot, "Flow")
            if (flowPublicDir.exists() || flowPublicDir.mkdirs()) {
                val publicFile = File(flowPublicDir, displayFileName)
                if (!publicFile.exists() || publicFile.length() != sourceFile.length()) {
                    sourceFile.copyTo(publicFile, overwrite = true)
                }
                exportedPublicPath = publicFile.absolutePath
                if (exportedUri == null) {
                    exportedUri = Uri.fromFile(publicFile)
                }
            }
        } catch (e: Exception) {
            Log.d("SongDownloadManager", "Direct public directory write skipped (using MediaStore URI): ${e.message}")
        }

        return PublicMediaExportResult(
            contentUri = exportedUri,
            publicFilePath = exportedPublicPath
        )
    }

    /**
     * Embeds standard container metadata & cover artwork into `.flac`, `.m4a`, or `.mp3` files:
     * - `.flac`: Vorbis Comment (`VORBIS_COMMENT`) + `METADATA_BLOCK_PICTURE`
     * - `.m4a`: MP4 `moov.udta.meta.ilst` atoms (`©nam`, `©ART`, `aART`, `©alb`, `©day`, `covr`) with chunk offset adjustment
     * - `.mp3`: ID3v2.3 frames (`TIT2`, `TPE1`, `TPE2`, `TALB`, `TYER`, `TSRC`, `APIC`)
     */
    fun embedContainerMetadataTags(
        audioFile: File,
        extension: String,
        track: AudioTrack,
        artworkBytes: ByteArray
    ) {
        if (!audioFile.exists() || audioFile.length() < 64L) return
        try {
            when (extension.lowercase()) {
                "flac" -> embedFlacVorbisAndPictureMetadata(audioFile, track, artworkBytes)
                "m4a", "mp4", "aac" -> embedM4aIlstMetadata(audioFile, track, artworkBytes)
                "mp3" -> embedId3v2Metadata(audioFile, track, artworkBytes)
            }
        } catch (e: Exception) {
            Log.w("SongDownloadManager", "Container metadata tagging skipped for ${audioFile.name}: ${e.message}")
        }
    }

    /**
     * Embeds standard iTunes/MP4 `moov -> udta -> meta -> ilst` metadata atoms (`©nam`, `©ART`, `aART`, `©alb`, `©day`, and `covr` cover art)
     * into `.m4a` (ISO Base Media File Format) containers and updates `stco`/`co64` chunk offsets if `moov` precedes `mdat`.
     */
    private fun embedM4aIlstMetadata(
        m4aFile: File,
        track: AudioTrack,
        artworkBytes: ByteArray
    ) {
        val fileBytes = m4aFile.readBytes()
        if (fileBytes.size < 32) return

        // Locate top-level boxes
        var offset = 0
        var moovOffset = -1
        var moovSize = 0
        var mdatOffset = -1

        while (offset + 8 <= fileBytes.size) {
            val size = readUint32BigEndian(fileBytes, offset).toInt()
            val type = String(fileBytes, offset + 4, 4, Charsets.ISO_8859_1)
            if (size < 8 || offset + size > fileBytes.size) break
            if (type == "moov") {
                moovOffset = offset
                moovSize = size
            } else if (type == "mdat" && mdatOffset == -1) {
                mdatOffset = offset
            }
            offset += size
        }

        if (moovOffset < 0 || moovSize < 8) return

        // Build `udta -> meta -> hdlr + ilst` atom hierarchy
        val udtaAtom = buildMp4UdtaAtom(track, artworkBytes)
        val moovEnd = moovOffset + moovSize

        // Check if moov already contains an existing `udta` child box; if so, replace it, otherwise append `udta` to `moov`
        var childPos = moovOffset + 8
        var existingUdtaOffset = -1
        var existingUdtaSize = 0
        while (childPos + 8 <= moovEnd) {
            val cSize = readUint32BigEndian(fileBytes, childPos).toInt()
            val cType = String(fileBytes, childPos + 4, 4, Charsets.ISO_8859_1)
            if (cSize < 8 || childPos + cSize > moovEnd) break
            if (cType == "udta") {
                existingUdtaOffset = childPos
                existingUdtaSize = cSize
                break
            }
            childPos += cSize
        }

        val deltaSize = if (existingUdtaOffset >= 0) {
            udtaAtom.size - existingUdtaSize
        } else {
            udtaAtom.size
        }

        val newMoovSize = moovSize + deltaSize
        val newMoovBytes = ByteArray(newMoovSize)

        // Write updated moov header
        writeInt32BigEndian(newMoovBytes, 0, newMoovSize)
        System.arraycopy(fileBytes, moovOffset + 4, newMoovBytes, 4, 4) // "moov"

        if (existingUdtaOffset >= 0) {
            val beforeUdtaLen = existingUdtaOffset - (moovOffset + 8)
            System.arraycopy(fileBytes, moovOffset + 8, newMoovBytes, 8, beforeUdtaLen)
            System.arraycopy(udtaAtom, 0, newMoovBytes, 8 + beforeUdtaLen, udtaAtom.size)
            val afterUdtaSrc = existingUdtaOffset + existingUdtaSize
            val afterUdtaLen = moovEnd - afterUdtaSrc
            if (afterUdtaLen > 0) {
                System.arraycopy(fileBytes, afterUdtaSrc, newMoovBytes, 8 + beforeUdtaLen + udtaAtom.size, afterUdtaLen)
            }
        } else {
            val existingPayloadLen = moovSize - 8
            System.arraycopy(fileBytes, moovOffset + 8, newMoovBytes, 8, existingPayloadLen)
            System.arraycopy(udtaAtom, 0, newMoovBytes, 8 + existingPayloadLen, udtaAtom.size)
        }

        // If `moov` appears before `mdat` in the file, shift all `stco` / `co64` chunk offsets by `deltaSize`
        if (mdatOffset > moovOffset && deltaSize != 0) {
            adjustMp4ChunkOffsets(newMoovBytes, 8, newMoovBytes.size, deltaSize)
        }

        FileOutputStream(m4aFile).use { fos ->
            if (moovOffset > 0) {
                fos.write(fileBytes, 0, moovOffset)
            }
            fos.write(newMoovBytes)
            if (moovEnd < fileBytes.size) {
                fos.write(fileBytes, moovEnd, fileBytes.size - moovEnd)
            }
            fos.flush()
        }
    }

    private fun buildMp4UdtaAtom(track: AudioTrack, artworkBytes: ByteArray): ByteArray {
        val ilstChildren = ByteArrayOutputStream()
        writeMp4TextMetadataItem(ilstChildren, "\u00A9nam", track.title)
        writeMp4TextMetadataItem(ilstChildren, "\u00A9ART", track.artist)
        writeMp4TextMetadataItem(ilstChildren, "aART", track.artist)
        writeMp4TextMetadataItem(ilstChildren, "\u00A9alb", track.album.ifBlank { "Single" })
        writeMp4TextMetadataItem(ilstChildren, "\u00A9day", track.releaseYear.toString())
        writeMp4TextMetadataItem(ilstChildren, "\u00A9gen", track.genre.ifBlank { "Music" })

        if (artworkBytes.isNotEmpty() && artworkBytes.size < 6 * 1024 * 1024) {
            writeMp4CoverArtItem(ilstChildren, artworkBytes)
        }

        val ilstBox = wrapMp4Atom("ilst", ilstChildren.toByteArray())

        // Standard MP4 `hdlr` box for iTunes `mdir` metadata
        val hdlrPayload = ByteBuffer.allocate(25).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0) // version + flags
            putInt(0) // pre_defined
            put("mdir".toByteArray(Charsets.ISO_8859_1))
            put("appl".toByteArray(Charsets.ISO_8859_1))
            putInt(0)
            putInt(0)
            put(0)
        }.array()
        val hdlrBox = wrapMp4Atom("hdlr", hdlrPayload)

        val metaPayload = ByteArrayOutputStream()
        metaPayload.write(byteArrayOf(0, 0, 0, 0)) // FullBox 4-byte version/flags
        metaPayload.write(hdlrBox)
        metaPayload.write(ilstBox)
        val metaBox = wrapMp4Atom("meta", metaPayload.toByteArray())

        return wrapMp4Atom("udta", metaBox)
    }

    private fun writeMp4TextMetadataItem(out: ByteArrayOutputStream, fourCc: String, value: String) {
        if (value.isBlank()) return
        val utf8 = value.toByteArray(Charsets.UTF_8)
        val dataPayload = ByteBuffer.allocate(8 + utf8.size).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(1) // type = 1 (UTF-8 text)
            putInt(0) // locale = 0
            put(utf8)
        }.array()
        val dataBox = wrapMp4Atom("data", dataPayload)
        val itemBox = wrapMp4Atom(fourCc, dataBox)
        out.write(itemBox)
    }

    private fun writeMp4CoverArtItem(out: ByteArrayOutputStream, artworkBytes: ByteArray) {
        val isPng = artworkBytes.size >= 4 &&
            (artworkBytes[0].toInt() and 0xFF) == 0x89 &&
            artworkBytes[1] == 'P'.code.toByte() &&
            artworkBytes[2] == 'N'.code.toByte() &&
            artworkBytes[3] == 'G'.code.toByte()
        val dataType = if (isPng) 14 else 13 // 13 = JPEG, 14 = PNG
        val dataPayload = ByteBuffer.allocate(8 + artworkBytes.size).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(dataType)
            putInt(0)
            put(artworkBytes)
        }.array()
        val dataBox = wrapMp4Atom("data", dataPayload)
        val covrBox = wrapMp4Atom("covr", dataBox)
        out.write(covrBox)
    }

    private fun wrapMp4Atom(type: String, payload: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        val total = 8 + payload.size
        return ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(total)
            put(typeBytes, 0, 4)
            put(payload)
        }.array()
    }

    private val mp4ContainerBoxes = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf")

    private fun adjustMp4ChunkOffsets(buffer: ByteArray, start: Int, end: Int, delta: Int) {
        var pos = start
        while (pos + 8 <= end) {
            val size = readUint32BigEndian(buffer, pos).toInt()
            if (size < 8 || pos + size > end) break
            val type = String(buffer, pos + 4, 4, Charsets.ISO_8859_1)
            when {
                type == "stco" && size >= 16 -> {
                    val count = readUint32BigEndian(buffer, pos + 12).toInt()
                    var entryPos = pos + 16
                    for (i in 0 until count) {
                        if (entryPos + 4 > pos + size) break
                        val oldOffset = readUint32BigEndian(buffer, entryPos)
                        writeInt32BigEndian(buffer, entryPos, (oldOffset + delta).toInt())
                        entryPos += 4
                    }
                }
                type == "co64" && size >= 16 -> {
                    val count = readUint32BigEndian(buffer, pos + 12).toInt()
                    var entryPos = pos + 16
                    for (i in 0 until count) {
                        if (entryPos + 8 > pos + size) break
                        val hi = readUint32BigEndian(buffer, entryPos)
                        val lo = readUint32BigEndian(buffer, entryPos + 4)
                        val oldOffset = (hi shl 32) or (lo and 0xFFFFFFFFL)
                        val updated = oldOffset + delta
                        writeInt32BigEndian(buffer, entryPos, (updated ushr 32).toInt())
                        writeInt32BigEndian(buffer, entryPos + 4, (updated and 0xFFFFFFFFL).toInt())
                        entryPos += 8
                    }
                }
                type in mp4ContainerBoxes -> {
                    adjustMp4ChunkOffsets(buffer, pos + 8, pos + size, delta)
                }
            }
            pos += size
        }
    }

    private fun readUint32BigEndian(bytes: ByteArray, offset: Int): Long {
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
    }

    private fun writeInt32BigEndian(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 24) and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 3] = (value and 0xFF).toByte()
    }

    /**
     * Embeds a Vorbis Comment metadata block (Block Type 4) and FLAC Picture block (Block Type 6)
     * into a native FLAC container if it begins with the "fLaC" magic header.
     */
    private fun embedFlacVorbisAndPictureMetadata(
        flacFile: File,
        track: AudioTrack,
        artworkBytes: ByteArray
    ) {
        val rawBytes = flacFile.readBytes()
        if (rawBytes.size < 42 ||
            rawBytes[0] != 'f'.code.toByte() ||
            rawBytes[1] != 'L'.code.toByte() ||
            rawBytes[2] != 'a'.code.toByte() ||
            rawBytes[3] != 'C'.code.toByte()
        ) {
            return
        }

        // First metadata block is STREAMINFO (34 bytes payload -> bytes 4..41)
        val firstBlockHeader = rawBytes[4].toInt() and 0xFF
        val firstBlockType = firstBlockHeader and 0x7F
        val firstBlockLength = ((rawBytes[5].toInt() and 0xFF) shl 16) or
            ((rawBytes[6].toInt() and 0xFF) shl 8) or
            (rawBytes[7].toInt() and 0xFF)
        if (firstBlockType != 0 || 8 + firstBlockLength > rawBytes.size) return

        val vorbisPayload = buildVorbisCommentPayload(track)
        val picturePayload = if (artworkBytes.isNotEmpty()) buildFlacPicturePayload(artworkBytes) else null

        val out = ByteArrayOutputStream(rawBytes.size + vorbisPayload.size + (picturePayload?.size ?: 0) + 16)
        // Write "fLaC"
        out.write(rawBytes, 0, 4)
        // Write STREAMINFO with isLast = false (clear bit 7)
        out.write(firstBlockType and 0x7F)
        out.write(rawBytes, 5, 3 + firstBlockLength)

        // Write VORBIS_COMMENT block (type = 4)
        val isVorbisLast = (picturePayload == null) && ((firstBlockHeader and 0x80) != 0)
        val vorbisHeaderByte = (if (isVorbisLast) 0x80 else 0x00) or 4
        out.write(vorbisHeaderByte)
        out.write((vorbisPayload.size shr 16) and 0xFF)
        out.write((vorbisPayload.size shr 8) and 0xFF)
        out.write(vorbisPayload.size and 0xFF)
        out.write(vorbisPayload)

        // Write PICTURE block (type = 6) if artwork is available and under 8 MB
        if (picturePayload != null && picturePayload.size < 8 * 1024 * 1024) {
            val isPicLast = (firstBlockHeader and 0x80) != 0
            val picHeaderByte = (if (isPicLast) 0x80 else 0x00) or 6
            out.write(picHeaderByte)
            out.write((picturePayload.size shr 16) and 0xFF)
            out.write((picturePayload.size shr 8) and 0xFF)
            out.write(picturePayload.size and 0xFF)
            out.write(picturePayload)
        }

        // Write remaining FLAC blocks and audio frames
        val remainingOffset = 8 + firstBlockLength
        out.write(rawBytes, remainingOffset, rawBytes.size - remainingOffset)

        FileOutputStream(flacFile).use { fos ->
            out.writeTo(fos)
            fos.flush()
        }
    }

    private fun buildVorbisCommentPayload(track: AudioTrack): ByteArray {
        val comments = mutableListOf(
            "TITLE=${track.title}",
            "ARTIST=${track.artist}",
            "ALBUMARTIST=${track.artist}",
            "ALBUM=${track.album.ifBlank { "Single" }}",
            "DATE=${track.releaseYear}",
            "GENRE=${track.genre}"
        )
        track.effectiveCanonicalIsrc?.let { isrc ->
            comments.add("ISRC=$isrc")
        }

        val vendorBytes = "FlowLosslessEngine/2.0".toByteArray(Charsets.UTF_8)
        val commentByteArrays = comments.map { it.toByteArray(Charsets.UTF_8) }
        val totalSize = 4 + vendorBytes.size + 4 + commentByteArrays.sumOf { 4 + it.size }

        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(vendorBytes.size)
        buffer.put(vendorBytes)
        buffer.putInt(commentByteArrays.size)
        for (cBytes in commentByteArrays) {
            buffer.putInt(cBytes.size)
            buffer.put(cBytes)
        }
        return buffer.array()
    }

    private fun buildFlacPicturePayload(artworkBytes: ByteArray): ByteArray {
        val mimeBytes = "image/jpeg".toByteArray(Charsets.US_ASCII)
        val descBytes = "Front Cover".toByteArray(Charsets.UTF_8)
        val totalSize = 4 + 4 + mimeBytes.size + 4 + descBytes.size + 16 + 4 + artworkBytes.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(3) // Picture type 3: Cover (front)
        buffer.putInt(mimeBytes.size)
        buffer.put(mimeBytes)
        buffer.putInt(descBytes.size)
        buffer.put(descBytes)
        buffer.putInt(512) // width
        buffer.putInt(512) // height
        buffer.putInt(24)  // color depth
        buffer.putInt(0)   // indexed palette colors
        buffer.putInt(artworkBytes.size)
        buffer.put(artworkBytes)
        return buffer.array()
    }

    /**
     * Prepends a standard ID3v2.3 header containing TIT2 (Title), TPE1 (Artist), TPE2 (Album Artist),
     * TALB (Album), TYER (Year), TSRC (ISRC), and APIC (Front Cover Art) to MP3 files.
     */
    private fun embedId3v2Metadata(
        mp3File: File,
        track: AudioTrack,
        artworkBytes: ByteArray
    ) {
        val rawBytes = mp3File.readBytes()
        if (rawBytes.size > 3 &&
            rawBytes[0] == 'I'.code.toByte() &&
            rawBytes[1] == 'D'.code.toByte() &&
            rawBytes[2] == '3'.code.toByte()
        ) {
            // Already has an ID3v2 header
            return
        }

        val framesOut = ByteArrayOutputStream()
        writeId3TextFrame(framesOut, "TIT2", track.title)
        writeId3TextFrame(framesOut, "TPE1", track.artist)
        writeId3TextFrame(framesOut, "TPE2", track.artist)
        writeId3TextFrame(framesOut, "TALB", track.album.ifBlank { "Single" })
        writeId3TextFrame(framesOut, "TYER", track.releaseYear.toString())
        track.effectiveCanonicalIsrc?.let { isrc ->
            writeId3TextFrame(framesOut, "TSRC", isrc)
        }
        if (artworkBytes.isNotEmpty() && artworkBytes.size < 4 * 1024 * 1024) {
            writeId3ApicFrame(framesOut, artworkBytes)
        }

        val framesBytes = framesOut.toByteArray()
        val tagSize = framesBytes.size
        val header = ByteArray(10)
        header[0] = 'I'.code.toByte()
        header[1] = 'D'.code.toByte()
        header[2] = '3'.code.toByte()
        header[3] = 3 // ID3v2.3
        header[4] = 0
        header[5] = 0
        // Synchsafe 28-bit integer size
        header[6] = ((tagSize shr 21) and 0x7F).toByte()
        header[7] = ((tagSize shr 14) and 0x7F).toByte()
        header[8] = ((tagSize shr 7) and 0x7F).toByte()
        header[9] = (tagSize and 0x7F).toByte()

        FileOutputStream(mp3File).use { fos ->
            fos.write(header)
            fos.write(framesBytes)
            fos.write(rawBytes)
            fos.flush()
        }
    }

    private fun writeId3TextFrame(out: ByteArrayOutputStream, frameId: String, value: String) {
        if (value.isBlank()) return
        val textBytes = value.toByteArray(Charsets.UTF_8)
        val payloadSize = 1 + textBytes.size // 1 byte encoding (3 = UTF-8) + text
        val header = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        header.put(frameId.toByteArray(Charsets.US_ASCII), 0, 4)
        header.putInt(payloadSize)
        header.putShort(0)
        out.write(header.array())
        out.write(3) // UTF-8 text encoding
        out.write(textBytes)
    }

    private fun writeId3ApicFrame(out: ByteArrayOutputStream, artworkBytes: ByteArray) {
        val mimeBytes = "image/jpeg".toByteArray(Charsets.US_ASCII)
        // Encoding (0) + MIME + \0 + PicType (3 = Front Cover) + Description ("") + \0 + image bytes
        val payloadSize = 1 + mimeBytes.size + 1 + 1 + 1 + artworkBytes.size
        val header = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        header.put("APIC".toByteArray(Charsets.US_ASCII))
        header.putInt(payloadSize)
        header.putShort(0)
        out.write(header.array())
        out.write(0) // ISO-8859-1 for MIME
        out.write(mimeBytes)
        out.write(0) // null terminator
        out.write(3) // Front Cover
        out.write(0) // Empty description null terminator
        out.write(artworkBytes)
    }

    fun deleteDownloadedTrack(trackId: String) {
        scope.launch {
            try {
                val base = sanitizeFileName(trackId)
                val candidateAudio = File(downloadsDir, "$base.mp3")
                val candidateM4a = File(downloadsDir, "$base.m4a")
                val candidateFlac = File(downloadsDir, "$base.flac")
                val candidateWav = File(downloadsDir, "$base.wav")
                val candidateOpus = File(downloadsDir, "$base.opus")
                val candidateArtLegacy = File(downloadsDir, "${base}_art.jpg")
                val candidateArt = File(artworkCacheDir, "${base}_art.jpg")

                listOf(
                    candidateAudio,
                    candidateM4a,
                    candidateFlac,
                    candidateWav,
                    candidateOpus,
                    candidateArtLegacy,
                    candidateArt
                ).forEach {
                    if (it.exists()) it.delete()
                }

                withContext(Dispatchers.Main) {
                    repository.removeDownloadedTrack(trackId)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun updateProgress(trackId: String, progress: Float) {
        val current = _downloadProgress.value.toMutableMap()
        current[trackId] = progress
        _downloadProgress.value = current
    }

    private fun removeProgress(trackId: String) {
        val current = _downloadProgress.value.toMutableMap()
        current.remove(trackId)
        _downloadProgress.value = current
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64)
    }

    private fun sanitizeDisplayFilename(name: String): String {
        return name
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(96)
            .ifBlank { "Flow_Track" }
    }

    companion object {
        @Volatile
        private var instance: SongDownloadManager? = null

        fun getInstance(context: Context, repository: MusicRepository): SongDownloadManager {
            return instance ?: synchronized(this) {
                instance ?: SongDownloadManager(context.applicationContext, repository).also { instance = it }
            }
        }
    }
}
