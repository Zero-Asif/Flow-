package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import com.example.model.AudioTrack
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Utility to generate and cache high-resolution album artwork for tracks.
 * Provides Bitmaps, ByteArrays (for MediaMetadata.artworkData), and file Uris (for MediaMetadata.artworkUri).
 * This enables the Android 13+ System UI to render full-bleed album artwork in the Live Media Notification.
 */
object AlbumArtHelper {

    private const val ARTWORK_SIZE = 512

    fun getArtworkBitmap(context: Context, track: AudioTrack): Bitmap {
        val cacheDir = File(context.cacheDir, "album_art")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val artFile = File(cacheDir, "${track.id}.jpg")

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
        val artFile = File(cacheDir, "${track.id}.jpg")
        if (!artFile.exists()) {
            getArtworkBitmap(context, track)
        }
        return Uri.fromFile(artFile)
    }

    fun getArtworkByteArray(context: Context, track: AudioTrack): ByteArray {
        val bitmap = getArtworkBitmap(context, track)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        return stream.toByteArray()
    }
}
