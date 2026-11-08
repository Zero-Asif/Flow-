package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import com.example.model.AudioTrack
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Utility to fetch, scale, and cache high-resolution 512x512 album artwork for tracks.
 * Provides Bitmaps, ByteArrays (for MediaMetadata.artworkData and container embedding),
 * and file Uris (for MediaMetadata.artworkUri).
 */
object AlbumArtHelper {

    private const val ARTWORK_SIZE = 512
    private val enhancedUrlCache = ConcurrentHashMap<String, String>()
    private val memoryBitmapCache = ConcurrentHashMap<String, Bitmap>()

    private val artHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Dynamic High-Resolution Cover Art URL Enhancer:
     * - Deezer: Replaces /56x56-, /100x100-, /250x250-, or /500x500- with /1000x1000-000000-80-0-0.jpg
     * - Amazon Music / Apple: Strips downscaled sizing parameters (._SX300_, ._AC_UL320_) -> ._UL1500_ and 1000x1000bb
     * - YouTube Music: Replaces =w60-h60 / =w120-h120 with =w1080-h1080-l95-rj or maxresdefault.jpg
     * - JioSaavn: Replaces 50x50 / 150x150 with 500x500
     */
    fun enhanceArtworkUrl(rawUrl: String?): String {
        if (rawUrl.isNullOrBlank()) return ""
        val trimmed = rawUrl.trim()
        enhancedUrlCache[trimmed]?.let { return it }

        var upgraded = trimmed

        // 1. Deezer CDN (dzcdn.net / deezer.com)
        if (upgraded.contains("dzcdn.net", ignoreCase = true) || upgraded.contains("deezer.com", ignoreCase = true)) {
            upgraded = upgraded
                .replace(Regex("""/\d+x\d+-[0-9a-fA-F-]+-\d+-\d+-\d+\.(jpg|png)"""), "/1000x1000-000000-80-0-0.jpg")
                .replace("/56x56-", "/1000x1000-")
                .replace("/100x100-", "/1000x1000-")
                .replace("/250x250-", "/1000x1000-")
                .replace("/500x500-", "/1000x1000-")
        }

        // 2. Amazon Music / Amazon CDN (m.media-amazon.com / images-na.ssl-images-amazon.com)
        if (upgraded.contains("amazon.com", ignoreCase = true) ||
            upgraded.contains("ssl-images-amazon", ignoreCase = true) ||
            upgraded.contains("._SX", ignoreCase = true) ||
            upgraded.contains("._SY", ignoreCase = true) ||
            upgraded.contains("._AC_UL", ignoreCase = true) ||
            upgraded.contains("._SS", ignoreCase = true)
        ) {
            upgraded = upgraded.replace(
                Regex("""\._(SX\d+|SY\d+|SS\d+|AC_UL\d+|AC_SL\d+|UL\d+|UX\d+|UY\d+|SR\d+,\d+)_"""),
                "._UL1500_"
            )
        }

        // 3. Apple / iTunes / Tidal CDN (mzstatic.com)
        if (upgraded.contains("mzstatic.com", ignoreCase = true) || upgraded.contains("100x100bb") || upgraded.contains("600x600bb")) {
            upgraded = upgraded
                .replace(Regex("""\d+x\d+bb"""), "1000x1000bb")
        }

        // 4. YouTube Music / GoogleUserContent / ytimg
        if (upgraded.contains("googleusercontent.com", ignoreCase = true) || upgraded.contains("ggpht.com", ignoreCase = true)) {
            upgraded = upgraded.replace(Regex("""=w\d+-h\d+[^&?\s]*"""), "=w1080-h1080-l95-rj")
        } else if (upgraded.contains("ytimg.com", ignoreCase = true)) {
            upgraded = upgraded
                .replace("hqdefault.jpg", "maxresdefault.jpg")
                .replace("mqdefault.jpg", "maxresdefault.jpg")
                .replace("sddefault.jpg", "maxresdefault.jpg")
        }

        // 5. JioSaavn CDN (saavncdn.com)
        if (upgraded.contains("saavncdn.com", ignoreCase = true) || upgraded.contains("150x150") || upgraded.contains("50x50")) {
            upgraded = upgraded
                .replace("50x50.jpg", "500x500.jpg")
                .replace("150x150.jpg", "500x500.jpg")
                .replace("50x50", "500x500")
                .replace("150x150", "500x500")
        }

        enhancedUrlCache[trimmed] = upgraded
        return upgraded
    }

    fun getCachedBitmap(trackId: String): Bitmap? {
        return memoryBitmapCache[trackId]?.takeIf { !it.isRecycled }
    }

    fun cacheBitmap(trackId: String, bitmap: Bitmap) {
        if (!bitmap.isRecycled) {
            memoryBitmapCache[trackId] = bitmap
        }
    }

    /**
     * Fetches the real album artwork from `track.artworkUrl` (local file or remote HTTP/HTTPS)
     * and scales it to 512x512 ARGB_8888, falling back to the generated vinyl cover if offline.
     */
    fun fetchOrGenerateArtworkBitmap(context: Context, track: AudioTrack): Bitmap {
        memoryBitmapCache[track.id]?.takeIf { !it.isRecycled }?.let { return it }

        val cacheDir = File(context.cacheDir, "album_art_real")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val cachedRealFile = File(cacheDir, "${track.id.replace(Regex("[^a-zA-Z0-9._-]"), "_")}.jpg")

        if (cachedRealFile.exists() && cachedRealFile.length() > 1024L) {
            runCatching {
                val decoded = BitmapFactory.decodeFile(cachedRealFile.absolutePath)
                if (decoded != null) {
                    val scaled = scaleTo512(decoded)
                    memoryBitmapCache[track.id] = scaled
                    return scaled
                }
            }
        }

        val rawArt = track.artworkUrl?.trim().orEmpty()
        if (rawArt.isNotBlank()) {
            // Check local file path first
            if (rawArt.startsWith("/") || rawArt.startsWith("file://")) {
                val f = File(rawArt.removePrefix("file://"))
                if (f.exists() && f.length() > 512L) {
                    runCatching {
                        val decoded = BitmapFactory.decodeFile(f.absolutePath)
                        if (decoded != null) {
                            val scaled = scaleTo512(decoded)
                            memoryBitmapCache[track.id] = scaled
                            return scaled
                        }
                    }
                }
            } else if (rawArt.startsWith("http://") || rawArt.startsWith("https://")) {
                val candidateUrls = listOf(enhanceArtworkUrl(rawArt), rawArt).distinct()
                for (url in candidateUrls) {
                    try {
                        val req = Request.Builder()
                            .url(url)
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) FlowAudio/2.0")
                            .build()
                        artHttpClient.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val bytes = resp.body?.bytes()
                                if (bytes != null && bytes.size > 512) {
                                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                    if (decoded != null) {
                                        val scaled = scaleTo512(decoded)
                                        runCatching {
                                            FileOutputStream(cachedRealFile).use { fos ->
                                                scaled.compress(Bitmap.CompressFormat.JPEG, 92, fos)
                                            }
                                        }
                                        memoryBitmapCache[track.id] = scaled
                                        return scaled
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Try unenhanced URL or fallback
                    }
                }
            }
        }

        return getArtworkBitmap(context, track)
    }

    fun scaleTo512(source: Bitmap): Bitmap {
        val soft = if (source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            source
        }
        return if (soft.width == ARTWORK_SIZE && soft.height == ARTWORK_SIZE) {
            soft
        } else {
            Bitmap.createScaledBitmap(soft, ARTWORK_SIZE, ARTWORK_SIZE, true)
        }
    }

    fun getArtworkBitmap(context: Context, track: AudioTrack): Bitmap {
        memoryBitmapCache[track.id]?.takeIf { !it.isRecycled }?.let { return it }

        val cacheDir = File(context.cacheDir, "album_art")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val artFile = File(cacheDir, "${track.id.replace(Regex("[^a-zA-Z0-9._-]"), "_")}.jpg")

        val bitmap = Bitmap.createBitmap(ARTWORK_SIZE, ARTWORK_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 1. Draw rich dual-tone gradient background based on track colors
        val startColor = if (track.gradientColors.isNotEmpty()) track.gradientColors[0].toInt() else 0xFF8B5CF6.toInt()
        val endColor = if (track.gradientColors.size > 1) track.gradientColors[1].toInt() else 0xFFEC4899.toInt()

        val gradient = LinearGradient(
            0f, 0f, ARTWORK_SIZE.toFloat(), ARTWORK_SIZE.toFloat(),
            startColor, endColor,
            Shader.TileMode.CLAMP
        )
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = gradient
        }
        canvas.drawRect(0f, 0f, ARTWORK_SIZE.toFloat(), ARTWORK_SIZE.toFloat(), bgPaint)

        // 2. Decorative vinyl / soundwave rings
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            alpha = 35
            strokeWidth = 4f
        }
        val center = ARTWORK_SIZE / 2f
        canvas.drawCircle(center, center, 180f, ringPaint)
        canvas.drawCircle(center, center, 140f, ringPaint)
        canvas.drawCircle(center, center, 100f, ringPaint)

        // Center vinyl disc core
        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(160, 15, 15, 25)
        }
        canvas.drawCircle(center, center, 60f, corePaint)
        val spindlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            alpha = 180
        }
        canvas.drawCircle(center, center, 12f, spindlePaint)

        // 3. Audio format badge pill (e.g., "FLAC 24B/96kHz")
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 0, 0, 0)
            style = Paint.Style.FILL
        }
        val badgeRect = RectF(32f, 32f, 260f, 76f)
        canvas.drawRoundRect(badgeRect, 22f, 22f, badgePaint)

        val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f
            isFakeBoldText = true
        }
        canvas.drawText(track.fidelityBadge, 50f, 62f, badgeTextPaint)

        // 4. Track title & artist at the bottom
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 34f
            isFakeBoldText = true
            setShadowLayer(8f, 0f, 4f, Color.argb(150, 0, 0, 0))
        }
        val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 240, 240, 250)
            textSize = 24f
            setShadowLayer(6f, 0f, 3f, Color.argb(150, 0, 0, 0))
        }

        val displayTitle = if (track.title.length > 22) track.title.take(20) + "…" else track.title
        val displayArtist = if (track.artist.length > 26) track.artist.take(24) + "…" else track.artist

        canvas.drawText(displayTitle, 36f, ARTWORK_SIZE - 72f, titlePaint)
        canvas.drawText(displayArtist, 36f, ARTWORK_SIZE - 32f, artistPaint)

        // Save to cache file
        try {
            FileOutputStream(artFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return bitmap
    }

    fun getArtworkUri(context: Context, track: AudioTrack): Uri {
        val cacheDir = File(context.cacheDir, "album_art")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val artFile = File(cacheDir, "${track.id.replace(Regex("[^a-zA-Z0-9._-]"), "_")}.jpg")
        if (!artFile.exists()) {
            getArtworkBitmap(context, track)
        }
        return Uri.fromFile(artFile)
    }

    fun getArtworkByteArray(context: Context, track: AudioTrack): ByteArray {
        val bitmap = memoryBitmapCache[track.id]?.takeIf { !it.isRecycled }
            ?: getArtworkBitmap(context, track)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
        return stream.toByteArray()
    }
}
