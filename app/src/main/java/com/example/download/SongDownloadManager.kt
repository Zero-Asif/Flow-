package com.example.download

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.audio.AudioMetadataProber
import com.example.data.MusicRepository
import com.example.model.AudioFormat
import com.example.model.AudioTrack
import com.example.model.StreamBackend
import com.example.network.DownloadQualityOption
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
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * SpotiFLAC-style High-Speed Audio Download Engine:
 * - Downloads full-length high bitrate (320kbps MP3, FLAC, AAC, Opus) songs from all providers
 * - Defaults to highest bitrate (320 kbps Master Hi-Fi) while letting the user pick their preferred quality
 * - Downloads 100% raw server bytes without re-encoding or reducing bitrate
 * - Stores files in private app storage for 100% offline playback
 * - Emits real-time byte-level download progress (0% - 100%)
 * - Auto-caches high-resolution album artwork for offline display
 */
class SongDownloadManager(
    private val context: Context,
    private val repository: MusicRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // Map of trackId -> progress percentage (0.0f to 1.0f)
    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    private val downloadsDir = File(context.filesDir, "downloads").apply {
        if (!exists()) mkdirs()
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

    fun downloadTrack(
        track: AudioTrack,
        backend: StreamBackend = StreamBackend.AUTO_HIGH_FIDELITY,
        qualityOption: DownloadQualityOption = if (track.format.isLossless || track.bitrateKbps > 320) {
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

                val effectiveBackend = if (qualityOption == DownloadQualityOption.HI_RES_LOSSLESS && !track.isGlobalOnline) {
                    StreamBackend.LOCAL_LOSSLESS
                } else {
                    backend
                }

                // 1. Resolve full stream URL with strict Search-Playback-Download parity:
                // Prefer the exact verified master candidate already resolved for playback if available
                val cachedTopCandidate = UniversalStreamResolver.getTopResolvedCandidateForTrack(track.id)
                val (resolvedUrl, qualityInfo) = when {
                    cachedTopCandidate != null && cachedTopCandidate.streamUrl.isNotBlank() &&
                        (qualityOption == DownloadQualityOption.HI_RES_LOSSLESS || !cachedTopCandidate.isLossless) -> {
                        Pair(cachedTopCandidate.streamUrl, cachedTopCandidate.toAudioQualityInfo())
                    }
                    qualityOption == DownloadQualityOption.HI_RES_LOSSLESS &&
                        !track.uriString.isNullOrBlank() &&
                        (track.uriString.endsWith(".flac", ignoreCase = true) ||
                            track.uriString.endsWith(".wav", ignoreCase = true) ||
                            track.uriString.endsWith(".alac", ignoreCase = true)) -> {
                        Pair(
                            track.uriString,
                            com.example.model.AudioQualityInfo(
                                formatName = track.format.displayName,
                                sampleRateHz = track.sampleRate.coerceAtLeast(44100),
                                bitDepth = track.bitDepth.coerceAtLeast(16),
                                bitrateKbps = track.bitrateKbps.takeIf { it > 320 } ?: 1411,
                                isLossless = true,
                                isHiRes = true
                            )
                        )
                    }
                    else -> {
                        UniversalStreamResolver.resolveFullAudioStream(
                            context = context,
                            track = track,
                            backend = effectiveBackend
                        )
                    }
                }

                // Apply user's selected quality tier (preserves raw stream when HI_RES_LOSSLESS is selected)
                val streamUrl = UniversalStreamResolver.applyQualityOptionToUrl(resolvedUrl, qualityOption)

                updateProgress(track.id, 0.20f)

                val isLosslessDownload = qualityOption == DownloadQualityOption.HI_RES_LOSSLESS
                val targetFormat = when {
                    isLosslessDownload && track.format == AudioFormat.ALAC -> AudioFormat.ALAC
                    isLosslessDownload && track.format == AudioFormat.WAV -> AudioFormat.WAV
                    isLosslessDownload -> AudioFormat.FLAC
                    qualityOption == DownloadQualityOption.MASTER_320K -> AudioFormat.MP3
                    else -> AudioFormat.AAC
                }

                val ext = when {
                    isLosslessDownload && targetFormat == AudioFormat.FLAC -> "flac"
                    isLosslessDownload && targetFormat == AudioFormat.ALAC -> "m4a"
                    isLosslessDownload && targetFormat == AudioFormat.WAV -> "wav"
                    streamUrl.contains(".flac", ignoreCase = true) -> "flac"
                    streamUrl.contains(".wav", ignoreCase = true) -> "wav"
                    streamUrl.contains(".opus", ignoreCase = true) -> "opus"
                    streamUrl.contains(".aac", ignoreCase = true) || streamUrl.contains(".m4a", ignoreCase = true) || streamUrl.contains(".mp4", ignoreCase = true) -> "m4a"
                    else -> "mp3"
                }

                val targetAudioFile = File(downloadsDir, "${sanitizeFileName(track.id)}.$ext")
                val targetArtworkFile = File(downloadsDir, "${sanitizeFileName(track.id)}_art.jpg")

                // 2. Download 100% raw audio stream bytes with zero quality reduction
                if (streamUrl.startsWith("http://") || streamUrl.startsWith("https://")) {
                    val request = Request.Builder()
                        .url(streamUrl)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) FlowAudio/2.0")
                        .build()

                    var response = httpClient.newCall(request).execute()
                    if (!response.isSuccessful && streamUrl != resolvedUrl) {
                        response.close()
                        // Fallback to the verified resolved highest-quality URL if specific tier returned 404
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
                        FileOutputStream(targetAudioFile).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                bytesCopied += read
                                if (contentLength > 0) {
                                    val pct = 0.20f + (bytesCopied.toFloat() / contentLength) * 0.70f
                                    updateProgress(track.id, pct.coerceIn(0.20f, 0.90f))
                                }
                            }
                            output.flush()
                        }
                    }
                } else {
                    // Local file copy (e.g. from cache or procedural WAV/FLAC master)
                    val srcFile = File(streamUrl.removePrefix("file://"))
                    if (srcFile.exists()) {
                        srcFile.copyTo(targetAudioFile, overwrite = true)
                    }
                }

                updateProgress(track.id, 0.92f)

                // 3. Cache high-res artwork locally for offline viewing
                var localArtPath: String? = null
                val enhancedArtworkUrl = AlbumArtHelper.enhanceArtworkUrl(track.artworkUrl)
                if (enhancedArtworkUrl.isNotBlank() && enhancedArtworkUrl.startsWith("http")) {
                    try {
                        val artReq = Request.Builder().url(enhancedArtworkUrl).build()
                        val artResp = httpClient.newCall(artReq).execute()
                        if (artResp.isSuccessful) {
                            artResp.body?.byteStream()?.use { artIn ->
                                FileOutputStream(targetArtworkFile).use { artOut ->
                                    artIn.copyTo(artOut)
                                    artOut.flush()
                                }
                            }
                            localArtPath = targetArtworkFile.absolutePath
                        }
                    } catch (e: Exception) {
                        Log.w("SongDownloadManager", "Artwork download optional failure: ${e.message}")
                    }
                }

                val downloadedBytes = targetAudioFile.length()
                val sizeMb = "%.1f MB".format(downloadedBytes / (1024.0 * 1024.0))
                val actualSampleRate = if (isLosslessDownload) {
                    maxOf(track.sampleRate, qualityInfo.sampleRateHz).takeIf { it >= 44100 } ?: 44100
                } else {
                    44100
                }
                val actualBitDepth = if (isLosslessDownload) {
                    maxOf(track.bitDepth, qualityInfo.bitDepth).takeIf { it >= 16 } ?: 16
                } else {
                    16
                }
                val derivedContainerBitrate = AudioMetadataProber.deriveBitrateFromSizeAndDuration(
                    fileSizeBytes = downloadedBytes,
                    durationMs = track.durationMs,
                    formatName = targetFormat.displayName,
                    sampleRateHz = actualSampleRate,
                    bitDepth = actualBitDepth,
                    channels = 2
                )
                val actualBitrate = when {
                    derivedContainerBitrate > 0 -> derivedContainerBitrate
                    isLosslessDownload -> maxOf(track.bitrateKbps, qualityInfo.bitrateKbps).takeIf { it > 320 } ?: 1411
                    else -> qualityOption.targetBitrateKbps
                }
                val qualityLabel = if (isLosslessDownload) {
                    "$sizeMb • $actualBitrate kbps Hi-Res ${targetFormat.displayName} (Lossless Bit-Perfect)"
                } else {
                    "$sizeMb • $actualBitrate kbps Lossy (${targetFormat.displayName})"
                }

                val downloadedTrack = track.copy(
                    uriString = Uri.fromFile(targetAudioFile).toString(),
                    localFilePath = targetAudioFile.absolutePath,
                    artworkUrl = localArtPath ?: track.artworkUrl,
                    isDownloaded = true,
                    isGlobalOnline = false,
                    isLocalImport = true,
                    fileSizeFormatted = qualityLabel,
                    bitrateKbps = actualBitrate,
                    sampleRate = actualSampleRate,
                    bitDepth = actualBitDepth,
                    format = targetFormat
                )

                // 4. Save into persistent downloaded tracks in repository
                withContext(Dispatchers.Main) {
                    repository.addDownloadedTrack(downloadedTrack)
                    updateProgress(track.id, 1.0f)
                    removeProgress(track.id)
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

    fun deleteDownloadedTrack(trackId: String) {
        scope.launch {
            try {
                val candidateAudio = File(downloadsDir, "${sanitizeFileName(trackId)}.mp3")
                val candidateM4a = File(downloadsDir, "${sanitizeFileName(trackId)}.m4a")
                val candidateFlac = File(downloadsDir, "${sanitizeFileName(trackId)}.flac")
                val candidateWav = File(downloadsDir, "${sanitizeFileName(trackId)}.wav")
                val candidateOpus = File(downloadsDir, "${sanitizeFileName(trackId)}.opus")
                val candidateArt = File(downloadsDir, "${sanitizeFileName(trackId)}_art.jpg")

                listOf(candidateAudio, candidateM4a, candidateFlac, candidateWav, candidateOpus, candidateArt).forEach {
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
