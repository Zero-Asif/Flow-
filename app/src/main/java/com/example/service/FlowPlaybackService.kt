package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Tracks
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.MainActivity
import com.example.R
import com.example.audio.ExoPlayerAudioEngine
import com.example.util.AlbumArtHelper
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Android 13+ Native MediaSessionService for lossless audiophile music playback.
 * 
 * Features:
 * - Standard Android MediaStyle notification with setShowActionsInCompactView(0, 1, 2)
 * - Clean 512x512 scaled largeIcon artwork without custom RemoteViews overflow
 * - Dynamic codec & bitrate subText (e.g., "FLAC 1411 kbps")
 * - Seek bar support via MediaSession & MediaMetadata duration synchronization
 * - Playback controls (Previous, Play/Pause, Next, Favorite, Shuffle)
 * - Robust foreground service lifecycle management
 */
@OptIn(UnstableApi::class)
class FlowPlaybackService : MediaSessionService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var mediaSession: MediaSession? = null
    lateinit var audioEngine: ExoPlayerAudioEngine
        private set

    inner class LocalBinder : Binder() {
        val service: FlowPlaybackService
            get() = this@FlowPlaybackService
    }

    private val binder = LocalBinder()

    companion object {
        const val CHANNEL_ID = "flow_playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PREV = "com.example.flow.ACTION_PREV"
        const val ACTION_PLAY_PAUSE = "com.example.flow.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.example.flow.ACTION_NEXT"
        const val ACTION_FAVORITE = "com.example.flow.ACTION_FAVORITE"
        const val ACTION_SHUFFLE = "com.example.flow.ACTION_SHUFFLE"

        const val CUSTOM_ACTION_FAVORITE = "CUSTOM_ACTION_FAVORITE"
        const val CUSTOM_ACTION_SHUFFLE = "CUSTOM_ACTION_SHUFFLE"
    }

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val baseResult = super.onConnect(session, controller)
            val sessionCommands = baseResult.availableSessionCommands.buildUpon()
                .add(SessionCommand(CUSTOM_ACTION_FAVORITE, Bundle.EMPTY))
                .add(SessionCommand(CUSTOM_ACTION_SHUFFLE, Bundle.EMPTY))
                .build()

            val playerCommands = baseResult.availablePlayerCommands.buildUpon()
                .add(Player.COMMAND_PLAY_PAUSE)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_SET_SHUFFLE_MODE)
                .add(Player.COMMAND_SET_REPEAT_MODE)
                .build()

            return MediaSession.ConnectionResult.accept(sessionCommands, playerCommands)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CUSTOM_ACTION_FAVORITE -> {
                    audioEngine.toggleFavoriteCurrentTrack()
                    syncNotificationAndMetadata()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CUSTOM_ACTION_SHUFFLE -> {
                    val nextShuffle = !audioEngine.exoPlayer.shuffleModeEnabled
                    audioEngine.setShuffleMode(nextShuffle)
                    syncNotificationAndMetadata()
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    override fun onCreate() {
        super.onCreate()

        audioEngine = ExoPlayerAudioEngine.getInstance(applicationContext)

        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, audioEngine.exoPlayer)
            .setSessionActivity(pendingIntent)
            .setCallback(sessionCallback)
            .setCustomLayout(buildCustomLayout())
            .build()

        // Immediate foreground start for strict Android 8.0+ / 14+ requirements
        startForegroundWithNotification()

        // 1. Direct Player.Listener to sync media notification immediately with engine events
        audioEngine.exoPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                syncNotificationAndMetadata()
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                syncNotificationAndMetadata()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                syncNotificationAndMetadata()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                syncNotificationAndMetadata()
            }

            override fun onTracksChanged(tracks: Tracks) {
                syncNotificationAndMetadata()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                syncNotificationAndMetadata()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                updateCustomLayout()
                syncNotificationAndMetadata()
            }
        })

        // 2. SSOT StateFlow subscribers: guarantees notification, codec subtext, and seekbar metadata sync
        serviceScope.launch {
            audioEngine.currentTrackState.collectLatest { track ->
                if (track != null) {
                    syncNotificationAndMetadata()
                }
            }
        }

        serviceScope.launch {
            audioEngine.isPlayingState.collectLatest {
                syncNotificationAndMetadata()
            }
        }

        serviceScope.launch {
            audioEngine.audioQualityState.collectLatest {
                syncNotificationAndMetadata()
            }
        }

        serviceScope.launch {
            audioEngine.durationState.collectLatest {
                syncNotificationAndMetadata()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()

        when (intent?.action) {
            ACTION_PREV -> audioEngine.playPrevious()
            ACTION_PLAY_PAUSE -> audioEngine.togglePlayPause()
            ACTION_NEXT -> audioEngine.playNext()
            ACTION_FAVORITE -> {
                audioEngine.toggleFavoriteCurrentTrack()
                syncNotificationAndMetadata()
            }
            ACTION_SHUFFLE -> {
                val nextShuffle = !audioEngine.exoPlayer.shuffleModeEnabled
                audioEngine.setShuffleMode(nextShuffle)
                syncNotificationAndMetadata()
            }
        }

        syncNotificationAndMetadata()
        return super.onStartCommand(intent, flags, startId)
    }

    private fun syncNotificationAndMetadata() {
        updateCustomLayout()
        updateNotification()
    }

    private fun buildCustomLayout(): List<CommandButton> {
        val track = audioEngine.currentPlayingTrack
        val isFav = track?.isFavorite == true
        val isShuffle = audioEngine.exoPlayer.shuffleModeEnabled

        val favButton = CommandButton.Builder()
            .setDisplayName(if (isFav) "Favorited" else "Favorite")
            .setIconResId(R.drawable.ic_notif_favorite)
            .setSessionCommand(SessionCommand(CUSTOM_ACTION_FAVORITE, Bundle.EMPTY))
            .setEnabled(track != null)
            .build()

        val shuffleButton = CommandButton.Builder()
            .setDisplayName(if (isShuffle) "Shuffle On" else "Shuffle Off")
            .setIconResId(R.drawable.ic_notif_shuffle)
            .setSessionCommand(SessionCommand(CUSTOM_ACTION_SHUFFLE, Bundle.EMPTY))
            .setEnabled(true)
            .build()

        return listOf(favButton, shuffleButton)
    }

    private fun updateCustomLayout() {
        mediaSession?.setCustomLayout(buildCustomLayout())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Flow Lossless Audio",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background playback and lockscreen live media controls"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createServicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, FlowPlaybackService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun buildNotification(): Notification {
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val track = audioEngine.currentPlayingTrack ?: audioEngine.currentTrackState.value
        val quality = audioEngine.audioQualityState.value
        val isPlaying = audioEngine.exoPlayer.isPlaying || audioEngine.isPlayingState.value
        val title = track?.title ?: "Flow Lossless Audio"
        val artist = track?.artist ?: "High-Fidelity Audio Engine"
        val codecName = quality.formatName.ifBlank { track?.format?.displayName ?: "FLAC" }
        val bitrateKbps = quality.bitrateKbps.takeIf { it > 0 } ?: track?.bitrateKbps ?: 1411
        val subTextCodec = "$codecName $bitrateKbps kbps"

        val prevPendingIntent = createServicePendingIntent(ACTION_PREV, 101)
        val playPausePendingIntent = createServicePendingIntent(ACTION_PLAY_PAUSE, 102)
        val nextPendingIntent = createServicePendingIntent(ACTION_NEXT, 103)
        val favPendingIntent = createServicePendingIntent(ACTION_FAVORITE, 104)
        val shufflePendingIntent = createServicePendingIntent(ACTION_SHUFFLE, 105)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText(subTextCodec)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentIntent(contentPendingIntent)
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // Attach 512x512 scaled album artwork bitmap for notification shade and lockscreen
        if (track != null) {
            try {
                val rawBitmap = AlbumArtHelper.getArtworkBitmap(this, track)
                val bitmapArtwork = if (rawBitmap.width == 512 && rawBitmap.height == 512) {
                    rawBitmap
                } else {
                    Bitmap.createScaledBitmap(rawBitmap, 512, 512, true)
                }
                builder.setLargeIcon(bitmapArtwork)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Standard notification action buttons (0 = Previous, 1 = Play/Pause, 2 = Next)
        builder.addAction(
            R.drawable.ic_notif_prev,
            "Previous",
            prevPendingIntent
        )
        builder.addAction(
            if (isPlaying) R.drawable.ic_notif_pause else R.drawable.ic_notif_play,
            if (isPlaying) "Pause" else "Play",
            playPausePendingIntent
        )
        builder.addAction(
            R.drawable.ic_notif_next,
            "Next",
            nextPendingIntent
        )
        builder.addAction(
            R.drawable.ic_notif_favorite,
            "Favorite",
            favPendingIntent
        )
        builder.addAction(
            R.drawable.ic_notif_shuffle,
            "Shuffle",
            shufflePendingIntent
        )

        // Connect standard Android MediaStyle to the active MediaSession with compact actions (0, 1, 2)
        mediaSession?.let { session ->
            val mediaStyle = MediaStyleNotificationHelper.MediaStyle(session)
                .setShowActionsInCompactView(0, 1, 2)
            builder.setStyle(mediaStyle)
        }

        return builder.build()
    }

    private fun startForegroundWithNotification() {
        createNotificationChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateNotification() {
        try {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            val notification = buildNotification()
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onBind(intent: Intent?): IBinder? {
        val superBinder = super.onBind(intent)
        return if (intent?.action == "androidx.media3.session.MediaSessionService") {
            superBinder
        } else {
            binder
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        mediaSession?.run {
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
