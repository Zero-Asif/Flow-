package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.MainActivity
import com.example.audio.ExoPlayerAudioEngine
import com.example.model.AudioTrack
import com.example.util.AlbumArtHelper
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Android System MediaSessionService & Notification Shade Controller.
 *
 * Implements:
 * 1. `ForwardingPlayer` wrapper around `ExoPlayerAudioEngine.exoPlayer` that ALWAYS exposes
 *    `COMMAND_SEEK_TO_PREVIOUS`, `COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM`, `COMMAND_SEEK_TO_NEXT`,
 *    and `COMMAND_SEEK_TO_NEXT_MEDIA_ITEM` in `getAvailableCommands()` and `isCommandAvailable(...)`
 *    even when a single `MediaItem` is loaded, routing `seekToNext()` / `seekToNextMediaItem()`
 *    directly to `audioEngine.playNext()` (which automatically fetches the next related track
 *    via `TrackRadioRepository` when the queue only has 1 song).
 * 2. 3 Mandatory Transport Actions in `NotificationCompat.Builder`:
 *    - Action 0: `ACTION_PREVIOUS` (`PendingIntent` for skipToPrevious)
 *    - Action 1: `ACTION_PLAY_PAUSE` (`PendingIntent` for play/pause toggle)
 *    - Action 2: `ACTION_NEXT` (`PendingIntent` for skipToNext)
 *    configured with `.setShowActionsInCompactView(0, 1, 2)`.
 */
@OptIn(UnstableApi::class)
class FlowPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var audioEngine: ExoPlayerAudioEngine
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var artworkLoadJob: Job? = null

    private val artworkBitmapCache = ConcurrentHashMap<String, Bitmap>()
    private val artworkBytesCache = ConcurrentHashMap<String, ByteArray>()

    @Volatile
    private var lastSyncedTrackId: String? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        audioEngine = ExoPlayerAudioEngine.getInstance(applicationContext)

        // Wrap ExoPlayer in a ForwardingPlayer that ALWAYS enables Previous and Next transport controls
        // in Android 13/14+ System UI & Bluetooth AVRCP, even for a 1-song queue.
        val forwardingPlayer = object : ForwardingPlayer(audioEngine.exoPlayer) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands()
                    .buildUpon()
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_PLAY_PAUSE)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_PLAY_PAUSE -> true
                    else -> super.isCommandAvailable(command)
                }
            }

            override fun hasNextMediaItem(): Boolean = true

            override fun hasPreviousMediaItem(): Boolean = true

            override fun seekToNext() {
                audioEngine.playNext()
            }

            override fun seekToNextMediaItem() {
                audioEngine.playNext()
            }

            override fun seekToPrevious() {
                audioEngine.playPrevious()
            }

            override fun seekToPreviousMediaItem() {
                audioEngine.playPrevious()
            }
        }

        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val previousButton = CommandButton.Builder()
            .setDisplayName("Previous")
            .setSessionCommand(SessionCommand(ACTION_PREVIOUS, Bundle.EMPTY))
            .setIconResId(android.R.drawable.ic_media_previous)
            .build()

        val nextButton = CommandButton.Builder()
            .setDisplayName("Next")
            .setSessionCommand(SessionCommand(ACTION_NEXT, Bundle.EMPTY))
            .setIconResId(android.R.drawable.ic_media_next)
            .build()

        val favoriteButton = CommandButton.Builder()
            .setDisplayName("Favorite")
            .setSessionCommand(SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY))
            .setIconResId(android.R.drawable.btn_star_big_on)
            .build()

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .setCallback(FlowMediaSessionCallback())
            .setCustomLayout(ImmutableList.of(previousButton, nextButton, favoriteButton))
            .build()

        setMediaNotificationProvider(FlowMediaNotificationProvider())
        promoteToForegroundImmediately(
            track = audioEngine.currentTrackState.value ?: audioEngine.currentPlayingTrack,
            isPlaying = audioEngine.isPlayingState.value,
            artworkBitmap = null
        )
        observeActiveTrackAndSyncMediaSession()
    }

    /**
     * Single Source of Truth Observer:
     * Observes `currentTrackState` and `isPlayingState` directly from `ExoPlayerAudioEngine`
     * so every track change or play/pause transition immediately updates both `MediaSession`
     * and `NotificationManager`.
     */
    private fun observeActiveTrackAndSyncMediaSession() {
        serviceScope.launch {
            combine(
                audioEngine.currentTrackState,
                audioEngine.isPlayingState
            ) { track, isPlaying ->
                track to isPlaying
            }.collect { (track, isPlaying) ->
                if (track == null) return@collect

                val trackChanged = (track.id != lastSyncedTrackId)
                lastSyncedTrackId = track.id

                val cachedBitmap = artworkBitmapCache[track.id]
                val cachedBytes = artworkBytesCache[track.id]
                val immediateBitmap = cachedBitmap ?: AlbumArtHelper.getArtworkBitmap(
                    context = applicationContext,
                    track = track
                )

                if (trackChanged || cachedBytes != null) {
                    pushMetadataToPlayerAndSession(track, immediateBitmap, cachedBytes)
                }
                postStandardMediaNotification(track, isPlaying, immediateBitmap)

                if (cachedBitmap == null && !track.artworkUrl.isNullOrBlank()) {
                    artworkLoadJob?.cancel()
                    artworkLoadJob = serviceScope.launch {
                        val fetched = fetchRemoteArtworkWithBytes(track.artworkUrl)
                        if (fetched != null) {
                            val (remoteBitmap, remoteBytes) = fetched
                            artworkBitmapCache[track.id] = remoteBitmap
                            artworkBytesCache[track.id] = remoteBytes

                            val activeNow = audioEngine.currentTrackState.value
                            if (activeNow?.id == track.id) {
                                pushMetadataToPlayerAndSession(
                                    track = activeNow,
                                    artworkBitmap = remoteBitmap,
                                    artworkBytes = remoteBytes
                                )
                                postStandardMediaNotification(
                                    track = activeNow,
                                    isPlaying = audioEngine.isPlayingState.value,
                                    artworkBitmap = remoteBitmap
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun pushMetadataToPlayerAndSession(
        track: AudioTrack,
        artworkBitmap: Bitmap,
        artworkBytes: ByteArray?
    ) {
        val player = audioEngine.exoPlayer
        val currentItem = player.currentMediaItem ?: return
        if (currentItem.mediaId != track.id) return

        val compressedBytes = artworkBytes ?: run {
            val out = ByteArrayOutputStream()
            artworkBitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            out.toByteArray().also { artworkBytesCache[track.id] = it }
        }

        val updatedMetadata = currentItem.mediaMetadata.buildUpon()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setDisplayTitle(track.title)
            .setSubtitle(track.artist)
            .setArtworkData(compressedBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()

        val updatedItem = currentItem.buildUpon()
            .setMediaMetadata(updatedMetadata)
            .build()

        if (player.currentMediaItemIndex >= 0 && player.mediaItemCount > 0) {
            runCatching {
                player.replaceMediaItem(player.currentMediaItemIndex, updatedItem)
            }
        }
    }

    /**
     * Posts a standard Android `NotificationCompat.MediaStyle` notification with
     * all 3 mandatory transport controls (0: Previous, 1: Play/Pause, 2: Next)
     * and guarantees foreground service promotion.
     */
    private fun postStandardMediaNotification(
        track: AudioTrack,
        isPlaying: Boolean,
        artworkBitmap: Bitmap
    ) {
        promoteToForegroundImmediately(track, isPlaying, artworkBitmap)
    }

    /**
     * Synchronously calls `Service.startForeground(...)` with `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK`
     * so Android's 5-second `startForegroundService()` contract is always satisfied immediately.
     */
    private fun promoteToForegroundImmediately(
        track: AudioTrack?,
        isPlaying: Boolean,
        artworkBitmap: Bitmap?
    ) {
        val session = mediaSession ?: return
        ensureNotificationChannel()
        val resolvedBitmap = artworkBitmap ?: track?.id?.let { artworkBitmapCache[it] }
        val notification = buildStandardMediaNotification(session, track, isPlaying, resolvedBitmap)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            runCatching {
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildStandardMediaNotification(
        session: MediaSession,
        track: AudioTrack?,
        isPlaying: Boolean,
        artworkBitmap: Bitmap?
    ): Notification {
        val displayTitle = track?.title?.takeIf { it.isNotBlank() } ?: "Flow Hi-Res Audio"
        val displayArtist = track?.artist?.takeIf { it.isNotBlank() } ?: "Ready to Play"
        val displayAlbum = track?.album?.takeIf { it.isNotBlank() } ?: "Flow"

        val fallbackTrack = track ?: AudioTrack(
            id = "notification_default",
            title = displayTitle,
            artist = displayArtist,
            album = displayAlbum,
            durationMs = 180_000L
        )
        val largeIcon = artworkBitmap ?: AlbumArtHelper.getArtworkBitmap(
            context = applicationContext,
            track = fallbackTrack
        )

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Action 0: Previous Track
        val prevAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_previous,
            "Previous",
            createServiceActionPendingIntent(ACTION_PREVIOUS, 101)
        ).build()

        // Action 1: Play / Pause Toggle
        val playPauseAction = NotificationCompat.Action.Builder(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (isPlaying) "Pause" else "Play",
            createServiceActionPendingIntent(ACTION_PLAY_PAUSE, 102)
        ).build()

        // Action 2: Next Track (Always enabled and visible in compact & expanded shade)
        val nextAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_media_next,
            "Next",
            createServiceActionPendingIntent(ACTION_NEXT, 103)
        ).build()

        val mediaStyle = MediaStyleNotificationHelper.MediaStyle(session)
            .setShowActionsInCompactView(0, 1, 2)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(displayTitle)
            .setContentText(displayArtist)
            .setSubText(displayAlbum)
            .setLargeIcon(largeIcon)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .setShowWhen(false)
            .addAction(prevAction)      // Index 0
            .addAction(playPauseAction) // Index 1
            .addAction(nextAction)      // Index 2
            .setStyle(mediaStyle)
            .build()
    }

    private fun createServiceActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, FlowPlaybackService::class.java).apply {
            this.action = action
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getService(this, requestCode, intent, flags)
    }

    private suspend fun fetchRemoteArtworkWithBytes(urlString: String): Pair<Bitmap, ByteArray>? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val highResUrl = urlString
                    .replace("100x100bb", "600x600bb")
                    .replace("150x150", "500x500")
                connection = (URL(highResUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4_000
                    readTimeout = 4_000
                    doInput = true
                    setRequestProperty("User-Agent", "FlowHiResAudioPlayer/4.0")
                }
                if (connection.responseCode in 200..299) {
                    val rawBytes = connection.inputStream.use { it.readBytes() }
                    if (rawBytes.isNotEmpty()) {
                        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                        if (decoded != null) {
                            return@withContext decoded to rawBytes
                        }
                    }
                }
                null
            } catch (_: Exception) {
                null
            } finally {
                connection?.disconnect()
            }
        }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Flow Media Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active audio playback and lossless stream controls"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Immediately satisfy Android's startForegroundService() requirement on entry
        promoteToForegroundImmediately(
            track = audioEngine.currentTrackState.value ?: audioEngine.currentPlayingTrack,
            isPlaying = audioEngine.isPlayingState.value,
            artworkBitmap = null
        )
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> audioEngine.togglePlayPause()
            ACTION_NEXT -> audioEngine.playNext()
            ACTION_PREVIOUS -> audioEngine.playPrevious()
            ACTION_TOGGLE_FAVORITE -> audioEngine.toggleFavoriteCurrentTrack()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        artworkLoadJob?.cancel()
        serviceScope.cancel()
        mediaSession?.run {
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    private inner class FlowMediaSessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_PREVIOUS, Bundle.EMPTY))
                .add(SessionCommand(ACTION_NEXT, Bundle.EMPTY))
                .add(SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY))
                .build()

            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_PLAY_PAUSE)
                .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            return when (customCommand.customAction) {
                ACTION_PREVIOUS -> {
                    audioEngine.playPrevious()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_NEXT -> {
                    audioEngine.playNext()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                ACTION_TOGGLE_FAVORITE -> {
                    audioEngine.toggleFavoriteCurrentTrack()
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                else -> super.onCustomCommand(session, controller, customCommand, args)
            }
        }
    }

    private inner class FlowMediaNotificationProvider : MediaNotification.Provider {
        override fun createNotification(
            mediaSession: MediaSession,
            customLayout: ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            onNotificationChangedCallback: MediaNotification.Provider.Callback
        ): MediaNotification {
            ensureNotificationChannel()
            val activeTrack = audioEngine.currentTrackState.value ?: audioEngine.currentPlayingTrack
            val isPlaying = audioEngine.isPlayingState.value || mediaSession.player.isPlaying
            val cachedBitmap = activeTrack?.id?.let { artworkBitmapCache[it] }

            val notification = buildStandardMediaNotification(
                session = mediaSession,
                track = activeTrack,
                isPlaying = isPlaying,
                artworkBitmap = cachedBitmap
            )
            return MediaNotification(NOTIFICATION_ID, notification)
        }

        override fun handleCustomCommand(
            session: MediaSession,
            action: String,
            extras: Bundle
        ): Boolean {
            return when (action) {
                ACTION_TOGGLE_FAVORITE -> {
                    audioEngine.toggleFavoriteCurrentTrack()
                    true
                }
                ACTION_NEXT -> {
                    audioEngine.playNext()
                    true
                }
                ACTION_PREVIOUS -> {
                    audioEngine.playPrevious()
                    true
                }
                else -> false
            }
        }
    }

    companion object {
        const val NOTIFICATION_ID = 4096
        const val NOTIFICATION_CHANNEL_ID = "flow_hires_playback_channel"

        const val ACTION_PLAY_PAUSE = "com.example.flow.action.PLAY_PAUSE"
        const val ACTION_NEXT = "com.example.flow.action.NEXT"
        const val ACTION_PREVIOUS = "com.example.flow.action.PREVIOUS"
        const val ACTION_TOGGLE_FAVORITE = "com.example.flow.action.TOGGLE_FAVORITE"

        fun startOrUpdate(context: Context) {
            val intent = Intent(context, FlowPlaybackService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                runCatching { context.startService(intent) }
            }
        }
    }
}
